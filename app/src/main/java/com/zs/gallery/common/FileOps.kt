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

    private val dialogTheme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** Lets the user pick a destination: existing folders + "New folder…". Null if cancelled. */
    suspend fun pickFolder(activity: Activity, title: String, folders: List<String>): String? {
        val choices = folders.mapNotNull { f -> relativePathOf(f)?.let { f to it } }
            .sortedBy { it.second.lowercase() }
        val labels = (listOf("+  New folder…") + choices.map { it.second.trimEnd('/') }).toTypedArray()
        val picked = suspendCancellableCoroutine<Int?> { cont ->
            val dialog = AlertDialog.Builder(activity, dialogTheme)
                .setTitle(title)
                .setItems(labels) { _, which -> if (cont.isActive) cont.resume(which) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        } ?: return null
        if (picked > 0) return choices[picked - 1].second
        // New folder: under Pictures/ unless the user types a full path like "DCIM/Trips".
        val name = askText(activity, "New folder", "", hint = "e.g. Trips or DCIM/Trips")
            ?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
        val top = name.substringBefore('/')
        val allowed = setOf("DCIM", "Pictures", "Movies", "Download", "Documents")
        return (if (top in allowed) name else "Pictures/$name") + "/"
    }

    /** A one-line text prompt; null if cancelled. */
    suspend fun askText(activity: Activity, title: String, initial: String, hint: String? = null): String? =
        suspendCancellableCoroutine { cont ->
            val input = EditText(activity).apply {
                setText(initial)
                setSelection(initial.length)
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                if (hint != null) this.hint = hint
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val box = FrameLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            val dialog = AlertDialog.Builder(activity, dialogTheme)
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

    /** Moves [files] into [relativePath] (e.g. "Pictures/Trips/"). Returns how many moved. */
    suspend fun move(activity: Activity, files: List<MediaFile>, relativePath: String): Int {
        if (files.isEmpty()) return 0
        val uris = files.map { it.mediaUri }
        if (!requestWrite(activity, uris)) return 0
        val moved = withContext(Dispatchers.IO) {
            files.count { file ->
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    activity.contentResolver.update(file.mediaUri, values, null, null) > 0
                }.getOrDefault(false)
            }
        }
        activity.toast(
            if (moved == files.size) "Moved ${plural(moved)} to ${relativePath.trimEnd('/')}"
            else "Moved $moved of ${files.size}; some can't go to that folder"
        )
        return moved
    }

    /** Copies [files] into [relativePath]. Returns how many were copied. */
    suspend fun copy(activity: Activity, files: List<MediaFile>, relativePath: String): Int {
        if (files.isEmpty()) return 0
        val resolver = activity.contentResolver
        val copied = withContext(Dispatchers.IO) {
            files.count { file ->
                runCatching {
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
                    val target = resolver.insert(collection, values) ?: return@runCatching false
                    try {
                        resolver.openInputStream(file.mediaUri).use { input ->
                            resolver.openOutputStream(target).use { output ->
                                requireNotNull(input).copyTo(requireNotNull(output))
                            }
                        }
                        resolver.update(
                            target,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null, null
                        )
                        true
                    } catch (e: Exception) {
                        resolver.delete(target, null, null)
                        false
                    }
                }.getOrDefault(false)
            }
        }
        activity.toast(
            if (copied == files.size) "Copied ${plural(copied)} to ${relativePath.trimEnd('/')}"
            else "Copied $copied of ${files.size}"
        )
        return copied
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
