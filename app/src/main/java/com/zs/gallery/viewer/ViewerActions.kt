/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.viewer

import com.zs.gallery.common.tr
import com.zs.gallery.common.photosText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.zs.gallery.R
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zs.compose.theme.Icon
import com.zs.compose.theme.menu.DropDownMenu
import com.zs.compose.theme.menu.DropDownMenuItem
import com.zs.gallery.common.Action

// Same visual size as the Photos / Folders / Map bar (was 56 dp).
private val ButtonSize = 46.dp
private val ButtonColor = Color(0xFF1C1C22).copy(alpha = 0.72f)

/**
 * Aves-style viewer actions: separate, large round buttons at the bottom-right (where the app's
 * navigation bar sits), the first [visibleCount] actions as buttons and the rest in a "⋮" menu.
 */
@Composable
fun ViewerActions(
    visible: Boolean,
    actions: List<Action>,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 3,
    leading: (@Composable () -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            leading?.invoke()
            for (action in actions.take(visibleCount)) {
                // A liked photo shows a solid yellow star (not just a slightly different outline).
                val liked = action.label == R.string.unlike
                RoundButton(
                    icon = if (liked) Icons.Filled.Star else action.icon ?: Icons.Outlined.BrokenImage,
                    contentDescription = stringResource(action.label),
                    enabled = action.enabled,
                    tint = if (liked) LikedYellow else Color.White,
                    onClick = { onAction(action) }
                )
            }
            val rest = actions.drop(visibleCount)
            if (rest.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    RoundButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = tr("More actions", "Další akce"),
                        onClick = { expanded = true }
                    )
                    DropDownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.widthIn(min = 200.dp)
                    ) {
                        for (action in rest) {
                            DropDownMenuItem(
                                title = stringResource(action.label),
                                onClick = {
                                    expanded = false
                                    onAction(action)
                                },
                                icon = action.icon
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Colour of the star on a liked photo. */
private val LikedYellow = Color(0xFFFFC83D)

@Composable
private fun RoundButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ButtonSize)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(ButtonColor)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}


/** Round accent bubble with the number of selected items (same size as the buttons). */
@Composable
fun CountBubble(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ButtonSize)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(com.zs.compose.theme.AppTheme.colors.accent)
    ) {
        com.zs.compose.theme.text.Label(
            text = "$count",
            color = com.zs.compose.theme.AppTheme.colors.onAccent,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            style = com.zs.compose.theme.AppTheme.typography.body1
        )
    }
}
