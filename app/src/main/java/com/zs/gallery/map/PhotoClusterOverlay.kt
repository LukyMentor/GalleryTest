/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.map

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.MediaStore
import android.util.LruCache
import android.view.MotionEvent
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.zs.core.store.GeoPhoto
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Photos grouped into one bubble on the map at the current zoom; newest photo first. */
class PhotoCluster(val photos: List<GeoPhoto>, val point: GeoPoint)

/**
 * Draws photos on the map as round thumbnails, grouped into clusters (with a count badge) that
 * split up as you zoom in. Tapping a cluster calls [onClusterTap].
 */
class PhotoClusterOverlay(
    private val context: Context,
    private val accent: Int,
    private val onClusterTap: (PhotoCluster) -> Unit,
) : Overlay() {

    private val density = context.resources.displayMetrics.density
    private val bubble = 52f * density
    private val cell = 64f * density

    var photos: List<GeoPhoto> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            clusters = emptyList()
            clusteredZoom = Double.NaN
        }

    private var clusters: List<PhotoCluster> = emptyList()
    private var clusteredZoom = Double.NaN

    // Thumbnails, loaded through Coil (MediaStore thumbnails, so it's fast).
    private val thumbs = LruCache<Long, Bitmap>(300)
    private val pending = HashSet<Long>()
    private var mapView: MapView? = null

    private val point = Point()
    private val src = Rect()
    private val dst = RectF()
    private val clip = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = android.graphics.Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9EB8.toInt() }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    /** Groups photos into grid cells of the world map at [zoom] (Web Mercator pixels). */
    private fun cluster(zoom: Double): List<PhotoCluster> {
        val world = 256.0 * density * 2.0.pow(zoom)
        val cells = LinkedHashMap<Long, MutableList<GeoPhoto>>()
        for (p in photos) {
            val x = (p.longitude + 180.0) / 360.0 * world
            val latRad = p.latitude.coerceIn(-85.0, 85.0) * PI / 180.0
            val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * world
            val cx = floor(x / cell).toLong()
            val cy = floor(y / cell).toLong()
            cells.getOrPut(cx * 1_000_003L + cy) { ArrayList() } += p
        }
        return cells.values.map { list ->
            var lat = 0.0
            var lon = 0.0
            for (p in list) {
                lat += p.latitude; lon += p.longitude
            }
            PhotoCluster(list, GeoPoint(lat / list.size, lon / list.size))
        }
    }

    private fun clustersFor(map: MapView): List<PhotoCluster> {
        val zoom = map.zoomLevelDouble
        // Re-cluster only when the zoom changed noticeably (panning keeps clusters stable).
        if (clusteredZoom.isNaN() || abs(zoom - clusteredZoom) > 0.2) {
            clusters = cluster(zoom)
            clusteredZoom = zoom
        }
        return clusters
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || photos.isEmpty()) return
        mapView = map
        val projection = map.projection
        val margin = bubble
        val w = map.width
        val h = map.height
        for (cluster in clustersFor(map)) {
            projection.toPixels(cluster.point, point)
            val x = point.x.toFloat()
            val y = point.y.toFloat()
            if (x < -margin || y < -margin || x > w + margin || y > h + margin) continue
            drawBubble(canvas, cluster, x, y)
        }
    }

    private fun drawBubble(canvas: Canvas, cluster: PhotoCluster, x: Float, y: Float) {
        val r = bubble / 2
        canvas.drawCircle(x, y + 2 * density, r + 1.5f * density, shadowPaint)
        val cover = cluster.photos.first()
        val bitmap = thumbs.get(cover.id)
        dst.set(x - r, y - r, x + r, y + r)
        if (bitmap != null && !bitmap.isRecycled) {
            // Centre-crop the thumbnail into the circle.
            val side = min(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2
            val top = (bitmap.height - side) / 2
            src.set(left, top, left + side, top + side)
            canvas.save()
            clip.reset()
            clip.addCircle(x, y, r, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawCircle(x, y, r, placeholderPaint)
            load(cover.id)
        }
        canvas.drawCircle(x, y, r, ringPaint)
        // Count badge
        val count = cluster.photos.size
        if (count > 1) {
            val text = if (count > 999) "999+" else count.toString()
            val tw = badgeText.measureText(text)
            val bh = 20f * density
            val bw = max(bh, tw + 12f * density)
            val bx = x + r * 0.7f
            val by = y - r * 0.7f
            dst.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
            canvas.drawRoundRect(dst, bh / 2, bh / 2, badgePaint)
            canvas.drawText(text, bx, by - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
        }
    }

    private fun load(id: Long) {
        if (!pending.add(id)) return
        val size = bubble.toInt()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(size, size)
            .allowHardware(false)
            .target(
                onSuccess = { image ->
                    thumbs.put(id, image.toBitmap())
                    pending.remove(id)
                    mapView?.postInvalidate()
                },
                onError = { pending.remove(id) }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val projection = map.projection
        val r = bubble / 2 + 6 * density
        var best: PhotoCluster? = null
        var bestDistance = Float.MAX_VALUE
        for (cluster in clustersFor(map)) {
            projection.toPixels(cluster.point, point)
            val dx = point.x - e.x
            val dy = point.y - e.y
            val d = dx * dx + dy * dy
            if (d <= r * r && d < bestDistance) {
                best = cluster
                bestDistance = d
            }
        }
        val hit = best ?: return false
        onClusterTap(hit)
        return true
    }

    override fun onDetach(map: MapView) {
        mapView = null
        thumbs.evictAll()
        super.onDetach(map)
    }
}
