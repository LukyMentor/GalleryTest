package com.zs.gallery.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.text.Label
import com.zs.core.store.Folder
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.max
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf

/**
 * "Move to" / "Copy to" folder chooser: a sheet with the folders' pictures (instead of a plain
 * list of paths). Asked for from anywhere with [pick]; shown by [FolderPickerHost] in Home.
 */
object FolderPicker {
    /** Returned by [pick] when the user wants a new folder. */
    const val NEW_FOLDER = "\u0000new"

    class Request(
        val title: String,
        val folders: List<Pair<Folder, String>>,
        val result: CompletableDeferred<String?>,
        /** Shown instead of "Choose a folder", e.g. why the last choice didn't work. */
        val note: String? = null,
    )

    var request by mutableStateOf<Request?>(null)
        private set

    /** The chosen folder's relative path ("DCIM/Camera/"), [NEW_FOLDER], or null if dismissed. */
    suspend fun pick(title: String, folders: List<Pair<Folder, String>>, note: String? = null): String? {
        val r = Request(title, folders, CompletableDeferred(), note)
        request?.result?.complete(null)
        request = r
        return try {
            r.result.await()
        } finally {
            if (request === r) request = null
        }
    }

    fun answer(value: String?) {
        request?.result?.complete(value)
        request = null
    }
}

@Composable
fun FolderPickerHost() {
    val current = FolderPicker.request
    // Keep showing the last request while the sheet animates away.
    var shown by remember { mutableStateOf<FolderPicker.Request?>(null) }
    if (current != null) shown = current
    val visible = current != null
    // Re-registered each time the sheet opens, so it's the newest back handler and the back
    // gesture closes the sheet first (not the photo / screen underneath).
    androidx.compose.runtime.key(visible) { BackHandler(enabled = visible) { FolderPicker.answer(null) } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { FolderPicker.answer(null) }
                    )
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val request = shown ?: return@AnimatedVisibility
            androidx.compose.runtime.key(request) { Sheet(request) }
        }
    }
}

@Composable
private fun Sheet(request: FolderPicker.Request) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    // Pull down to close: by the handle/title, or by pulling the list down once it's at the top.
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density
    fun release(velocity: Float) {
        scope.launch {
            val close = offset > height * 0.25f || velocity > 1200f * density
            val target = if (close) height.toFloat() else 0f
            animate(offset, target, initialVelocity = velocity,
                animationSpec = if (close) tween(160) else spring()) { v, _ -> offset = v }
            if (close) FolderPicker.answer(null)
        }
    }
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Sheet pulled down and the finger goes back up: raise the sheet first.
                if (available.y < 0f && offset > 0f) {
                    val used = max(available.y, -offset)
                    offset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // List already at the top and still pulled down: move the sheet.
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    offset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val drag = rememberDraggableState { d -> offset = (offset + d).coerceAtLeast(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .onSizeChanged { height = it.height }
            .graphicsLayer { translationY = offset }
            .nestedScroll(pull)
            .clip(shape)
            .background(colors.background(3.dp))
            // Swallow taps so they don't reach the scrim.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Handle + title: the part you grab to pull the sheet down.
        Column(
            Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = drag,
                    onDragStopped = { v -> release(v) }
                )
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.onBackground.copy(alpha = 0.3f), RoundedCornerShape(50))
            )
            Label(
                request.title,
                style = AppTheme.typography.title2,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp)
            )
            Label(
                request.note ?: "Choose a folder",
                style = AppTheme.typography.body2,
                color = if (request.note != null) colors.error else colors.onBackground.copy(alpha = 0.65f),
                fontWeight = if (request.note != null) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "new") { NewFolderTile() }
            items(request.folders, key = { it.first.path }) { (folder, relative) ->
                FolderTile(folder) { FolderPicker.answer(relative) }
            }
        }
    }
}

@Composable
private fun NewFolderTile() {
    val colors = AppTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { FolderPicker.answer(FolderPicker.NEW_FOLDER) }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accent.copy(alpha = 0.16f))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.accent, CircleShape)
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = colors.onAccent)
            }
        }
        Label(
            "New folder",
            style = AppTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = colors.accent,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )
    }
}

@Composable
private fun FolderTile(folder: Folder, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = remember(folder.artworkID) {
                ImageRequest.Builder(context).data(folder.artworkUri).size(320).build()
            },
            contentDescription = folder.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.background(6.dp))
        )
        Label(
            folder.name,
            style = AppTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = colors.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )
        Label(
            if (folder.count == 1) "1 item" else "${folder.count} items",
            style = AppTheme.typography.label3,
            color = colors.onBackground.copy(alpha = 0.6f),
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
