/*
 * Copyright 2025 sheik
 *
 * Created by sheik on 26-04-2025.
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

@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.zs.gallery.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zs.compose.foundation.findActivity
import com.zs.compose.foundation.stickyHeader
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.IconButton
import com.zs.compose.theme.LocalContentColor
import com.zs.compose.theme.LocalWindowSize
import com.zs.compose.theme.None
import com.zs.compose.theme.Surface
import com.zs.compose.theme.VerticalDivider
import com.zs.compose.theme.WindowSize.Category
import com.zs.compose.theme.adaptive.FabPosition
import com.zs.compose.theme.adaptive.Scaffold
import com.zs.compose.theme.adaptive.content
import com.zs.compose.theme.appbar.AdaptiveLargeTopAppBar
import com.zs.compose.theme.appbar.AppBarDefaults
import com.zs.compose.theme.minimumInteractiveComponentSize
import com.zs.compose.theme.snackbar.SnackbarDuration
import com.zs.compose.theme.text.Label
import com.zs.compose.theme.text.TonalHeader
import com.zs.core.store.MediaFile
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.SelectionTracker
import com.zs.gallery.common.compose.blockVerticalDrag
import com.zs.gallery.common.compose.FloatingActionMenu
import com.zs.gallery.common.compose.FloatingLargeTopAppBar
import com.zs.gallery.viewer.CountBubble
import com.zs.gallery.viewer.ViewerActions
import com.zs.gallery.common.compose.LocalNavController
import com.zs.gallery.common.compose.LocalSystemFacade
import com.zs.gallery.common.compose.OverflowMenu
import com.zs.gallery.common.compose.background
import com.zs.gallery.common.compose.emit
import com.zs.gallery.common.compose.fadingEdge2
import com.zs.gallery.common.compose.preference
import com.zs.gallery.common.compose.rememberAcrylicSurface
import com.zs.gallery.common.compose.section
import com.zs.gallery.common.compose.shine
import com.zs.gallery.common.compose.source
import com.zs.gallery.settings.RouteSettings
import com.zs.gallery.settings.Settings
import androidx.compose.foundation.combinedClickable as clickable
import androidx.compose.foundation.layout.PaddingValues as Padding
import androidx.compose.ui.res.vectorResource as Drawable
import com.zs.gallery.common.compose.ContentPadding as CP

private val SelectionTracker.Level.toImageVector
    get() = when (this) {
        SelectionTracker.Level.NONE -> Icons.Outlined.Circle
        SelectionTracker.Level.PARTIAL -> Icons.Outlined.RemoveCircle
        SelectionTracker.Level.FULL -> Icons.Outlined.Verified
    }
private val HeaderPadding = Padding(2.dp, 4.dp, 2.dp, 4.dp)

@Composable
fun Files(viewState: FilesViewState) {
    // prioritise clearing of selection mode if back is pressed
    BackHandler(
        viewState.isInSelectionMode,
        viewState::clear
    )

    // The top nav insets
    val (width, _) = LocalWindowSize.current
    val compact = width < Category.Medium
    val inAppNavInsets = WindowInsets.content
    //
    val topAppBarScrollBehavior = AppBarDefaults.exitUntilCollapsedScrollBehavior()
    val surface = rememberAcrylicSurface()
    val colors = AppTheme.colors
    // actions
    val actions = viewState.actions
    val ctx = LocalContext.current
    val navController = LocalNavController.current


    Scaffold(
        fabPosition = if (compact) FabPosition.Center else FabPosition.End,
        topBar = {
            val (icon, title) = viewState.meta
            FloatingLargeTopAppBar(
                title = { Label(title, maxLines = 2) },
                background = colors.background(surface),
                insets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
                scrollBehavior = topAppBarScrollBehavior,
                // Collapse only via the grid's scroll, never by dragging the bar itself.
                modifier = Modifier.blockVerticalDrag(),
                navigationIcon = {
                    val facade = LocalSystemFacade.current
                    when {
                        icon != null -> Icon(
                            imageVector = icon,
                            title.toString(),
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )

                        // Plain app icon; the "what's new" popup was removed.
                        else -> Icon(
                            ImageVector.Drawable(R.drawable.ic_app),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                    }
                },
                actions = {
                    // Show menu in TopBar if not in selection.
                    if (!viewState.isInSelectionMode)
                        OverflowMenu(
                            actions,
                            compact = true,
                            collapsed = 3,
                            onItemClicked = {
                                when (it) {
                                    Action.SETTINGS -> navController.navigate(RouteSettings())
                                    else -> viewState.onRequest(it, ctx.findActivity())
                                }
                            }
                        )
                }
            )
        },
        //
        floatingActionButton = {
            // Same big round buttons as the photo viewer: count, like, delete, share, "⋮".
            ViewerActions(
                visible = viewState.isInSelectionMode,
                actions = actions,
                onAction = { viewState.onRequest(it, ctx.findActivity()) },
                leading = { CountBubble(viewState.selectedCount) },
                modifier = Modifier.windowInsetsPadding(
                    (if (compact) inAppNavInsets else WindowInsets.None).union(WindowInsets.systemBars)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
                ),
            )
        },
        //
        content = {
            val state = rememberLazyGridState()
            val multiplier by preference(key = Settings.KEY_GRID_ITEM_SIZE_MULTIPLIER)
            val navController = LocalNavController.current
            //
            val data = viewState.data
            // Data
            val showSizes = viewState.groupedBySize
            val content: LazyGridScope.() -> Unit = content@{
                // Filter / sort bar: always first, so a filter that matches nothing can be undone.
                if (viewState.supportsFilters)
                    item(key = "filter_bar", contentType = "filter_bar", span = { GridItemSpan(maxLineSpan) }) {
                        FilterBar(
                            filter = viewState.filter,
                            order = viewState.order,
                            onFilter = { viewState.filter = it },
                            onOrder = { viewState.order = it },
                        )
                    }
                // emit state or return non-null | non empty data points
                val data = emit(data) ?: return@content
                // items along with sticky headers
                for ((header, items) in data) {
                    stickyHeader(state, key = header, contentType = "header") {
                        // Selection level of the group.
                        val level by remember(header) { viewState.isGroupSelected(header.toString()) }
                        Row(
                            Modifier.padding(HeaderPadding),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            content = {
                                TonalHeader(header)
                                // toggle
                                IconButton(
                                    icon = level.toImageVector,
                                    contentDescription = null,
                                    tint = if (level == SelectionTracker.Level.FULL) AppTheme.colors.accent else LocalContentColor.current,
                                    onClick = { viewState.select(header.toString()) }
                                )
                            }
                        )
                    }

                    // Rest of the items
                    items(
                        items,
                        key = MediaFile::id,
                        contentType = { "media_file" },
                        itemContent = { item ->
                            //
                            // Long-press is handled by dragToSelect on the grid (select + drag
                            // to extend), so tiles only handle plain taps.
                            val clickable = Modifier.clickable(
                                onClick = {
                                    if (viewState.isInSelectionMode || viewState.pickMode)
                                        viewState.select(item.id)
                                    else
                                        navController.navigate(viewState.direction(item.id))
                                }
                            )
                            //
                            MediaFile(
                                focused = false,
                                value = item,
                                sizeLabel = if (showSizes) formatSize(item.size) else null,
                                favourite = viewState.isFavourite(item.id),
                                checked = when {
                                    !viewState.isInSelectionMode -> -1
                                    viewState.isSelected(item.id) -> 1
                                    else -> 0
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .then(RouteFiles.sharedElement(item.id))
                                    .then(clickable)
                            )
                        }
                    )

                    // spacer
                    section()
                }
            }
            val contentPadding = (inAppNavInsets.add(WindowInsets.content)
                .union(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))).asPaddingValues() +
                    (Padding(end = if (!compact) CP.large else 0.dp) + Padding(horizontal = CP.medium))
            // Position model shared by the fast scroller and drag-to-select.
            val leading = if (viewState.supportsFilters) 1 else 0
            val byDate = !viewState.groupedBySize
            val timeline = remember(data, leading, byDate) {
                data?.takeIf { it.isNotEmpty() }?.let { TimelineIndex(it, byDate, leading) }
            }
            val currentTimeline by rememberUpdatedState(timeline)
            val currentPadding by rememberUpdatedState(contentPadding)
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val haptic = LocalHapticFeedback.current
            // Exact column count, using the same formula as GridCells.Adaptive.
            val minTile = Settings.STANDARD_TILE_SIZE * multiplier
            val spacing = CP.SmallArrangement.spacing
            val startPad = contentPadding.calculateStartPadding(layoutDirection)
            val endPad = contentPadding.calculateEndPadding(layoutDirection)
            val gridColumns by remember(minTile, spacing, startPad, endPad, density) {
                derivedStateOf {
                    with(density) {
                        val available = state.layoutInfo.viewportSize.width -
                                startPad.roundToPx() - endPad.roundToPx()
                        val gap = spacing.roundToPx()
                        maxOf((available + gap) / (minTile.roundToPx() + gap), 1)
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                // Content
                LazyVerticalGrid(
                    state = state,
                    columns = GridCells.Adaptive(Settings.STANDARD_TILE_SIZE * multiplier),
                    horizontalArrangement = CP.SmallArrangement,
                    verticalArrangement = CP.SmallArrangement,
                    contentPadding = contentPadding,
                    modifier = Modifier
                        .fillMaxSize()
                        .dragToSelect(
                            state = state,
                            index = { currentTimeline },
                            contentOffset = {
                                with(density) {
                                    Offset(
                                        currentPadding.calculateStartPadding(layoutDirection).toPx(),
                                        currentPadding.calculateTopPadding().toPx()
                                    )
                                }
                            },
                            isSelected = viewState::isSelected,
                            setSelected = viewState::setSelected,
                            onStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        )
                        .fadingEdge2(length = 56.dp)
                        .source(surface)
                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                    content = content
                )
                // Aves-style "jump to month" scroller on the right edge.
                val index = timeline
                if (index != null)
                    TimelineFastScroller(
                        state = state,
                        index = index,
                        columns = gridColumns,
                        topPadding = contentPadding.calculateTopPadding(),
                        bottomPadding = contentPadding.calculateBottomPadding(),
                        modifier = Modifier.matchParentSize()
                    )
            }
        }
    )
}