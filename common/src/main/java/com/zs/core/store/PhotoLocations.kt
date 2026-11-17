/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.core.store

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** A photo with a known GPS position. */
class GeoPhoto(
    @JvmField val id: Long,
    @JvmField val latitude: Double,
    @JvmField val longitude: Double,
    @JvmField val date: Long,
)

/**
 * Finds where photos were taken (GPS in their EXIF data) for the map.
 *
 * Reading EXIF means opening every file, so results are cached on disk (keyed by id + modification
 * date) and only new or changed photos are read on later scans. Results are published
 * progressively through [photos] while a scan runs.
 *
 * Needs the ACCESS_MEDIA_LOCATION permission; without it Android hides the GPS data.
 */
object PhotoLocations {

    private const val TAG = "PhotoLocations"
    private const val CACHE_FILE = "photo_locations.bin"
    private const val CACHE_VERSION = 1

    private class Entry(val modified: Long, val lat: Double, val lon: Double, val date: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var cache: HashMap<Long, Entry>? = null

    private val _photos = MutableStateFlow<List<GeoPhoto>>(emptyList())
    /** Photos with a location, newest first. */
    val photos: StateFlow<List<GeoPhoto>> = _photos.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** (done, total) of the running scan. */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    /** Starts (or restarts) a scan; cheap when nothing changed thanks to the cache. */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        // Without the permission every photo would look location-less (and be cached as such).
        if (!hasPermission(app)) return
        job = scope.launch {
            _scanning.value = true
            try {
                scan(app)
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * GPS position (latitude, longitude) of one photo: from the scan cache when known, otherwise
     * read from the file right now. Null if the photo has no location (or no permission).
     */
    suspend fun locationOf(context: Context, id: Long): DoubleArray? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val known = (cache ?: load(app).also { cache = it })[id]
        if (known != null)
            return@withContext if (known.lat.isNaN()) null else doubleArrayOf(known.lat, known.lon)
        if (!hasPermission(app)) return@withContext null
        readLocation(app, id)
    }

    /** Whether Android will let us see GPS data in photos. */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private class Row(val id: Long, val modified: Long, val date: Long)

    private suspend fun scan(context: Context) {
        val cache = cache ?: load(context).also { cache = it }
        // Show what we already know straight away.
        publish(cache)

        // All images (videos are skipped: their location lives in a different container).
        val rows = ArrayList<Row>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val modified = c.getLong(1)
                val taken = c.getLong(2)
                rows += Row(c.getLong(0), modified, if (taken > 0) taken else modified * 1000)
            }
        }

        // Forget deleted photos.
        val alive = HashSet<Long>(rows.size * 2)
        rows.forEach { alive += it.id }
        val removed = cache.keys.retainAll(alive)

        val todo = rows.filter { cache[it.id]?.modified != it.modified }
        _progress.value = 0 to todo.size
        if (todo.isEmpty()) {
            if (removed) {
                publish(cache)
                save(context, cache)
            }
            return
        }
        // Read EXIF in small parallel batches, publishing as we go.
        var done = 0
        for (chunk in todo.chunked(64)) {
            val results = withContext(Dispatchers.IO) {
                chunk.map { row -> async { row to readLocation(context, row.id) } }.awaitAll()
            }
            for ((row, location) in results) {
                cache[row.id] = Entry(
                    row.modified,
                    location?.get(0) ?: Double.NaN,
                    location?.get(1) ?: Double.NaN,
                    row.date
                )
            }
            done += chunk.size
            _progress.value = done to todo.size
            if (done % 512 < 64 || done == todo.size) publish(cache)
        }
        publish(cache)
        save(context, cache)
    }

    private fun readLocation(context: Context, id: Long): DoubleArray? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        // The original (unredacted) file is needed to see GPS data.
        val original = try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: Exception) {
            uri
        }
        return try {
            context.contentResolver.openFileDescriptor(original, "r")?.use { pfd ->
                val latLong = ExifInterface(pfd.fileDescriptor).latLong ?: return null
                // 0,0 is what redacted/garbage data looks like.
                if (latLong[0] == 0.0 && latLong[1] == 0.0) null else latLong
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun publish(cache: HashMap<Long, Entry>) {
        val list = ArrayList<GeoPhoto>()
        for ((id, e) in cache) {
            if (e.lat.isNaN() || e.lon.isNaN()) continue
            list += GeoPhoto(id, e.lat, e.lon, e.date)
        }
        list.sortByDescending { it.date }
        _photos.value = list
    }

    private fun load(context: Context): HashMap<Long, Entry> {
        val map = HashMap<Long, Entry>()
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return map
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_VERSION) return map
                val count = input.readInt()
                repeat(count) {
                    val id = input.readLong()
                    map[id] = Entry(input.readLong(), input.readDouble(), input.readDouble(), input.readLong())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cache unreadable; rescanning", e)
            map.clear()
        }
        return map
    }

    private fun save(context: Context, cache: HashMap<Long, Entry>) {
        try {
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION)
                out.writeInt(cache.size)
                for ((id, e) in cache) {
                    out.writeLong(id)
                    out.writeLong(e.modified)
                    out.writeDouble(e.lat)
                    out.writeDouble(e.lon)
                    out.writeLong(e.date)
                }
            }
            tmp.renameTo(File(context.filesDir, CACHE_FILE))
        } catch (e: Exception) {
            Log.e(TAG, "cache not saved", e)
        }
    }
}
