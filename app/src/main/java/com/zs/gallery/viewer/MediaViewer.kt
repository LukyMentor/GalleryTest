/*
 * Copyright 2025 sheik
 *
 * Created by sheik on 04-04-2025.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.zs.gallery.viewer

import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.PlayCircleFilled
import androidx.compose.material.icons.outlined.ReplyAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.systemGestures
import coil3.size.Precision
import coil3.size.Scale
import com.zs.gallery.common.compose.dragToDismiss
import com.zs.gallery.common.compose.edgeSwipeBack
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.transformations
import com.zs.compose.foundation.ImageBrush
import com.zs.compose.foundation.SignalWhite
import com.zs.compose.foundation.findActivity
import com.zs.compose.foundation.foreground
import com.zs.compose.foundation.thenIf
import com.zs.compose.foundation.visualEffect
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.LocalWindowSize
import com.zs.compose.theme.WindowSize.Category
import com.zs.compose.theme.adaptive.FabPosition
import com.zs.compose.theme.adaptive.Scaffold
import com.zs.core.coil.RsBlurTransformation
import com.zs.core.coil.preferCachedThumbnail
import com.zs.core.store.MediaProvider
import com.zs.gallery.common.WindowStyle
import com.zs.gallery.common.compose.ContentPadding
import com.zs.gallery.common.compose.FloatingActionMenu
import com.zs.gallery.common.compose.LocalNavController
import com.zs.gallery.common.compose.LocalSystemFacade
import com.zs.gallery.common.compose.OverflowMenu
import com.zs.gallery.common.compose.background
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.zs.gallery.R
import androidx.compose.animation.core.FastOutSlowInEasing
import com.zs.core.store.MediaFile
import android.app.Activity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onPlaced
import com.zs.gallery.common.compose.preference
import com.zs.gallery.common.compose.rememberAcrylicSurface
import com.zs.gallery.common.compose.shine
import com.zs.gallery.common.compose.source
import com.zs.gallery.common.scaledInsideAndCenterAlignedFrom
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.files.thumbnailCacheKey
import com.zs.gallery.settings.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.zoomable
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.pager.rememberPagerState as PagerState
import androidx.compose.ui.graphics.vector.rememberVectorPainter as VectorPainter
import coil3.compose.rememberAsyncImagePainter as Painter
import coil3.request.ImageRequest.Builder as ImageRequest
import me.saket.telephoto.zoomable.rememberZoomableState as ZoomableState

private const val TAG = "MediaViewer"

private val MediaViewerViewState.index
    get() = data.indexOfFirst { focused == it.id }

private const val EVENT_BACK_PRESS = 0
private const val EVENT_SHOW_INFO = 1
private const val EVENT_IMMERSIVE_VIEW = 2

private val DEFAULT_ZOOM_SPECS = ZoomSpec(5f)

/** Image types Android can decode in tiles (needed for the quick-preview path). */
private val TILED_FORMATS = setOf(
    "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif"
)

/** Size of the quick preview (MediaStore thumbnail) shown until tiles of the original load. */
private const val PREVIEW_SIZE = 512

// FixMe - This is not the best solution; find another.
private val ZoomableState.isZoomedOut get() = (zoomFraction ?: 0f) <= 0.0001f


/**
 * Owns the drag-to-close offset of the photo (px, 0 = resting) and every animation of it, so
 * there is exactly one place that can move the photo: the finger feeds [dragBy], and [end]
 * either springs it back or flies it out and closes the screen.
 */
@Stable
private class DismissController(
    private val scope: CoroutineScope,
    private val density: Float,
    private val close: () -> Unit,
    private val onThresholdCrossed: () -> Unit,
) {
    private val anim = Animatable(0f)
    /** Current offset in px; read it in draw/graphics-layer lambdas. */
    val offset: Float get() = anim.value
    /** True once a drag decided to close; disables the shared-element "fly back" animation. */
    var dismissed by mutableStateOf(false)
        private set
    private val shrink = Animatable(1f)
    /** Extra scale while closing by back / side swipe (the photo shrinks a little as it fades). */
    val scale: Float get() = shrink.value

    /**
     * Back, side swipe or the back button: close the same way as a swipe down does (grid picture
     * at once, no shared-element fly back), with the photo shrinking slightly while it fades.
     */
    fun closeNow() {
        if (dismissed) return
        dismissed = true
        close()
        scope.launch { shrink.animateTo(0.9f, tween(durationMillis = 240, easing = LinearOutSlowInEasing)) }
    }
    var screenHeight = 0f

    /**
     * Distance after which letting go always closes the photo: ~1/9 of the screen (a short pull,
     * like Google Photos). A quick downward flick closes it even before that, see [end].
     */
    private val threshold: Float
        get() = maxOf((if (screenHeight > 0f) screenHeight else 2000f) * 0.11f, 72f * density)

    /** Whether letting go now would close; a light haptic tick marks crossing the line. */
    private var armed = false

    fun dragBy(dy: Float) {
        if (dismissed) return
        // Apply synchronously from the known value so no event is lost, even with animations off.
        val target = (anim.value + dy).coerceAtLeast(0f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(target) }
        val nowArmed = target >= threshold
        if (nowArmed && !armed) onThresholdCrossed()
        armed = nowArmed
    }

    fun end(velocityY: Float, released: Boolean) {
        if (dismissed) return
        val height = if (screenHeight > 0f) screenHeight else 2000f
        armed = false
        // A flick = still moving down fast when the finger lifts (after a small pull, so a
        // tap-and-wobble doesn't count). Works the same with system animations turned off.
        val flick = velocityY > 650f * density && anim.value > 20f * density
        if (released && (anim.value >= threshold || flick)) {
            dismissed = true
            // Close right away: the grid is composed underneath at once (the black backdrop
            // disappears as soon as `dismissed` is set) while the photo keeps flying down and
            // fades out on top of it - no black gap between the photo and the grid.
            close()
            scope.launch {
                anim.animateTo(
                    anim.value + height,
                    tween(durationMillis = 220, easing = LinearOutSlowInEasing),
                    initialVelocity = velocityY
                )
                // If the screen is somehow still here, never leave the photo hanging.
                delay(700)
                anim.snapTo(0f)
                dismissed = false
            }
        } else {
            scope.launch {
                anim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
            }
        }
    }
}

/**
 * Close gestures shared by the opening image and the carousel: swipe in from a screen edge
 * (= back) and drag down to close (photo follows the finger). [enabled] is checked when each
 * gesture starts, e.g. to ignore them while zoomed in.
 */
@Composable
private fun Modifier.closeGestures(
    enabled: () -> Boolean,
    dismiss: DismissController,
    onClose: () -> Unit,
    sheet: DetailsSheetState? = null,
): Modifier {
    // Point that was grabbed; scaling happens around it so it stays under the finger.
    val pivot = remember { floatArrayOf(0.5f, 0.5f, 0f, 0f) } // fx, fy, x, y
    // Edge zone for the back swipe: the system gesture inset plus a little margin.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemGestures = WindowInsets.systemGestures
    val edgeZone = {
        val inset = maxOf(
            systemGestures.getLeft(density, layoutDirection),
            systemGestures.getRight(density, layoutDirection)
        ).toFloat()
        with(density) { maxOf(inset + 12.dp.toPx(), 32.dp.toPx()) }
    }
    return this
        .edgeSwipeBack(enabled = enabled, edge = edgeZone, onBack = onClose)
        .dragToDismiss(
            enabled = enabled,
            onDragStart = { p -> pivot[2] = p.x; pivot[3] = p.y },
            onDrag = { dy -> dismiss.dragBy(dy) },
            onDragEnd = { velocity, released -> dismiss.end(velocity, released) },
            sheetOpen = { sheet?.isOpen == true },
            onSheetDrag = sheet?.let { s -> { dy: Float -> s.dragBy(dy) } },
            onSheetRelease = sheet?.let { s -> { v: Float -> s.settle(v) } }
        )
        .graphicsLayer {
            // Follow the finger 1:1 and shrink slightly around the grabbed point.
            val offset = dismiss.offset
            val extra = dismiss.scale
            if (offset == 0f && extra == 1f) return@graphicsLayer
            val progress = (offset / size.height).coerceIn(0f, 1f)
            translationY = offset
            val scale = (1f - 0.35f * progress) * extra
            scaleX = scale
            scaleY = scale
            // Closing by back: shrink around the centre; dragging: around the grabbed point.
            if (offset == 0f) transformOrigin = TransformOrigin.Center
            else if (size.width > 0f && size.height > 0f)
                transformOrigin = TransformOrigin(
                    (pivot[2] / size.width).coerceIn(0f, 1f),
                    (pivot[3] / size.height).coerceIn(0f, 1f)
                )
        }
}

@Composable
private fun Carousel(
    viewState: MediaViewerViewState,
    onRequest: (event: Int) -> Unit,
    sharedElementEnabled: Boolean,
    onReady: () -> Unit,
    zoomHolder: MutableState<ZoomableState?>,
    rotator: Rotator,
    deleter: Deleter,
    modifier: Modifier = Modifier,
) {
    val data = viewState.data
    val state = PagerState(initialPage = viewState.index, pageCount = { data.size })
    val scope = rememberCoroutineScope()
    // Every image page owns its zoom state; this points at the one currently in focus.
    var focusedZoom by zoomHolder
    // Boolean only, so the pager recomposes when zoomed-in/out flips, not on every zoom frame.
    val zoomedOut by remember { derivedStateOf { focusedZoom?.isZoomedOut ?: true } }
    // TODO - Remove this once inBuilt Video Player is available.
    val vector = VectorPainter(Icons.Outlined.PlayCircleFilled)
    val playIconModifier = Modifier.drawWithCache {
        onDrawWithContent {
            drawContent()
            // overlay icon on it.
            val iconSize = Size(74.dp.toPx(), 74.dp.toPx())
            // draw at the centre of the screen.
            translate(size.width / 2 - iconSize.width / 2, size.height / 2 - iconSize.height / 2) {
                with(vector) {
                    draw(iconSize, alpha = 1f, colorFilter = ColorFilter.tint(Color.White))
                }
            }
        }
    }

    // Handle BackPress
    BackHandler {
        val zoom = focusedZoom
        when {
            // reset zoom - only if really zoomed in: pulling the properties sheet up could
            // leave a tiny zoom/pan that made the first back gesture "do nothing".
            zoom != null && (zoom.zoomFraction ?: 0f) > 0.02f -> scope.launch { zoom.resetZoom() }
            // else ask parent to handle back-press
            else -> onRequest(EVENT_BACK_PRESS)
        }
    }
    val navController = LocalNavController.current
    val ctx = LocalContext.current
    HorizontalPager(
        state = state,
        key = { data[it].id },
        pageSpacing = ContentPadding.normal,
        modifier = modifier,
        // Disable swipe when zoomed in
        userScrollEnabled = zoomedOut,
        // Preload adjacent pages for smoother transitions
        beyondViewportPageCount = 1,
        pageContent = { index ->
            val item = data[index]
            // Deleting: the photo fades and shrinks away; the next one fades in (see Deleter).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Fade by modulating each draw instead of an offscreen buffer: the
                        // buffer briefly showed black / the old frame when the fade started.
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        val fade = deleter.fading
                        if (fade != null && fade.id == item.id) {
                            alpha = fade.alpha.value
                            scaleX = fade.scale.value
                            scaleY = fade.scale.value
                        }
                        // Faded out and being deleted: stays invisible until it leaves the list
                        // (no flash of it coming back for a frame).
                        else if (item.id in deleter.hidden) alpha = 0f
                        else if (deleter.appearing != null && state.currentPage == index)
                            alpha = deleter.appearing!!.value
                    }
            ) {
            // isFocused indicates whether this item is currently the focused item in the viewpager.
            val isFocused = state.currentPage == index
            // if the user navigated to this item
            if (isFocused) viewState.focused = item.id
            if (item.isImage) {
                // Sharp zoom: telephoto shows a screen-sized image first and then loads full
                // resolution tiles for just the zoomed-in part (sub-sampling), like Google
                // Photos / Aves. The grid thumbnail is shown until the first image is ready.
                // While a rotation finishes, the old picture (turned on screen) stays until the
                // re-loaded, really rotated one is ready underneath; then they swap in one frame.
                val rev = rotator.revisions[item.id] ?: 0
                val turn = rotator.turn?.takeIf { it.id == item.id }
                val revs = if (turn != null && turn.fromRev != rev) listOf(turn.fromRev, rev) else listOf(rev)
                Box(Modifier.fillMaxSize()) {
                for (r in revs) androidx.compose.runtime.key(r) {
                val current = r == rev
                val turning = turn != null && r == turn.fromRev
                val waiting = turn != null && current && r != turn.fromRev
                val pageZoom = ZoomableState(DEFAULT_ZOOM_SPECS)
                val imageState = rememberZoomableImageState(pageZoom)
                if (isFocused && current) {
                    SideEffect { focusedZoom = pageZoom }
                    // Tell the screen once something (placeholder or image) is on screen.
                    LaunchedEffect(imageState) {
                        snapshotFlow { imageState.isPlaceholderDisplayed || imageState.isImageDisplayed }
                            .first { it }
                        onReady()
                    }
                }
                if (waiting) LaunchedEffect(imageState) {
                    snapshotFlow { imageState.isPlaceholderDisplayed || imageState.isImageDisplayed }
                        .first { it }
                    rotator.done(turn!!)
                }
                ZoomableAsyncImage(
                    model = remember(item.id, r) {
                        // Formats that support tiled (region) decoding: the small MediaStore
                        // thumbnail is enough as telephoto's preview, because the sharp detail
                        // comes from tiles of the original file anyway. That makes the photo
                        // zoomable almost immediately instead of after a full decode.
                        // (Just rotated: the system thumbnail may still be the old one, so the
                        // preview is decoded from the file itself.)
                        val tiled = item.mimeType in TILED_FORMATS
                        ImageRequest(ctx)
                            .data(MediaProvider.buildContentUri(item.id))
                            .apply {
                                if (r == 0) placeholderMemoryCacheKey(thumbnailCacheKey(item.id))
                                else memoryCacheKey("rotated_${item.id}_$r")
                                if (tiled) {
                                    size(PREVIEW_SIZE)
                                    preferCachedThumbnail(r == 0)
                                } else {
                                    // GIFs etc.: show the real image.
                                    preferCachedThumbnail(false)
                                }
                            }
                            .build()
                    },
                    contentDescription = item.name,
                    state = imageState,
                    onClick = { onRequest(EVENT_IMMERSIVE_VIEW) },
                    onDoubleClick = DoubleClickToZoomListener.cycle(2f),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (turning) {
                                rotationZ = turn!!.angle.value
                                scaleX = turn.scale.value
                                scaleY = turn.scale.value
                            }
                            if (waiting) alpha = 0f
                        }
                        .thenIf(isFocused && sharedElementEnabled && current) {
                            RouteFiles.sharedElement(viewState.focused)
                        }
                )
                }
                }
            } else {
                if (isFocused) SideEffect {
                    focusedZoom = null
                    onReady()
                }
                // Video: still frame with a play button that opens the player.
                val painter = Painter(
                    ImageRequest(ctx)
                        .data(item.mediaUri)
                        .placeholderMemoryCacheKey(thumbnailCacheKey(item.id))
                        .build()
                )
                Image(
                    painter = painter,
                    contentDescription = item.name,
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .thenIf(isFocused) {
                            playIconModifier.clickable(null, null) {
                                navController.navigate(
                                    RouteIntentViewer(
                                        item.mediaUri,
                                        item.mimeType
                                    )
                                )
                            }.thenIf(sharedElementEnabled) { RouteFiles.sharedElement(viewState.focused) }
                        }
                )
            }
            }
        }
    )
}

/** Represents the MediaViewer Screen. */
@Composable
fun MediaViewer(viewState: MediaViewerViewState) {
    //
    var immersive by remember { mutableStateOf(false) }
    // Guards against closing twice (e.g. our edge swipe + the system back gesture together).
    var closing by remember { mutableStateOf(false) }
    // Vertical drag-to-dismiss offset in px, shared by the pager and the background fade.
    // (Created further down, once onRequest exists; declared here for the derived state.)
    val dismissScope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val closeRequest = remember { arrayOfNulls<() -> Unit>(1) }
    val haptic = LocalHapticFeedback.current
    val dismissOffset = remember {
        DismissController(
            dismissScope,
            density,
            close = { closeRequest[0]?.invoke() },
            onThresholdCrossed = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )
    }
    dismissOffset.screenHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val isDragging by remember { derivedStateOf { dismissOffset.offset != 0f } }
    // Zoom state of the photo in focus (set by the carousel).
    val zoomHolder = remember { mutableStateOf<ZoomableState?>(null) }
    // Rotate left/right: the photo turns on screen while the file is rotated.
    val rotator = remember { Rotator(dismissScope) }
    val deleter = remember { Deleter(dismissScope) }
    // Where this screen sits in the window (for drawing the grid picture, see Backdrop).
    val windowPos = remember { floatArrayOf(0f, 0f) }

    // Swipe-up properties sheet (the photo stays still; the sheet follows the finger).
    val sheet = rememberDetailsSheetState()
    val sheetOpen by remember { derivedStateOf { sheet.isOpen } }
    val compact = LocalWindowSize.current.width < Category.Medium
    val surface = rememberAcrylicSurface()
    val colors = AppTheme.colors
    //
    val facade = LocalSystemFacade.current
    val navController = LocalNavController.current
    // System-bar style before the viewer changed it. Restored *as the viewer starts closing*
    // (not after its exit animation), so the status bar (e.g. coming back from full-screen) is
    // already in place when the grid appears - no layout jump afterwards.
    val originalStyle = remember { arrayOfNulls<WindowStyle>(1) }
    val restoreStyle = {
        originalStyle[0]?.let { facade.style = it }
        immersive = false
    }
    val onRequest: (Int) -> Unit = { request: Int ->
        when (request) {
            // Toggle the visibility of detailed information.
            EVENT_SHOW_INFO -> if (sheet.isOpen) sheet.close() else sheet.open()
            // Toggle immersive mode and update system UI accordingly.
            EVENT_IMMERSIVE_VIEW -> {
                immersive = !immersive
                facade.style += if (immersive) WindowStyle.FLAG_SYSTEM_BARS_HIDDEN else WindowStyle.FLAG_SYSTEM_BARS_VISIBLE
            }
            // Handle back press events, prioritizing focused states (immersive, details)
            // before navigating up.
            EVENT_BACK_PRESS if sheet.isOpen -> sheet.close()
            // Back closes the viewer straight away, also from immersive (UI hidden) mode;
            // the original system-bar style is restored when the screen is disposed.
            EVENT_BACK_PRESS -> if (!closing) dismissOffset.closeNow()
            // This must not happen.
            else -> error("Received unexpected event: $request")
        }
    }
    // Drag-to-close finishes by closing the screen like back does (but without the shared-element
    // animation, see DismissController.dismissed).
    closeRequest[0] = {
        if (!closing) {
            closing = true
            restoreStyle()
            navController.navigateUp()
        }
    }
    BackHandler { onRequest(EVENT_BACK_PRESS) }
    // Properties of the photo currently shown (for the sheet).
    val currentFile by remember {
        derivedStateOf { viewState.data.find { it.id == viewState.focused } }
    }

    val isLoading by remember { derivedStateOf { viewState.data.isEmpty() } }
    val ctx = LocalContext.current
    Scaffold(
        fabPosition = FabPosition.End,
        contentColor = Color.SignalWhite,
        // Black backdrop that fades out while the photo is dragged down (read in draw phase).
        containerColor = Color.Transparent,
        modifier = Modifier
            .onPlaced { windowPos[0] = it.positionInWindow().x; windowPos[1] = it.positionInWindow().y }
            .drawBehind {
                val done = closing || dismissOffset.dismissed
                val progress = (dismissOffset.offset / (size.height * 0.4f)).coerceIn(0f, 1f)
                // The grid as it was when the photo opened: revealed while dragging, and shown
                // in full while closing (hiding the real grid settling underneath).
                val grid = Backdrop.image
                if (grid != null && (done || progress > 0f))
                    translate(-windowPos[0], -windowPos[1]) { drawImage(grid) }
                // Closing: no black at all.
                if (done) return@drawBehind
                drawRect(Color.Black.copy(alpha = 1f - progress))
            },
        // TopAppBar
        topBar = {
            MediaViewerTopAppBar(
                visible = !immersive && !isDragging && !closing,
                title = viewState.title,
                provider = surface,
                navigationIcon = {
                    IconButton(
                        Icons.Outlined.ReplyAll,
                        contentDescription = "Back",
                        onClick = { onRequest(EVENT_BACK_PRESS) }
                    )
                },
                actions = {
                    IconButton(
                        icon = Icons.Default.Info,
                        contentDescription = null,
                        onClick = { onRequest(EVENT_SHOW_INFO) }
                    )
                },
            )
        },
        // FloatingActionMenu
        floatingActionButton = {
            // Large round buttons on the right, like Aves (and like the app's navigation bar).
            ViewerActions(
                visible = !immersive && !isLoading && !isDragging && !sheetOpen && !closing,
                actions = viewState.actions,
                onAction = {
                    val file = currentFile
                    when {
                        it == com.zs.gallery.crop.CROP && file != null ->
                            navController.navigate(com.zs.gallery.crop.RouteCrop(file.mediaUri))
                        (it.label == R.string.rotate_left || it.label == R.string.rotate_right) && file != null ->
                            rotator.rotate(ctx.findActivity(), file, it.label == R.string.rotate_right, zoomHolder.value)
                        it.label == R.string.show_in_photos && file != null -> {
                            com.zs.gallery.files.TimelineJump.target = file.id
                            // Straight back down to the Photos grid (no saved/restored tab stacks:
                            // restoring one brought back an old person/photo screen instead).
                            val start = navController.graph.findStartDestination().id
                            if (!navController.popBackStack(start, inclusive = false))
                                navController.navigate(com.zs.gallery.files.RouteFiles()) { launchSingleTop = true }
                        }
                        it.label == R.string.add_to_person && file != null ->
                            com.zs.gallery.people.AddToPerson.open(file.id, video = !file.isImage)
                        it.label == R.string.delete && file != null -> {
                            val activity = ctx.findActivity()
                            // By id: by the time it runs, the next photo is the focused one.
                            deleter.delete(file.id, hide = { viewState.hide(file.id) }) {
                                viewState.deleteFile(activity, file.id)
                            }
                        }
                        else -> viewState.onPerformAction(it, ctx.findActivity())
                    }
                },

                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
                )
            )
        },
        // Carousal
        content = {
            // Ambient mode: applies a blur effect and noise overlay for an immersive background.
            // since content in placed inside of Box; so doing this is pretty normal
            val isAmbientModeEnabled by preference(Settings.KEY_VISUAL_EFFECT_MODE)

            if (isAmbientModeEnabled == 1) {
                val transformation =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                        remember { RsBlurTransformation(ctx, 25f, 2.1f) }
                    else null
                Crossfade(
                    // Crossfade animation for the ambient background when the focused item changes.
                    viewState.focused,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = if (closing || dismissOffset.dismissed) 0f
                            else 1f - (dismissOffset.offset / (size.height * 0.4f)).coerceIn(0f, 1f)
                        }
                        .thenIf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            blur(100.dp)
                        }
                        .foreground(Color(0xFF0E0E0F).copy(0.90f))
                        .visualEffect(ImageBrush.NoiseBrush, 0.08f, true, BlendMode.Luminosity),
                    animationSpec = AppTheme.motionScheme.slowEffectsSpec(),
                    // Content of the Crossfade: an AsyncImage with blur and noise effects.
                    content = {
                        AsyncImage(
                            ImageRequest(ctx)
                                .data(MediaProvider.buildContentUri(it))
                                .size(512)
                                .let {
                                    if (transformation == null) it else it.transformations(
                                        transformation
                                    )
                                }.build(),
                            null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
            }
            // Modifier for the main content, applying surface properties and filling the maximum size.
            val modifier = Modifier
                .source(surface)
                .fillMaxSize()
            // If data is loading, display a placeholder image with shared element transition.
            // The opening thumbnail stays underneath until the carousel has actually drawn the
            // focused photo, so switching between them never shows an empty frame.
            var carouselReady by remember { mutableStateOf(false) }
            // One container owns the close gestures for both the opening image and the carousel,
            // so the switch between them (the photo finishing loading) can't interrupt a drag.
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { rotator.pageSize = it }
                    .closeGestures(
                        enabled = { zoomHolder.value?.isZoomedOut ?: true },
                        dismiss = dismissOffset,
                        onClose = { onRequest(EVENT_BACK_PRESS) },
                        sheet = sheet
                    )
            ) {
            if (isLoading || !carouselReady)
                AsyncImage(
                    contentDescription = null,
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                    // Just the grid thumbnail (already in memory, so instant). The full-quality
                    // decode happens once, in the carousel, with this same thumbnail as its
                    // placeholder - so there is never an empty/black frame in between.
                    model = ImageRequest(ctx)
                        .memoryCacheKey(thumbnailCacheKey(viewState.focused))
                        .precision(Precision.INEXACT)
                        .data(MediaProvider.buildContentUri(viewState.focused))
                        .build(),
                    modifier = modifier
                        .thenIf(!carouselReady && !dismissOffset.dismissed) {
                            RouteFiles.sharedElement(viewState.focused)
                        },
                )
            // If data is loaded, display the Carousel with the media items (drawn on top).
            if (!isLoading)
                Carousel(
                    viewState,
                    onRequest,
                    sharedElementEnabled = carouselReady && !dismissOffset.dismissed,
                    onReady = { carouselReady = true },
                    zoomHolder = zoomHolder,
                    rotator = rotator,
                    deleter = deleter,
                    modifier = modifier
                )
            }
            // Swipe-up properties sheet, on top of everything, anchored to the bottom.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                DetailsSheet(of = currentFile, state = sheet)
            }
        }
    )

    // set/reset
    DisposableEffect(key1 = Unit) {
        //facade.enableEdgeToEdge(dark = false, translucent = false)
        val original = facade.style
        originalStyle[0] = original
        facade.style =
            original + WindowStyle.FLAG_SYSTEM_BARS_APPEARANCE_DARK + WindowStyle.FLAG_SYSTEM_BARS_BG_TRANSPARENT
        onDispose {
            // Reset to default on disposal
            facade.style = original
        }
    }
}

/**
 * Rotate left / right in the viewer: the photo turns on screen (and resizes to fit) while the file
 * is rotated; then the re-loaded photo replaces it (see the carousel).
 */
@Stable
internal class Rotator(private val scope: CoroutineScope) {
    class Turn(val id: Long, val fromRev: Int) {
        val angle = Animatable(0f)
        val scale = Animatable(1f)
    }

    /** Per photo: how many times it was rotated while the viewer is open (new image per value). */
    val revisions = mutableStateMapOf<Long, Int>()
    var turn by mutableStateOf<Turn?>(null)
        private set
    var pageSize = IntSize.Zero

    fun rotate(activity: Activity, file: MediaFile, clockwise: Boolean, zoom: ZoomableState?) {
        if (turn != null) return
        val t = Turn(file.id, revisions[file.id] ?: 0)
        turn = t
        scope.launch {
            zoom?.resetZoom()
            // Scale so the turned photo fits the screen like a freshly opened one would.
            val bounds = zoom?.transformedContentBounds
            val w = pageSize.width.toFloat()
            val h = pageSize.height.toFloat()
            val fit = if (bounds != null && bounds.width > 1f && bounds.height > 1f && w > 0f && h > 0f)
                kotlin.math.min(w / bounds.height, h / bounds.width) else 1f
            val spec = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
            val animation = launch {
                launch { t.angle.animateTo(if (clockwise) 90f else -90f, spec) }
                t.scale.animateTo(fit, spec)
            }
            val ok = com.zs.gallery.common.FileOps.rotate(activity, file, clockwise)
            animation.join()
            if (!ok) {
                launch { t.scale.animateTo(1f, spec) }
                t.angle.animateTo(0f, spec)
                turn = null
                return@launch
            }
            // Load the really rotated photo underneath; the carousel calls done() once it shows.
            revisions[file.id] = t.fromRev + 1
            delay(4000)
            done(t)
        }
    }

    fun done(t: Turn) {
        if (turn === t) turn = null
    }
}


/**
 * Delete in the viewer: the photo fades and shrinks away first, then it's deleted; the photo that
 * takes its place fades in. If the deletion is declined (system dialog), the photo comes back.
 */
@Stable
internal class Deleter(private val scope: CoroutineScope) {
    class Fade(val id: Long) {
        val alpha = Animatable(1f)
        val scale = Animatable(1f)
    }

    var fading by mutableStateOf<Fade?>(null)
        private set
    /** Photos that finished fading out: drawn invisible until they're gone from the list. */
    val hidden = androidx.compose.runtime.mutableStateListOf<Long>()
    var appearing by mutableStateOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?>(null)
        private set

    fun delete(id: Long, hide: () -> Unit, perform: () -> Unit) {
        if (fading != null) return
        val f = Fade(id)
        fading = f
        scope.launch {
            val spec = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
            // 1. The photo fades and shrinks away.
            launch { f.scale.animateTo(0.88f, spec) }
            f.alpha.animateTo(0f, spec)
            hidden += id
            // 2. It leaves the list at once and the next photo fades in (if the deletion is
            //    declined, the photo comes back).
            hide()
            val a = Animatable(0f)
            appearing = a
            fading = null
            a.animateTo(1f, tween(160))
            appearing = null
            hidden -= id
            // 3. Only now delete: Android's (invisible, auto-approved) confirmation screen
            //    freezes the display for a moment, which now lands on a still photo instead of
            //    swallowing an animation or showing black.
            perform()
        }
    }
}
