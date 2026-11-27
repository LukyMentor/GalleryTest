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

import com.zs.gallery.common.tr
import com.zs.gallery.common.photosText
import com.zs.gallery.settings.Settings

import kotlinx.coroutines.flow.StateFlow

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.zs.compose.foundation.castTo
import com.zs.core.store.Folder
import com.zs.core.store.MediaProvider
import com.zs.gallery.R
import com.zs.gallery.common.Action
import com.zs.gallery.common.Filter
import com.zs.gallery.common.Mapped
import com.zs.gallery.common.compose.FilterDefaults
import com.zs.gallery.common.debounceAfterFirst
import com.zs.gallery.folders.FoldersViewState
import com.zs.preferences.stringPreferenceKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

private const val TAG = "FoldersViewModel"

private val Folder.firstTitleChar
    inline get() = name.uppercase(Locale.ROOT)[0].toString()

private val ORDER_BY_DATE_MODIFIED =
    Action(R.string.last_modified, Icons.Outlined.DateRange, id = "date_modified")
private val ORDER_BY_NAME = Action(R.string.name, Icons.Outlined.TextFields, id = "name")
private val ORDER_BY_NONE = FilterDefaults.ORDER_NONE

class FoldersViewModel(provider: MediaProvider) : KoinViewModel(), FoldersViewState {

    override val hidden: StateFlow<Set<String>> =
        preferences.observe(Settings.KEY_HIDDEN_FOLDERS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, preferences[Settings.KEY_HIDDEN_FOLDERS])

    override val pinned: StateFlow<Set<String>> =
        preferences.observe(Settings.KEY_PINNED_FOLDERS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, preferences[Settings.KEY_PINNED_FOLDERS])

    override fun togglePinned(path: String) {
        val current = preferences[Settings.KEY_PINNED_FOLDERS]
        preferences[Settings.KEY_PINNED_FOLDERS] = if (path in current) current - path else current + path
    }

    override fun toggleHidden(path: String) {
        val current = preferences[Settings.KEY_HIDDEN_FOLDERS]
        val hiding = path !in current
        preferences[Settings.KEY_HIDDEN_FOLDERS] = if (hiding) current + path else current - path
        showPlatformToast(
            if (hiding) tr("Hidden from Photos (still here in Folders)", "Skryto ve Fotkách (ve Složkách zůstává)") else tr("Shown in Photos again", "Znovu ve Fotkách")
        )
    }


    val filterKey = stringPreferenceKey(
        "folders_filter",
        null,
        FilterDefaults.FilterSaver { id ->
            when (id) {
                ORDER_BY_DATE_MODIFIED.id -> ORDER_BY_DATE_MODIFIED
                ORDER_BY_NAME.id -> ORDER_BY_NAME
                else -> ORDER_BY_NONE
            }
        }
    )
    // "None" is no longer offered (it also disabled the direction button); fall back to name.
    override var filter: Filter by mutableStateOf(
        preferences[filterKey]?.takeIf { it.second != ORDER_BY_NONE } ?: (true to ORDER_BY_NAME)
    )
    override val orders: List<Action> = listOf(ORDER_BY_NAME, ORDER_BY_DATE_MODIFIED)

    override fun filter(ascending: Boolean, order: Action) {
        if (ascending == filter.first && order == filter.second) return
        // means only change in ascending happened
        // we don't support that, in order none.
        if (order == filter.second && order == ORDER_BY_NONE && filter.first != ascending)
            return
        val newFilter = ascending to order
        preferences[filterKey] = newFilter
        filter = newFilter
    }


    override val data = combine(
        snapshotFlow(::filter).debounceAfterFirst(300L),
        provider.observer(MediaProvider.EXTERNAL_CONTENT_URI).debounceAfterFirst(300L),
        preferences.observe(Settings.KEY_PINNED_FOLDERS)
    ) { (ascending, order), _, pins ->
        val all = provider.fetchFolders(ascending = false)
        // Pinned folders get their own section at the top (by name); the rest as chosen.
        val pinnedFolders = all.filter { it.path in pins }.sortedBy { it.name.lowercase() }
        val folders = all.filter { it.path !in pins }
        val result = when (order) {
            ORDER_BY_NONE -> folders.groupBy { "" }
            ORDER_BY_NAME -> folders.sortedBy { it.firstTitleChar }
                .let { if (ascending) it else it.reversed() }.groupBy { it.firstTitleChar }

            ORDER_BY_DATE_MODIFIED -> folders.sortedBy { it.lastModified }
                .let { if (ascending) it else it.reversed() }
                .groupBy { DateUtils.getRelativeTimeSpanString(it.lastModified).toString() }

            else -> error("Oops invalid id passed $order")
        }
        val withPins = if (pinnedFolders.isEmpty()) result
        else LinkedHashMap<Any, List<Folder>>().apply {
            put(tr("Pinned", "Připnuté"), pinnedFolders)
            for ((k, v) in result) put(k, v)
        }
        // This should be safe
        castTo(withPins) as Mapped<Folder>
    }
        // Catch any exceptions in upstream flow and emit using the snackbar.
        .catch { exception ->
            Log.e(TAG, "provider: ${exception.message}")
            // Handle any exceptions that occur during the flow.
            // This might involve logging the exception using Firebase Crashlytics.
            // Display a toast message to the user, indicating something went wrong and suggesting they report the issue.
            report(
                exception.message ?: getText(R.string.msg_unknown_error),
            )
        }
        // Convert to state.
        .stateIn(viewModelScope, started = SharingStarted.WhileSubscribed(500), null)
}
