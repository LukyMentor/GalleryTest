/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.files

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import com.zs.compose.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private val ThumbWidth = 40.dp
private val ThumbHeight = 64.dp
private val BubbleColor = Color.White
private val BubbleTextColor = Color(0xFF1B1B1F)
private val PillShape = RoundedCornerShape(50)

/**
 * Aves-style fast scroller for the timeline.
 *
 * - Appears while the grid scrolls and fades out ~1.5 s after it stops.
 * - Grab the thumb and drag: the grid jumps to the matching place in time, a bubble next to the
 *   thumb shows the current month, and month/year markers appear along the right edge.
 *
 * Place it over the grid (e.g. `Modifier.matchParentSize()` in a `Box`). It only intercepts
 * touches on the visible thumb itself.
 *
 * @param topPadding/bottomPadding keep the track clear of the top bar and the bottom navigation.
 */
@Composable
internal fun TimelineFastScroller(
    state: LazyGridState,
    index: TimelineIndex,
    columns: Int,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (index.sections == 0) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // Columns come from the grid's own formula (passed in). Guessing them from the visible items
    // was unstable and kept restarting the drag gesture, making the thumb vanish mid-drag.
    val rows = remember(index, columns) { index.Rows(columns.coerceAtLeast(1)) }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Jumps requested by the thumb; a new jump cancels the previous one.
    val scope = rememberCoroutineScope()
    val jump = remember { arrayOfNulls<Job>(1) }

    // Height of a photo row (from a photo tile, never a header / gap / bar - those are much
    // shorter and made the estimate, and with it the thumb, jump around while scrolling).
    val lastTile = remember { intArrayOf(0) }
    val tileRowPx by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val width = info.viewportSize.width
            val h = info.visibleItemsInfo.firstOrNull { it.size.width in 1 until width - 1 }?.size?.height ?: 0
            if (h > 0) lastTile[0] = h
            lastTile[0]
        }
    }

    // Number of grid lines on screen (fraction 1 == end of the list). From the screen height,
    // so it stays the same while scrolling instead of changing with the headers in view.
    val visibleRows by remember(rows) {
        derivedStateOf {
            val viewport = state.layoutInfo.viewportSize.height
            if (tileRowPx <= 0 || viewport <= 0) 1 else (viewport / tileRowPx).coerceAtLeast(1)
        }
    }

    /** Scroll position as 0..1 (sub-row precision from the first item's scroll offset). */
    fun scrollFraction(): Float {
        val scrollable = (rows.total - visibleRows).coerceAtLeast(1)
        if (!state.canScrollForward) return 1f
        val first = state.firstVisibleItemIndex
        val firstInfo = state.layoutInfo.visibleItemsInfo.firstOrNull()
        val rowHeight = firstInfo?.size?.height?.coerceAtLeast(1) ?: 1
        val within = (state.firstVisibleItemScrollOffset / rowHeight.toFloat()).coerceIn(0f, 1f)
        val row = rows.rowOf(first) + within
        return (row / scrollable).coerceIn(0f, 1f)
    }

    fun indexForFraction(fraction: Float): Int {
        val scrollable = (rows.total - visibleRows).coerceAtLeast(0)
        val row = (fraction * scrollable).roundToInt()
        return rows.indexOfRow(row)
    }

    // Light "tick" each time the drag crosses into another month (or section), like Google
    // Photos but softer.
    LaunchedEffect(dragging, index) {
        if (!dragging) return@LaunchedEffect
        snapshotFlow {
            val section = index.sectionOf(state.firstVisibleItemIndex)
            if (index.byDate) index.monthKey[section] else section
        }
            .drop(1)
            .collect { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    // Visibility: show while scrolling or dragging, hide a moment after.
    val scrolling = state.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true
        else {
            delay(1500)
            visible = false
        }
    }
    val canScroll by remember { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
    val thumbAlpha by animateFloatAsState(
        targetValue = if (visible && canScroll) 1f else 0f,
        animationSpec = tween(if (visible) 150 else 400),
        label = "fast_scroller_alpha"
    )

    // Nothing to draw and, importantly, nothing to catch touches when hidden.
    if (thumbAlpha == 0f) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Starts a bit below the top, so the thumb never sits on the top bar's buttons.
        val topPx = with(density) { (topPadding + 72.dp).toPx() }
        val bottomPx = with(density) { (bottomPadding + 8.dp).toPx() }
        val thumbHeightPx = with(density) { ThumbHeight.toPx() }
        val heightPx = constraints.maxHeight.toFloat()
        val range = (heightPx - topPx - bottomPx - thumbHeightPx).coerceAtLeast(1f)

        val currentFraction = { if (dragging) dragFraction else scrollFraction() }
        val thumbTop = { topPx + currentFraction() * range }
        // Where the thumb was last laid out; pointer positions on the thumb are relative to it.
        val placedTop = remember { floatArrayOf(0f) }

        // Month / year markers along the track while dragging.
        if (dragging) {
            val crumbs = remember(rows, range) { buildCrumbs(index, rows, visibleRows, density.density) }
            Crumbs(crumbs, topPx, range, thumbHeightPx)
        }

        // Current month bubble next to the thumb while dragging.
        if (dragging) {
            val label by remember(index) {
                derivedStateOf {
                    val section = index.sectionOf(state.firstVisibleItemIndex)
                    if (index.byDate) monthYearLabel(index.time[section]) else index.labels[section]
                }
            }
            Bubble(
                text = label,
                large = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .centerVerticallyAt { thumbTop() + thumbHeightPx / 2 }
                    .padding(end = ThumbWidth + 20.dp)
            )
        }

        // The thumb. Its gesture is started once (pointerInput(Unit)) and always reads the
        // latest values, so data reloads or relayouts can never cancel a drag in progress.
        val latestRange by rememberUpdatedState(range)
        val latestTopPx by rememberUpdatedState(topPx)
        val latestFraction by rememberUpdatedState(currentFraction)
        val latestIndexFor by rememberUpdatedState<(Float) -> Int>({ f -> indexForFraction(f) })
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val top = thumbTop()
                    placedTop[0] = top
                    IntOffset(0, top.roundToInt())
                }
                .graphicsLayer { alpha = thumbAlpha }
                .padding(end = 6.dp)
                .size(ThumbWidth, ThumbHeight)
                .shadow(6.dp, PillShape)
                .background(BubbleColor, PillShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // Finger position on the thumb at grab time; keeps the thumb under it.
                        val grab = down.position.y
                        dragFraction = latestFraction()
                        dragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                // Absolute y within the track = where the thumb is drawn + local y.
                                val y = placedTop[0] + change.position.y - grab
                                val fraction = ((y - latestTopPx) / latestRange).coerceIn(0f, 1f)
                                if (fraction != dragFraction) {
                                    dragFraction = fraction
                                    val target = latestIndexFor(fraction)
                                    jump[0]?.cancel()
                                    jump[0] = scope.launch { state.scrollToItem(target) }
                                }
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
        ) {
            ChevronIcon(Icons.Filled.KeyboardArrowUp)
            ChevronIcon(Icons.Filled.KeyboardArrowDown)
        }
    }
}

@Composable
private fun ChevronIcon(vector: ImageVector) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        colorFilter = ColorFilter.tint(BubbleTextColor),
        modifier = Modifier.padding(top = 4.dp).size(26.dp)
    )
}

/** Places the element so that its vertical centre is at [y] (px, read during layout). */
private fun Modifier.centerVerticallyAt(y: () -> Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, (y() - placeable.height / 2f).roundToInt())
        }
    }

@Composable
private fun Bubble(
    text: String,
    large: Boolean,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    // Year markers stand out: accent background with bold white text.
    val accent = AppTheme.colors.accent
    Box(
        modifier = modifier
            .shadow(if (large) 6.dp else 3.dp, PillShape)
            .background(if (emphasized) accent else BubbleColor, PillShape)
            .padding(
                horizontal = if (large) 18.dp else 12.dp,
                vertical = if (large) 8.dp else 5.dp
            )
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = if (emphasized) Color.White else BubbleTextColor,
                fontSize = if (large) 22.sp else 14.sp,
                fontWeight = when {
                    emphasized -> FontWeight.Bold
                    large -> FontWeight.Medium
                    else -> FontWeight.Normal
                }
            )
        )
    }
}

/** A marker on the track: [fraction] 0..1 along it, its text, and whether it marks a year. */
private class Crumb(val fraction: Float, val label: String, val emphasized: Boolean = false)

/**
 * One candidate per month that has photos. Year changes are kept first, then as many months as
 * fit without overlapping (min ~30 dp apart), like Aves.
 */
private fun buildCrumbs(
    index: TimelineIndex,
    rows: TimelineIndex.Rows,
    visibleRows: Int,
    density: Float,
): List<Crumb> {
    val scrollable = (rows.total - visibleRows).coerceAtLeast(1).toFloat()
    data class Candidate(val fraction: Float, val label: String, val isYear: Boolean)

    val candidates = ArrayList<Candidate>()
    if (!index.byDate) {
        // Not a date order (e.g. by size): one marker per section, by its title.
        for (s in 0 until index.sections) {
            val fraction = (rows.rowStart[s] / scrollable).coerceIn(0f, 1f)
            candidates += Candidate(fraction, index.labels[s], false)
        }
    }
    var previousMonth = Int.MIN_VALUE
    var previousYear = Int.MIN_VALUE
    val monthFormat = SimpleDateFormat("LLL", Locale.getDefault())
    for (s in 0 until (if (index.byDate) index.sections else 0)) {
        val key = index.monthKey[s]
        if (key == previousMonth) continue
        previousMonth = key
        val year = key / 12
        val fraction = (rows.rowStart[s] / scrollable).coerceIn(0f, 1f)
        val isYear = year != previousYear
        previousYear = year
        val label = if (isYear) year.toString() else monthFormat.format(index.time[s])
        candidates += Candidate(fraction, label, isYear)
    }
    return candidates
        .sortedByDescending { it.isYear } // stable: years first, each group keeps time order
        .let { ordered ->
            // Greedy placement; the min gap is applied later in px, here as a fraction estimate.
            val placed = ArrayList<Candidate>()
            for (c in ordered) {
                if (placed.none { kotlin.math.abs(it.fraction - c.fraction) < MIN_GAP_FRACTION })
                    placed += c
            }
            placed.sortedBy { it.fraction }.map { Crumb(it.fraction, it.label, it.isYear) }
        }
}

/** Minimum distance between markers, as a fraction of the track (~30 dp on a phone). */
private const val MIN_GAP_FRACTION = 0.045f

@Composable
private fun Crumbs(crumbs: List<Crumb>, topPx: Float, range: Float, thumbHeightPx: Float) {
    Layout(
        content = {
            for (crumb in crumbs) Bubble(crumb.label, large = false, emphasized = crumb.emphasized)
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val endPadding = (ThumbWidth + 16.dp).roundToPx()
            placeables.forEachIndexed { i, placeable ->
                val centerY = topPx + crumbs[i].fraction * range + thumbHeightPx / 2
                placeable.place(
                    x = constraints.maxWidth - endPadding - placeable.width,
                    y = (centerY - placeable.height / 2f).roundToInt()
                )
            }
        }
    }
}

private fun monthYearLabel(time: Long): String =
    SimpleDateFormat("LLL yyyy", Locale.getDefault()).format(time)
