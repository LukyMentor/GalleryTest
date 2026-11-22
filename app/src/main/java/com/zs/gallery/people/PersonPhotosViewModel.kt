package com.zs.gallery.people

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.zs.core.store.MediaFile
import com.zs.core.store.MediaProvider
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.FileOps
import com.zs.gallery.common.NearByShareIntent
import com.zs.gallery.common.ShareFilesIntent
import com.zs.gallery.common.icons.NearbyShare
import com.zs.gallery.impl.StoreViewModel
import com.zs.gallery.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The same selection actions as in Photos (like, delete, share, move…), for a person's photos. */
val P_STAR = Action(R.string.like, Icons.Outlined.StarBorder)
val P_DELETE = Action(R.string.delete, Icons.TwoTone.Delete)
val P_SHARE = Action(R.string.share, Icons.Outlined.Share)
val P_QUICK_SHARE = Action(R.string.beam, Icons.Filled.NearbyShare)
val P_MOVE = Action(R.string.move_to, Icons.Outlined.DriveFileMove)
val P_COPY = Action(R.string.copy_to, Icons.Outlined.ContentCopy)
val P_RENAME = Action(R.string.rename, Icons.Outlined.DriveFileRenameOutline)
val P_ADD_TO_PERSON = Action(R.string.add_to_person, Icons.Outlined.PersonAdd)
val P_SELECT_ALL = Action(R.string.select_all, Icons.Outlined.SelectAll)
val P_USE_AS_PICTURE = Action(R.string.use_as_their_picture, Icons.Outlined.AccountCircle)

class PersonPhotosViewModel(provider: MediaProvider) : StoreViewModel(provider) {

    var favourites: Set<Long> by mutableStateOf(emptySet())
        private set

    init {
        viewModelScope.launch {
            preferences.observe(Settings.KEY_FAVOURITE_FILES)
                .map { it.toHashSet() }
                .flowOn(Dispatchers.Default)
                .collect { favourites = it }
        }
    }

    // Deleted / binned: refresh the person's lists right away.
    override fun onRemoved(ids: LongArray) = FaceIndexer.changed()

    /** Runs one of the Photos-style actions on [files] (the selected photos). */
    fun run(action: Action, activity: Activity, files: List<MediaFile>) {
        if (files.isEmpty()) return
        val ids = files.map { it.id }.toLongArray()
        when (action) {
            P_STAR -> toggleLike(*ids)
            P_DELETE -> remove(activity, *ids)
            P_SHARE -> runCatching { activity.startActivity(ShareFilesIntent(*ids)) }
                .onFailure { showPlatformToast(R.string.msg_error_sharing_files) }
            P_QUICK_SHARE -> runCatching { activity.startActivity(NearByShareIntent(*ids)) }
                .onFailure { showPlatformToast(R.string.msg_files_quick_share_error) }
            P_ADD_TO_PERSON -> AddToPerson.openMany(ids, files.filter { !it.isImage }.mapTo(HashSet()) { it.id })
            P_MOVE, P_COPY, P_RENAME -> viewModelScope.launch {
                if (action == P_RENAME) FileOps.rename(activity, files.first())
                else FileOps.moveOrCopy(activity, files, action == P_MOVE, provider.fetchFolders())
                FaceIndexer.changed()
            }
        }
    }
}
