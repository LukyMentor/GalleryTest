package com.zs.gallery.people

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Scans the photo library for faces in the background (one low-priority thread, while the app
 * process is alive) and groups them into people. Progress is in [status] (shown on the People
 * screen); [version] changes whenever the groups change.
 */
object FaceIndexer {

    data class Status(
        val running: Boolean = false,
        val paused: Boolean = false,
        val total: Int = 0,
        val scanned: Int = 0,
        val faces: Int = 0,
        val people: Int = 0,
        val msPerPhoto: Int = 0,
        val current: String? = null,
        val error: String? = null,
        val note: String? = null,
    )

    /** Face must look at least this similar to a group's average to join it. */
    private const val ASSIGN = 0.42f
    /** Groups whose averages are this similar are the same person. */
    private const val MERGE = 0.55f
    /** Photos are decoded with this longer side for scanning. */
    private const val DECODE = 1280
    private const val MIN_FACE_PX = 36f
    private const val MIN_SCORE = 0.6f
    private const val MAX_FACES = 12

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "face-scan")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    @Volatile
    private var store: FaceStore? = null

    internal fun store(context: Context): FaceStore = store ?: synchronized(this) {
        store ?: FaceStore(context).also { store = it }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("faces", Context.MODE_PRIVATE)

    fun isPaused(context: Context) = prefs(context).getBoolean("paused", false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean("paused", paused).apply()
        _status.update { it.copy(paused = paused) }
        if (paused) job?.cancel() else start(context)
    }

    /** Called by the UI after changes it made (rename, move, ...). */
    fun changed() = _version.update { it + 1 }

    /** Starts (or continues) scanning, unless paused or already running. */
    fun start(context: Context) {
        val app = context.applicationContext
        val paused = isPaused(app)
        _status.update { it.copy(paused = paused) }
        if (paused || job?.isActive == true) return
        if (!hasPermission(app)) {
            _status.update { it.copy(error = "No access to photos yet") }
            return
        }
        job = scope.launch {
            _status.update { it.copy(running = true, error = null, note = "Looking for new photos…") }
            try {
                run(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.update { it.copy(error = e.toString()) }
            } finally {
                _status.update { it.copy(running = false, current = null) }
            }
        }
    }

    /** Re-groups everything from scratch (keeps the user's own moves). */
    fun regroup(context: Context) {
        val app = context.applicationContext
        scope.launch {
            _status.update { it.copy(note = "Re-grouping…") }
            val store = store(app)
            store.resetGrouping()
            group(store)
            _status.update { it.copy(note = "Re-grouped") }
        }
    }

    /** Forgets everything and scans again. */
    fun reset(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        scope.launch {
            store(app).clearAll()
            _status.update { Status(paused = isPaused(app), note = "Reset - scanning again") }
            changed()
            start(app)
        }
    }

    private fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private class Photo(val id: Long, val modified: Long, val name: String)

    private fun listPhotos(context: Context): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2).orEmpty()
                // Screenshots and tiny images aren't worth scanning.
                if (path.contains("Screenshots", ignoreCase = true)) continue
                val w = c.getInt(3)
                val h = c.getInt(4)
                if (w in 1..199 || h in 1..199) continue
                val mime = c.getString(5).orEmpty()
                if (!mime.startsWith("image/") || mime == "image/gif") continue
                list += Photo(c.getLong(0), c.getLong(1), c.getString(6).orEmpty())
            }
        }
        return list
    }

    private suspend fun run(context: Context) {
        val store = store(context)
        val photos = listPhotos(context)
        val known = store.scanned()
        val existing = photos.mapTo(HashSet()) { it.id }
        store.removeMedia(known.keys.filter { it !in existing })
        val todo = photos.filter { known[it.id] != it.modified }
        _status.update {
            it.copy(
                total = photos.size, scanned = photos.size - todo.size,
                faces = store.faceCount(), note = if (todo.isEmpty()) "Up to date" else "Scanning"
            )
        }
        if (todo.isEmpty()) {
            group(store)
            return
        }
        val engine = FaceEngine(context)
        try {
            var pending = 0
            var sinceMerge = 0
            var time = 0L
            var done = 0
            for (photo in todo) {
                coroutineContext.ensureActive()
                // Let a "Re-group" request in between photos.
                kotlinx.coroutines.yield()
                val start = SystemClock.elapsedRealtime()
                _status.update { it.copy(current = photo.name) }
                val faces = runCatching { process(context, engine, photo.id) }.getOrDefault(emptyList())
                store.saveScan(photo.id, photo.modified, faces)
                done++
                time += SystemClock.elapsedRealtime() - start
                pending += faces.size
                sinceMerge += faces.size
                _status.update {
                    it.copy(
                        scanned = it.scanned + 1, faces = it.faces + faces.size,
                        msPerPhoto = (time / done).toInt()
                    )
                }
                // Group as we go, so people show up while the scan is still running.
                if (pending >= 40) {
                    // Joining groups compares every pair: only now and then.
                    val merge = sinceMerge >= 600
                    group(store, merge)
                    if (merge) sinceMerge = 0
                    pending = 0
                }
            }
            group(store)
            _status.update { it.copy(note = "Up to date") }
        } finally {
            engine.close()
        }
    }

    /** Finds the faces in one photo: (box in 0..1, (score, embedding)). */
    private fun process(context: Context, engine: FaceEngine, id: Long): List<Pair<RectF, Pair<Float, FloatArray>>> {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, DECODE.toFloat() / max(w, h))
            if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
        try {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            return engine.detect(bitmap)
                .filter { it.score >= MIN_SCORE && min(it.box.width(), it.box.height()) >= MIN_FACE_PX }
                .take(MAX_FACES)
                .map { face ->
                    val box = RectF(
                        (face.box.left / w).coerceIn(0f, 1f), (face.box.top / h).coerceIn(0f, 1f),
                        (face.box.right / w).coerceIn(0f, 1f), (face.box.bottom / h).coerceIn(0f, 1f)
                    )
                    box to (face.score to engine.embed(bitmap, face.landmarks))
                }
        } finally {
            bitmap.recycle()
        }
    }

    // --- grouping ---------------------------------------------------------------------------------

    private class Centre(val sum: FloatArray, var count: Int) {
        val unit = FloatArray(sum.size)
        fun refresh() {
            var n = 0f
            for (v in sum) n += v * v
            n = sqrt(n).coerceAtLeast(1e-6f)
            for (i in sum.indices) unit[i] = sum[i] / n
        }
    }

    private fun centres(store: FaceStore): HashMap<Long, Centre> {
        val map = HashMap<Long, Centre>()
        for ((person, list) in store.embeddingsByPerson()) {
            val sum = FloatArray(list.first().size)
            for (e in list) for (i in e.indices) sum[i] += e[i]
            map[person] = Centre(sum, list.size).also { it.refresh() }
        }
        return map
    }

    /** Puts new faces into the closest group (or a new one), then joins groups of one person. */
    @Synchronized
    private fun group(store: FaceStore, merge: Boolean = true) {
        val centres = centres(store)
        val ungrouped = store.ungrouped()
        if (ungrouped.isNotEmpty()) store.inTransaction {
            for ((faceId, e) in ungrouped) {
                var best = -1L
                var bestSim = ASSIGN
                for ((person, c) in centres) {
                    val s = FaceEngine.similarity(c.unit, e)
                    if (s >= bestSim) { bestSim = s; best = person }
                }
                if (best < 0) {
                    best = store.newPerson()
                    centres[best] = Centre(e.copyOf(), 1).also { it.refresh() }
                } else {
                    val c = centres.getValue(best)
                    for (i in e.indices) c.sum[i] += e[i]
                    c.count++
                    c.refresh()
                }
                store.assign(faceId, best)
            }
        }
        if (!merge) {
            _status.update { it.copy(people = store.people().count { p -> p.photos >= 3 }) }
            changed()
            return
        }
        // Join groups that are clearly the same person (never two different names).
        val names = store.names()
        val ids = centres.keys.toList()
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            return r
        }
        val groupName = HashMap<Long, String?>()
        for (id in ids) { parent[id] = id; groupName[id] = names[id] }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            if (FaceEngine.similarity(centres.getValue(a).unit, centres.getValue(b).unit) < MERGE) continue
            val ra = find(a); val rb = find(b)
            if (ra == rb) continue
            val na = groupName[ra]; val nb = groupName[rb]
            if (na != null && nb != null && na != nb) continue
            // The bigger group stays.
            val (keep, gone) = if (centres.getValue(ra).count >= centres.getValue(rb).count) ra to rb else rb to ra
            parent[gone] = keep
            groupName[keep] = groupName[keep] ?: groupName[gone]
        }
        for (id in ids) {
            val root = find(id)
            if (root != id) store.merge(id, root)
        }
        store.dropEmptyPersons()
        _status.update { it.copy(people = store.people().count { p -> p.photos >= 3 }) }
        changed()
    }
}
