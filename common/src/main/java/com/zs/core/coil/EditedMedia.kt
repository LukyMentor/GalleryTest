package com.zs.core.coil

import android.content.ContentUris
import android.content.Context
import android.net.Uri

/**
 * Photos M Gallery rotated or overwrote. Android's own thumbnail of such a photo can stay the old
 * one (e.g. after a rotation that only changes the orientation tag), so for these the app makes its
 * thumbnails from the file itself instead of asking the system for one.
 */
object EditedMedia {
    private const val PREFS = "edited_media"
    private const val KEY = "ids"

    @Volatile
    private var ids: MutableSet<Long>? = null

    private fun load(context: Context): MutableSet<Long> = ids ?: synchronized(this) {
        ids ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())!!
            .mapNotNullTo(HashSet()) { it.toLongOrNull() }
            .also { ids = it }
    }

    fun mark(context: Context, id: Long) {
        val set = load(context)
        synchronized(this) {
            if (!set.add(id)) return
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, set.mapTo(HashSet()) { it.toString() }).apply()
        }
    }

    fun mark(context: Context, uri: Uri) {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
        if (id >= 0) mark(context, id)
    }

    fun contains(context: Context, uri: Uri): Boolean {
        val set = load(context)
        if (set.isEmpty()) return false
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return false
        return id in set
    }
}
