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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The offline world map bundled in the app (assets/world.bin, built by tools/build_world.py from
 * Natural Earth public-domain data). Everything is in normalized Web-Mercator coordinates:
 * x and y go from 0 to 1 across the whole world.
 */
internal class WorldData(
    val countries: List<Country>,
    val lakes: Shape,
    val cities: List<City>,
    val countryLabels: List<CountryLabel>,
    /** Rivers and roads, bundled into groups (same kind, zoom and area) drawn as one path each. */
    val lines: List<LineGroup> = emptyList(),
) {
    /**
     * Lines of one [kind] (0 motorway, 1 main road, 2 other road, 3 river) that appear from
     * [minZoom], in one patch of the world.
     */
    class LineGroup(
        val kind: Int, val minZoom: Int,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
        val path: Path,
    ) {
        fun intersects(left: Float, top: Float, right: Float, bottom: Float) =
            maxX >= left && minX <= right && maxY >= top && minY <= bottom
    }

    /** A closed polygon ring with its bounding box. */
    class Ring(
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
        val points: FloatArray,
    )

    /** A set of rings drawn as one path (even-odd, so holes stay holes). */
    class Shape(val rings: List<Ring>) {
        val minX = rings.minOfOrNull { it.minX } ?: 0f
        val minY = rings.minOfOrNull { it.minY } ?: 0f
        val maxX = rings.maxOfOrNull { it.maxX } ?: 0f
        val maxY = rings.maxOfOrNull { it.maxY } ?: 0f

        val path: Path by lazy {
            Path().apply {
                fillType = PathFillType.EvenOdd
                for (ring in rings) {
                    val p = ring.points
                    if (p.size < 4) continue
                    moveTo(p[0], p[1])
                    var i = 2
                    while (i < p.size) {
                        lineTo(p[i], p[i + 1])
                        i += 2
                    }
                    close()
                }
            }
        }

        fun intersects(left: Float, top: Float, right: Float, bottom: Float) =
            rings.isNotEmpty() && maxX >= left && minX <= right && maxY >= top && minY <= bottom
    }

    /** One country in three levels of detail: coarse (world view), mid, fine (zoomed in). */
    class Country(val lods: Array<Shape>)

    class City(val x: Float, val y: Float, val minZoom: Int, val name: String, val country: String) {
        /** Geographic position (from the mercator coordinates). */
        val longitude: Double get() = x * 360.0 - 180.0
        val latitude: Double
            get() = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * y))))
    }

    /** A country name, shown between [minZoom] and [maxZoom]. */
    class CountryLabel(val x: Float, val y: Float, val minZoom: Int, val maxZoom: Int, val name: String)

    companion object {
        @Volatile
        private var cached: WorldData? = null

        suspend fun load(context: Context): WorldData {
            cached?.let { return it }
            return withContext(Dispatchers.IO) {
                cached ?: parse(context.assets.open("world.bin").use { it.readBytes() })
                    .also { cached = it }
            }
        }

        private fun parse(bytes: ByteArray): WorldData {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            val format = String(magic)
            check(format == "MGW4" || format == "MGW5") { "Unknown world map format" }

            fun rings(): List<Ring> {
                val count = b.int
                return List(count) {
                    val minX = b.float
                    val minY = b.float
                    val maxX = b.float
                    val maxY = b.float
                    val n = b.int
                    val points = FloatArray(n * 2)
                    b.asFloatBuffer().get(points)
                    b.position(b.position() + n * 8)
                    Ring(minX, minY, maxX, maxY, points)
                }
            }

            val countries = List(b.int) {
                Country(arrayOf(Shape(rings()), Shape(rings()), Shape(rings())))
            }
            val lakes = Shape(rings())
            val cities = List(b.int) {
                val x = b.float
                val y = b.float
                val minZoom = b.get().toInt() and 0xFF
                val len = b.short.toInt() and 0xFFFF
                val name = ByteArray(len).also { b.get(it) }
                val countryLen = b.short.toInt() and 0xFFFF
                val country = ByteArray(countryLen).also { b.get(it) }
                City(x, y, minZoom, String(name, Charsets.UTF_8), String(country, Charsets.UTF_8))
            }
            val labels = List(b.int) {
                val x = b.float
                val y = b.float
                val minZoom = b.get().toInt() and 0xFF
                val maxZoom = b.get().toInt() and 0xFF
                val len = b.short.toInt() and 0xFFFF
                val name = ByteArray(len).also { b.get(it) }
                CountryLabel(x, y, minZoom, maxZoom, String(name, Charsets.UTF_8))
            }
            // MGW5: rivers + roads, grouped by kind, zoom and a 32 x 32 grid of the world.
            val lines = ArrayList<LineGroup>()
            if (format == "MGW5") {
                class Acc(val kind: Int, val zoom: Int) {
                    val path = Path()
                    var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
                }
                val groups = HashMap<Long, Acc>()
                repeat(b.int) {
                    val minX = b.float; val minY = b.float; val maxX = b.float; val maxY = b.float
                    val zoom = b.get().toInt() and 0xFF
                    val kind = b.get().toInt() and 0xFF
                    val n = b.int
                    val gx = (((minX + maxX) / 2f) * 32).toInt().coerceIn(0, 31)
                    val gy = (((minY + maxY) / 2f) * 32).toInt().coerceIn(0, 31)
                    val key = (kind.toLong() shl 40) or (zoom.toLong() shl 20) or (gx.toLong() shl 8) or gy.toLong()
                    val acc = groups.getOrPut(key) { Acc(kind, zoom) }
                    for (i in 0 until n) {
                        val x = b.float; val y = b.float
                        if (i == 0) acc.path.moveTo(x, y) else acc.path.lineTo(x, y)
                    }
                    acc.minX = minOf(acc.minX, minX); acc.minY = minOf(acc.minY, minY)
                    acc.maxX = maxOf(acc.maxX, maxX); acc.maxY = maxOf(acc.maxY, maxY)
                }
                // Rivers first, then roads from minor to major (major on top).
                groups.values.sortedWith(compareBy<Acc> { if (it.kind == 3) 0 else 1 }.thenByDescending { it.kind })
                    .mapTo(lines) { LineGroup(it.kind, it.zoom, it.minX, it.minY, it.maxX, it.maxY, it.path) }
            }
            return WorldData(countries, lakes, cities, labels, lines)
        }
    }
}
