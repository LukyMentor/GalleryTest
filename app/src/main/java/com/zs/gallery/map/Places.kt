/*
 * Personal-build additions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package com.zs.gallery.map

import android.content.Context
import com.zs.core.store.PhotoLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline "where is this": names the nearest of the ~7,000 cities bundled with the map
 * (no internet, no geocoding service).
 */
object Places {

    /** Great-circle distance in km. */
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6371.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** "Hanoi, Vietnam", "Near Hue, Vietnam" or "35 km from Sapa, Vietnam". */
    suspend fun describe(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.Default) {
            val world = WorldData.load(context)
            var best: WorldData.City? = null
            var bestKm = Double.MAX_VALUE
            for (city in world.cities) {
                val d = distanceKm(latitude, longitude, city.latitude, city.longitude)
                if (d < bestKm) {
                    bestKm = d
                    best = city
                }
            }
            val city = best ?: return@withContext null
            val where = if (city.country.isBlank() || city.country == city.name) city.name
            else "${city.name}, ${city.country}"
            when {
                bestKm < 10 -> where
                bestKm < 60 -> "Near $where"
                else -> "${bestKm.roundToInt()} km from $where"
            }
        }

    /** Place of a photo (by MediaStore id), or null if it has no location. */
    suspend fun describePhoto(context: Context, id: Long): String? {
        val location = PhotoLocations.locationOf(context, id) ?: return null
        return describe(context, location[0], location[1])
    }
}
