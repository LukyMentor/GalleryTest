/*
 * Copyright 2025 sheik
 *
 * Created by sheik on 04-04-2025.
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

package com.zs.gallery.folders

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import com.zs.compose.theme.IconButton
import com.zs.gallery.files.FilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.HotelClass
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zs.compose.foundation.fullLineSpan
import com.zs.compose.foundation.stickyHeader
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.LocalWindowSize
import com.zs.compose.theme.WindowSize.Category
import com.zs.compose.theme.adaptive.Scaffold
import com.zs.compose.theme.adaptive.content
import com.zs.compose.theme.appbar.AppBarDefaults
import com.zs.compose.theme.minimumInteractiveComponentSize
import com.zs.compose.theme.text.Header
import com.zs.compose.theme.text.Label
import com.zs.compose.theme.text.TonalHeader
import com.zs.core.store.Folder
import com.zs.gallery.R
import com.zs.gallery.common.compose.blockVerticalDrag
import com.zs.gallery.common.compose.Filters
import com.zs.gallery.common.compose.FloatingLargeTopAppBar
import com.zs.gallery.common.compose.LocalNavController
import com.zs.gallery.common.compose.background
import com.zs.gallery.common.compose.emit
import com.zs.gallery.common.compose.fadingEdge2
import com.zs.gallery.common.compose.preference
import com.zs.gallery.common.compose.rememberAcrylicSurface
import com.zs.gallery.common.compose.section
import com.zs.gallery.common.compose.source
import com.zs.gallery.files.RouteBin
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.files.RouteFolder
import com.zs.gallery.files.RouteLiked
import com.zs.gallery.settings.Settings
import androidx.compose.foundation.layout.PaddingValues as Padding
import com.zs.gallery.common.compose.ContentPadding as CP

/**
 * The min size of the single cell in grid.
 */
private val DEF_MIN_TILE_SIZE = 100.dp

@Composable
fun Folders(viewState: FoldersViewState) {
    // The top nav insets
    val inAppNavInsets = WindowInsets.content
    val compact = LocalWindowSize.current.width < Category.Medium
    //
    // Define the scroll behavior for the top app bar
    val topAppBarScrollBehavior = AppBarDefaults.exitUntilCollapsedScrollBehavior()
    val surface = rememberAcrylicSurface()
    val navController = LocalNavController.current
    // Actual composable
    val colors = AppTheme.colors
    //
    Scaffold(
        topBar = {
            FloatingLargeTopAppBar(
                title = { Label(stringResource(R.string.folders)) },
                background = colors.background(surface),
                scrollBehavior = topAppBarScrollBehavior,
                modifier = Modifier.blockVerticalDrag(),
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FolderCopy,
                        null,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    )
                },
                insets = WindowInsets.systemBars.only(WindowInsetsSides.Top)
            )
        },
        //
        content = {
            val data by viewState.data.collectAsState()
            val multiplier by preference(key = Settings.KEY_GRID_ITEM_SIZE_MULTIPLIER)
            val state = rememberLazyGridState()
            //
            val content: LazyGridScope.() -> Unit = content@{
                // Quick access: favourites and the recycle bin, side by side, full width.
                item(key = "quick_access", contentType = "quick_access", span = fullLineSpan) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(CP.medium),
                        modifier = Modifier.padding(top = CP.small)
                    ) {
                        QuickAccess(
                            Icons.Outlined.HotelClass,
                            stringResource(R.string.favourites),
                            onClick = { navController.navigate(RouteLiked()) },
                            modifier = Modifier.weight(1f)
                        )
                        // Only available if R and above
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            QuickAccess(
                                Icons.Outlined.Recycling,
                                stringResource(R.string.recycle_bin),
                                onClick = { navController.navigate(RouteBin()) },
                                modifier = Modifier.weight(1f)
                            )
                    }
                }
                // Sort chips, same style as the Photos filter bar.
                item(key = "folder_sort", contentType = "folder_sort", span = fullLineSpan) {
                    val (ascending, order) = viewState.filter
                    val byName = order.id == "name"
                    val nameOrder = viewState.orders.first { it.id == "name" }
                    val dateOrder = viewState.orders.first { it.id != "name" }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = CP.normal, bottom = CP.small)
                    ) {
                        FilterChip("Name", byName) { viewState.filter(ascending, nameOrder) }
                        FilterChip("Recent", !byName) { viewState.filter(ascending, dateOrder) }
                        // Divider between "by what" and "which way".
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .width(1.dp)
                                .height(24.dp)
                                .background(AppTheme.colors.onBackground.copy(alpha = 0.2f))
                        )
                        if (byName) {
                            FilterChip("A–Z", ascending) { viewState.filter(true, order) }
                            FilterChip("Z–A", !ascending) { viewState.filter(false, order) }
                        } else {
                            FilterChip("Newest", !ascending) { viewState.filter(false, order) }
                            FilterChip("Oldest", ascending) { viewState.filter(true, order) }
                        }
                    }
                }
                // show from here only if available.
                // else emit error | empty | Loading
                val data = emit(data) ?: return@content
                // Actual content
                for ((header, items) in data) {
                    if (header.isNotEmpty())
                        stickyHeader(
                            state,
                            header,
                            contentType = "header",
                            content = {
                                Box(
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 6.dp),
                                    content = {
                                        TonalHeader(header)
                                    }
                                )
                            }
                        )
                    // rest of the items
                    items(
                        items,
                        key = Folder::path,
                        contentType = { "folder" },
                        itemContent = {
                            Folder(
                                value = it,
                                modifier = Modifier
                                    .animateItem()
                                    .clickable() { navController.navigate(RouteFolder(it.path)) }
                            )
                        }
                    )

                    // Marks the end of the section.
                    section()
                }
            }
            // Content
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Adaptive(DEF_MIN_TILE_SIZE * multiplier),
                horizontalArrangement = CP.SmallArrangement,
                verticalArrangement = CP.SmallArrangement,
                contentPadding = (inAppNavInsets.add(WindowInsets.content)
                    .union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))).asPaddingValues() +
                        (Padding(end = if (!compact) CP.large else 0.dp) + Padding(horizontal = CP.medium)),
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge2(length = 56.dp)
                    .source(surface)
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                content = content
            )
        }
    )
}

/** A full-width quick-access card (icon + title), used for Favourites and the Recycle Bin. */
@Composable
private fun QuickAccess(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .clip(shape)
            .background(colors.background(4.dp))
            .border(1.dp, colors.onBackground.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent)
        Label(title, style = AppTheme.typography.title3)
    }
}
