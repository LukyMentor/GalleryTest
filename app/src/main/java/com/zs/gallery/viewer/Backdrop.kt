package com.zs.gallery.viewer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A picture of the screen (the grid) taken the moment a photo is opened.
 *
 * The viewer draws it behind the photo, so dragging the photo down reveals the grid (like Google
 * Photos), and while the viewer closes the grid underneath gets a few frames to settle (insets,
 * navigation bar) hidden behind an identical picture of itself - no visible jump.
 */
object Backdrop {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Bumped on every capture, so an old viewer closing late can't clear a newer picture. */
    var generation = 0
        private set

    fun capture(window: Window) {
        val generation = ++generation
        image = null
        val view = window.decorView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return
        }
        try {
            // Copies the frame currently on screen: the grid, since the viewer hasn't drawn yet.
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && generation == this.generation)
                    image = bitmap.asImageBitmap()
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            // Window not ready (no surface yet): just go without a backdrop.
        }
    }

    /** Drops the picture after the viewer's closing animation is surely over. */
    fun clearLater() {
        val generation = generation
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation == this.generation) image = null
        }, 800)
    }
}
