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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zs.core.store.Folder
import com.zs.core.store.MediaFile
import com.zs.gallery.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Copying / moving runs here, not in a screen: it keeps going when you leave the screen or the
 * app, shows its progress in a notification (with Cancel), and finishes with a short summary.
 * A foreground service keeps the app alive meanwhile.
 *
 * Items a folder refuses (photos into Movies/...) come back as a folder sheet for just those -
 * right away if the app is open, or after tapping the notification.
 */
object FileJobs {
    private const val CHANNEL = "file_jobs"
    const val PROGRESS_ID = 4711
    private const val DONE_ID = 4712

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val jobs = HashSet<Job>()
    private val main = Handler(Looper.getMainLooper())

    /** The visible, unlocked activity, if any. */
    @Volatile private var visible: WeakReference<Activity>? = null

    /** Items left over after a job, waiting for the user to pick another folder. */
    private class Retry(
        val files: List<MediaFile>, val move: Boolean, val folders: List<Folder>,
        val note: String, val done: Int, val placed: List<String>,
    )

    @Volatile private var pendingRetry: Retry? = null

    /** Last progress notification (the service shows it when it starts). */
    @Volatile internal var current: Notification? = null

    // ---------------------------------------------------------------------------------------------

    fun onResumed(activity: Activity) {
        visible = WeakReference(activity)
        val retry = pendingRetry ?: return
        pendingRetry = null
        nm(activity).cancel(DONE_ID)
        runRetry(activity, retry)
    }

    fun onPaused(activity: Activity) {
        if (visible?.get() === activity) visible = null
    }

    /**
     * Starts moving / copying [files] into [target] in the background. Call it from the screen
     * (the app must be visible to start a foreground service). [done] / [placed] carry the counts
     * of earlier rounds (after "pick another folder").
     */
    fun start(
        context: Context, files: List<MediaFile>, target: String, move: Boolean,
        folders: List<Folder>, done: Int = 0, placed: List<String> = emptyList(),
    ) {
        if (files.isEmpty()) return
        val app = context.applicationContext
        channel(app)
        val verb = if (move) "Moving" else "Copying"
        val where = target.trimEnd('/')
        current = progress(app, "$verb ${plural(files.size)}", "to $where", 0, 0, true)
        runCatching {
            val intent = Intent(app, FileJobService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        }
        // Bigger jobs: say where to follow them.
        if (files.size > 5 || files.sumOf { it.size } > 50L * 1024 * 1024)
            toast(app, "$verb ${plural(files.size)} in the background – progress is in your notifications")

        val job = scope.launch {
            queue.withLock {
                val started = SystemClock.elapsedRealtime()
                var count = 0
                var last = 0L
                fun post(force: Boolean = false, build: () -> Notification) {
                    val now = SystemClock.elapsedRealtime()
                    if (!force && now - last < 400) return
                    last = now
                    current = build()
                    notify(app, PROGRESS_ID, current!!)
                }
                val failed = try {
                    if (move) {
                        FileOps.moveFiles(app.contentResolver, files, target) { n ->
                            count = n
                            post { progress(app, "Moving ${n + 1} of ${files.size}", "to $where", files.size, n, false) }
                        }
                    } else {
                        val total = files.sumOf { it.size }.coerceAtLeast(1)
                        var bytes = 0L
                        FileOps.copyFiles(
                            app.contentResolver, files, target,
                            onFile = { n -> count = n },
                            onBytes = { b ->
                                bytes += b
                                post {
                                    progress(
                                        app, "Copying ${(count + 1).coerceAtMost(files.size)} of ${files.size}",
                                        "to $where", 1000, (bytes * 1000 / total).toInt(), false
                                    )
                                }
                            }
                        )
                    }
                } catch (e: CancellationException) {
                    val past = if (move) "Moved" else "Copied"
                    finished(app, "${if (move) "Move" else "Copy"} cancelled", "$past $count of ${files.size}")
                    com.zs.gallery.people.FaceIndexer.changed()
                    throw e
                }
                com.zs.gallery.people.FaceIndexer.changed()
                val ok = files.size - failed.size
                val allDone = done + ok
                val allPlaced = if (ok > 0) (placed + where).distinct() else placed
                val past = if (move) "Moved" else "Copied"
                if (failed.isEmpty()) {
                    val text = "$past ${plural(allDone)} to ${allPlaced.joinToString(", ")}"
                    // Quick ones only need a toast; long ones also leave a "done" notification.
                    if (SystemClock.elapsedRealtime() - started > 3000) finished(app, "$past ${plural(allDone)}", "to ${allPlaced.joinToString(", ")}")
                    toast(app, text)
                } else {
                    val short = where.substringAfterLast('/')
                    val v = if (move) "move" else "copy"
                    val note = if (ok > 0)
                        "${plural(ok).cap()} ${past.lowercase()}. ${plural(failed.size).cap()} can't go to $short – pick another folder for them."
                    else "Can't $v ${if (failed.size == 1) "it" else "them"} to $short – pick another folder."
                    val retry = Retry(failed, move, folders, note, allDone, allPlaced)
                    val activity = visible?.get()
                    if (activity != null && !activity.isFinishing) main.post { runRetry(activity, retry) }
                    else {
                        pendingRetry = retry
                        finished(
                            app, "${plural(failed.size).cap()} can't go to $short",
                            "Tap to choose another folder", openApp = true
                        )
                    }
                }
            }
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion {
            val empty = synchronized(jobs) { jobs -= job; jobs.isEmpty() }
            if (empty) runCatching { app.stopService(Intent(app, FileJobService::class.java)) }
        }
    }

    fun cancelAll() {
        val all = synchronized(jobs) { jobs.toList() }
        all.forEach { it.cancel() }
        pendingRetry = null
    }

    private fun runRetry(activity: Activity, r: Retry) {
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            FileOps.continueMoveOrCopy(activity, r.files, r.move, r.folders, r.note, r.done, r.placed)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------------------

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm(app).createNotificationChannel(
            NotificationChannel(CHANNEL, "Copying & moving", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of copying and moving photos"
                setShowBadge(false)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(app: Context, id: Int, n: Notification) {
        runCatching { nm(app).notify(id, n) }
    }

    private fun openAppIntent(app: Context): PendingIntent? {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            app, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    internal fun progress(
        app: Context, title: String, text: String, max: Int, value: Int, indeterminate: Boolean,
    ): Notification {
        val cancel = PendingIntent.getService(
            app, 2, Intent(app, FileJobService::class.java).setAction(FileJobService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_copy)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, value, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(app))
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun finished(app: Context, title: String, text: String, openApp: Boolean = false) {
        nm(app).cancel(PROGRESS_ID)
        notify(
            app, DONE_ID,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_copy)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(app))
                .apply { if (openApp) setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
                .build()
        )
    }

    private fun toast(app: Context, text: String) = main.post {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    private fun plural(n: Int) = if (n == 1) "1 item" else "$n items"
    private fun String.cap() = replaceFirstChar { it.uppercase() }
}

/** Keeps the app alive while [FileJobs] copy / move; shows their progress notification. */
class FileJobService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.zs.gallery.CANCEL_FILE_JOBS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileJobs.cancelAll()
            return START_NOT_STICKY
        }
        val n = FileJobs.current ?: FileJobs.progress(this, "Preparing…", "", 0, 0, true)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(FileJobs.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(FileJobs.PROGRESS_ID, n)
        }
        return START_NOT_STICKY
    }

    // Android 15+: data-sync services get a daily time limit; the copy goes on without it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
