/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.impl

import com.zs.core.store.MediaFile

/**
 * Last list of media loaded by each grid screen (timeline, a folder, favourites, bin), already
 * sorted the way it is displayed. The viewer reads it so it can show and swipe through photos
 * immediately instead of querying the whole library again on every open.
 *
 * The grid screens keep it fresh: they observe MediaStore and update it on every change.
 */
object MediaListCache {
    private val lists = HashMap<String, List<MediaFile>>()

    private fun key(source: String, arg: String?) = "$source|${arg.orEmpty()}"

    @Synchronized
    fun put(source: String, arg: String?, list: List<MediaFile>) {
        lists[key(source, arg)] = list
    }

    @Synchronized
    fun get(source: String, arg: String?): List<MediaFile>? = lists[key(source, arg)]
}
