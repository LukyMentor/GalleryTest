/*
 * Copyright 2025 Zakir Sheikh
 *
 * Created by Zakir Sheikh on 16-05-2025.
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

package com.zs.gallery.impl

import android.annotation.SuppressLint
import android.app.Activity
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderCopy
import androidx.compose.material.icons.twotone.HotelClass
import androidx.compose.material.icons.twotone.Star
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zs.core.common.PathUtils
import com.zs.core.store.MediaFile
import com.zs.gallery.common.FileOps
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import com.zs.core.store.sortedForTimeline
import com.zs.gallery.files.MediaFilter
import androidx.compose.material.icons.outlined.PersonAdd
import kotlinx.coroutines.launch
import com.zs.gallery.files.RouteFiles.SOURCE_PICK
import com.zs.gallery.files.MediaOrder
import androidx.compose.runtime.snapshotFlow
import com.zs.core.store.MediaProvider
import com.zs.gallery.MainActivity
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.Mapped
import com.zs.gallery.common.NearByShareIntent
import com.zs.gallery.common.SelectionTracker.Level
import com.zs.gallery.common.ShareFilesIntent
import com.zs.gallery.common.debounceAfterFirst
import com.zs.gallery.common.ellipsize
import com.zs.gallery.common.icons.NearbyShare
import com.zs.gallery.files.FilesViewState
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.files.RouteFiles.SOURCE_BIN
import com.zs.gallery.files.RouteFiles.SOURCE_FAV
import com.zs.gallery.files.RouteFiles.SOURCE_FOLDER
import com.zs.gallery.files.RouteFiles.SOURCE_TIMELINE
import com.zs.gallery.files.get
import com.zs.gallery.settings.Settings
import com.zs.gallery.viewer.RouteViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "FilesViewModel"

private val DELETE = Action(R.string.delete, Icons.TwoTone.Delete)
private val SHARE = Action(R.string.share, Icons.Outlined.Share)
private val STAR = Action(R.string.like, Icons.Outlined.StarBorder)
private val UN_STAR = Action(R.string.unlike, Icons.TwoTone.Star)
private val SELECT_ALL = Action(R.string.select_all, Icons.Outlined.SelectAll)
private val RESTORE = Action(R.string.restore, Icons.Outlined.Restore)
private val EMPTY_BIN = Action(R.string.empty_bin, Icons.Outlined.PlaylistRemove)
private val QUICK_SHARE = Action(R.string.beam, Icons.Filled.NearbyShare)
private val SETTINGS = Action.SETTINGS
private val MOVE = Action(R.string.move_to, Icons.Outlined.DriveFileMove)
private val COPY = Action(R.string.copy_to, Icons.Outlined.ContentCopy)
private val RENAME = Action(R.string.rename, Icons.Outlined.DriveFileRenameOutline)
private val MOVE_HERE = Action(R.string.move_here, Icons.Outlined.DriveFileMove)
private val ADD_TO_PERSON = Action(R.string.add_to_person, Icons.Outlined.PersonAdd)
private val COPY_HERE = Action(R.string.copy_here, Icons.Outlined.ContentCopy)

@SuppressLint("NewApi")
class FilesViewModel(
    handle: SavedStateHandle,
    provider: MediaProvider,
) : StoreViewModel(provider), FilesViewState {

    val param = handle[RouteFiles]
    val source = param.first
    val arg = param.second

    override val meta: Pair<ImageVector?, CharSequence> =
        when (source) {
            SOURCE_BIN -> Icons.Outlined.Recycling to getText(R.string.recycle_bin)
            SOURCE_FAV -> Icons.Outlined.FavoriteBorder to getText(R.string.favourites)
            SOURCE_TIMELINE -> null to getText(R.string.timeline)
            SOURCE_PICK -> Icons.Outlined.DriveFileMove to
                    "Add to ${arg.orEmpty().trimEnd('/').substringAfterLast('/')}"
            else -> Icons.TwoTone.FolderCopy to buildAnnotatedString {
                // Folder name, with where it is underneath (readable in light and dark mode).
                append(PathUtils.name(arg!!).ellipsize(40))
                val parent = PathUtils.parent(arg)
                val where = com.zs.gallery.common.FileOps.relativePathOf(parent)
                    ?.trimEnd('/')?.let { if (it.isEmpty()) "Phone" else "Phone › ${it.replace("/", " › ")}" }
                    ?: parent
                val dim = if (com.zs.gallery.common.FileOps.darkTheme) Color(0xFFB9B9C6) else Color(0xFF5E5E6A)
                withStyle(ParagraphStyle(lineHeight = 14.sp)) {
                    withStyle(SpanStyle(fontSize = 12.sp, color = dim)) {
                        append(where)
                    }
                }
            }
        }

    override var data: Mapped<MediaFile>? by mutableStateOf(null)

    override val supportsFilters: Boolean = source != SOURCE_BIN
    override val pickMode: Boolean = source == SOURCE_PICK ||
            (com.zs.gallery.picker.PickMode.active && com.zs.gallery.picker.PickMode.multiple)
    override val isTimeline: Boolean = source == SOURCE_TIMELINE
    // Picking only photos / only videos for another app: start on that filter.
    override var filter: MediaFilter by mutableStateOf(
        when {
            !com.zs.gallery.picker.PickMode.active -> MediaFilter.ALL
            com.zs.gallery.picker.PickMode.imagesOnly -> MediaFilter.PHOTOS
            com.zs.gallery.picker.PickMode.videosOnly -> MediaFilter.VIDEOS
            else -> MediaFilter.ALL
        }
    )
    override var order: MediaOrder by mutableStateOf(MediaOrder.NEWEST)
    override var groupedBySize: Boolean by mutableStateOf(false)
        private set

    /** Applies the filter and order to the freshly loaded list. */
    private fun arrange(values: List<MediaFile>, filter: MediaFilter, order: MediaOrder): List<MediaFile> {
        if (source == SOURCE_BIN) return values
        val filtered = when (filter) {
            MediaFilter.ALL -> values
            MediaFilter.PHOTOS -> values.filter { it.isImage }
            MediaFilter.VIDEOS -> values.filter { !it.isImage }
            MediaFilter.SCREENSHOTS -> values.filter {
                it.path.contains("/Screenshots/", ignoreCase = true) ||
                        it.name.startsWith("Screenshot", ignoreCase = true)
            }
        }
        return when (order) {
            MediaOrder.NEWEST -> filtered.sortedForTimeline()
            MediaOrder.OLDEST -> filtered.sortedBy { it.timelineDate }
            MediaOrder.LARGEST -> filtered.sortedByDescending { it.size }
        }
    }

    /** Folder a file belongs to, same rule as the Folders tab ("img*" subfolders merge up). */
    private fun folderOf(path: String): String {
        val parent = PathUtils.parent(path)
        return if (PathUtils.name(parent).startsWith("img", true)) PathUtils.parent(parent) else parent
    }

    /** Size groups used when ordering by size (largest first). */
    private fun sizeGroup(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 100 -> "Over 100 MB"
            mb >= 25 -> "25 – 100 MB"
            mb >= 10 -> "10 – 25 MB"
            mb >= 5 -> "5 – 10 MB"
            mb >= 1 -> "1 – 5 MB"
            else -> "Under 1 MB"
        }
    }

    // Represents the
    // Selection is kept in a hash set: membership checks are O(1), which matters for drag-select
    // (hundreds of items) and for every grid cell asking "am I selected?" on each frame.
    // Picking several photos for another app: one selection shared by every screen.
    // (Also when only one is allowed: a long-press selection then still has a way to be sent.)
    private val selection =
        if (com.zs.gallery.picker.PickMode.active)
            com.zs.gallery.picker.PickMode.selected
        else mutableStateSetOf<Long>()
    override fun clear() = selection.clear()
    override val selected: List<Long> get() = selection.toList()
    override val selectedCount: Int get() = selection.size
    override fun isSelected(id: Long): Boolean = id in selection

    private var favouriteIds: Set<Long> by mutableStateOf(emptySet())
    override fun isFavourite(id: Long): Boolean = id in favouriteIds
    override val isInSelectionMode: Boolean by derivedStateOf { selection.isNotEmpty() }
    override val allSelected: Boolean by derivedStateOf {
        // FixMe: For now this seems reasonable.
        //      However take the example of the case when size is same but ids are different
        selection.size == data?.values?.sumOf { it.size }
    }

    override fun setSelected(ids: Collection<Long>, value: Boolean) {
        if (value) selection.addAll(ids) else selection.removeAll(ids.toSet())
    }


    /**
     * Consumes the currently selected items and returns them as an array.
     *
     * This function creates a new array containing the selected items, clears the `selected` list, and returns the array.
     * @return An array containing the previously selected items.
     */
    fun consume(): LongArray {
        // Efficiently convert the list to an array.
        val data = selection.toLongArray()
        // Clear the selected items list.
        selection.clear()
        Log.d(TAG, "consume: ${data.size}")
        return data
    }

    override fun selectAll() {
        val data = data ?: return
        // Iterate through all items in the data and select them if they are not already selected.
        data.forEach { _, items ->
            items.forEach { item -> selection.add(item.id) }
        }
    }

    override fun select(id: Long) {
        if (!selection.remove(id)) selection.add(id)
    }

    fun evaluateGroupSelectionLevel(key: String): Level {
        // Return NONE if data is not available.
        val data = data?.get(key) ?: return Level.NONE
        // Count selected
        val count = data.count { it.id in selection }
        return when (count) {
            data.size -> Level.FULL // All items in the group are selected.
            in 1..data.size -> Level.PARTIAL // Some items in the group are selected.
            else -> Level.NONE // No items in the group are selected.
        }
    }

    override fun isGroupSelected(key: String) =
        derivedStateOf { evaluateGroupSelectionLevel(key) }

    override fun select(key: String) {
        // Return if data is not available.
        val data = data ?: return
        // Get the current selection level of the group.
        val level = evaluateGroupSelectionLevel(key)
        // Get the IDs of all items in the group.
        val all = data[key]?.map { it.id } ?: emptyList()
        // Update the selected items based on the group selection level.
        when (level) {
            Level.NONE, Level.PARTIAL -> selection.addAll(all) // Select every item of the group.
            Level.FULL -> selection.removeAll(all.toSet()) // Deselect all items of the group.
        }
    }

    override fun direction(id: Long) =
        RouteViewer(source, id, arg ?: "")

    @Suppress("BuildListAdds")
    override val actions: List<Action> by derivedStateOf {
        buildList {
            // Picking photos for a new folder: only "move here" / "copy here".
            if (source == SOURCE_PICK) {
                if (isInSelectionMode) {
                    this += MOVE_HERE; this += COPY_HERE
                }
                return@buildList
            }
            // "Select all" leads the header when nothing is selected; with a selection it goes to
            // the end (into "⋮"), so the bottom bar looks the same everywhere.
            val selectAll = source != SOURCE_TIMELINE && !allSelected
            if (selectAll && !isInSelectionMode)
                this += SELECT_ALL
            // Handle actions specific to the Bin source
            if (source == SOURCE_BIN && !isInSelectionMode) {
                this += RESTORE; this += EMPTY_BIN
                return@buildList
            }
            // Handle actions for bin when not selected.
            else if (source == SOURCE_BIN) {
                this += RESTORE; this += DELETE
                if (selectAll) this += SELECT_ALL
            }
            // Handle actions for Timeline when not in selection mode
            else if (source == SOURCE_TIMELINE && !isInSelectionMode) {
                this += SETTINGS
            }
            // Handle actions for Favorites when not in selection mode
            else if (source == SOURCE_FAV && !isInSelectionMode) {
                this += UN_STAR
            }
            // A folder with nothing selected: just "select all" in the header. (Like / delete /
            // share used to act on the *whole folder* from a single tap - and froze big folders.)
            else if (!isInSelectionMode) {
                return@buildList
            }
            // Default actions for other cases (e.g., albums, selection mode)
            // These actions are typically available when items are selected or in a general context.
            // - QUICK_SHARE: For quickly sharing selected items.
            // - STAR: For marking items as favorites.
            // - DELETE: For moving items to the bin.
            // - SHARE: For standard sharing options.
            else {
                // Same bar in Photos, folders and favourites: like, delete, share as buttons;
                // the rest (quick share, organising, select all) in "⋮".
                this += STAR; this += DELETE; this += SHARE
                this += QUICK_SHARE
                this += MOVE; this += COPY
                if (selection.size == 1) this += RENAME
                this += ADD_TO_PERSON
                if (selectAll) this += SELECT_ALL
            }
        }
    }

    override fun onRequest(value: Action, resolver: Activity) {
        // Launch a coroutine to handle the action asynchronously.
        launch {
            // Determine the items to focus on for the action.
            val focused = when {
                // If there are selected items, consume them.
                // "consume()" clears the selection and returns the selected item IDs.
                selection.isNotEmpty() -> consume()
                // Otherwise, if no items are selected, consider all items in the current view as focused.
                // This is relevant for actions like "Empty Bin" or "Unlike" (when viewing favorites).
                else -> {
                    // If data is null (e.g., still loading), do nothing.
                    val data = data ?: return@launch
                    // Build a list of all item IDs from the current data.
                    buildList {
                        data.forEach { _, items ->
                            this.addAll(items.map { it.id })
                        }
                    }.toLongArray()
                }
            }
            // Perform the action based on the 'value' parameter.
            when (value) {
                RESTORE -> restore(resolver = resolver, *focused)
                // If deleting and the source is the bin, permanently delete.
                DELETE if(source == SOURCE_BIN) -> delete(resolver, *focused)
                DELETE -> remove(resolver, *focused)
                STAR -> toggleLike(*focused)
                UN_STAR -> toggleLike(*focused)
                EMPTY_BIN -> delete(resolver = resolver, *focused)
                SELECT_ALL -> selectAll()
                // Handle sharing files.
                SHARE -> {
                    val result = runCatching {
                        resolver.startActivity(ShareFilesIntent(*focused))
                    }
                    // If sharing fails, display an error message.
                    if (result.isFailure)
                        error(getText(R.string.msg_error_sharing_files))
                }
                // Handle quick sharing (e.g., Nearby Share).
                QUICK_SHARE -> {
                    val res = runCatching {
                        resolver.startActivity(NearByShareIntent(*focused))
                    }
                    // If quick sharing fails, display an error message.
                    if (res.isFailure)
                        error(getText(R.string.msg_files_quick_share_error))
                }

                MOVE_HERE, COPY_HERE -> {
                    val target = arg ?: return@launch
                    val wanted = focused.toHashSet()
                    val files = data?.values?.flatten()?.filter { it.id in wanted }.orEmpty()
                    if (files.isEmpty()) return@launch
                    val done = if (value == MOVE_HERE) FileOps.move(resolver, files, target)
                    else FileOps.copy(resolver, files, target)
                    // Back to Folders, where the new folder now shows up.
                    if (done > 0) (resolver as? androidx.activity.ComponentActivity)
                        ?.onBackPressedDispatcher?.onBackPressed()
                }
                ADD_TO_PERSON -> {
                    val wanted = focused.toHashSet()
                    val files = data?.values?.flatten()?.filter { it.id in wanted }.orEmpty()
                    if (files.isEmpty()) return@launch
                    com.zs.gallery.people.AddToPerson.openMany(
                        files.map { it.id }.toLongArray(),
                        files.filter { !it.isImage }.mapTo(HashSet()) { it.id }
                    )
                    clear()
                }
                MOVE, COPY, RENAME -> {
                    val wanted = focused.toHashSet()
                    val files = data?.values?.flatten()?.filter { it.id in wanted }.orEmpty()
                    if (files.isEmpty()) return@launch
                    when (value) {
                        RENAME -> FileOps.rename(resolver, files.first())
                        else -> {
                            val folders = provider.fetchFolders()
                            FileOps.moveOrCopy(resolver, files, value == MOVE, folders)
                        }
                    }
                }
                else -> error("${getText(value.label)} is not supported.")
            }
        }
    }

    /**
     * Groups [values] (already sorted newest first) into labelled sections.
     *
     * Runs off the main thread. The timeline groups by calendar day: the day is computed with
     * cheap arithmetic per item and the (expensive, locale-aware) label is formatted only once per
     * day instead of once per photo.
     */
    private fun group(values: List<MediaFile>, order: MediaOrder): Mapped<MediaFile> {
        if (source != SOURCE_BIN && order == MediaOrder.LARGEST) {
            val sized = LinkedHashMap<CharSequence, MutableList<MediaFile>>()
            for (file in values) sized.getOrPut(sizeGroup(file.size)) { ArrayList() } += file
            return sized
        }
        if (source == SOURCE_BIN)
            return values.groupBy { (it.expires - System.currentTimeMillis()).milliseconds.inWholeDays }
                // Transform the keys to user-friendly labels
                .mapKeys { (daysLeft, _) ->
                    when {
                        daysLeft <= 0 -> getText(R.string.today) // Handle expired items
                        daysLeft == 1L -> getText(R.string.trash_one_day_left)
                        else -> getText(
                            R.string.trash_days_left_d,
                            daysLeft
                        ) // Format remaining days
                    }.toString()
                }
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val result = LinkedHashMap<CharSequence, MutableList<MediaFile>>()
        var currentDay = Long.MIN_VALUE
        var current: MutableList<MediaFile>? = null
        for (file in values) {
            val time = file.timelineDate
            val day = Math.floorDiv(time + zone.getOffset(time), DateUtils.DAY_IN_MILLIS)
            if (day != currentDay || current == null) {
                currentDay = day
                val label = DateUtils.getRelativeTimeSpanString(
                    time, now, DateUtils.DAY_IN_MILLIS
                ).toString()
                // Same label twice (e.g. unsorted input) -> append to the existing section.
                current = result.getOrPut(label) { ArrayList() }
            }
            current!!.add(file)
        }
        return result
    }

    val onRequestUpdate: suspend (Pair<Mapped<MediaFile>, Boolean>) -> Unit = { (values, bySize) ->
        groupedBySize = bySize
        data = values
    }

    init {
        viewModelScope.launch {
            preferences.observe(Settings.KEY_FAVOURITE_FILES)
                .map { it.toHashSet() }
                .flowOn(kotlinx.coroutines.Dispatchers.Default)
                .collect { favouriteIds = it }
        }
        if (source == SOURCE_PICK)
            showPlatformToast("Tap photos to select them, then Move here or Copy here")
        provider.observer(MediaProvider.EXTERNAL_CONTENT_URI)
            .debounceAfterFirst(300)
            .let { stream ->
                when (source) {
                    SOURCE_FAV -> stream.combine(
                        preferences.observe(Settings.KEY_FAVOURITE_FILES).debounceAfterFirst(300),
                        transform = { _, ids ->
                            provider.fetchFiles(
                                ids = ids.toLongArray(),
                                order = MediaProvider.COLUMN_DATE_MODIFIED,
                                ascending = false
                            )
                        }
                    )

                    SOURCE_BIN -> stream.map { provider.fetchTrashedFiles() }
                    SOURCE_FOLDER -> stream.map {
                        provider.fetchFilesFromDirectory(
                            arg!!,
                            order = MediaProvider.COLUMN_DATE_MODIFIED,
                            ascending = false
                        )
                    }

                    else -> stream.map {
                        provider.fetchFiles(
                            order = MediaProvider.COLUMN_DATE_MODIFIED,
                            ascending = false
                        )
                    }
                }
            }
            // Query + sorting + grouping happen on a background thread; only the result touches
            // the UI. The sorted list is also shared with the viewer so it opens instantly.
            .combine(
                combine(
                    snapshotFlow { filter to order },
                    // Folders hidden from the Photos tab (only applies there).
                    if (source == SOURCE_TIMELINE) preferences.observe(Settings.KEY_HIDDEN_FOLDERS)
                    else flowOf(emptySet<String>())
                ) { options, hidden -> options to hidden }
            ) { values, (options, hidden) ->
                val (filter, order) = options
                val visible = if (hidden.isEmpty()) values
                else values.filter { folderOf(it.path) !in hidden }
                val arranged = arrange(visible, filter, order)
                // Same list (and order) the viewer swipes through.
                MediaListCache.put(source, arg, arranged)
                group(arranged, order) to (source != SOURCE_BIN && order == MediaOrder.LARGEST)
            }
            .flowOn(Dispatchers.Default)
            .onEach(onRequestUpdate)
            .catch { exception ->
                Log.d(TAG, "provider: ${exception.stackTraceToString()}")
                val action = report(exception.message ?: getText(R.string.msg_unknown_error))
            }
            .launchIn(viewModelScope)
    }
}