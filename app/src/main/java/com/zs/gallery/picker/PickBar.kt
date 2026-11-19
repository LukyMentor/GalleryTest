package com.zs.gallery.picker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.text.Label

/** While picking for another app: what's being chosen, and Cancel / Add (n). */
@Composable
fun PickBar() {
    if (!PickMode.active) return
    val colors = AppTheme.colors
    val count = PickMode.selected.size
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // Above the Photos / Folders / People / Map bar.
                .padding(start = 16.dp, end = 16.dp, bottom = 92.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .shadow(10.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(colors.background(4.dp))
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Column(Modifier.padding(end = 4.dp)) {
                    Label(
                        when {
                            PickMode.multiple && count > 0 -> "$count selected"
                            PickMode.multiple -> "Choose photos"
                            PickMode.videosOnly -> "Choose a video"
                            else -> "Choose a photo"
                        },
                        color = colors.onBackground, fontWeight = FontWeight.SemiBold
                    )
                    PickMode.caller?.let {
                        Label("for $it", style = AppTheme.typography.label3, color = colors.onBackground.copy(alpha = 0.6f))
                    }
                }
                Pill("Cancel", primary = false) { PickMode.cancel() }
                if (PickMode.multiple && count > 0)
                    Pill("Add ($count)", primary = true) { PickMode.deliver(PickMode.selected.toList()) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) colors.accent else colors.onBackground.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Label(text, color = if (primary) colors.onAccent else colors.onBackground, fontWeight = FontWeight.SemiBold)
    }
}
