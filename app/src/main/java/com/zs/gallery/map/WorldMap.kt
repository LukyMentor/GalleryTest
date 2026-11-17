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
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.zs.core.store.GeoPhoto
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Photos grouped into one bubble at the current zoom; newest photo first. */
class PhotoCluster(val photos: List<GeoPhoto>, val x: Float, val y: Float)

/** Normalized Web-Mercator coordinates (0..1) of a latitude/longitude. */
internal fun mercator(latitude: Double, longitude: Double): Pair<Float, Float> {
    val x = (longitude + 180.0) / 360.0
    val r = latitude.coerceIn(-85.0, 85.0) * PI / 180.0
    val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    return x.toFloat() to y.toFloat()
}

private const val MAX_ZOOM = 12f

/** Map colours for light/dark theme. */
private class MapColors(
    val water: Color,
    val land: Color,
    val border: Color,
    val coast: Color,
    val city: Color,
    val cityHalo: Color,
)

private val LightMap = MapColors(
    water = Color(0xFFCFDDEA), land = Color(0xFFF3F0E8), border = Color(0xFFC5BCB0),
    coast = Color(0xFFAFC3D6), city = Color(0xFF4A4A55), cityHalo = Color(0xCCF3F0E8),
)
private val DarkMap = MapColors(
    water = Color(0xFF0D1117), land = Color(0xFF1D212A), border = Color(0xFF363B47),
    coast = Color(0xFF2A3140), city = Color(0xFF9EA4B0), cityHalo = Color(0xCC1D212A),
)

/**
 * Fully offline world map (no internet, no API keys) with photos drawn on it as clustered round
 * thumbnails. Pinch/drag to move, double-tap to zoom in, tap a bubble to see its photos.
 */
@Composable
fun WorldMap(
    photos: List<GeoPhoto>,
    dark: Boolean,
    accent: Color,
    onClusterTap: (PhotoCluster) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = if (dark) DarkMap else LightMap
    var world by remember { mutableStateOf<WorldData?>(null) }
    LaunchedEffect(Unit) { world = WorldData.load(context) }

    // Camera: zoom (0 = whole world in 256 dp) and the centre in normalized coordinates.
    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.42f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val tilePx = with(density) { 256.dp.toPx() }
    fun worldSize(z: Float = zoom) = tilePx * 2f.pow(z)
    fun minZoom(): Float =
        if (viewport.width == 0) 0f else log2(viewport.width / tilePx).coerceAtLeast(0f)

    fun clampCamera() {
        zoom = zoom.coerceIn(minZoom(), MAX_ZOOM)
        val w = worldSize()
        val halfW = viewport.width / 2f / w
        val halfH = viewport.height / 2f / w
        cx = if (halfW >= 0.5f) 0.5f else cx.coerceIn(halfW, 1f - halfW)
        cy = if (halfH >= 0.5f) 0.5f else cy.coerceIn(halfH, 1f - halfH)
    }

    // Photo positions (projected once).
    val points = remember(photos) { photos.map { mercator(it.latitude, it.longitude) } }

    // Start by showing all photos (once they're known).
    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(points, viewport) {
        if (fitted || points.isEmpty() || viewport == IntSize.Zero) return@LaunchedEffect
        fitted = true
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val spanX = max(maxX - minX, 0.002f) * 1.4f
        val spanY = max(maxY - minY, 0.002f) * 1.4f
        val fit = min(viewport.width / (spanX * tilePx), viewport.height / (spanY * tilePx))
        zoom = log2(fit).coerceAtMost(10f)
        cx = (minX + maxX) / 2
        cy = (minY + maxY) / 2
        clampCamera()
    }

    // Clusters for the current zoom (recomputed only when the zoom changes noticeably).
    val bubblePx = with(density) { 52.dp.toPx() }
    val cellPx = with(density) { 64.dp.toPx() }
    // Plain (non-state) cache: it's filled while drawing, which must not trigger redraws.
    val cache = remember(points) { ClusterCache() }
    fun clustersNow(): List<PhotoCluster> {
        if (abs(zoom - cache.zoom) > 0.2f) {
            val cell = cellPx / worldSize()
            val cells = LinkedHashMap<Long, MutableList<Int>>()
            points.forEachIndexed { i, (x, y) ->
                val key = floor(x / cell).toLong() * 1_000_003L + floor(y / cell).toLong()
                cells.getOrPut(key) { ArrayList() } += i
            }
            cache.clusters = cells.values.map { idx ->
                PhotoCluster(
                    idx.map { photos[it] },
                    idx.map { points[it].first }.average().toFloat(),
                    idx.map { points[it].second }.average().toFloat(),
                )
            }
            cache.zoom = zoom
        }
        return cache.clusters
    }

    // Thumbnails for bubbles, loaded on demand through Coil (MediaStore thumbnails; fast).
    val thumbs = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val requested = remember { HashSet<Long>() }
    val requests = remember { Channel<Long>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        val size = bubblePx.roundToInt()
        for (id in requests) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(uri).size(size, size).allowHardware(false).build()
            )
            (result as? SuccessResult)?.image?.toBitmap()?.let { thumbs[id] = it.asImageBitmap() }
        }
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val cityStyle = TextStyle(fontSize = 11.sp, color = colors.city, fontWeight = FontWeight.Medium)
    val badgeStyle = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }

    fun toScreen(x: Float, y: Float): Offset {
        val w = worldSize()
        return Offset((x - cx) * w + viewport.width / 2f, (y - cy) * w + viewport.height / 2f)
    }

    fun clusterAt(p: Offset): PhotoCluster? {
        val r = bubblePx / 2 + with(density) { 6.dp.toPx() }
        return clustersNow()
            .map { it to (toScreen(it.x, it.y) - p).getDistance() }
            .filter { it.second <= r }
            .minByOrNull { it.second }?.first
    }

    /** Zoom by [dz] keeping the world point under [focus] (screen px) fixed. */
    fun zoomAround(focus: Offset, dz: Float) {
        val w0 = worldSize()
        val fx = cx + (focus.x - viewport.width / 2f) / w0
        val fy = cy + (focus.y - viewport.height / 2f) / w0
        zoom = (zoom + dz).coerceIn(minZoom(), MAX_ZOOM)
        val w1 = worldSize()
        cx = fx - (focus.x - viewport.width / 2f) / w1
        cy = fy - (focus.y - viewport.height / 2f) / w1
        clampCamera()
    }

    Canvas(
        modifier = modifier
            .onSizeChanged {
                viewport = it
                clampCamera()
            }
            .pointerInput(Unit) {
                coroutineScope {
                    var lastTapTime = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        motion?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var moved = 0f
                        var multi = false
                        var upTime = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) {
                                upTime = event.changes.first().uptimeMillis
                                break
                            }
                            if (pressed > 1) multi = true
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            moved += pan.getDistance()
                            if (zoomChange != 1f && centroid != Offset.Unspecified)
                                zoomAround(centroid, log2(zoomChange))
                            if (pan != Offset.Zero) {
                                val w = worldSize()
                                cx -= pan.x / w
                                cy -= pan.y / w
                                clampCamera()
                            }
                            event.changes.firstOrNull { it.id == down.id && it.pressed }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                            event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                        val isTap = !multi && moved < viewConfiguration.touchSlop &&
                                upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                        if (isTap) {
                            val hit = clusterAt(down.position)
                            if (hit != null) {
                                onClusterTap(hit)
                                lastTapTime = 0L
                            } else if (upTime - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
                                (down.position - lastTapPos).getDistance() < 48.dp.toPx()
                            ) {
                                // Double tap: animate one zoom level in around the finger.
                                lastTapTime = 0L
                                val focus = down.position
                                motion = launch {
                                    val anim = Animatable(0f)
                                    var applied = 0f
                                    anim.animateTo(1f, tween(250)) {
                                        zoomAround(focus, value - applied)
                                        applied = value
                                    }
                                }
                            } else {
                                lastTapTime = upTime
                                lastTapPos = down.position
                            }
                        } else if (!multi) {
                            // Fling the map.
                            val v = tracker.calculateVelocity()
                            val speed = kotlin.math.hypot(v.x, v.y)
                            if (speed > 300f) {
                                motion = launch {
                                    var last = 0f
                                    AnimationState(0f, speed).animateDecay(exponentialDecay(frictionMultiplier = 2f)) {
                                        val d = value - last
                                        last = value
                                        val w = worldSize()
                                        cx -= d * (v.x / speed) / w
                                        cy -= d * (v.y / speed) / w
                                        clampCamera()
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        drawRect(colors.water)
        val data = world ?: return@Canvas
        val w = worldSize()
        val left = cx - size.width / 2f / w
        val top = cy - size.height / 2f / w
        val right = cx + size.width / 2f / w
        val bottom = cy + size.height / 2f / w
        val lod = when {
            zoom < 3.5f -> 0
            zoom < 6f -> 1
            else -> 2
        }
        withTransform({
            translate(-left * w, -top * w)
            scale(w, w, pivot = Offset.Zero)
        }) {
            for (country in data.countries) {
                val shape = country.lods[lod]
                if (!shape.intersects(left, top, right, bottom)) continue
                drawPath(shape.path, colors.land)
            }
            if (lod > 0 && data.lakes.intersects(left, top, right, bottom))
                drawPath(data.lakes.path, colors.water)
            // Hairlines (width 0) are always one pixel thick, whatever the zoom.
            for (country in data.countries) {
                val shape = country.lods[lod]
                if (!shape.intersects(left, top, right, bottom)) continue
                drawPath(shape.path, colors.border, style = Stroke(width = 0f))
            }
        }

        // City names, most important first, skipping ones that would overlap.
        val placed = ArrayList<Rect>()
        var labels = 0
        val dot = 2.5.dp.toPx()
        for (city in data.cities) {
            if (labels >= 70) break
            if (city.minZoom > zoom + 1.5f) continue
            if (city.x < left || city.x > right || city.y < top || city.y > bottom) continue
            val p = Offset((city.x - left) * w, (city.y - top) * w)
            val layout = textMeasurer.measure(city.name, cityStyle)
            val box = Rect(
                p.x + dot * 2, p.y - layout.size.height / 2f,
                p.x + dot * 2 + layout.size.width, p.y + layout.size.height / 2f
            ).inflate(4f)
            if (placed.any { it.overlaps(box) }) continue
            placed += box
            labels++
            drawCircle(colors.city, dot, p)
            drawText(layout, topLeft = Offset(box.left + 4f, box.top + 4f))
        }

        // Photo bubbles.
        val r = bubblePx / 2
        val ring = 2.5.dp.toPx()
        for (cluster in clustersNow()) {
            val c = Offset((cluster.x - left) * w, (cluster.y - top) * w)
            if (c.x < -r || c.y < -r || c.x > size.width + r || c.y > size.height + r) continue
            drawCircle(Color.Black.copy(alpha = 0.25f), r + ring, c + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, r + ring, c)
            val cover = cluster.photos.first().id
            val image = thumbs[cover]
            if (image == null) {
                drawCircle(colors.border, r, c)
                if (requested.add(cover)) requests.trySend(cover)
            } else {
                val side = min(image.width, image.height)
                val clip = Path().apply { addOval(Rect(c, r)) }
                clipPath(clip) {
                    drawImage(
                        image,
                        srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2),
                        srcSize = IntSize(side, side),
                        dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()),
                        dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()),
                    )
                }
            }
            val count = cluster.photos.size
            if (count > 1) {
                val text = textMeasurer.measure(if (count > 999) "999+" else count.toString(), badgeStyle)
                val bh = 20.dp.toPx()
                val bw = max(bh, text.size.width + 12.dp.toPx())
                val bc = c + Offset(r * 0.7f, -r * 0.7f)
                drawRoundRect(
                    accent,
                    topLeft = Offset(bc.x - bw / 2, bc.y - bh / 2),
                    size = Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bh / 2)
                )
                drawText(
                    text,
                    topLeft = Offset(bc.x - text.size.width / 2f, bc.y - text.size.height / 2f)
                )
            }
        }
    }
}

private class ClusterCache {
    var zoom = -100f
    var clusters: List<PhotoCluster> = emptyList()
}
