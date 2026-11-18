/*
 * Copyright 2025 sheik
 *
 * Created by sheik on 30-04-2025.
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

package com.zs.gallery.viewer

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zs.compose.theme.AlertDialog
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.LocalWindowSize
import com.zs.compose.theme.WindowSize.Category
import com.zs.compose.theme.minimumInteractiveComponentSize
import com.zs.compose.theme.text.Header
import com.zs.compose.theme.text.Label
import com.zs.core.store.MediaFile
import com.zs.gallery.R
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.outlined.Face
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import com.zs.gallery.common.compose.ContentPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val formatter
    get() = SimpleDateFormat("EEE, dd MMM yyyy, h:mm aa", Locale.getDefault())

private val MediaFile.megapixels
    get() = if (width > 0 && height > 0) (width * height) / 1_000_000f else -1f

@Composable
@NonRestartableComposable
private fun Info(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) = Row(
    modifier = modifier,
    horizontalArrangement = ContentPadding.LargeArrangement,
    verticalAlignment = Alignment.Top
) {
    Icon(icon, contentDescription = title.toString(), modifier = Modifier.size(20.dp))
    Label(
        buildAnnotatedString {
            append(title)
            val style = AppTheme.typography.body2
            withStyle(style.toSpanStyle()) {
                withStyle(style.toParagraphStyle()) {
                    appendLine(value)
                }
            }
        }, style = AppTheme.typography.label3,
        maxLines = 2
    )
}

@Composable
fun DetailsViewDialog(
    of: MediaFile?,
    onDismissRequest: () -> Unit,
) {
    val compact = LocalWindowSize.current.width < Category.Medium
    val value = of ?: return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Label(stringResource(R.string.properties))
        },
        gravity = if (compact) Gravity.BOTTOM else Gravity.CENTER,
        navigationIcon = {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        },
        actions = {
            IconButton(
                Icons.Outlined.Close,
                contentDescription = null,
                onClick = onDismissRequest
            )
        },
        content = { DetailsBody(value) }
    )
}

/** The properties shown in the dialog and in the swipe-up sheet. */
@Composable
private fun DetailsBody(value: MediaFile) = Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    val context = LocalContext.current
    // When: "Tuesday, 17 November 2026" with the 24-hour time.
    val date = remember(value.id) { Date(value.timelineDate) }
    Column(Modifier.padding(bottom = 4.dp)) {
        Label(
            remember(value.id) { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(date) },
            style = AppTheme.typography.title2,
            fontWeight = FontWeight.SemiBold
        )
        Label(
            remember(value.id) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(date) },
            style = AppTheme.typography.body1,
            color = AppTheme.colors.onBackground.copy(alpha = 0.7f)
        )
    }
    DetailRow(
        icon = if (value.isImage) Icons.Outlined.Image else Icons.Outlined.Videocam,
        label = "File name",
        value = value.name
    )
    // Place (offline: nearest bundled city), when the photo has a location.
    if (value.isImage) {
        val place by produceState<String?>(null, value.id) {
            this.value = com.zs.gallery.map.Places.describePhoto(context, value.id)
        }
        val navController = com.zs.gallery.common.compose.LocalNavController.current
        val scope = rememberCoroutineScope()
        place?.let {
            DetailRow(Icons.Outlined.Place, "Location", it, onClick = {
                scope.launch {
                    val loc = com.zs.core.store.PhotoLocations.locationOf(context, value.id) ?: return@launch
                    com.zs.gallery.map.MapJump.target = com.zs.gallery.map.MapJump.Target(value.id, loc[0], loc[1])
                    // Straight to the Map tab (fresh, not a restored old stack).
                    val start = navController.graph.findStartDestination().id
                    navController.popBackStack(start, inclusive = false)
                    navController.navigate(com.zs.gallery.map.RouteMap()) { launchSingleTop = true }
                }
            })
        }
    }
    // Who's in it (photos and scanned videos).
    PeopleRow(value.id)
    // Folder: "Phone › DCIM › Camera" instead of /storage/emulated/0/...
    val folder = value.path.substringBeforeLast("/")
    DetailRow(
        icon = Icons.Outlined.Folder,
        label = "Folder",
        value = com.zs.gallery.common.FileOps.relativePathOf(folder)?.trimEnd('/')
            ?.let { if (it.isEmpty()) "Phone" else "Phone › " + it.replace("/", " › ") } ?: folder
    )
    DetailRow(
        icon = Icons.Outlined.PhotoSizeSelectActual,
        label = "Size",
        value = buildString {
            append(Formatter.formatFileSize(context, value.size))
            if (value.isImage && value.megapixels > 0)
                append(String.format(Locale.getDefault(), " · %.1f MP", value.megapixels))
            if (value.width > 0 && value.height > 0)
                append(" · ${value.width} × ${value.height}")
            if (value.duration > 0)
                append(" · " + DateUtils.formatElapsedTime(value.duration / 1000L))
        }
    )
}

/** One property: icon in a soft accent circle, a small label and the value. */
@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick == null) Modifier
        else Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(colors.accent.copy(alpha = 0.16f), CircleShape)
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f, fill = onClick != null)) {
            Label(label, style = AppTheme.typography.label2, color = colors.onBackground.copy(alpha = 0.6f))
            Label(value, style = AppTheme.typography.body1, maxLines = 2)
        }
        // Tappable: a small arrow says "this takes you somewhere".
        if (onClick != null)
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                tint = colors.onBackground.copy(alpha = 0.5f), modifier = Modifier.size(22.dp)
            )
    }
}

/**
 * State of the swipe-up properties sheet: how far it is pulled up ([reveal], px) out of its
 * full [height]. Driven directly by the finger; [settle] snaps it open or closed on release.
 */
@Stable
class DetailsSheetState(private val scope: CoroutineScope, private val density: Float) {
    var height by mutableIntStateOf(0)
    val reveal = mutableFloatStateOf(0f)
    /** Heading for fully open (opened, or let go to open): follows height changes. */
    private var openTarget = false
    val isOpen: Boolean get() = reveal.floatValue > 1f

    /** Visibly out: opened (or opening), or at least a quarter pulled up. */
    val isShowing: Boolean get() = openTarget || reveal.floatValue > height * 0.25f
    private var job: Job? = null

    /** Finger moved by [dy] px (negative = up = pull the sheet further out). */
    fun dragBy(dy: Float) {
        job?.cancel()
        openTarget = false
        reveal.floatValue = (reveal.floatValue - dy).coerceIn(0f, height.toFloat())
    }

    /** Finger lifted with vertical [velocityY] (px/s; negative = upwards). */
    fun settle(velocityY: Float) {
        val fling = 600f * density
        val open = when {
            velocityY < -fling -> true
            velocityY > fling -> false
            else -> reveal.floatValue > height * 0.35f
        }
        openTarget = open
        animateTo(if (open) height.toFloat() else 0f)
    }

    /**
     * The content changed size (e.g. swiped to a photo with fewer details): an open sheet follows,
     * so it stays sitting on the bottom edge instead of floating (or being cut off).
     */
    fun onHeightChanged(newHeight: Int) {
        val old = height
        height = newHeight
        if (old <= 0 || newHeight == old) return
        // Open (or on its way to open, e.g. content changed during the opening animation).
        val open = openTarget || reveal.floatValue >= old - 1f
        when {
            open -> animateTo(newHeight.toFloat())
            reveal.floatValue > newHeight -> reveal.floatValue = newHeight.toFloat()
        }
    }

    fun open() {
        openTarget = true
        animateTo(height.toFloat())
    }

    fun close() {
        openTarget = false
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        job?.cancel()
        val start = reveal.floatValue
        if (start == target) return
        job = scope.launch {
            animate(start, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                reveal.floatValue = v
            }
        }
    }
}

@Composable
fun rememberDetailsSheetState(): DetailsSheetState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember { DetailsSheetState(scope, density) }
}

/**
 * Properties sheet anchored to the bottom of the viewer. It is pulled up by swiping up on the
 * photo (the photo itself never moves) and follows the finger; it can be dragged back down on
 * the sheet itself or on the photo.
 */
@Composable
fun DetailsSheet(
    of: MediaFile?,
    state: DetailsSheetState,
    modifier: Modifier = Modifier,
) {
    val value = of ?: return
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.onHeightChanged(it.height) }
            .graphicsLayer {
                // Hidden below the screen edge until pulled up.
                // Never more than its own height: it can't float above the bottom edge.
                translationY = size.height - state.reveal.floatValue.coerceAtMost(size.height)
            }
            .shadow(16.dp, shape)
            .background(colors.background(2.dp), shape)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> state.dragBy(delta) },
                onDragStopped = { velocity -> state.settle(velocity) }
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = ContentPadding.large, vertical = ContentPadding.medium)
    ) {
        // Grab handle
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .background(colors.onBackground.copy(alpha = 0.3f), RoundedCornerShape(50))
        )
        Spacer(Modifier.size(18.dp))
        DetailsBody(value)
        Spacer(Modifier.size(8.dp))
    }
}


/**
 * Who's in the photo (from the People scan): small face bubbles, with the name under the named
 * ones. Tap one to open that person. Nothing is shown if nobody was found.
 */
@Composable
private fun PeopleRow(mediaId: Long) {
    val context = LocalContext.current
    val navController = com.zs.gallery.common.compose.LocalNavController.current
    val version by com.zs.gallery.people.FaceIndexer.version.collectAsState()
    val people by produceState(emptyList<Pair<com.zs.gallery.people.FaceRow, String?>>(), mediaId, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.zs.gallery.people.FaceIndexer.store(context).peopleIn(mediaId) }.getOrDefault(emptyList())
        }
    }
    if (people.isEmpty()) return
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(colors.accent.copy(alpha = 0.16f), CircleShape)
        ) {
            Icon(Icons.Outlined.Face, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Label("People", style = AppTheme.typography.label2, color = colors.onBackground.copy(alpha = 0.6f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                for ((face, name) in people) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { navController.navigate(com.zs.gallery.people.RoutePerson(face.personId)) }
                            .padding(2.dp)
                    ) {
                        com.zs.gallery.people.FaceAvatar(face, 40.dp)
                        if (name != null)
                            Label(
                                name, style = AppTheme.typography.label3, maxLines = 1,
                                modifier = Modifier.padding(top = 3.dp).widthIn(max = 72.dp)
                            )
                    }
                }
            }
        }
    }
}
