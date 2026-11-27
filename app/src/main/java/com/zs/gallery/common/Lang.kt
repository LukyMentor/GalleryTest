/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.common

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Czech / English. The app's language (Settings > Language, or Android's per-app language);
 * texts written in code use [tr], the rest comes from strings.xml / values-cs-rCZ.
 */
object Lang {
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val CZECH = "cs-CZ"

    val czech: Boolean get() = java.util.Locale.getDefault().language == "cs"

    /** The chosen language tag ("" = like the phone). */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return SYSTEM
        val list = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (list.isEmpty) SYSTEM else list[0].toLanguageTag().let {
            if (it.startsWith("cs")) CZECH else if (it.startsWith("en")) ENGLISH else it
        }
    }

    /** Switches the app's language; Android restarts the screens in the new one. */
    fun set(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    val supported get() = Build.VERSION.SDK_INT >= 33
}

/** [en] or [cs], by the app's language. */
fun tr(en: String, cs: String): String = if (Lang.czech) cs else en

/** Czech plural: 1 [one], 2–4 [few], else [many]. */
fun czPlural(n: Int, one: String, few: String, many: String) = when {
    n == 1 -> one
    n in 2..4 -> few
    else -> many
}

/** "1 item" / "5 items" — "1 položka" / "3 položky" / "5 položek". */
fun itemsText(n: Int): String =
    if (Lang.czech) "$n ${czPlural(n, "položka", "položky", "položek")}"
    else if (n == 1) "1 item" else "$n items"

/** "1 photo" / "5 photos" — "1 fotka" / "3 fotky" / "5 fotek". */
fun photosText(n: Int): String =
    if (Lang.czech) "$n ${czPlural(n, "fotka", "fotky", "fotek")}"
    else if (n == 1) "1 photo" else "$n photos"
