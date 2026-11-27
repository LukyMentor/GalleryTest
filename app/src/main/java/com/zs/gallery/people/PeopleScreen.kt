package com.zs.gallery.people

import com.zs.gallery.common.tr
import com.zs.gallery.common.photosText
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.filled.Star
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
private val MARK_GROUP = Action(R.string.mark_group_photo, Icons.Outlined.Groups)
private val MARK_SINGLE = Action(R.string.mark_just_them, Icons.Outlined.Person)

/** Person screen filter: everything, just them, or group photos. */
private const val SHOW_ALL = 0
private const val SHOW_SINGLE = 1
private const val SHOW_GROUP = 2

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
            status.paused -> tr("Face scan paused", "Hledání obličejů pozastaveno")
            status.running && status.total > 0 && status.scanned < status.total ->
                tr("Scanning photos… ", "Prohledávám fotky… ") + "${"%,d".format(status.scanned)} / ${"%,d".format(status.total)}"
            status.running -> status.note ?: tr("Working…", "Pracuji…")
            else -> status.note ?: tr("Face scan", "Hledání obličejů")
        }
        Label(title, style = AppTheme.typography.body1, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        if (status.total > 0) {
            val p = (status.scanned.toFloat() / status.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(colors.onBackground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(50)).background(colors.accent))
            }
        }
        Label(
            "${"%,d".format(status.faces)} ${tr("faces", "obličejů")} · ${status.people} ${tr("people", "osob")}" +
                    if (status.msPerPhoto > 0) " · ${status.msPerPhoto} ms/photo" else "",
            style = AppTheme.typography.body2, color = colors.onBackground.copy(alpha = 0.7f)
        )
        if (status.videos && status.videoTotal > 0)
            Label(
                tr("Videos: ", "Videa: ") + "${"%,d".format(status.videoScanned)} / ${"%,d".format(status.videoTotal)}" +
                        if (status.msPerVideo > 0) " · ${"%.1f".format(status.msPerVideo / 1000f)} s/video" else "",
                style = AppTheme.typography.body2, color = colors.onBackground.copy(alpha = 0.7f)
            )
        status.error?.let { Label(it, style = AppTheme.typography.body2, color = Color(0xFFFF6B6B), maxLines = 3) }
        if (open) {
            status.current?.let {
                Label(tr("Now: ", "Teď: ") + it, style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f), maxLines = 1)
            }
            Label(
                tr("Runs on the phone only, while Gallery M is open (or still in memory). Experimental.", "Běží jen v telefonu, když je Gallery M otevřená (nebo ještě v paměti). Experimentální."),
                style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f)
            )
            Label(
                tr("Videos up to 5 minutes are scanned after all photos (a frame every 2.5 s; screen recordings skipped).", "Videa do 5 minut se prohledají po všech fotkách (snímek každých 2,5 s; záznamy obrazovky se přeskakují)."),
                style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                SmallPill(if (status.paused) tr("Resume", "Pokračovat") else tr("Pause", "Pozastavit"), primary = true) {
                    FaceIndexer.setPaused(context, !status.paused)
                }
                SmallPill(if (status.videos) tr("Videos: on", "Videa: zapnuto") else tr("Scan videos too", "Prohledat i videa"), primary = status.videos) {
                    FaceIndexer.setScanVideos(context, !status.videos)
                }
                SmallPill(tr("Re-group", "Přeskupit")) { FaceIndexer.regroup(context) }
                SmallPill(tr("Rescan all", "Prohledat vše znovu")) { FaceIndexer.reset(context) }
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
                tr("People", "Lidé"),
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
                    tr("Faces will appear here as the scan finds them.", "Obličeje se tu objeví, jak je hledání najde."),
                    style = AppTheme.typography.body2,
                    color = colors.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        items(main, key = { it.id }) { person -> PersonTile(person) { navController.navigate(RoutePerson(person.id)) } }
        if (rest.isNotEmpty())
            item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                SmallRowButton(
                    if (showAll) tr("Hide people with 1–2 photos", "Skrýt lidi s 1–2 fotkami") else tr("Show ${rest.size} more (only 1–2 photos)", "Ukázat dalších ${rest.size} (jen 1–2 fotky)")
                ) { showAll = !showAll }
            }
        if (showAll) items(rest, key = { it.id }) { person ->
            PersonTile(person) { navController.navigate(RoutePerson(person.id)) }
        }
        if (hiddenCount > 0)
            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden") {
                SmallRowButton(if (showHidden) tr("Don't show hidden people", "Neukazovat skryté lidi") else tr("Show $hiddenCount hidden", "Ukázat skryté ($hiddenCount)")) {
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
            person.name ?: tr("Add a name", "Přidat jméno"),
            style = AppTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = if (person.name != null) colors.onBackground else colors.onBackground.copy(alpha = 0.55f),
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp)
        )
        Label(
            countLabel(person.images, person.videos),
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
    // Group photos (several people who matter, and they aren't the main face).
    val groupIds by produceState(LastLists.groups[id] ?: emptySet(), version) {
        value = withContext(Dispatchers.IO) { store.groupPhotosOf(id) }
        LastLists.groups[id] = value
    }
    val prefs = remember { context.getSharedPreferences("faces", Context.MODE_PRIVATE) }
    var show by rememberSaveable { mutableStateOf(prefs.getInt("person_filter", SHOW_ALL)) }
    val shown = remember(sections, groupIds, show) {
        if (show == SHOW_ALL) sections
        else sections.mapValues { (_, list) ->
            list.filter { (it.id in groupIds) == (show == SHOW_GROUP) }
        }.filterValues { it.isNotEmpty() }
    }
    val allCount = remember(sections) { sections.values.sumOf { it.size } }
    val groupCount = remember(sections, groupIds) { sections.values.sumOf { l -> l.count { it.id in groupIds } } }
    val photos = remember(shown) { shown.values.flatten().map { it.id } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Section layout for the fast scroller (1 leading item: the header).
    // (2 leading items: header + filter chips.)
    val index = remember(shown) {
        shown.takeIf { it.isNotEmpty() }?.let { com.zs.gallery.files.TimelineIndex(it, true, 2) }
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
    // Like / delete / share / move... - the same as in Photos.
    val photoActions = org.koin.androidx.compose.koinViewModel<PersonPhotosViewModel>()
    // What the person picker is for: moving photos, or merging this whole person.
    var picker by remember { mutableStateOf<String?>(null) }
    // Choosing which photo their picture comes from.
    var pickingCover by remember { mutableStateOf(false) }
    BackHandler(enabled = selected.isNotEmpty() && picker == null) { selected.clear() }
    BackHandler(enabled = pickingCover) { pickingCover = false }

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
                            contentDescription = tr("Back", "Zpět"),
                            onClick = { navController.navigateUp() }
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(icon = Icons.Outlined.Edit, contentDescription = tr("Name", "Jméno"), onClick = {
                            scope.launch {
                                val name = FileOps.askText(context.findActivity(), tr("Name", "Jméno"), person?.name.orEmpty(), hint = tr("Who is this?", "Kdo to je?"))
                                    ?: return@launch
                                edit { store.rename(id, name) }
                            }
                        })
                        IconButton(icon = Icons.Outlined.AccountCircle, contentDescription = tr("Choose picture", "Vybrat fotku"),
                            onClick = { selected.clear(); pickingCover = true })
                        IconButton(icon = Icons.Outlined.CallMerge, contentDescription = tr("Same person as…", "Stejná osoba jako…"),
                            onClick = { picker = "merge" })
                        IconButton(
                            icon = if (person?.hidden == true) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (person?.hidden == true) tr("Show", "Ukázat") else tr("Hide", "Skrýt"),
                            onClick = { edit { store.setHidden(id, person?.hidden != true) } }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                        FaceAvatar(person?.cover, 72.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Label(
                                person?.name ?: tr("Add a name", "Přidat jméno"),
                                style = AppTheme.typography.title1,
                                fontWeight = FontWeight.SemiBold,
                                color = if (person?.name != null) colors.onBackground else colors.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val name = FileOps.askText(context.findActivity(), tr("Name", "Jméno"), person?.name.orEmpty(), hint = tr("Who is this?", "Kdo to je?"))
                                            ?: return@launch
                                        edit { store.rename(id, name) }
                                    }
                                }
                            )
                            Label(
                                countLabel(
                                    sections.values.sumOf { list -> list.count { it.isImage } },
                                    sections.values.sumOf { list -> list.count { !it.isImage } }
                                ) +
                                        if (person?.hidden == true) tr(" · hidden", " · skrytá") else "",
                                style = AppTheme.typography.body2,
                                color = colors.onBackground.copy(alpha = 0.65f)
                            )
                            Label(
                                tr("Long-press photos that aren't them to move them.", "Fotky, kde nejsou, podržte a přesuňte."),
                                style = AppTheme.typography.label3,
                                color = colors.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
            // All · Just them · Group photos
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    fun pick(v: Int) { show = v; prefs.edit().putInt("person_filter", v).apply(); selected.clear() }
                    com.zs.gallery.files.FilterChip(tr("All", "Vše") + "  $allCount", show == SHOW_ALL) { pick(SHOW_ALL) }
                    com.zs.gallery.files.FilterChip(tr("Just them", "Jen on/ona") + "  ${allCount - groupCount}", show == SHOW_SINGLE) { pick(SHOW_SINGLE) }
                    com.zs.gallery.files.FilterChip(tr("Group photos", "Skupinové") + "  $groupCount", show == SHOW_GROUP) { pick(SHOW_GROUP) }
                }
            }
            for ((label, files) in shown) {
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
                // Liked: the same yellow star as in Photos.
                if (media in photoActions.favourites)
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Star, contentDescription = tr("Favourite", "Oblíbené"),
                        tint = Color(0xFFFFC83D),
                        modifier = Modifier
                            .align(if (file.isImage) Alignment.TopEnd else Alignment.BottomEnd)
                            .padding(5.dp).zIndexTop()
                            .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
                            .padding(3.dp).size(14.dp)
                    )
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
                                if (pickingCover) {
                                    pickingCover = false
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { store.setCover(id, media) }
                                        FaceIndexer.changed()
                                        android.widget.Toast.makeText(
                                            context, if (ok) tr("Picture changed", "Fotka změněna") else tr("No face of them in that photo", "Na té fotce jejich obličej není"),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else if (selected.isNotEmpty()) {
                                    if (isSelected) selected.remove(media) else selected.add(media)
                                } else navController.navigate(
                                    RouteViewer(RouteFiles.SOURCE_IDS, media, PhotoSets.put(photos.toLongArray()))
                                )
                            },
                            onLongClick = { if (!pickingCover) { if (isSelected) selected.remove(media) else selected.add(media) } }
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
        // "Tap a photo…" hint while choosing their picture.
        AnimatedVisibility(
            visible = pickingCover,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Label(tr("Tap the photo for their picture", "Klepněte na fotku pro profilovou fotku"), color = colors.onAccent, fontWeight = FontWeight.Medium)
                SmallPill(tr("Cancel", "Zrušit")) { pickingCover = false }
            }
        }
        ViewerActions(
            visible = selected.isNotEmpty(),
            // Photos' bar first (like, delete, share as buttons; the rest in the menu), then the
            // face-only ones.
            actions = buildList {
                add(P_STAR); add(P_DELETE); add(P_SHARE)
                add(P_QUICK_SHARE); add(P_MOVE); add(P_COPY)
                if (selected.size == 1) add(P_RENAME)
                add(MOVE_TO_PERSON); add(NOT_THIS_PERSON)
                when (show) {
                    SHOW_SINGLE -> add(MARK_GROUP)
                    SHOW_GROUP -> add(MARK_SINGLE)
                    else -> { add(MARK_SINGLE); add(MARK_GROUP) }
                }
                if (selected.size == 1) add(P_USE_AS_PICTURE)
                add(P_ADD_TO_PERSON)
                if (selected.size < photos.size) add(P_SELECT_ALL)
            },
            onAction = { action ->
                if (action == P_SELECT_ALL) {
                    val have = selected.toHashSet()
                    selected.addAll(photos.filter { it !in have })
                } else if (action == P_USE_AS_PICTURE) {
                    val media = selected.firstOrNull() ?: return@ViewerActions
                    selected.clear()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.setCover(id, media) }
                        FaceIndexer.changed()
                        android.widget.Toast.makeText(
                            context, if (ok) tr("Picture changed", "Fotka změněna") else tr("No face of them in that photo", "Na té fotce jejich obličej není"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else if (action in PHOTO_ACTIONS) {
                    val wanted = selected.toHashSet()
                    val files = sections.values.flatten().filter { it.id in wanted }
                    selected.clear()
                    val activity = context.findActivity() ?: return@ViewerActions
                    photoActions.run(action, activity, files)
                } else if (action == MOVE_TO_PERSON) picker = "move"
                else if (action == MARK_GROUP || action == MARK_SINGLE) {
                    val ids = selected.toList()
                    selected.clear()
                    edit { store.setGroup(id, ids, group = action == MARK_GROUP) }
                } else {
                    val ids = selected.toList()
                    selected.clear()
                    edit { store.moveFaces(id, ids, -1L) }
                }
            },
            leading = { CountBubble(selected.size) },
            visibleCount = 3,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
        PersonPicker(
            visible = picker != null,
            title = if (picker == "merge") tr("Same person as…", "Stejná osoba jako…") else tr("Move to…", "Přesunout k…"),
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

private val PHOTO_ACTIONS = setOf(P_STAR, P_DELETE, P_SHARE, P_QUICK_SHARE, P_MOVE, P_COPY, P_RENAME, P_ADD_TO_PERSON)

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Last loaded lists, shown at once when a screen comes back (keeps scroll positions). */
private object LastLists {
    var people: List<Person> = emptyList()
    val photos = HashMap<Long, com.zs.gallery.common.Mapped<com.zs.core.store.MediaFile>>()
    val groups = HashMap<Long, Set<Long>>()
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
                            Label(tr("New person", "Nová osoba"), style = AppTheme.typography.body2, color = colors.accent,
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
                                p.name ?: photosText(p.photos), style = AppTheme.typography.body2,
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
