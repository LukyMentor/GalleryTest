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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Google-Photos/Aves style drag-to-select for the timeline grid.
 *
 * Long-press a photo to select it (or deselect, if it was selected); keep the finger down and
 * move it to extend the selection to every photo between the first one and the one under the
 * finger, row by row. Moving back shrinks the range again (photos leaving it return to how they
 * were). Near the top/bottom edge the grid scrolls by itself.
 *
 * Runs in the Initial pass so that once the long press fires, the grid's own scrolling and the
 * tiles' click handlers never see the gesture (no accidental "open photo" on release).
 *
 * @param index current timeline index (null while loading).
 * @param contentOffset grid content padding (start, top) in px: item offsets exclude it.
 */
internal fun Modifier.dragToSelect(
    state: LazyGridState,
    index: () -> TimelineIndex?,
    contentOffset: () -> Offset,
    isSelected: (Long) -> Boolean,
    setSelected: (Collection<Long>, Boolean) -> Unit,
    onStart: () -> Unit,
): Modifier = pointerInput(state) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val timeline = index() ?: return@awaitEachGesture
            // Wait for a long press: abort on lift, on a second finger, or if it turns into a scroll.
            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) return@withTimeoutOrNull true
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull true
                    if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                        return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
            if (cancelled == true) return@awaitEachGesture

            fun itemAt(position: Offset): Int? {
                val local = position - contentOffset()
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull {
                    val x = local.x - it.offset.x
                    val y = local.y - it.offset.y
                    x >= 0 && y >= 0 && x < it.size.width && y < it.size.height
                } ?: return null
                val id = info.key as? Long ?: return null
                return timeline.flatIndexOf[id]
            }

            val anchor = itemAt(down.position) ?: return@awaitEachGesture
            val ids = timeline.ids
            // Long-pressing a selected photo starts a "deselect" drag.
            val value = !isSelected(ids[anchor])
            // State of every photo before the drag touched it, to restore when the range shrinks.
            val initial = HashMap<Int, Boolean>()
            var lo = anchor
            var hi = anchor
            initial[anchor] = !value
            setSelected(listOf(ids[anchor]), value)
            onStart()

            fun extendTo(target: Int) {
                val newLo = min(anchor, target)
                val newHi = max(anchor, target)
                if (newLo == lo && newHi == hi) return
                val restoreOn = ArrayList<Long>()
                val restoreOff = ArrayList<Long>()
                for (i in lo..hi) if (i < newLo || i > newHi) {
                    if (initial[i] == true) restoreOn += ids[i] else restoreOff += ids[i]
                }
                val apply = ArrayList<Long>()
                for (i in newLo..newHi) if (i < lo || i > hi) {
                    if (i !in initial) initial[i] = isSelected(ids[i])
                    apply += ids[i]
                }
                if (restoreOn.isNotEmpty()) setSelected(restoreOn, true)
                if (restoreOff.isNotEmpty()) setSelected(restoreOff, false)
                if (apply.isNotEmpty()) setSelected(apply, value)
                lo = newLo
                hi = newHi
            }

            // Auto-scroll when the finger is near the top or bottom edge.
            var pointer = down.position
            val edge = 72.dp.toPx()
            val maxSpeed = 28.dp.toPx() // px per frame at the very edge
            fun speedAt(y: Float): Float {
                val top = contentOffset().y + edge
                val bottom = size.height - edge
                return when {
                    y < top -> -maxSpeed * ((top - y) / edge).coerceAtMost(1f)
                    y > bottom -> maxSpeed * ((y - bottom) / edge).coerceAtMost(1f)
                    else -> 0f
                }
            }
            val scroller: Job = launch {
                while (isActive) {
                    withFrameNanos { }
                    val speed = speedAt(pointer.y)
                    if (speed != 0f) {
                        state.scrollBy(speed)
                        itemAt(pointer)?.let(::extendTo)
                    }
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Keep the grid and the tiles out of this gesture entirely.
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                    pointer = change.position
                    itemAt(pointer)?.let(::extendTo)
                }
            } finally {
                scroller.cancel()
            }
        }
    }
}
