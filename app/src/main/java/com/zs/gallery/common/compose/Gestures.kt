/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.common.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Stops vertical drags that start on this element from reaching its children.
 *
 * Used on the collapsing top app bar: the bar should only collapse/expand when the content below
 * it scrolls, never when the finger is dragged on the bar itself. Taps are unaffected because
 * movement is only consumed once it exceeds the touch slop (i.e. once it is clearly a drag).
 */
fun Modifier.blockVerticalDrag(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var total = Offset.Zero
        var blocking = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!blocking) {
                total += change.positionChange()
                if (abs(total.y) > viewConfiguration.touchSlop)
                    blocking = true
            }
            // Consume in the Initial pass so the bar's own draggable never sees the movement.
            if (blocking) change.consume()
        }
    }
}

/**
 * Aves/Google-Photos style "drag down to close" for the media viewer — gesture detection only.
 *
 * This modifier never moves anything itself: it reports the finger movement through [onDrag] and
 * the end of the drag through [onDragEnd]; the caller owns the offset and all its animations.
 * (Having the gesture and the screen both animate the same offset is what could leave the photo
 * stuck half-way.)
 *
 * Runs in the Main pass on the pager's parent, so horizontal paging and pinch-zoom (handled by
 * children) win whenever they claim the gesture first.
 *
 * @param enabled evaluated at the start of every gesture (e.g. only when not zoomed in).
 * @param onDragStart where the drag began (for scaling around the finger).
 * @param onDrag vertical finger movement in px (positive = down).
 * @param onDragEnd vertical velocity (px/s) and whether the finger was lifted (vs. cancelled).
 * @param sheetOpen whether a bottom sheet (e.g. properties) is currently pulled out; then every
 *   vertical drag moves the sheet instead.
 * @param onSheetDrag if set, an *upward* drag (or any drag while the sheet is open) is forwarded
 *   here as raw finger movement (px, negative = up) and the content itself does not move.
 * @param onSheetRelease called with the vertical velocity (px/s) when such a drag ends.
 */
fun Modifier.dragToDismiss(
    enabled: () -> Boolean,
    onDragStart: (position: Offset) -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: (velocityY: Float, released: Boolean) -> Unit,
    sheetOpen: () -> Boolean = { false },
    onSheetDrag: ((dy: Float) -> Unit)? = null,
    onSheetRelease: ((velocityY: Float) -> Unit)? = null,
): Modifier = pointerInput(onSheetDrag != null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        var dragging = false
        var sheet = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                // A second finger *before* the drag starts means pinch-zoom: let zoom handle it.
                // Once dragging, extra touches are ignored (e.g. the palm resting on a curved
                // screen edge during a slow drag used to cancel it and snap the photo back).
                if (!dragging && event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // A real lift has a new timestamp; Compose's synthetic "cancel" repeats the
                    // previous one. Only a real lift may close the photo.
                    released = change.uptimeMillis != change.previousUptimeMillis
                    break
                }
                // Someone else (pager / zoom) claimed this gesture.
                if (!dragging && change.isConsumed) break
                val delta = change.positionChange()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!dragging) {
                    total += delta
                    if (total.getDistance() < slop) continue
                    // Only a clearly vertical drag starts. Up (or anything while the sheet is
                    // open) drives the sheet; down closes the viewer.
                    val vertical = abs(total.y) > abs(total.x) * 1.2f
                    when {
                        !vertical -> break
                        onSheetDrag != null && (sheetOpen() || total.y < 0) -> {
                            dragging = true
                            sheet = true
                            change.consume()
                            onSheetDrag(total.y)
                            continue
                        }
                        total.y > 0 -> {
                            dragging = true
                            change.consume()
                            onDragStart(down.position)
                            // Include the slop so the photo stays exactly under the finger.
                            onDrag(total.y)
                            continue
                        }
                        else -> break
                    }
                }
                change.consume()
                if (sheet) onSheetDrag?.invoke(delta.y) else onDrag(delta.y)
            }
        } finally {
            // Always report the end of a drag, even if the gesture was cancelled, so the owner
            // can settle the photo (or the sheet) - nothing is ever left half-way.
            if (dragging) {
                val velocity = if (released) tracker.calculateVelocity().y else 0f
                if (sheet) onSheetRelease?.invoke(velocity)
                else onDragEnd(velocity, released)
            }
        }
    }
}

/**
 * Treats a horizontal swipe that *starts at the left/right screen edge* as "back" and keeps it
 * away from the pager underneath, so the system edge-back gesture is never mistaken for
 * "next/previous photo".
 *
 * Runs in the Initial pass (before the pager sees anything). Taps at the edge still work because
 * movement is only intercepted after it exceeds the touch slop.
 *
 * @param edge width of the edge zone, measured from each side.
 */
fun Modifier.edgeSwipeBack(
    enabled: () -> Boolean,
    edge: () -> Float,
    onBack: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val zone = edge()
        val fromLeft = down.position.x <= zone
        val fromRight = down.position.x >= size.width - zone
        if (!fromLeft && !fromRight) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        var intercepting = false
        var released = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) break
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                released = true
                break
            }
            total += change.positionChange()
            if (!intercepting && total.getDistance() > slop) intercepting = true
            if (intercepting) change.consume()
        }
        val inward = if (fromLeft) total.x else -total.x
        // Also fires if the system already handled the back gesture (we then receive a
        // synthetic "up"); the caller makes closing idempotent.
        if (released && intercepting && inward > 40.dp.toPx() && inward > abs(total.y))
            onBack()
    }
}
