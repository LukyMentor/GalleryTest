/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.foundation.layout.navigationBars
import android.Manifest
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.adaptive.content
import com.zs.compose.theme.text.Label
import com.zs.core.store.GeoPhoto
import com.zs.core.store.PhotoLocations
import com.zs.gallery.common.Route
import com.zs.gallery.common.compose.LocalNavController
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.files.PhotoSets
import com.zs.gallery.files.thumbnailCacheKey
import com.zs.gallery.viewer.RouteViewer

/** The "Map" tab: where photos were taken. */
object RouteMap : Route

/** "Location" tapped in a photo's Properties: the map flies there and marks the spot. */
object MapJump {
    class Target(val id: Long, val latitude: Double, val longitude: Double)
    var target by androidx.compose.runtime.mutableStateOf<Target?>(null)
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val colors = AppTheme.colors
    val dark = !colors.isLight

    // Location data in photos is hidden by Android unless this permission is granted.
    var granted by remember { mutableStateOf(PhotoLocations.hasPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(granted) {
        if (granted) PhotoLocations.refresh(context)
        else launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    val photos by PhotoLocations.photos.collectAsState()
    val scanning by PhotoLocations.scanning.collectAsState()
    val progress by PhotoLocations.progress.collectAsState()
    // The opened place (its photo ids), saved so it's still open after viewing one of its photos.
    var selectedIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    val selected = remember(selectedIds, photos) {
        val ids = selectedIds ?: return@remember null
        val byId = photos.associateBy { it.id }
        val list = ids.asList().mapNotNull { byId[it] }
        if (list.isEmpty()) null else PhotoCluster(list, 0f, 0f)
    }

    // Folder filter + day slider ("replay a trip").
    val folderOf by produceState(emptyMap<Long, String>(), photos) {
        value = withContext(Dispatchers.IO) { foldersOf(context, photos.map { it.id }) }
    }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var dayIndex by rememberSaveable { mutableIntStateOf(-1) }   // -1 = every day
    var pickingFolder by remember { mutableStateOf(false) }
    val folderPhotos = remember(photos, folderOf, folder) {
        val f = folder ?: return@remember photos
        photos.filter { folderOf[it.id] == f }
    }
    val days = remember(folderPhotos, folder) {
        if (folder == null) emptyList()
        else folderPhotos.groupBy { dayOf(it.date) }.toSortedMap().values.map { list -> list.sortedBy { it.date } }
    }
    if (dayIndex >= days.size) dayIndex = -1
    val dayPhotos = if (folder != null && dayIndex >= 0) days[dayIndex] else folderPhotos
    val trail = remember(folderPhotos, folder) { if (folder == null) emptyList() else folderPhotos.sortedBy { it.date } }

    Box(Modifier.fillMaxSize()) {
        // Offline map bundled in the app: no internet, no API key.
        val viewBounds = remember { FloatArray(4) }
        // Top-right button: photos on / off (just the map).
        var showPhotos by rememberSaveable { mutableStateOf(true) }
        // Coming from a photo's "Location": fly there, mark the spot, show that place's photos.
        val jump = MapJump.target
        val focus = remember(jump) {
            jump?.let { mercator(it.latitude, it.longitude).let { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) } }
        }
        var spot by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        // Rings from the start of the glide, for a few seconds.
        LaunchedEffect(focus) { if (focus != null) spot = focus }
        LaunchedEffect(spot) {
            if (spot == null) return@LaunchedEffect
            kotlinx.coroutines.delay(5000)
            spot = null
        }
        WorldMap(
            photos = dayPhotos,
            dots = if (folder != null && dayIndex >= 0) folderPhotos.filter { dayOf(it.date) != dayOf(dayPhotos.first().date) } else emptyList(),
            trail = trail,
            trailDay = if (dayIndex >= 0) dayPhotos else emptyList(),
            fitKey = folder,
            fitPhotos = folderPhotos,
            dark = dark,
            accent = colors.accent,
            onClusterTap = { cluster -> selectedIds = cluster.photos.map { it.id }.toLongArray() },
            modifier = Modifier.fillMaxSize(),
            boundsOut = viewBounds,
            showPhotos = showPhotos,
            focus = focus,
            highlight = spot,
            onFocused = {
                val j = jump
                MapJump.target = null
                if (j != null) {
                    // That photo first, then others taken within ~1 km.
                    val near = photos.filter { distanceKm(it.latitude, it.longitude, j.latitude, j.longitude) <= 1.0 }
                        .map { it.id }.filter { it != j.id }
                    selectedIds = (listOf(j.id) + near).toLongArray()
                }
            }
        )

        // Top: title + scan progress.
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(50))
                    .background(colors.background(2.dp), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, tint = colors.accent)
                val (done, total) = progress
                Label(
                    text = when {
                        !granted -> "Map"
                        scanning && total > 0 -> "Finding places… $done / $total"
                        else -> "${photos.size} photos with a location"
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            // Folder filter.
            if (granted && photos.isNotEmpty())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .shadow(6.dp, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .background(if (folder != null) colors.accent else colors.background(2.dp))
                        .clickable { pickingFolder = true }
                        .padding(start = 14.dp, end = if (folder != null) 4.dp else 14.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    val on = if (folder != null) colors.onAccent else colors.onBackground
                    Icon(Icons.Outlined.Folder, null, tint = on, modifier = Modifier.size(18.dp))
                    Label(
                        folder?.substringAfterLast('/') ?: "All photos",
                        color = on, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    if (folder != null)
                        IconButton(icon = Icons.Outlined.Close, contentDescription = "All photos",
                            onClick = { folder = null; dayIndex = -1; selectedIds = null }, tint = on)
                    else
                        Icon(Icons.Outlined.ArrowDropDown, null, tint = on, modifier = Modifier.size(20.dp))
                }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .size(46.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(if (showPhotos) colors.background(2.dp) else colors.accent)
                .clickable { showPhotos = !showPhotos }
        ) {
            Icon(
                if (showPhotos) Icons.Outlined.HideImage else Icons.Outlined.Image,
                contentDescription = if (showPhotos) "Hide photos" else "Show photos",
                tint = if (showPhotos) colors.onBackground else colors.onAccent,
                modifier = Modifier.size(22.dp)
            )
        }

        // Permission explanation if it was refused.
        if (!granted) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp))
                    .background(colors.background(2.dp), RoundedCornerShape(24.dp))
                    .clickable { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
                    .padding(24.dp)
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, tint = colors.accent,
                    modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(12.dp))
                Label(
                    "Allow access to photo locations to see them on the map. Tap to allow.",
                    maxLines = 3
                )
            }
        }

        // Photos of the tapped place: a strip, which can be maximised into a full grid.
        var expanded by rememberSaveable { mutableStateOf(false) }
        val cluster = selected
        androidx.activity.compose.BackHandler(enabled = expanded && cluster != null) { expanded = false }
        // Full-screen grid: hide the Photos / Map / Folders bar; bring it back when it closes.
        val facade = com.zs.gallery.common.compose.LocalSystemFacade.current
        val gridShown = expanded && cluster != null
        androidx.compose.runtime.DisposableEffect(gridShown) {
            if (gridShown)
                facade.style += com.zs.gallery.common.WindowStyle.FLAG_APP_NAV_BAR_HIDDEN
            onDispose {
                if (gridShown)
                    facade.style += com.zs.gallery.common.WindowStyle.FLAG_APP_NAV_BAR_VISIBILITY_AUTO
            }
        }
        // The day slider for a chosen folder.
        if (folder != null && cluster == null && days.isNotEmpty())
            DaySlider(
                days = days,
                index = dayIndex,
                onIndex = { dayIndex = it; selectedIds = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.content.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        // "Photos in this area": everything visible on the map now, in the full grid.
        if (cluster == null && photos.isNotEmpty() && showPhotos && folder == null)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.content.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (dark) Color(0xCC1E4A3A) else Color(0xEEFFFFFF))
                    .clickable {
                        val (l, t, r, b) = viewBounds.toList()
                        val inView = photos.filter {
                            val (x, y) = mercator(it.latitude, it.longitude)
                            x in l..r && y in t..b
                        }.sortedByDescending { it.id }
                        if (inView.isNotEmpty()) {
                            selectedIds = inView.map { it.id }.toLongArray()
                            expanded = true
                        }
                    }
                    .padding(horizontal = 26.dp, vertical = 13.dp)
            ) {
                Label(
                    "Photos in this area",
                    color = if (dark) Color.White else Color(0xFF1B1B21),
                    fontWeight = FontWeight.Medium
                )
            }
        if (cluster != null)
            PlaceStrip(
                cluster = cluster,
                onExpand = { expanded = true },
                onDismiss = {
                    selectedIds = null
                    expanded = false
                },
                onOpen = { photo ->
                    navController.navigate(
                        RouteViewer(
                            RouteFiles.SOURCE_IDS, photo.id,
                            PhotoSets.put(cluster.photos.map { it.id }.toLongArray())
                        )
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.content.union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded && cluster != null,
            enter = androidx.compose.animation.slideInVertically { it / 3 } +
                    androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it / 3 } +
                    androidx.compose.animation.fadeOut(),
        ) {
            val shown = cluster ?: return@AnimatedVisibility
            PlaceGrid(
                cluster = shown,
                onCollapse = { expanded = false },
                onOpen = { photo ->
                    navController.navigate(
                        RouteViewer(
                            RouteFiles.SOURCE_IDS, photo.id,
                            PhotoSets.put(shown.photos.map { it.id }.toLongArray())
                        )
                    )
                }
            )
        }
        // Back: close the folder list, else leave the folder.
        androidx.activity.compose.BackHandler(enabled = pickingFolder) { pickingFolder = false }
        androidx.activity.compose.BackHandler(enabled = !pickingFolder && folder != null && !expanded && cluster == null) {
            folder = null; dayIndex = -1
        }
        if (pickingFolder)
            FolderPickerSheet(
                photos = photos,
                folderOf = folderOf,
                onPick = { path -> folder = path; dayIndex = -1; selectedIds = null; pickingFolder = false },
                onDismiss = { pickingFolder = false }
            )
    }
}

/** Title for a place: "Near Hue, Vietnam · 12 photos" (place looked up offline). */
@Composable
private fun placeTitle(cluster: PhotoCluster): String {
    val context = LocalContext.current
    val count = cluster.photos.size
    val place by produceState<String?>(null, cluster.photos.first().id, count) {
        val lat = cluster.photos.map { it.latitude }.average()
        val lon = cluster.photos.map { it.longitude }.average()
        value = Places.describe(context, lat, lon)
    }
    val photosLabel = if (count == 1) "1 photo" else "$count photos"
    return place?.let { "$it · $photosLabel" } ?: photosLabel
}

/** All photos of one place in a full-screen grid (like the Photos tab). */
@Composable
private fun PlaceGrid(
    cluster: PhotoCluster,
    onCollapse: () -> Unit,
    onOpen: (GeoPhoto) -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Label(
                text = placeTitle(cluster),
                style = AppTheme.typography.title3,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            IconButton(icon = Icons.Outlined.CloseFullscreen, contentDescription = "Back to map", onClick = onCollapse)
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = WindowInsets.content
                .union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cluster.photos.size, key = { cluster.photos[it].id }) { index ->
                val photo = cluster.photos[index]
                AsyncImage(
                    model = remember(photo.id) {
                        ImageRequest.Builder(context)
                            .data(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id))
                            .memoryCacheKey(thumbnailCacheKey(photo.id))
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(colors.background(4.dp))
                        .clickable { onOpen(photo) }
                )
            }
        }
    }
}

/** Horizontal strip with the photos of one map bubble. */
@Composable
private fun PlaceStrip(
    cluster: PhotoCluster,
    onDismiss: () -> Unit,
    onOpen: (GeoPhoto) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .background(colors.background(2.dp), shape)
            .padding(vertical = 8.dp)
    ) {
        // Header: tap it (or the expand button) to see all photos of the place in a big grid.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onExpand)
                .padding(start = 16.dp, end = 4.dp)
        ) {
            Label(
                text = placeTitle(cluster),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(icon = Icons.Outlined.OpenInFull, contentDescription = "Show all", onClick = onExpand)
            IconButton(icon = Icons.Outlined.Close, contentDescription = "Close", onClick = onDismiss)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            items(cluster.photos, key = { it.id }) { photo ->
                AsyncImage(
                    model = remember(photo.id) {
                        ImageRequest.Builder(context)
                            .data(
                                ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id
                                )
                            )
                            .memoryCacheKey(thumbnailCacheKey(photo.id))
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.background(4.dp))
                        .clickable { onOpen(photo) }
                )
            }
        }
    }
}


private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
}


/** Day number (local time) of a photo date in ms. */
private fun dayOf(date: Long): Long {
    val zone = java.util.TimeZone.getDefault()
    return Math.floorDiv(date + zone.getOffset(date), android.text.format.DateUtils.DAY_IN_MILLIS)
}

/** id -> folder (absolute path) for the photos on the map. */
private fun foldersOf(context: android.content.Context, ids: List<Long>): Map<Long, String> {
    val map = HashMap<Long, String>()
    for (chunk in ids.chunked(500)) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
            "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                map[c.getLong(0)] = path.substringBeforeLast('/')
            }
        }
    }
    return map
}

/**
 * Horizontal slider through the folder's days (only days with photos): a little bar chart of
 * photos per day, drag or tap to pick one day; "All" shows every day.
 */
@Composable
private fun DaySlider(
    days: List<List<GeoPhoto>>,
    index: Int,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val max = days.maxOf { it.size }.coerceAtLeast(1)
    val current by androidx.compose.runtime.rememberUpdatedState(index)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(colors.background(3.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (index < 0) {
                    Label("All days", fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    Label(
                        "${days.size} days · ${days.sumOf { it.size }} photos",
                        style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.65f)
                    )
                } else {
                    val day = days[index]
                    Label(
                        "Day ${index + 1} · " + java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(day.first().date)),
                        fontWeight = FontWeight.SemiBold, color = colors.onBackground
                    )
                    Label(
                        if (day.size == 1) "1 photo" else "${day.size} photos",
                        style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.65f)
                    )
                }
            }
            com.zs.gallery.files.FilterChip("All", index < 0) { onIndex(-1) }
        }
        // Bars: one per day; the chosen one in the accent colour.
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(44.dp)
                .pointerInput(days.size) {
                    fun pick(x: Float) {
                        val i = (x / size.width * days.size).toInt().coerceIn(0, days.size - 1)
                        if (i != current) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onIndex(i)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            pick(ch.position.x)
                        }
                    }
                }
        ) {
            val n = days.size
            val slot = size.width / n
            val gap = if (slot > 6.dp.toPx()) 2.dp.toPx() else 0f
            for (i in 0 until n) {
                val h = (size.height - 10.dp.toPx()) * (days[i].size.toFloat() / max).coerceAtLeast(0.08f)
                val chosen = i == index
                drawRoundRect(
                    if (chosen) colors.accent else colors.onBackground.copy(alpha = if (index < 0) 0.45f else 0.25f),
                    topLeft = Offset(i * slot + gap / 2, size.height - 6.dp.toPx() - h),
                    size = androidx.compose.ui.geometry.Size((slot - gap).coerceAtLeast(1f), h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            // Thumb under the chosen day.
            if (index >= 0)
                drawCircle(colors.accent, 3.dp.toPx(), Offset((index + 0.5f) * slot, size.height - 2.dp.toPx()))
        }
    }
}

/** Folders that have photos with a location, with a search field. */
@Composable
private fun FolderPickerSheet(
    photos: List<GeoPhoto>,
    folderOf: Map<Long, String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val folders = remember(photos, folderOf) {
        photos.groupBy { folderOf[it.id] }.filterKeys { it != null }
            .map { (path, list) -> Triple(path!!, list.size, list.maxBy { it.date }.id) }
            .sortedByDescending { it.second }
    }
    var query by remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(colors.background(3.dp))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {})
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Label("Show a folder", style = AppTheme.typography.title2, fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, modifier = Modifier.padding(start = 20.dp, top = 20.dp))
            // Search.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(colors.onBackground.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.onBackground.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (query.isEmpty())
                        Label("Search folders", color = colors.onBackground.copy(alpha = 0.5f))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = AppTheme.typography.body1.copy(color = colors.onBackground),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            val shown = folders.filter { query.isBlank() || it.first.substringAfterLast('/').contains(query.trim(), ignoreCase = true) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                items(shown.size, key = { shown[it].first }) { i ->
                    val (path, count, cover) = shown[i]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        AsyncImage(
                            model = remember(cover) {
                                ImageRequest.Builder(context)
                                    .data(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cover))
                                    .memoryCacheKey(thumbnailCacheKey(cover)).size(160).build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(colors.background(6.dp))
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Label(path.substringAfterLast('/'), fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 1)
                            Label(
                                if (count == 1) "1 photo with a location" else "$count photos with a location",
                                style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
