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

import android.content.Context
import android.os.Build
import androidx.startup.Initializer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the device (the app has no online crash reporting), so it can be
 * copied from Settings and sent to whoever fixes it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "last_crash.seen"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val text = buildString {
            appendLine("Gallery M crash")
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("App: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(context.filesDir, FILE).writeText(text)
        File(context.filesDir, SEEN).delete()
    }

    /** The last crash report, or null if there is none. */
    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, SEEN).delete()
    }

    /** True once per crash: lets the app mention it on the next launch. */
    fun consumeUnseen(context: Context): Boolean {
        if (!File(context.filesDir, FILE).exists()) return false
        val seen = File(context.filesDir, SEEN)
        if (seen.exists()) return false
        seen.writeText("1")
        return true
    }
}

/** Installs [CrashLog] as early as possible (androidx.startup). */
class CrashLogInitializer : Initializer<Unit> {
    override fun create(context: Context) = CrashLog.install(context)
    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf()
}
