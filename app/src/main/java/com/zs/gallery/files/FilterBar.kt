/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.files

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.text.Label
import java.util.Locale

private val ChipShape = RoundedCornerShape(50)

/** "12.4 MB", "830 KB", "1.2 GB". */
fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 10 -> String.format(Locale.US, "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

/**
 * Filter (what to show) and order (how to sort) for the grid, as one horizontally scrollable
 * row of chips: `All · Photos · Videos · Screenshots | Newest · Oldest · Largest`.
 */
@Composable
internal fun FilterBar(
    filter: MediaFilter,
    order: MediaOrder,
    onFilter: (MediaFilter) -> Unit,
    onOrder: (MediaOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        FilterChip("All", filter == MediaFilter.ALL) { onFilter(MediaFilter.ALL) }
        FilterChip("Photos", filter == MediaFilter.PHOTOS) { onFilter(MediaFilter.PHOTOS) }
        FilterChip("Videos", filter == MediaFilter.VIDEOS) { onFilter(MediaFilter.VIDEOS) }
        FilterChip("Screenshots", filter == MediaFilter.SCREENSHOTS) { onFilter(MediaFilter.SCREENSHOTS) }
        // Divider between "what" and "how".
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .width(1.dp)
                .height(24.dp)
                .background(AppTheme.colors.onBackground.copy(alpha = 0.2f))
        )
        FilterChip("Newest", order == MediaOrder.NEWEST) { onOrder(MediaOrder.NEWEST) }
        FilterChip("Oldest", order == MediaOrder.OLDEST) { onOrder(MediaOrder.OLDEST) }
        FilterChip("Largest", order == MediaOrder.LARGEST) { onOrder(MediaOrder.LARGEST) }
    }
}

@Composable
internal fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val background by animateColorAsState(
        if (selected) colors.accent else colors.background(3.dp), label = "chip_bg"
    )
    val content by animateColorAsState(
        if (selected) colors.onAccent else colors.onBackground, label = "chip_fg"
    )
    Label(
        text = text,
        color = content,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(ChipShape)
            .background(background)
            .border(1.dp, colors.onBackground.copy(alpha = if (selected) 0f else 0.12f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
