/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.common

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.zs.core.store.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Move / copy / rename of photos and videos through MediaStore.
 *
 * Changing files other apps created needs the user's OK (one system dialog for the whole batch);
 * with "Media management" special access granted, Android skips that dialog. Copies are new files
 * owned by this app, so they need no permission.
 */
object FileOps {

    private val root: String get() = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"

    /** "/storage/emulated/0/DCIM/Camera" -> "DCIM/Camera/" (null if not on the main storage). */
    fun relativePathOf(folder: String): String? {
        val dir = folder.trimEnd('/') + "/"
        return if (dir.startsWith(root)) dir.removePrefix(root) else null
    }

    private fun Activity.toast(text: String) = runOnUiThread {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------------
    // Dialogs (plain platform dialogs; they follow the system light/dark theme)
    // ---------------------------------------------------------------------------------------------

    /** Set by the UI from the app's own light/dark setting (which can differ from the system's). */
    @Volatile
    var darkTheme: Boolean = false

    private val dialogTheme: Int
        get() = if (darkTheme) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** Lets the user pick a destination: existing folders + "New folder…". Null if cancelled. */
    suspend fun pickFolder(
        activity: Activity, title: String, folders: List<com.zs.core.store.Folder>, note: String? = null,
    ): String? {
        // Most recently used folders first (usually where things go).
        val choices = folders.mapNotNull { f -> relativePathOf(f.path)?.let { f to it } }
            .sortedByDescending { it.first.lastModified }
        val picked = FolderPicker.pick(title, choices, note) ?: return null
        if (picked != FolderPicker.NEW_FOLDER) return picked
        return askNewFolder(activity)
    }

    /**
     * Asks for a new folder name; returns its relative path ("Pictures/Trips/"), or null.
     * Folders go under Pictures/ unless the user types a full path like "DCIM/Trips".
     * (Android only shows a folder once it contains a photo or video.)
     */
    suspend fun askNewFolder(activity: Activity): String? {
        val name = askText(activity, "New folder", "", hint = "e.g. Trips or DCIM/Trips")
            ?.trim()?.trim('/')?.replace(Regex("[\\\\:*?\"<>|]"), "_")
            ?.takeIf { it.isNotEmpty() } ?: return null
        val top = name.substringBefore('/')
        val allowed = setOf("DCIM", "Pictures", "Movies", "Download", "Documents")
        return (if (top in allowed) name else "Pictures/$name") + "/"
    }

    /** A one-line text prompt; null if cancelled. */
    suspend fun askText(activity: Activity, title: String, initial: String, hint: String? = null): String? =
        suspendCancellableCoroutine { cont ->
            val builder = AlertDialog.Builder(activity, dialogTheme)
            // Created from the dialog's themed context, so the text is white on dark and black on
            // light (it used to inherit the activity's theme and stay black).
            val input = EditText(builder.context).apply {
                setText(initial)
                setSelection(initial.length)
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                if (hint != null) this.hint = hint
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val box = FrameLayout(builder.context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            val dialog = builder
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (cont.isActive) cont.resume(input.text.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(null) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .show()
            input.requestFocus()
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    // ---------------------------------------------------------------------------------------------
    // Permission for changing other apps' files
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("NewApi")
    private suspend fun requestWrite(activity: Activity, uris: List<Uri>): Boolean {
        // Android 10 has no batch request; the update itself will tell whether it's allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val owner = activity as? ComponentActivity ?: return false
        val pending = MediaStore.createWriteRequest(activity.contentResolver, uris)
        return suspendCancellableCoroutine { cont ->
            val key = UUID.randomUUID().toString()
            val holder = arrayOfNulls<ActivityResultLauncher<IntentSenderRequest>>(1)
            val launcher = owner.activityResultRegistry.register(
                key, ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                holder[0]?.unregister()
                if (cont.isActive) cont.resume(result.resultCode == Activity.RESULT_OK)
            }
            holder[0] = launcher
            launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------------------------------

    /**
     * "Move to..." / "Copy to..." from the folder sheet. The work itself runs in the background
     * ([FileJobs]); items a folder refuses (e.g. photos into Movies/) come back as a folder sheet
     * for just those, so a careful selection isn't lost.
     */
    suspend fun moveOrCopy(
        activity: Activity, files: List<MediaFile>, move: Boolean, folders: List<com.zs.core.store.Folder>,
    ) = continueMoveOrCopy(activity, files, move, folders, null, 0, emptyList())

    /** One round: pick a folder (with [note] about the last round), then start the job. */
    suspend fun continueMoveOrCopy(
        activity: Activity, files: List<MediaFile>, move: Boolean, folders: List<com.zs.core.store.Folder>,
        note: String?, done: Int, placed: List<String>,
    ) {
        if (files.isEmpty()) return
        val target = pickFolder(activity, if (move) "Move to" else "Copy to", folders, note)
        if (target == null) {
            // Gave up on the leftovers.
            if (note != null) {
                val past = if (move) "Moved" else "Copied"
                activity.toast(
                    if (done == 0) "Nothing was ${past.lowercase()}"
                    else "$past ${plural(done)}; ${plural(files.size)} stayed where they were"
                )
            }
            return
        }
        startJob(activity, files, move, target, folders, done, placed)
    }

    /** "Move here" / "Copy here" (new folder): starts the job. False if it couldn't start. */
    suspend fun moveOrCopyTo(
        activity: Activity, files: List<MediaFile>, move: Boolean, target: String,
        folders: List<com.zs.core.store.Folder>,
    ): Boolean = startJob(activity, files, move, target, folders, 0, emptyList())

    private suspend fun startJob(
        activity: Activity, files: List<MediaFile>, move: Boolean, target: String,
        folders: List<com.zs.core.store.Folder>, done: Int, placed: List<String>,
    ): Boolean {
        if (move && !requestWrite(activity, files.map { it.mediaUri })) {
            activity.toast("Not allowed to move these")
            return false
        }
        askForNotifications(activity)
        FileJobs.start(activity, files, target, move, folders, done, placed)
        return true
    }

    /** Progress shows as a notification; ask once for permission to show it (Android 13+). */
    private fun askForNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (activity.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences("file_jobs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        activity.requestPermissions(arrayOf(perm), 4711)
    }

    /** Moves [files] into [relativePath]; returns the ones that couldn't be moved. */
    internal suspend fun moveFiles(
        resolver: android.content.ContentResolver, files: List<MediaFile>, relativePath: String,
        onFile: (Int) -> Unit,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val failed = ArrayList<MediaFile>()
        files.forEachIndexed { i, file ->
            ensureActive()
            onFile(i)
            val ok = try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
                resolver.update(file.mediaUri, values, null, null) > 0
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) failed += file
        }
        failed
    }

    /** Copies [files] into [relativePath]; returns the ones that couldn't be copied. */
    internal suspend fun copyFiles(
        resolver: android.content.ContentResolver, files: List<MediaFile>, relativePath: String,
        onFile: (Int) -> Unit, onBytes: (Long) -> Unit,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val failed = ArrayList<MediaFile>()
        val buffer = ByteArray(256 * 1024)
        files.forEachIndexed { i, file ->
            ensureActive()
            onFile(i)
            val collection = if (file.isImage)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (file.dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, file.dateTaken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = try { resolver.insert(collection, values) } catch (e: Exception) { null }
            if (target == null) { failed += file; return@forEachIndexed }
            var ok = false
            try {
                resolver.openInputStream(file.mediaUri).use { input ->
                    resolver.openOutputStream(target).use { output ->
                        requireNotNull(input); requireNotNull(output)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
                resolver.update(
                    target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
                )
                ok = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // counted as failed below
            } finally {
                // Unfinished (failed or cancelled): no half-copied file left behind.
                if (!ok) runCatching { resolver.delete(target, null, null) }
            }
            if (!ok) failed += file
        }
        failed
    }

    /**
     * Rotates [file] by 90° in place. JPEG / WebP: only the orientation tag changes (lossless);
     * PNG (e.g. screenshots): the pixels are turned and saved again (still lossless).
     */
    suspend fun rotate(activity: Activity, file: MediaFile, clockwise: Boolean): Boolean {
        val mime = file.mimeType
        val exifFormat = mime == "image/jpeg" || mime == "image/webp"
        if (!exifFormat && mime != "image/png") {
            activity.toast("This photo format can't be rotated")
            return false
        }
        if (!requestWrite(activity, listOf(file.mediaUri))) return false
        val resolver = activity.contentResolver
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                if (exifFormat) {
                    resolver.openFileDescriptor(file.mediaUri, "rw").use { pfd ->
                        val exif = androidx.exifinterface.media.ExifInterface(requireNotNull(pfd).fileDescriptor)
                        exif.rotate(if (clockwise) 90 else -90)
                        exif.saveAttributes()
                    }
                } else {
                    val source = android.graphics.ImageDecoder.createSource(resolver, file.mediaUri)
                    val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val matrix = android.graphics.Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
                    val turned = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    resolver.openOutputStream(file.mediaUri, "wt").use { out ->
                        turned.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(out))
                    }
                }
                true
            }.getOrDefault(false)
        }
        if (ok) com.zs.core.coil.EditedMedia.mark(activity, file.id)
        if (!ok) activity.toast("Couldn't rotate the photo")
        // Old (unrotated) pictures must not come back from the in-memory cache.
        else coil3.SingletonImageLoader.get(activity).memoryCache?.clear()
        return ok
    }

    /** Asks for a new name (extension kept) and renames [file]. */
    suspend fun rename(activity: Activity, file: MediaFile): Boolean {
        val dot = file.name.lastIndexOf('.')
        val base = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        val typed = askText(activity, "Rename", base)?.trim()
            ?.replace('/', '_')?.takeIf { it.isNotEmpty() && it != base } ?: return false
        if (!requestWrite(activity, listOf(file.mediaUri))) return false
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, typed + ext)
                }
                activity.contentResolver.update(file.mediaUri, values, null, null) > 0
            }.getOrDefault(false)
        }
        activity.toast(if (ok) "Renamed to $typed$ext" else "Couldn't rename (name already taken?)")
        return ok
    }

    private fun plural(n: Int) = if (n == 1) "1 item" else "$n items"
}
