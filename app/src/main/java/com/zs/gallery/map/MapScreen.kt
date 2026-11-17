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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.viewinterop.AndroidView
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
import com.zs.gallery.files.thumbnailCacheKey
import com.zs.gallery.viewer.RouteViewer
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

/** The "Map" tab: where photos were taken. */
object RouteMap : Route

// CARTO basemaps (OpenStreetMap data), retina tiles. Light "Voyager" and "Dark Matter".
private val LightTiles = XYTileSource(
    "CartoVoyager", 1, 19, 256, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
    ),
    "© OpenStreetMap contributors © CARTO"
)
private val DarkTiles = XYTileSource(
    "CartoDarkMatter", 1, 19, 256, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
    ),
    "© OpenStreetMap contributors © CARTO"
)

// Fallback: the standard OpenStreetMap tiles (used if CARTO can't be reached).
private val OsmTiles = XYTileSource(
    "OpenStreetMap", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors"
)

/** Identifies the app to the tile servers (generic user agents get blocked). */
private const val USER_AGENT = "MGallery/1.0 (Android; com.lukymentor.gallery; personal photo map)"

/**
 * Tries to download one tile; returns null if it worked, otherwise a short reason
 * ("HTTP 403", "UnknownHostException", ...). Used to pick a working server and to tell the user
 * why the map is empty instead of silently showing nothing.
 */
private fun probe(url: String): String? = try {
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    connection.setRequestProperty("User-Agent", USER_AGENT)
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    val code = connection.responseCode
    connection.disconnect()
    if (code in 200..299) null else "HTTP $code"
} catch (e: Exception) {
    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
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
    var selected by remember { mutableStateOf<PhotoCluster?>(null) }

    // The map itself (an Android View).
    val accent = colors.accent.toArgb()
    val overlay = remember { PhotoClusterOverlay(context, accent) { selected = it } }
    var fitted by remember { mutableStateOf(false) }
    // Which tile server works (checked once per visit): CARTO first, OpenStreetMap as fallback.
    var useOsm by remember { mutableStateOf(false) }
    var tileError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val carto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            probe("https://a.basemaps.cartocdn.com/rastertiles/voyager/2/2/1@2x.png")
        }
        if (carto == null) return@LaunchedEffect
        val osm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            probe("https://tile.openstreetmap.org/2/2/1.png")
        }
        if (osm == null) useOsm = true
        else tileError = "Map can't load (CARTO: $carto · OSM: $osm)"
    }
    val mapHolder = remember { arrayOfNulls<MapView>(1) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            // Own render layer: tiles arrive in the background and invalidate the map view; with
            // its own layer that redraw never depends on the blurred bars' recording.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(),
            factory = { ctx ->
                Configuration.getInstance().apply {
                    load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                    userAgentValue = USER_AGENT
                    val base = File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
                    val tiles = File(base, "tiles").apply { mkdirs() }
                    osmdroidBasePath = base
                    osmdroidTileCache = tiles
                }
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setTilesScaledToDpi(true)
                    setMinZoomLevel(2.0)
                    setMaxZoomLevel(19.0)
                    setHorizontalMapRepetitionEnabled(false)
                    setVerticalMapRepetitionEnabled(false)
                    setScrollableAreaLimitLatitude(
                        MapView.getTileSystem().maxLatitude,
                        MapView.getTileSystem().minLatitude,
                        0
                    )
                    controller.setZoom(3.0)
                    overlays.add(overlay)
                    mapHolder[0] = this
                }
            },
            update = { map ->
                // Only switch tiles when the theme actually changed: setTileSource() wipes the
                // tile cache and pending downloads, and this runs on every recomposition (e.g. each
                // time the photo scan publishes progress) - that's why the map stayed empty.
                val tiles = when {
                    useOsm -> OsmTiles
                    dark -> DarkTiles
                    else -> LightTiles
                }
                if (map.tileProvider.tileSource !== tiles) map.setTileSource(tiles)
                overlay.photos = photos
                // First time we have photos: show all of them.
                if (!fitted && photos.isNotEmpty()) {
                    fitted = true
                    val points = photos.map { GeoPoint(it.latitude, it.longitude) }
                    map.post {
                        if (points.size == 1) {
                            map.controller.setZoom(12.0)
                            map.controller.setCenter(points[0])
                        } else {
                            val box = BoundingBox.fromGeoPointsSafe(points)
                            map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 64)
                        }
                    }
                }
                map.invalidate()
            }
        )
        // Release the map's tile threads/caches when leaving the tab.
        DisposableEffect(Unit) {
            onDispose { mapHolder[0]?.onDetach() }
        }

        // Top: title + scan progress + attribution.
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
                        tileError != null -> tileError!!
                        !granted -> "Map"
                        scanning && total > 0 -> "Finding places… $done / $total"
                        else -> "${photos.size} photos with a location"
                    },
                    fontWeight = FontWeight.Medium,
                    maxLines = 3
                )
            }
            Label(
                text = if (useOsm) "© OpenStreetMap contributors" else "© OpenStreetMap contributors © CARTO",
                style = AppTheme.typography.label3.copy(fontSize = 10.sp),
                color = if (dark) Color.White.copy(0.6f) else Color.Black.copy(0.6f),
                modifier = Modifier.padding(start = 8.dp, top = 6.dp)
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

        // Photos of the tapped place.
        val cluster = selected
        if (cluster != null)
            PlaceStrip(
                cluster = cluster,
                onDismiss = { selected = null },
                onOpen = { photo ->
                    navController.navigate(RouteViewer(RouteFiles.SOURCE_TIMELINE, photo.id))
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.content.union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
    }
}

/** Horizontal strip with the photos of one map bubble. */
@Composable
private fun PlaceStrip(
    cluster: PhotoCluster,
    onDismiss: () -> Unit,
    onOpen: (GeoPhoto) -> Unit,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp)
        ) {
            val count = cluster.photos.size
            Label(
                text = if (count == 1) "1 photo" else "$count photos",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
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
