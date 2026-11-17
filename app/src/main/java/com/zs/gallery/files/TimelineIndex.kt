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

import com.zs.core.store.MediaFile
import com.zs.gallery.common.Mapped
import java.util.Calendar

/**
 * Maps between the lazy-grid item indices of the timeline and "rows" (grid lines), and knows the
 * month of every section. Used by the fast scroller and drag-to-select.
 *
 * Layout assumption (must match `Files`): each section emits a full-width header, its media items,
 * then a full-width spacer — i.e. `1 + size + 1` lazy items.
 */
internal class TimelineIndex(data: Mapped<MediaFile>) {

    val sections = data.size

    /** Lazy index of each section's header. */
    val startIndex = IntArray(sections)

    /** Number of media items in each section. */
    val sizes = IntArray(sections)

    /** `year * 12 + month` of each section's first item. */
    val monthKey = IntArray(sections)

    /** Timestamp of each section's first item. */
    val time = LongArray(sections)

    /** Media ids in display order, and the reverse lookup. */
    val ids: LongArray
    val flatIndexOf: HashMap<Long, Int>

    init {
        val calendar = Calendar.getInstance()
        var lazyIndex = 0
        var total = 0
        var i = 0
        for ((_, items) in data) {
            startIndex[i] = lazyIndex
            sizes[i] = items.size
            val t = items.firstOrNull()?.dateModified ?: 0L
            time[i] = t
            calendar.timeInMillis = t
            monthKey[i] = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)
            lazyIndex += items.size + 2
            total += items.size
            i++
        }
        ids = LongArray(total)
        flatIndexOf = HashMap(total * 2)
        var k = 0
        for ((_, items) in data) for (item in items) {
            ids[k] = item.id
            flatIndexOf[item.id] = k
            k++
        }
    }

    /** Section that contains the lazy item [lazyIndex]. */
    fun sectionOf(lazyIndex: Int): Int {
        if (sections == 0) return 0
        var lo = 0
        var hi = sections - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startIndex[mid] <= lazyIndex) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Row bookkeeping for a given number of [columns]. */
    inner class Rows(val columns: Int) {
        /** First row (header line) of every section. */
        val rowStart = IntArray(sections)
        val total: Int

        init {
            var row = 0
            for (s in 0 until sections) {
                rowStart[s] = row
                row += rowsOf(s)
            }
            total = row
        }

        private fun itemRows(s: Int) = (sizes[s] + columns - 1) / columns

        private fun rowsOf(s: Int) = 2 + itemRows(s)

        /** Grid line of lazy item [lazyIndex]. */
        fun rowOf(lazyIndex: Int): Int {
            if (sections == 0) return 0
            val s = sectionOf(lazyIndex)
            val offset = lazyIndex - startIndex[s]
            return rowStart[s] + when {
                offset <= 0 -> 0
                offset <= sizes[s] -> 1 + (offset - 1) / columns
                else -> 1 + itemRows(s)
            }
        }

        /** First lazy item placed on grid line [row]. */
        fun indexOfRow(row: Int): Int {
            if (sections == 0) return 0
            // last section whose first row <= row
            var lo = 0
            var hi = sections - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (rowStart[mid] <= row) lo = mid else hi = mid - 1
            }
            val r = row - rowStart[lo]
            val items = itemRows(lo)
            return startIndex[lo] + when {
                r <= 0 -> 0
                r <= items -> 1 + (r - 1) * columns
                else -> sizes[lo] + 1
            }
        }
    }
}
