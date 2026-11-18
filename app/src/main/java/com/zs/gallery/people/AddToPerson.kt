package com.zs.gallery.people

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zs.compose.theme.AppTheme
import com.zs.compose.theme.Icon
import com.zs.compose.theme.text.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add to person" for one photo (from the viewer's menu): pick which face (if the scan found
 * several), then who it is. With no detected face, the whole photo is added to that person.
 */
object AddToPerson {
    var mediaId by mutableStateOf<Long?>(null)
        private set

    fun open(id: Long) {
        mediaId = id
    }

    fun close() {
        mediaId = null
    }
}

private const val WHOLE_PHOTO = -1L
private const val NEW_PERSON = -2L

@Composable
fun AddToPersonHost() {
    val current = AddToPerson.mediaId
    var shown by remember { mutableStateOf<Long?>(null) }
    if (current != null) shown = current
    val visible = current != null
    BackHandler(enabled = visible) { AddToPerson.close() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { AddToPerson.close() }
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val media = shown ?: return@AnimatedVisibility
            key(media) { Sheet(media) }
        }
    }
}

@Composable
private fun Sheet(mediaId: Long) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val store = remember { FaceIndexer.store(context) }
    val faces by produceState(emptyList<Pair<FaceRow, String?>>(), mediaId) {
        value = withContext(Dispatchers.IO) { store.facesIn(mediaId) }
    }
    val people by produceState(emptyList<Person>()) {
        value = withContext(Dispatchers.IO) { store.people() }
    }
    // Which face is being added: the first one nobody's been assigned to, else the first face.
    var face by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(faces) {
        if (face == null && faces.isNotEmpty())
            face = (faces.firstOrNull { it.first.personId <= 0 } ?: faces.first()).first.id
    }

    fun pick(person: Long) {
        val chosenFace = face ?: WHOLE_PHOTO
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                val target = if (person == NEW_PERSON) store.newPerson() else person
                if (chosenFace == WHOLE_PHOTO) store.addManual(mediaId, target)
                else store.assignFace(chosenFace, target)
                store.names()[target]
            }
            FaceIndexer.changed()
            AddToPerson.close()
            Toast.makeText(context, if (name != null) "Added to $name" else "Added", Toast.LENGTH_SHORT).show()
        }
    }

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
            "Add to person", style = AppTheme.typography.title2, fontWeight = FontWeight.SemiBold,
            color = colors.onBackground, modifier = Modifier.padding(start = 20.dp, top = 20.dp)
        )
        // Which face (only when there's a choice).
        run {
            Label(
                if (faces.isEmpty()) "No face was found here - the whole photo will be added."
                else "Which face?",
                style = AppTheme.typography.body2,
                color = colors.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp)
            )
        }
        if (faces.isNotEmpty())
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                for ((row, name) in faces) {
                    val chosen = row.id == face
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { face = row.id }
                            .padding(3.dp)
                    ) {
                        FaceAvatar(
                            row, 52.dp,
                            Modifier.border(if (chosen) 3.dp else 0.dp, if (chosen) colors.accent else Color.Transparent, CircleShape)
                        )
                        Label(
                            name ?: if (row.personId > 0) "Unnamed" else "Unknown",
                            style = AppTheme.typography.label3,
                            color = if (chosen) colors.accent else colors.onBackground.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp).widthIn(max = 72.dp)
                        )
                    }
                }
                // Or: none of these (e.g. a face the scan missed) - add the whole photo.
                val chosen = face == WHOLE_PHOTO
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { face = WHOLE_PHOTO }
                        .padding(3.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(52.dp)
                            .border(if (chosen) 3.dp else 0.dp, if (chosen) colors.accent else Color.Transparent, CircleShape)
                            .background(colors.onBackground.copy(alpha = 0.08f), CircleShape)
                    ) { Icon(Icons.Outlined.Image, null, tint = colors.onBackground.copy(alpha = 0.7f)) }
                    Label("Other", style = AppTheme.typography.label3,
                        color = if (chosen) colors.accent else colors.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        Label(
            "Who is it?",
            style = AppTheme.typography.body2,
            color = colors.onBackground.copy(alpha = 0.65f),
            modifier = Modifier.padding(start = 20.dp, top = 10.dp)
        )
        val currentPerson = faces.firstOrNull { it.first.id == face }?.first?.personId
        val choices = people.filter { !it.hidden && it.id != currentPerson }
            .sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.photos })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "new") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick(NEW_PERSON) }.padding(6.dp)
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
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick(p.id) }.padding(6.dp)
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
