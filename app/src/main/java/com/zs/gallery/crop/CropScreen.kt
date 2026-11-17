package com.zs.gallery.crop

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.zs.compose.foundation.findActivity
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.text.Label
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.Route
import com.zs.gallery.common.compose.LocalNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The viewer's crop button (handled by the viewer screen itself). */
val CROP = Action(R.string.crop, Icons.Outlined.Edit)

object RouteCrop : Route {
    const val PARAM_URI = "param_uri"
    const val PARAM_EXTERNAL = "param_external"
    override val route: String = "$domain/{$PARAM_URI}/{$PARAM_EXTERNAL}"
    override fun invoke(): String = error("RouteCrop needs a photo")

    /** [external]: opened by another app ("Edit with"), so finishing returns to that app. */
    operator fun invoke(uri: Uri, external: Boolean = false): String =
        "$domain/${Uri.encode(uri.toString())}/$external"
}

// Which parts of the frame a drag moves.
private const val LEFT = 1
private const val TOP = 2
private const val RIGHT = 4
private const val BOTTOM = 8
private const val MOVE = 16

/** Free plus a few common shapes; the value is width / height (0 = free). */
private val Ratios = listOf(
    "Free" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f,
    "16:9" to 16f / 9f, "9:16" to 9f / 16f,
)

private val RectSaver = listSaver<Rect, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { Rect(it[0], it[1], it[2], it[3]) }
)

private class CropSource(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val relativePath: String?,
) {
    /** Only photos in the system media library can be overwritten in place. */
    val canReplace: Boolean get() = uri.authority == MediaStore.AUTHORITY
}

private fun loadSource(context: Context, uri: Uri): CropSource? {
    val resolver = context.contentResolver
    val type = resolver.getType(uri) ?: "image/jpeg"
    // Media library photos: name, type and folder.
    if (uri.authority == MediaStore.AUTHORITY) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst())
                    return CropSource(uri, c.getString(0) ?: "image", c.getString(1) ?: type, c.getString(2))
            }
        }
    }
    // Anything else another app hands us: just a name; copies go to Pictures.
    val name = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "image"
    return CropSource(uri, name, type, null)
}

/** Decodes [uri] (rotated upright) so its longer side is at most [maxSide] px. */
private fun decode(context: Context, uri: Uri, maxSide: Int, software: Boolean): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
        if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/**
 * Crop screen: the photo stays perfectly still; only the frame moves. Drag a corner or an edge to
 * resize it, drag inside it to move it.
 */
@Composable
fun CropScreen(uri: Uri, external: Boolean = false) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val source by produceState<CropSource?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadSource(context, uri) }.getOrNull() }
    }
    // Leaving: back into M Gallery, or - when another app asked for the edit - back to that app.
    val leave = {
        if (external) context.findActivity().finish() else navController.navigateUp()
    }
    val preview by produceState<ImageBitmap?>(null, source) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { decode(context, src.uri, 2048, software = false).asImageBitmap() }.getOrNull()
        }
    }
    // Frame in 0..1 of the photo; ratio = width / height in pixels (0 = free).
    var crop by rememberSaveable(stateSaver = RectSaver) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var ratio by rememberSaveable { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Drawing: marks in 0..1 of the photo, so they land in the same place on the full image.
    var drawMode by rememberSaveable { mutableStateOf(false) }
    val marks = remember { mutableStateListOf<Mark>() }
    var tool by rememberSaveable { mutableStateOf(Tool.ARROW) }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // Room around the photo, so the handles at its edges are easy to grab.
    val padX = with(density) { 24.dp.toPx() }
    val padY = with(density) { 20.dp.toPx() }

    // Where the photo is drawn: fitted into the area, centred. Never changes while cropping.
    val image = preview
    val imageRect = remember(image, area) {
        val aw = area.width - 2 * padX
        val ah = area.height - 2 * padY
        if (image == null || aw <= 0f || ah <= 0f) Rect.Zero
        else {
            val scale = min(aw / image.width, ah / image.height)
            val w = image.width * scale
            val h = image.height * scale
            Rect(Offset((area.width - w) / 2f, (area.height - h) / 2f), Size(w, h))
        }
    }

    fun applyRatio(value: Float) {
        ratio = value
        if (value == 0f || imageRect.isEmpty) return
        // Largest frame of that shape, around the current frame's centre.
        val rn = value * imageRect.height / imageRect.width
        var w = 1f
        var h = w / rn
        if (h > 1f) { h = 1f; w = rn }
        val cx = crop.center.x.coerceIn(w / 2f, 1f - w / 2f)
        val cy = crop.center.y.coerceIn(h / 2f, 1f - h / 2f)
        crop = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    fun save(replace: Boolean) {
        val src = source ?: return
        if (saving) return
        saving = true
        scope.launch {
            val activity = context.findActivity()
            val ok = saveCrop(activity, src, crop, replace, marks.map { it.frozen() })
            saving = false
            Toast.makeText(
                context,
                when {
                    !ok -> "Couldn't save the photo"
                    replace -> "Saved"
                    else -> "Saved a copy"
                },
                Toast.LENGTH_SHORT
            ).show()
            if (ok) leave()
        }
    }

    androidx.activity.compose.BackHandler(enabled = external) { leave() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Top: close, title, reset.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Cancel",
                onClick = { leave() },
                tint = Color.White
            )
            // Crop | Draw
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.weight(1f)
            ) {
                Pill("Crop", selected = !drawMode) { drawMode = false }
                Pill("Draw", selected = drawMode) { drawMode = true }
            }
            if (drawMode)
                IconButton(
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "Undo",
                    enabled = marks.isNotEmpty(),
                    onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) },
                    tint = if (marks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f)
                )
            else
                IconButton(
                    icon = Icons.Outlined.RestartAlt,
                    contentDescription = "Reset",
                    onClick = { ratio = 0f; crop = Rect(0f, 0f, 1f, 1f) },
                    tint = Color.White
                )
        }
        // The photo and the frame.
        val touch = with(density) { 28.dp.toPx() }
        val minSide = with(density) { 56.dp.toPx() }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { area = it }
                .pointerInput(imageRect, ratio, drawMode, tool, colorIndex) {
                    if (imageRect.isEmpty) return@pointerInput
                    if (drawMode) {
                        // Draw: one finger paints the chosen tool.
                        val widthPx = when (tool) {
                            Tool.PEN -> 4.dp.toPx()
                            Tool.HIGHLIGHTER -> 22.dp.toPx()
                            Tool.ARROW -> 5.dp.toPx()
                        }
                        fun norm(p: Offset) = Offset(
                            ((p.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
                            ((p.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
                        )
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val start = norm(down.position)
                            val points = mutableStateListOf(start, start)
                            val mark = Mark(tool, Palette[colorIndex], widthPx / imageRect.width, points)
                            marks += mark
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val p = change.position
                                if (tool == Tool.ARROW) points[1] = norm(p)
                                else if ((p - last).getDistance() > 1.5f) {
                                    points += norm(p)
                                    last = p
                                }
                            }
                            // A tap with the arrow tool isn't an arrow.
                            if (tool == Tool.ARROW &&
                                (points[1] - points[0]).let { Offset(it.x * imageRect.width, it.y * imageRect.height) }
                                    .getDistance() < 12.dp.toPx()
                            ) marks.remove(mark)
                        }
                        return@pointerInput
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = down.position
                        val r = crop.toScreen(imageRect)
                        val handle = hitTest(p, r, touch)
                        if (handle == 0) return@awaitEachGesture
                        down.consume()
                        dragging = true
                        val start = crop
                        var total = Offset.Zero
                        val minW = minSide / imageRect.width
                        val minH = minSide / imageRect.height
                        val rn = if (ratio == 0f) 0f else ratio * imageRect.height / imageRect.width
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                total += change.positionChange()
                                change.consume()
                                val d = Offset(total.x / imageRect.width, total.y / imageRect.height)
                                crop = resize(start, handle, d, rn, minW, minH)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
        ) {
            if (image != null && !imageRect.isEmpty)
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(imageRect.left.toInt(), imageRect.top.toInt()),
                        dstSize = IntSize(imageRect.width.toInt(), imageRect.height.toInt()),
                        filterQuality = FilterQuality.High
                    )
                    // Drawings, clipped to the photo.
                    if (marks.isNotEmpty())
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom)
                            for (m in marks)
                                drawMark(native, m, imageRect.left, imageRect.top, imageRect.width, imageRect.height)
                            native.restore()
                        }
                    val r = crop.toScreen(imageRect)
                    // Dim everything outside the frame.
                    val shade = Color.Black.copy(alpha = 0.62f)
                    drawRect(shade, imageRect.topLeft, Size(imageRect.width, r.top - imageRect.top))
                    drawRect(shade, Offset(imageRect.left, r.bottom), Size(imageRect.width, imageRect.bottom - r.bottom))
                    drawRect(shade, Offset(imageRect.left, r.top), Size(r.left - imageRect.left, r.height))
                    drawRect(shade, Offset(r.right, r.top), Size(imageRect.right - r.right, r.height))
                    // Rule-of-thirds guides while adjusting.
                    if (dragging) {
                        val guide = Color.White.copy(alpha = 0.45f)
                        for (i in 1..2) {
                            val x = r.left + r.width * i / 3f
                            val y = r.top + r.height * i / 3f
                            drawLine(guide, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                            drawLine(guide, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                        }
                    }
                    // Frame + corner and edge handles (just the frame while drawing).
                    drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
                    if (drawMode) return@Canvas
                    val t = 3.dp.toPx()
                    val len = min(22.dp.toPx(), min(r.width, r.height) / 2f)
                    val c = Color.White
                    // corners
                    for ((x, y, sx, sy) in listOf(
                        Quad(r.left, r.top, 1f, 1f), Quad(r.right, r.top, -1f, 1f),
                        Quad(r.left, r.bottom, 1f, -1f), Quad(r.right, r.bottom, -1f, -1f)
                    )) {
                        drawLine(c, Offset(x - sx * t / 2, y), Offset(x + sx * len, y), t)
                        drawLine(c, Offset(x, y - sy * t / 2), Offset(x, y + sy * len), t)
                    }
                    // edge midpoints
                    val half = min(10.dp.toPx(), min(r.width, r.height) / 4f)
                    drawLine(c, Offset(r.center.x - half, r.top), Offset(r.center.x + half, r.top), t)
                    drawLine(c, Offset(r.center.x - half, r.bottom), Offset(r.center.x + half, r.bottom), t)
                    drawLine(c, Offset(r.left, r.center.y - half), Offset(r.left, r.center.y + half), t)
                    drawLine(c, Offset(r.right, r.center.y - half), Offset(r.right, r.center.y + half), t)
                }
        }
        // Shapes.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            if (!drawMode)
                for ((label, value) in Ratios)
                    Pill(label, selected = ratio == value, onClick = { applyRatio(value) })
            else {
                ToolButton(Icons.Outlined.NorthEast, "Arrow", tool == Tool.ARROW) { tool = Tool.ARROW }
                ToolButton(Icons.Outlined.Gesture, "Pen", tool == Tool.PEN) { tool = Tool.PEN }
                ToolButton(Icons.Outlined.BorderColor, "Highlighter", tool == Tool.HIGHLIGHTER) { tool = Tool.HIGHLIGHTER }
                Spacer(Modifier.size(width = 6.dp, height = 1.dp))
                Palette.forEachIndexed { i, c ->
                    Swatch(c, selected = i == colorIndex) { colorIndex = i }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Save.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (source?.canReplace != false)
                Pill("Replace original", selected = false, big = true, modifier = Modifier.weight(1f)) {
                    save(replace = true)
                }
            Pill(if (saving) "Saving…" else "Save copy", selected = true, big = true, modifier = Modifier.weight(1f)) {
                save(replace = false)
            }
        }
    }
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

internal enum class Tool { PEN, HIGHLIGHTER, ARROW }

/** Red, yellow, green, blue, purple, white, black. */
private val Palette = listOf(
    Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF),
    Color(0xFF8E8FD1), Color.White, Color.Black,
)

/** One drawing: points in 0..1 of the photo, width as a fraction of the photo's width. */
internal class Mark(val tool: Tool, val color: Color, val width: Float, val points: List<Offset>) {
    fun frozen() = Mark(tool, color, width, points.toList())
}

/** Draws [m] onto a photo occupying (left, top, w x h) of [c]. Same code for screen and file. */
private fun drawMark(c: android.graphics.Canvas, m: Mark, left: Float, top: Float, w: Float, h: Float) {
    val pts = m.points
    if (pts.isEmpty()) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = max(1f, m.width * w)
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = if (m.tool == Tool.HIGHLIGHTER) android.graphics.Paint.Cap.SQUARE else android.graphics.Paint.Cap.ROUND
        color = (if (m.tool == Tool.HIGHLIGHTER) m.color.copy(alpha = 0.4f) else m.color).toArgb()
    }
    fun x(o: Offset) = left + o.x * w
    fun y(o: Offset) = top + o.y * h
    if (m.tool == Tool.ARROW) {
        val ax = x(pts.first()); val ay = y(pts.first())
        val bx = x(pts.last()); val by = y(pts.last())
        val angle = kotlin.math.atan2(by - ay, bx - ax)
        val len = kotlin.math.hypot(bx - ax, by - ay)
        val head = min(paint.strokeWidth * 4.2f, len * 0.6f)
        val spread = 0.48f
        // Shaft stops at the head's base, so the tip stays sharp.
        val baseX = bx - kotlin.math.cos(angle) * head * 0.8f
        val baseY = by - kotlin.math.sin(angle) * head * 0.8f
        c.drawLine(ax, ay, baseX, baseY, paint)
        val tri = android.graphics.Path().apply {
            moveTo(bx, by)
            lineTo(bx - kotlin.math.cos(angle - spread) * head, by - kotlin.math.sin(angle - spread) * head)
            lineTo(bx - kotlin.math.cos(angle + spread) * head, by - kotlin.math.sin(angle + spread) * head)
            close()
        }
        paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = paint.strokeWidth * 0.5f
        c.drawPath(tri, paint)
        return
    }
    // Pen / highlighter: smoothed through the midpoints.
    val distinct = pts.size < 3 && pts.first() == pts.last()
    if (distinct) {
        c.drawPoint(x(pts.first()), y(pts.first()), paint.apply { strokeCap = android.graphics.Paint.Cap.ROUND })
        return
    }
    val path = android.graphics.Path()
    path.moveTo(x(pts[0]), y(pts[0]))
    for (i in 1 until pts.size) {
        val p = pts[i - 1]; val q = pts[i]
        path.quadTo(x(p), y(p), (x(p) + x(q)) / 2f, (y(p) + y(q)) / 2f)
    }
    path.lineTo(x(pts.last()), y(pts.last()))
    c.drawPath(path, paint)
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = AppTheme.colors.accent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) accent else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
    ) {
        com.zs.compose.theme.Icon(
            icon, contentDescription = label,
            tint = if (selected) AppTheme.colors.onAccent else Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(if (selected) 34.dp else 28.dp)
                .border(if (selected) 3.dp else 1.dp, Color.White.copy(alpha = if (selected) 1f else 0.5f), CircleShape)
                .padding(if (selected) 5.dp else 2.dp)
                .background(color, CircleShape)
        )
    }
}

@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = AppTheme.colors.accent
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 20.dp else 16.dp, vertical = if (big) 14.dp else 9.dp)
    ) {
        Label(
            text,
            color = if (selected) AppTheme.colors.onAccent else Color.White,
            fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

private fun Rect.toScreen(image: Rect) = Rect(
    image.left + left * image.width, image.top + top * image.height,
    image.left + right * image.width, image.top + bottom * image.height
)

/** Which handle sits under [p]: corners first, then edges, then inside (move). 0 = nothing. */
private fun hitTest(p: Offset, r: Rect, touch: Float): Int {
    val inX = p.x in (r.left - touch)..(r.right + touch)
    val inY = p.y in (r.top - touch)..(r.bottom + touch)
    if (!inX || !inY) return 0
    var h = 0
    val dl = abs(p.x - r.left)
    val dr = abs(p.x - r.right)
    val dt = abs(p.y - r.top)
    val db = abs(p.y - r.bottom)
    if (dl < touch || dr < touch) h = h or if (dl <= dr) LEFT else RIGHT
    if (dt < touch || db < touch) h = h or if (dt <= db) TOP else BOTTOM
    if (h != 0) return h
    return if (r.contains(p)) MOVE else 0
}

/**
 * New frame (0..1 units) after dragging [handle] of [s] by [d]. [rn] is the locked shape in
 * 0..1 units (width / height), 0 = free. Always stays inside the photo and at least min size.
 */
private fun resize(s: Rect, handle: Int, d: Offset, rn: Float, minW: Float, minH: Float): Rect {
    if (handle == MOVE) {
        val dx = d.x.coerceIn(-s.left, 1f - s.right)
        val dy = d.y.coerceIn(-s.top, 1f - s.bottom)
        return s.translate(dx, dy)
    }
    val horizontal = handle and (LEFT or RIGHT)
    val vertical = handle and (TOP or BOTTOM)
    if (rn == 0f) {
        var l = s.left; var t = s.top; var r = s.right; var b = s.bottom
        if (handle and LEFT != 0) l = (l + d.x).coerceIn(0f, r - minW)
        if (handle and RIGHT != 0) r = (r + d.x).coerceIn(l + minW, 1f)
        if (handle and TOP != 0) t = (t + d.y).coerceIn(0f, b - minH)
        if (handle and BOTTOM != 0) b = (b + d.y).coerceIn(t + minH, 1f)
        return Rect(l, t, r, b)
    }
    val minWr = max(minW, minH * rn)
    if (horizontal != 0 && vertical != 0) {
        // Corner: the opposite corner stays put.
        val left = handle and LEFT != 0
        val top = handle and TOP != 0
        val ax = if (left) s.right else s.left
        val ay = if (top) s.bottom else s.top
        val fx = (if (left) s.left else s.right) + d.x
        val fy = (if (top) s.top else s.bottom) + d.y
        val wx = if (left) ax - fx else fx - ax
        val hy = if (top) ay - fy else fy - ay
        val maxW = min(if (left) ax else 1f - ax, (if (top) ay else 1f - ay) * rn)
        val w = max(wx, hy * rn).coerceAtMost(maxW).coerceAtLeast(min(minWr, maxW))
        val h = w / rn
        val l = if (left) ax - w else ax
        val t = if (top) ay - h else ay
        return Rect(l, t, l + w, t + h)
    }
    if (horizontal != 0) {
        // Left/right edge: the other edge stays, the height follows around the centre.
        val left = handle and LEFT != 0
        val ax = if (left) s.right else s.left
        val cy = s.center.y
        val fx = (if (left) s.left else s.right) + d.x
        val maxW = min(if (left) ax else 1f - ax, 2f * min(cy, 1f - cy) * rn)
        val w = (if (left) ax - fx else fx - ax).coerceAtMost(maxW).coerceAtLeast(min(minWr, maxW))
        val h = w / rn
        val l = if (left) ax - w else ax
        return Rect(l, cy - h / 2f, l + w, cy + h / 2f)
    }
    // Top/bottom edge.
    val top = handle and TOP != 0
    val ay = if (top) s.bottom else s.top
    val cx = s.center.x
    val fy = (if (top) s.top else s.bottom) + d.y
    val maxH = min(if (top) ay else 1f - ay, 2f * min(cx, 1f - cx) / rn)
    val minHr = minWr / rn
    val h = (if (top) ay - fy else fy - ay).coerceAtMost(maxH).coerceAtLeast(min(minHr, maxH))
    val w = h * rn
    val t = if (top) ay - h else ay
    return Rect(cx - w / 2f, t, cx + w / 2f, t + h)
}

/** Writes the cropped photo: as a copy next to the original, or over the original. */
private suspend fun saveCrop(
    activity: Activity, src: CropSource, crop: Rect, replace: Boolean, marks: List<Mark>,
): Boolean {
    if (replace && !requestWrite(activity, src.uri)) return false
    val resolver = activity.contentResolver
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            // Full quality (only huge photos are scaled, to stay within memory).
            var full = decode(activity, src.uri, 8000, software = true)
            if (marks.isNotEmpty()) {
                if (!full.isMutable) full = full.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(full)
                for (m in marks) drawMark(canvas, m, 0f, 0f, full.width.toFloat(), full.height.toFloat())
            }
            val l = (crop.left * full.width).toInt().coerceIn(0, full.width - 1)
            val t = (crop.top * full.height).toInt().coerceIn(0, full.height - 1)
            val w = ((crop.right * full.width).toInt() - l).coerceIn(1, full.width - l)
            val h = ((crop.bottom * full.height).toInt() - t).coerceIn(1, full.height - t)
            val out = Bitmap.createBitmap(full, l, t, w, h)
            val png = src.mimeType == "image/png"
            val webp = src.mimeType == "image/webp"
            val format = when {
                png -> Bitmap.CompressFormat.PNG
                webp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            val mime = when (format) {
                Bitmap.CompressFormat.PNG -> "image/png"
                Bitmap.CompressFormat.JPEG -> "image/jpeg"
                else -> "image/webp"
            }
            if (replace) {
                if (mime != src.mimeType && !(webp && mime == "image/webp")) error("format")
                resolver.openOutputStream(src.uri, "wt").use { requireNotNull(it); out.compress(format, 95, it) }
                true
            } else {
                val dot = src.name.lastIndexOf('.')
                val base = if (dot > 0) src.name.substring(0, dot) else src.name
                val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_edit.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, src.relativePath ?: "Pictures/")
                    put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val target = resolver.insert(collection, values) ?: error("insert")
                try {
                    resolver.openOutputStream(target).use { requireNotNull(it); out.compress(format, 95, it) }
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(target, null, null)
                    throw e
                }
                true
            }
        }.getOrDefault(false)
    }
    // The old picture of a replaced photo must not linger in the in-memory image cache.
    if (ok && replace) SingletonImageLoader.get(activity).memoryCache?.clear()
    return ok
}

private suspend fun requestWrite(activity: Activity, uri: Uri): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    val owner = activity as? ComponentActivity ?: return false
    val pending = MediaStore.createWriteRequest(activity.contentResolver, listOf(uri))
    return suspendCancellableCoroutine { cont ->
        val holder = arrayOfNulls<ActivityResultLauncher<IntentSenderRequest>>(1)
        val launcher = owner.activityResultRegistry.register(
            UUID.randomUUID().toString(), ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            holder[0]?.unregister()
            if (cont.isActive) cont.resume(result.resultCode == Activity.RESULT_OK)
        }
        holder[0] = launcher
        launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }
}
