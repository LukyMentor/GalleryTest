package com.zs.gallery.picker

import com.zs.gallery.common.tr
import com.zs.gallery.common.photosText
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue

/**
 * Gallery M opened by another app to pick photos ("attach a photo"). The whole app works as
 * usual (Photos, Folders, People, Map); opening a photo picks it instead. With several allowed,
 * taps select (drag-select works too) and "Add" sends them.
 */
object PickMode {
    var active by mutableStateOf(false)
        private set
    var multiple by mutableStateOf(false)
        private set
    var imagesOnly = false
        private set
    var videosOnly = false
        private set
    /** Name of the app asking, if known ("Chrome"). */
    var caller: String? = null
        private set

    /** Chosen so far (several allowed), shared by every screen. */
    val selected = mutableStateSetOf<Long>()

    private var activity: Activity? = null

    fun start(activity: Activity, multiple: Boolean, type: String?, caller: String?) {
        this.activity = activity
        this.multiple = multiple
        val t = type.orEmpty()
        imagesOnly = t.startsWith("image/") || t.contains("dir/image")
        videosOnly = t.startsWith("video/") || t.contains("dir/video")
        this.caller = caller
        selected.clear()
        active = true
    }

    fun stop(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        active = false
        multiple = false
        selected.clear()
    }

    /** A photo was opened somewhere in the app: pick it (one) or toggle it (several). */
    fun onOpen(id: Long) {
        val a = activity ?: return
        val video = isVideo(a, id) ?: return
        if ((imagesOnly && video) || (videosOnly && !video)) {
            Toast.makeText(a, if (imagesOnly) tr("Choose a photo", "Vyberte fotku") else tr("Choose a video", "Vyberte video"), Toast.LENGTH_SHORT).show()
            return
        }
        if (multiple) {
            if (!selected.remove(id)) selected.add(id)
        } else deliver(listOf(id))
    }

    fun cancel() {
        val a = activity ?: return
        a.setResult(Activity.RESULT_CANCELED)
        a.finish()
    }

    fun deliver(ids: List<Long>) {
        val a = activity ?: return
        val uris = ids.mapNotNull { id -> isVideo(a, id)?.let { uriOf(id, it) } }
        if (uris.isEmpty()) return cancel()
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
                for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.setResult(Activity.RESULT_OK, result)
        a.finish()
    }

    private fun uriOf(id: Long, video: Boolean): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
    )

    /** Whether [id] is a video (null if it doesn't exist). */
    private fun isVideo(activity: Activity, id: Long): Boolean? {
        val uri = MediaStore.Files.getContentUri("external", id)
        return activity.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else null }
    }
}
