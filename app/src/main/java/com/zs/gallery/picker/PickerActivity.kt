package com.zs.gallery.picker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Colors
import com.zs.compose.theme.Icon
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.text.Label
import com.zs.compose.theme.text.TonalHeader
import com.zs.core.store.MediaFile
import com.zs.core.store.MediaProvider
import com.zs.gallery.common.Mapped
import com.zs.gallery.files.TimelineFastScroller
import com.zs.gallery.files.TimelineIndex
import com.zs.gallery.files.thumbnailCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery M as a photo picker: other apps (and websites' "attach a file") can ask for a picture
 * or video and choose it here. Its own small activity, because the main one is single-instance
 * and can't return a result to another app.
 */
class PickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val type = intent.type.orEmpty()
        val wantsImages = type.isEmpty() || type.startsWith("image/") || type.startsWith("*/") ||
                type.contains("vnd.android.cursor.dir/image")
        val wantsVideos = type.isEmpty() || type.startsWith("video/") || type.startsWith("*/") ||
                type.contains("vnd.android.cursor.dir/video")
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        setContent {
            PickerTheme {
                Picker(
                    images = wantsImages,
                    videos = wantsVideos,
                    multiple = multiple,
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                    onDone = { uris -> deliver(uris) }
                )
            }
        }
    }

    private fun deliver(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
                for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun PickerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) Color(0xFF8E8FD1) else Color(0xFF6667AB)
    val colors = Colors(
        accent = accent,
        background = if (dark) Color(0xFF0E0E14) else Color(0xFFF7F7FC),
        onBackground = if (dark) Color(0xFFEDEDF2) else Color(0xFF1B1B21),
        onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White,
        error = Color(0xFFD32F2F),
        onError = Color.White,
    )
    AppTheme(
        colors = colors,
        typography = com.zs.compose.theme.Typography(androidx.compose.ui.text.font.FontFamily.Default),
        content = content
    )
}

/** Day sections, newest first (same labels as the Photos tab). */
private fun byDay(files: List<MediaFile>): Mapped<MediaFile> {
    val now = System.currentTimeMillis()
    val zone = java.util.TimeZone.getDefault()
    val result = LinkedHashMap<CharSequence, MutableList<MediaFile>>()
    var currentDay = Long.MIN_VALUE
    var current: MutableList<MediaFile>? = null
    for (file in files.sortedByDescending { it.timelineDate }) {
        val time = file.timelineDate
        val day = Math.floorDiv(time + zone.getOffset(time), android.text.format.DateUtils.DAY_IN_MILLIS)
        if (day != currentDay || current == null) {
            currentDay = day
            val label = android.text.format.DateUtils.getRelativeTimeSpanString(
                time, now, android.text.format.DateUtils.DAY_IN_MILLIS
            ).toString()
            current = result.getOrPut(label) { ArrayList() }
        }
        current!!.add(file)
    }
    return result
}

@Composable
private fun Picker(
    images: Boolean,
    videos: Boolean,
    multiple: Boolean,
    onCancel: () -> Unit,
    onDone: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val sections by produceState(emptyMap<CharSequence, List<MediaFile>>()) {
        value = withContext(Dispatchers.IO) {
            val all = runCatching {
                MediaProvider(context).fetchFiles(order = MediaProvider.COLUMN_DATE_MODIFIED, ascending = false)
            }.getOrDefault(emptyList())
            byDay(all.filter { (images && it.isImage) || (videos && !it.isImage) })
        }
    }
    val selected = remember { mutableStateListOf<MediaFile>() }
    BackHandler(onBack = onCancel)
    val state = rememberLazyGridState()
    val index = remember(sections) { sections.takeIf { it.isNotEmpty() }?.let { TimelineIndex(it, true, 1) } }
    val density = LocalDensity.current
    val columns by remember(density) {
        derivedStateOf {
            with(density) {
                val gap = 2.dp.roundToPx()
                maxOf((state.layoutInfo.viewportSize.width + gap) / (100.dp.roundToPx() + gap), 1)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = WindowInsets.systemBars.asPaddingValues().let {
                PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding() + 96.dp)
            },
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(icon = Icons.Outlined.Close, contentDescription = "Cancel", onClick = onCancel)
                    Label(
                        when {
                            multiple -> "Choose photos"
                            images && !videos -> "Choose a photo"
                            videos && !images -> "Choose a video"
                            else -> "Choose a photo or video"
                        },
                        style = AppTheme.typography.title2,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            for ((label, files) in sections) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$label", contentType = "header") {
                    TonalHeader(label, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp))
                }
                items(files, key = { it.id }) { file ->
                    val isSelected = selected.any { it.id == file.id }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clickable {
                                if (!multiple) onDone(listOf(file.mediaUri))
                                else if (isSelected) selected.removeAll { it.id == file.id }
                                else selected.add(file)
                            }
                    ) {
                        AsyncImage(
                            model = remember(file.id) {
                                ImageRequest.Builder(context).data(file.mediaUri)
                                    .memoryCacheKey(thumbnailCacheKey(file.id)).size(320).build()
                            },
                            contentDescription = file.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.background(4.dp))
                                .then(if (isSelected) Modifier.padding(8.dp).clip(RoundedCornerShape(12.dp)) else Modifier)
                        )
                        if (!file.isImage)
                            Icon(Icons.Filled.PlayCircle, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).zIndex(1f))
                        if (isSelected)
                            Icon(Icons.Filled.CheckCircle, null, tint = colors.accent,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp)
                                    .background(Color.White, CircleShape).zIndex(1f))
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "s_$label", contentType = "spacer") {
                    Box(Modifier.size(6.dp))
                }
            }
        }
        if (index != null)
            TimelineFastScroller(
                state = state,
                index = index,
                columns = columns,
                topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
                modifier = Modifier.matchParentSize()
            )
        // Several photos: "Add (3)".
        if (multiple && selected.isNotEmpty())
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .clickable { onDone(selected.map { it.mediaUri }) }
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Label("Add (${selected.size})", color = colors.onAccent, fontWeight = FontWeight.SemiBold)
            }
    }
}
