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
 * Aves/Google-Photos style "drag down to close" for the media viewer.
 *
 * The content follows the finger (the caller reads [offset] to translate/scale/fade); releasing
 * past a distance or with enough downward velocity calls [onDismiss], otherwise the content
 * springs back. Runs in the Main pass on the pager's parent, so horizontal paging and pinch-zoom
 * (handled by children) win whenever they claim the gesture first.
 *
 * @param enabled evaluated at the start of every gesture (e.g. only when not zoomed in).
 * @param offset current vertical drag offset in px (0 = resting; negative = dragged up).
 * @param onSwipeUp if set, dragging *up* (with resistance) past a threshold calls it, e.g. to
 *   show the photo's details; the content then springs back.
 */
fun Modifier.dragToDismiss(
    enabled: () -> Boolean,
    offset: MutableFloatState,
    onDismiss: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
): Modifier = pointerInput(onSwipeUp != null) {
    coroutineScope {
        // Settle animation; cancelled whenever a new gesture starts.
        var settle: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled()) return@awaitEachGesture
            settle?.cancel()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var dragging = false
            var up = false
            var released = false
            while (true) {
                val event = awaitPointerEvent()
                // A second finger means pinch-zoom: give up and let the zoom handler work.
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    released = true
                    break
                }
                // Someone else (pager / zoom) claimed this gesture.
                if (!dragging && change.isConsumed) break
                val delta = change.positionChange()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!dragging) {
                    total += delta
                    if (total.getDistance() < slop) continue
                    // Only a clearly vertical drag starts: down = close, up = details.
                    val vertical = abs(total.y) > abs(total.x) * 1.2f
                    when {
                        vertical && total.y > 0 -> dragging = true
                        vertical && total.y < 0 && onSwipeUp != null -> {
                            dragging = true
                            up = true
                        }
                        else -> break
                    }
                }
                change.consume()
                offset.floatValue =
                    if (up) (offset.floatValue + delta.y * 0.5f).coerceAtMost(0f) // resistance
                    else (offset.floatValue + delta.y).coerceAtLeast(0f)
            }
            if (!dragging) return@awaitEachGesture
            val velocity = tracker.calculateVelocity().y
            val threshold = 96.dp.toPx()
            if (released && !up && (offset.floatValue > threshold || velocity > 1200.dp.toPx())) {
                onDismiss()
                return@awaitEachGesture
            }
            if (released && up && (offset.floatValue < -threshold / 2 || velocity < -1200.dp.toPx()))
                onSwipeUp?.invoke()
            // Spring back to rest.
            val start = offset.floatValue
            settle = launch {
                animate(start, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    offset.floatValue = value
                }
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
