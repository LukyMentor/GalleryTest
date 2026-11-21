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

import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    val river: Color,
    val motorway: Color,
    val mainRoad: Color,
    val road: Color,
)

// iPhone-Maps-like: soft green land, blue water, pinkish borders, white / yellow roads.
private val LightMap = MapColors(
    water = Color(0xFF9CC6F2), land = Color(0xFFE4EEDB), border = Color(0x5A4A6B55),
    coast = Color(0xFF8DB8E8), city = Color(0xFF34343C), cityHalo = Color(0xE6E4EEDB),
    river = Color(0xFF8FBCEB), motorway = Color(0xFFF2C766), mainRoad = Color(0xF2FFFFFF),
    road = Color(0xB3FFFFFF),
)
// Dark: deep blue water, teal-green land, light blue-grey roads (like the iPhone's dark map).
private val DarkMap = MapColors(
    water = Color(0xFF1C2A80), land = Color(0xFF2B6650), border = Color(0x59FFFFFF),
    coast = Color(0xFF243A8C), city = Color(0xFFF2F4F8), cityHalo = Color(0xE6173B2E),
    river = Color(0xB32C45B8), motorway = Color(0x99B4C5EC), mainRoad = Color(0x668EA2CF),
    road = Color(0x4D8EA2CF),
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
    /** Fly the camera here (normalized map point), then [onFocused]. */
    focus: Offset? = null,
    onFocused: () -> Unit = {},
    /** A spot to mark with pulsing rings (the photo opened from Properties). */
    highlight: Offset? = null,
    /** Receives the visible area (left, top, right, bottom in map units) on every frame. */
    boundsOut: FloatArray? = null,
    /** False: just the map, no photo bubbles. */
    showPhotos: Boolean = true,
    /** Photos shown as small dots (a folder's other days). */
    dots: List<GeoPhoto> = emptyList(),
    /** The trip line: photos in the order they were taken; [trailDay] is the chosen day's part. */
    trail: List<GeoPhoto> = emptyList(),
    trailDay: List<GeoPhoto> = emptyList(),
    /** When this changes (and isn't null), the camera fits [fitPhotos]. */
    fitKey: Any? = null,
    fitPhotos: List<GeoPhoto> = emptyList(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = if (dark) DarkMap else LightMap
    var world by remember { mutableStateOf<WorldData?>(null) }
    LaunchedEffect(Unit) { world = WorldData.load(context) }

    // Camera: zoom (0 = whole world in 256 dp) and the centre in normalized coordinates.
    // Saved, so coming back from a photo returns to the same place on the map.
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var cx by rememberSaveable { mutableStateOf(0.5f) }
    var cy by rememberSaveable { mutableStateOf(0.42f) }
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
    var fitted by rememberSaveable { mutableStateOf(false) }
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

    // A folder was chosen: show all of its photos.
    LaunchedEffect(fitKey, viewport) {
        if (fitKey == null || fitPhotos.isEmpty() || viewport == IntSize.Zero) return@LaunchedEffect
        fitted = true
        val pts = fitPhotos.map { mercator(it.latitude, it.longitude) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val spanX = max(maxX - minX, 0.0005f) * 1.5f
        val spanY = max(maxY - minY, 0.0005f) * 1.8f
        val z1 = log2(min(viewport.width / (spanX * tilePx), viewport.height / (spanY * tilePx))).coerceAtMost(11f)
        val z0 = zoom; val x0 = cx; val y0 = cy
        val x1 = (minX + maxX) / 2; val y1 = (minY + maxY) / 2
        val t = androidx.compose.animation.core.Animatable(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
            zoom = z0 + (z1 - z0) * value
            cx = x0 + (x1 - x0) * value
            cy = y0 + (y1 - y0) * value
            clampCamera()
        }
    }
    val dotPoints = remember(dots) { dots.map { mercator(it.latitude, it.longitude) } }
    val trailPoints = remember(trail) { trail.map { mercator(it.latitude, it.longitude) } }
    val trailDayPoints = remember(trailDay) { trailDay.map { mercator(it.latitude, it.longitude) } }

    // Flying to a photo's place ("Location" in a photo's Properties).
    LaunchedEffect(focus, viewport) {
        val target = focus ?: return@LaunchedEffect
        if (viewport == IntSize.Zero) return@LaunchedEffect
        fitted = true
        // Start already above the spot (a few levels out) and glide in slowly - flying across
        // the whole world was far too fast to follow.
        val z1 = 11f.coerceAtMost(MAX_ZOOM)
        val z0 = (z1 - 3.5f).coerceAtLeast(minZoom())
        zoom = z0; cx = target.x; cy = target.y
        clampCamera()
        val t = androidx.compose.animation.core.Animatable(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
            zoom = z0 + (z1 - z0) * value
            cx = target.x; cy = target.y
            clampCamera()
        }
        onFocused()
    }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "spot")
        .animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1200)),
            label = "pulse"
        )

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
    val cityStyle = TextStyle(fontSize = 12.sp, color = colors.city, fontWeight = FontWeight.Medium)
    // Outline drawn under each city name (in the map's dark/light tone), so it stays readable
    // over roads and rivers. Colour and stroke are given when drawing - set in the TextStyle
    // they were ignored and the outline came out in the text colour (white blobs).
    val haloColor = colors.cityHalo.copy(alpha = 1f)
    val haloStroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = with(density) { 3.dp.toPx() }, join = androidx.compose.ui.graphics.StrokeJoin.Round
    )
    val countryStyle = TextStyle(
        fontSize = 12.sp,
        color = colors.city.copy(alpha = 0.85f),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        shadow = androidx.compose.ui.graphics.Shadow(colors.cityHalo, blurRadius = 6f)
    )
    val borderPx = with(density) { 1.dp.toPx() }
    val badgeStyle = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }

    fun toScreen(x: Float, y: Float): Offset {
        val w = worldSize()
        return Offset((x - cx) * w + viewport.width / 2f, (y - cy) * w + viewport.height / 2f)
    }

    fun clusterAt(p: Offset): PhotoCluster? {
        if (!showPhotos) return null
        val r = bubblePx / 2 + with(density) { 6.dp.toPx() }
        return clustersNow()
            .map { it to (toScreen(it.x, it.y) - p).getDistance() }
            .filter { it.second <= r }
            .minByOrNull { it.second }?.first
    }

    val tapTarget = androidx.compose.runtime.rememberUpdatedState<(Offset) -> PhotoCluster?> { p -> clusterAt(p) }
    val tapAction = androidx.compose.runtime.rememberUpdatedState(onClusterTap)


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
                // The gesture loop lives on across recompositions: always ask the *current*
                // bubbles (e.g. only the chosen day's), not the ones from when it started.
                val clusterAt: (Offset) -> PhotoCluster? = { p -> tapTarget.value(p) }
                val onClusterTap: (PhotoCluster) -> Unit = { c -> tapAction.value(c) }
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
        boundsOut?.let { it[0] = left; it[1] = top; it[2] = right; it[3] = bottom }
        val lod = when {
            zoom < 3.5f -> 0
            zoom < 6f -> 1
            else -> 2
        }
        withTransform({
            translate(-left * w, -top * w)
            scale(w, w, pivot = Offset.Zero)
        }) {
            // Zoomed in: only the tiles on screen (whole countries made zooming in choppy).
            val layer = if (lod > 0) data.tiles[lod] else null
            fun cells(grid: Int, block: (Int) -> Unit) {
                val x0 = (left * grid).toInt().coerceIn(0, grid - 1)
                val x1 = (right * grid).toInt().coerceIn(0, grid - 1)
                val y0 = (top * grid).toInt().coerceIn(0, grid - 1)
                val y1 = (bottom * grid).toInt().coerceIn(0, grid - 1)
                for (gy in y0..y1) for (gx in x0..x1) block(gy * grid + gx)
            }
            if (layer != null) {
                cells(layer.grid) { i -> layer.land[i]?.let { drawPath(it, colors.land) } }
                cells(WorldData.LAKE_GRID) { i -> data.lakeTiles[i]?.let { drawPath(it, colors.water) } }
            } else for (country in data.countries) {
                val shape = country.lods[lod]
                if (!shape.intersects(left, top, right, bottom)) continue
                drawPath(shape.path, colors.land)
            }
            // Rivers and roads (each kind from the zoom it belongs to), ~constant width on screen.
            val px = 1f / w
            // Each kind only from a zoom where it helps (all roads at country level made a
            // spider web), and thinner when further out.
            val grow = ((zoom - 5f) / 5f).coerceIn(0f, 1f)
            for (g in data.lines) {
                val from = when (g.kind) {
                    3 -> 5f        // rivers
                    0 -> 5.5f      // motorways
                    1 -> 7f        // main roads
                    else -> 8.5f   // other roads
                }
                if (zoom < from || g.minZoom > zoom + 0.5f || !g.intersects(left, top, right, bottom)) continue
                val (color, width) = when (g.kind) {
                    3 -> colors.river to (0.8f + 0.6f * grow)
                    0 -> colors.motorway to (1.2f + 1.4f * grow)
                    1 -> colors.mainRoad to (0.9f + 0.9f * grow)
                    else -> colors.road to (0.7f + 0.5f * grow)
                }
                drawPath(
                    g.path, color,
                    style = Stroke(
                        width = width.dp.toPx() * px,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
            // Borders ~1 dp wide on screen at any zoom (the path is drawn in world units).
            // Hairline when zoomed far out (thick outlines made the world view look busy).
            val borderScale = when {
                zoom < 2.5f -> 0.45f
                zoom < 4.5f -> 0.7f
                else -> 1f
            }
            val border = Stroke(width = borderPx * borderScale / w)
            if (layer != null) {
                for (piece in layer.borders)
                    if (piece.intersects(left, top, right, bottom)) drawPath(piece.path, colors.border, style = border)
            } else for (country in data.countries) {
                val shape = country.lods[lod]
                if (!shape.intersects(left, top, right, bottom)) continue
                drawPath(shape.path, colors.border, style = border)
            }
        }

        // Country names (from the zoom where they fit until they get too big), then city names;
        // most important first, skipping ones that would overlap.
        val placed = ArrayList<Rect>()
        for (label in data.countryLabels) {
            if (zoom < label.minZoom - 1f || zoom > label.maxZoom + 0.5f) continue
            if (label.x < left || label.x > right || label.y < top || label.y > bottom) continue
            val layout = textMeasurer.measure(label.name.uppercase(), countryStyle)
            val p = Offset((label.x - left) * w, (label.y - top) * w)
            val box = Rect(
                p.x - layout.size.width / 2f, p.y - layout.size.height / 2f,
                p.x + layout.size.width / 2f, p.y + layout.size.height / 2f
            )
            if (placed.any { it.overlaps(box) }) continue
            placed += box.inflate(6f)
            drawText(layout, topLeft = box.topLeft)
        }
        var labels = 0
        val dot = 2.5.dp.toPx()
        // Fewer names the further out (only the biggest places), more when zoomed in.
        val maxLabels = when {
            zoom < 4f -> 12
            zoom < 6f -> 22
            zoom < 8f -> 34
            else -> 50
        }
        for (city in data.cities) {
            if (labels >= maxLabels) break
            if (city.minZoom > zoom + 0.5f) continue
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
            drawCircle(colors.cityHalo.copy(alpha = 1f), dot + 1.5.dp.toPx(), p)
            drawCircle(colors.city, dot, p)
            val at = Offset(box.left + 4f, box.top + 4f)
            drawText(layout, color = haloColor, topLeft = at, drawStyle = haloStroke)
            // Explicitly filled: the text paint keeps the last stroke otherwise, and the name got
            // drawn as a thick white outline over its own outline.
            drawText(layout, color = colors.city, topLeft = at, drawStyle = androidx.compose.ui.graphics.drawscope.Fill)
        }

        // Trip line (whole folder faint, the chosen day in the accent colour) and the other days'
        // photos as small dots.
        fun screenPath(pts: List<Pair<Float, Float>>): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            pts.forEachIndexed { i, (x, y) ->
                val sx = (x - left) * w; val sy = (y - top) * w
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            return path
        }
        if (showPhotos && trailPoints.size >= 2)
            drawPath(
                screenPath(trailPoints), colors.city.copy(alpha = 0.45f),
                style = Stroke(1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        if (showPhotos && trailDayPoints.size >= 2)
            drawPath(
                screenPath(trailDayPoints), accent,
                style = Stroke(3.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        if (showPhotos) for ((x, y) in dotPoints) {
            val c = Offset((x - left) * w, (y - top) * w)
            if (c.x < -10 || c.y < -10 || c.x > size.width + 10 || c.y > size.height + 10) continue
            drawCircle(colors.cityHalo.copy(alpha = 1f), 5.dp.toPx(), c)
            drawCircle(colors.city, 3.5.dp.toPx(), c)
        }

        // The photo's spot: two rings growing and fading, under the bubbles.
        if (highlight != null) {
            val hc = Offset((highlight.x - left) * w, (highlight.y - top) * w)
            val base = bubblePx / 2 + 4.dp.toPx()
            for (k in 0..1) {
                val p = (pulse.value + k * 0.5f) % 1f
                drawCircle(
                    accent.copy(alpha = (1f - p) * 0.9f),
                    radius = base + p * 34.dp.toPx(),
                    center = hc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx() * (1f - p * 0.5f))
                )
            }
            drawCircle(accent.copy(alpha = 0.18f), base, hc)
        }

        // Photo bubbles.
        val r = bubblePx / 2
        val ring = 2.5.dp.toPx()
        for (cluster in if (showPhotos) clustersNow() else emptyList()) {
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
