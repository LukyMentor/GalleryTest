package com.zs.gallery.people

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.zs.compose.foundation.findActivity
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.adaptive.content
import com.zs.compose.theme.text.Label
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.FileOps
import com.zs.gallery.common.Route
import com.zs.gallery.common.compose.LocalNavController
import com.zs.gallery.files.PhotoSets
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.files.thumbnailCacheKey
import com.zs.gallery.viewer.CountBubble
import com.zs.gallery.viewer.RouteViewer
import com.zs.gallery.viewer.ViewerActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

object RoutePeople : Route

object RoutePerson : Route {
    const val PARAM_ID = "param_id"
    override val route: String = "$domain/{$PARAM_ID}"
    override fun invoke(): String = error("RoutePerson needs an id")
    operator fun invoke(id: Long): String = "$domain/$id"
}

private val MOVE_TO_PERSON = Action(R.string.move_to_person, Icons.Outlined.SwitchAccount)
private val NOT_THIS_PERSON = Action(R.string.not_this_person, Icons.Outlined.PersonRemove)

private fun imageUri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/** A person's face, cut out of one of their photos. */
@Composable
fun FaceAvatar(face: FaceRow?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    if (face == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size).background(colors.accent.copy(alpha = 0.16f), CircleShape)
        ) { Icon(Icons.Outlined.Face, null, tint = colors.accent) }
        return
    }
    if (face.isVideo) {
        // Video faces: the crop saved during the scan (or the video's own thumbnail).
        val crop = remember(face.id) { FaceIndexer.store(context).cropFile(face.id).takeIf { it.exists() } }
        AsyncImage(
            model = remember(face.id) {
                ImageRequest.Builder(context)
                    .data(crop ?: ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, face.mediaId))
                    .size(256).build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape).background(colors.background(6.dp))
        )
        return
    }
    val painter = rememberAsyncImagePainter(
        remember(face.id) { ImageRequest.Builder(context).data(imageUri(face.mediaId)).size(640).build() }
    )
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.background(6.dp))
            .drawBehind {
                val img = painter.intrinsicSize
                if (img.isUnspecifiedSize() || img.width <= 0f || img.height <= 0f) return@drawBehind
                val box = face.box
                // A square around the face, a bit bigger than the face itself.
                val side = max(box.width() * img.width, box.height() * img.height) * 1.5f
                val s = this.size.width / side
                val cx = box.centerX() * img.width
                val cy = box.centerY() * img.height
                translate(this.size.width / 2f - cx * s, this.size.height / 2f - cy * s) {
                    scale(s, pivot = Offset.Zero) {
                        with(painter) { draw(Size(img.width, img.height)) }
                    }
                }
            }
    )
}

private fun Size.isUnspecifiedSize() = this == Size.Unspecified || width.isNaN() || height.isNaN()

/** "Scanning 1,234 / 10,456" etc. - so you can see the scan is doing something. */
@Composable
private fun ScanStatus(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by FaceIndexer.status.collectAsState()
    val colors = AppTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.background(2.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val title = when {
            status.paused -> "Face scan paused"
            status.running && status.total > 0 && status.scanned < status.total ->
                "Scanning photos… ${"%,d".format(status.scanned)} / ${"%,d".format(status.total)}"
            status.running -> status.note ?: "Working…"
            else -> status.note ?: "Face scan"
        }
        Label(title, style = AppTheme.typography.body1, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        if (status.total > 0) {
            val p = (status.scanned.toFloat() / status.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(colors.onBackground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(50)).background(colors.accent))
            }
        }
        Label(
            "${"%,d".format(status.faces)} faces · ${status.people} people" +
                    if (status.msPerPhoto > 0) " · ${status.msPerPhoto} ms/photo" else "",
            style = AppTheme.typography.body2, color = colors.onBackground.copy(alpha = 0.7f)
        )
        if (status.videos && status.videoTotal > 0)
            Label(
                "Videos: ${"%,d".format(status.videoScanned)} / ${"%,d".format(status.videoTotal)}" +
                        if (status.msPerVideo > 0) " · ${"%.1f".format(status.msPerVideo / 1000f)} s/video" else "",
                style = AppTheme.typography.body2, color = colors.onBackground.copy(alpha = 0.7f)
            )
        status.error?.let { Label(it, style = AppTheme.typography.body2, color = Color(0xFFFF6B6B), maxLines = 3) }
        if (open) {
            status.current?.let {
                Label("Now: $it", style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f), maxLines = 1)
            }
            Label(
                "Runs on the phone only, while Gallery M is open (or still in memory). Experimental.",
                style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f)
            )
            Label(
                "Videos up to 5 minutes are scanned after all photos (a frame every 2.5 s; screen recordings skipped).",
                style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                SmallPill(if (status.paused) "Resume" else "Pause", primary = true) {
                    FaceIndexer.setPaused(context, !status.paused)
                }
                SmallPill(if (status.videos) "Videos: on" else "Scan videos too", primary = status.videos) {
                    FaceIndexer.setScanVideos(context, !status.videos)
                }
                SmallPill("Re-group") { FaceIndexer.regroup(context) }
                SmallPill("Rescan all") { FaceIndexer.reset(context) }
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) colors.accent else colors.onBackground.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Label(text, style = AppTheme.typography.body2, color = if (primary) colors.onAccent else colors.onBackground)
    }
}

/** The People tab: everyone found in the photos. */
@Composable
fun PeopleScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val colors = AppTheme.colors
    LaunchedEffect(Unit) { FaceIndexer.start(context) }
    val version by FaceIndexer.version.collectAsState()
    // Starts from the last list (not empty), so coming back from a person keeps the scroll position.
    val people by produceState(LastLists.people, version) {
        value = withContext(Dispatchers.IO) { FaceIndexer.store(context).people() }
        LastLists.people = value
    }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val visible = people.filter { !it.hidden || showHidden }
    val main = visible.filter { it.name != null || it.photos >= 3 }
    val rest = visible.filter { it.name == null && it.photos < 3 }
    val hiddenCount = people.count { it.hidden }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = WindowInsets.content
            .union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
            .union(WindowInsets.statusBars)
            .asPaddingValues(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
            Label(
                "People",
                style = AppTheme.typography.headline2,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                modifier = Modifier.padding(start = 6.dp, top = 24.dp, bottom = 4.dp)
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "status") { ScanStatus() }
        if (people.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Label(
                    "Faces will appear here as the scan finds them.",
                    style = AppTheme.typography.body2,
                    color = colors.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        items(main, key = { it.id }) { person -> PersonTile(person) { navController.navigate(RoutePerson(person.id)) } }
        if (rest.isNotEmpty())
            item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                SmallRowButton(
                    if (showAll) "Hide people with 1–2 photos" else "Show ${rest.size} more (only 1–2 photos)"
                ) { showAll = !showAll }
            }
        if (showAll) items(rest, key = { it.id }) { person ->
            PersonTile(person) { navController.navigate(RoutePerson(person.id)) }
        }
        if (hiddenCount > 0)
            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden") {
                SmallRowButton(if (showHidden) "Don't show hidden people" else "Show $hiddenCount hidden") {
                    showHidden = !showHidden
                }
            }
    }
}

@Composable
private fun SmallRowButton(text: String, onClick: () -> Unit) {
    Label(
        text,
        style = AppTheme.typography.body2,
        color = AppTheme.colors.accent,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun PersonTile(person: Person, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        FaceAvatar(person.cover, 84.dp)
        Label(
            person.name ?: "Add a name",
            style = AppTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = if (person.name != null) colors.onBackground else colors.onBackground.copy(alpha = 0.55f),
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp)
        )
        Label(
            if (person.photos == 1) "1 photo" else "${person.photos} photos",
            style = AppTheme.typography.label3,
            color = colors.onBackground.copy(alpha = 0.6f)
        )
    }
}

/** Photos grouped by day ("Today", "3 days ago", "12 March 2025"…), newest first. */
private suspend fun byDay(context: Context, ids: List<Long>): com.zs.gallery.common.Mapped<com.zs.core.store.MediaFile> {
    if (ids.isEmpty()) return emptyMap()
    val provider = com.zs.core.store.MediaProvider(context)
    val files = ids.chunked(500).flatMap { provider.fetchFiles(*it.toLongArray()) }
    val sorted = files.distinctBy { it.id }.sortedByDescending { it.timelineDate }
    val now = System.currentTimeMillis()
    val zone = java.util.TimeZone.getDefault()
    val result = LinkedHashMap<CharSequence, MutableList<com.zs.core.store.MediaFile>>()
    var currentDay = Long.MIN_VALUE
    var current: MutableList<com.zs.core.store.MediaFile>? = null
    for (file in sorted) {
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

/** Photo ids sorted newest first (by date taken). */
private fun sortedByDate(context: Context, ids: List<Long>): List<Long> {
    if (ids.isEmpty()) return ids
    val dates = HashMap<Long, Long>()
    for (chunk in ids.chunked(500)) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED),
            "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(1)
                dates[c.getLong(0)] = if (taken > 0) taken else c.getLong(2) * 1000
            }
        }
    }
    // Photos that no longer exist drop out.
    return ids.filter { it in dates }.sortedByDescending { dates[it] }
}

/** One person's photos; select photos to move them to someone else. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(id: Long) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val version by FaceIndexer.version.collectAsState()
    val store = remember { FaceIndexer.store(context) }
    val person by produceState(LastLists.people.firstOrNull { it.id == id }, version) {
        value = withContext(Dispatchers.IO) { store.person(id) }
    }
    // Same as above: back from a photo, the grid is still where it was.
    // Photos grouped by day, like the Photos tab.
    val sections by produceState(LastLists.photos[id] ?: emptyMap(), version) {
        value = withContext(Dispatchers.IO) { byDay(context, store.photosOf(id)) }
        LastLists.photos[id] = value
    }
    val photos = remember(sections) { sections.values.flatten().map { it.id } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Section layout for the fast scroller (1 leading item: the header).
    val index = remember(sections) {
        sections.takeIf { it.isNotEmpty() }?.let { com.zs.gallery.files.TimelineIndex(it, true, 1) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gridColumns by remember(density) {
        androidx.compose.runtime.derivedStateOf {
            with(density) {
                val available = gridState.layoutInfo.viewportSize.width
                val gap = 2.dp.roundToPx()
                maxOf((available + gap) / (100.dp.roundToPx() + gap), 1)
            }
        }
    }
    val selected = remember { mutableStateListOf<Long>() }
    // What the person picker is for: moving photos, or merging this whole person.
    var picker by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selected.isNotEmpty() && picker == null) { selected.clear() }

    fun edit(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            FaceIndexer.changed()
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = WindowInsets.systemBars.asPaddingValues().let {
                PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding() + 96.dp)
            },
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            onClick = { navController.navigateUp() }
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(icon = Icons.Outlined.Edit, contentDescription = "Name", onClick = {
                            scope.launch {
                                val name = FileOps.askText(context.findActivity(), "Name", person?.name.orEmpty(), hint = "Who is this?")
                                    ?: return@launch
                                edit { store.rename(id, name) }
                            }
                        })
                        IconButton(icon = Icons.Outlined.CallMerge, contentDescription = "Same person as…",
                            onClick = { picker = "merge" })
                        IconButton(
                            icon = if (person?.hidden == true) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (person?.hidden == true) "Show" else "Hide",
                            onClick = { edit { store.setHidden(id, person?.hidden != true) } }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                        FaceAvatar(person?.cover, 72.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Label(
                                person?.name ?: "Add a name",
                                style = AppTheme.typography.title1,
                                fontWeight = FontWeight.SemiBold,
                                color = if (person?.name != null) colors.onBackground else colors.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val name = FileOps.askText(context.findActivity(), "Name", person?.name.orEmpty(), hint = "Who is this?")
                                            ?: return@launch
                                        edit { store.rename(id, name) }
                                    }
                                }
                            )
                            Label(
                                if (photos.size == 1) "1 photo" else "${photos.size} photos" +
                                        if (person?.hidden == true) " · hidden" else "",
                                style = AppTheme.typography.body2,
                                color = colors.onBackground.copy(alpha = 0.65f)
                            )
                            Label(
                                "Long-press photos that aren't them to move them.",
                                style = AppTheme.typography.label3,
                                color = colors.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
            for ((label, files) in sections) {
            // Day header (compact) + that day's photos + a small gap.
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_$label", contentType = "header") {
                com.zs.compose.theme.text.TonalHeader(
                    label, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp)
                )
            }
            items(files, key = { it.id }) { file ->
                val media = file.id
                val isSelected = media in selected
                Box {
                if (!file.isImage)
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.PlayCircle, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).zIndexTop()
                    )
                AsyncImage(
                    model = remember(media) {
                        ImageRequest.Builder(context).data(file.mediaUri)
                            .memoryCacheKey(thumbnailCacheKey(media)).size(320).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(colors.background(4.dp))
                        .combinedClickable(
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    if (isSelected) selected.remove(media) else selected.add(media)
                                } else navController.navigate(
                                    RouteViewer(RouteFiles.SOURCE_IDS, media, PhotoSets.put(photos.toLongArray()))
                                )
                            },
                            onLongClick = { if (isSelected) selected.remove(media) else selected.add(media) }
                        )
                        .then(
                            if (isSelected) Modifier.border(3.dp, colors.accent).padding(3.dp) else Modifier
                        )
                )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "s_$label", contentType = "spacer") {
                Spacer(Modifier.height(6.dp))
            }
            }
        }
        // The month slider, like in Photos.
        if (index != null)
            com.zs.gallery.files.TimelineFastScroller(
                state = gridState,
                index = index,
                columns = gridColumns,
                topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
                modifier = Modifier.matchParentSize()
            )
        ViewerActions(
            visible = selected.isNotEmpty(),
            actions = listOf(MOVE_TO_PERSON, NOT_THIS_PERSON),
            onAction = { action ->
                if (action == MOVE_TO_PERSON) picker = "move"
                else {
                    val ids = selected.toList()
                    selected.clear()
                    edit { store.moveFaces(id, ids, -1L) }
                }
            },
            leading = { CountBubble(selected.size) },
            visibleCount = 2,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
        PersonPicker(
            visible = picker != null,
            title = if (picker == "merge") "Same person as…" else "Move to…",
            exclude = id,
            allowNew = picker == "move",
            onDismiss = { picker = null },
            onPick = { target ->
                val mode = picker
                picker = null
                if (mode == "merge") {
                    if (target > 0) {
                        edit { store.merge(id, target) }
                        navController.navigateUp()
                        navController.navigate(RoutePerson(target))
                    }
                } else {
                    val ids = selected.toList()
                    selected.clear()
                    edit {
                        val to = if (target == NEW_PERSON) store.newPerson() else target
                        store.moveFaces(id, ids, to)
                    }
                }
            }
        )
    }
}

private const val NEW_PERSON = -2L

/** Last loaded lists, shown at once when a screen comes back (keeps scroll positions). */
private object LastLists {
    var people: List<Person> = emptyList()
    val photos = HashMap<Long, com.zs.gallery.common.Mapped<com.zs.core.store.MediaFile>>()
}

/** A sheet with everyone's face to choose from (plus "New person" when moving photos). */
@Composable
private fun PersonPicker(
    visible: Boolean,
    title: String,
    exclude: Long,
    allowNew: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val version by FaceIndexer.version.collectAsState()
    val people by produceState(emptyList<Person>(), version, visible) {
        if (visible) value = withContext(Dispatchers.IO) { FaceIndexer.store(context).people() }
    }
    // Re-registered each time the sheet opens, so it's the newest back handler and the back
    // gesture closes the sheet first (not the photo / screen underneath).
    androidx.compose.runtime.key(visible) { BackHandler(enabled = visible, onBack = onDismiss) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(colors.background(3.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Label(
                    title, style = AppTheme.typography.title2, fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
                )
                val choices = people.filter { it.id != exclude && !it.hidden }
                    .sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.photos })
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (allowNew) item(key = "new") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(NEW_PERSON) }.padding(6.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(68.dp).background(colors.accent, CircleShape)
                            ) { Icon(Icons.Outlined.PersonAdd, null, tint = colors.onAccent) }
                            Label("New person", style = AppTheme.typography.body2, color = colors.accent,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    items(choices, key = { it.id }) { p ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(p.id) }.padding(6.dp)
                        ) {
                            FaceAvatar(p.cover, 68.dp)
                            Label(
                                p.name ?: "${p.photos} photos", style = AppTheme.typography.body2,
                                color = if (p.name != null) colors.onBackground else colors.onBackground.copy(alpha = 0.6f),
                                maxLines = 1, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


/** Draw above the photo underneath (the play badge sits before the image in the Box). */
private fun Modifier.zIndexTop() = this.then(androidx.compose.ui.Modifier.zIndex(1f))
