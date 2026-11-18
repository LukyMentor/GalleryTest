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

    Box(Modifier.fillMaxSize()) {
        // Offline map bundled in the app: no internet, no API key.
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
            photos = photos,
            dark = dark,
            accent = colors.accent,
            onClusterTap = { cluster -> selectedIds = cluster.photos.map { it.id }.toLongArray() },
            modifier = Modifier.fillMaxSize(),
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
