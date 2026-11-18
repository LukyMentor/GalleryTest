package com.zs.gallery.people

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Scans the photo library for faces in the background (one low-priority thread, while the app
 * process is alive) and groups them into people. Progress is in [status] (shown on the People
 * screen); [version] changes whenever the groups change.
 */
object FaceIndexer {

    data class Status(
        val running: Boolean = false,
        val paused: Boolean = false,
        val total: Int = 0,
        val scanned: Int = 0,
        val faces: Int = 0,
        val people: Int = 0,
        val msPerPhoto: Int = 0,
        val current: String? = null,
        val error: String? = null,
        val note: String? = null,
        /** Videos (up to [MAX_VIDEO_MS]): scanned after the photos, when switched on. */
        val videos: Boolean = false,
        val videoTotal: Int = 0,
        val videoScanned: Int = 0,
        val msPerVideo: Int = 0,
    )

    /** Face must look at least this similar to a group's average to join it. */
    private const val ASSIGN = 0.42f
    /** Groups whose averages are this similar are the same person. */
    private const val MERGE = 0.55f
    /** Photos are decoded with this longer side for scanning. */
    private const val DECODE = 1280
    private const val MIN_FACE_PX = 36f
    private const val MIN_SCORE = 0.6f
    private const val MAX_FACES = 12
    const val MAX_VIDEO_MS = 5 * 60_000L
    /** One frame every this many ms, at most [MAX_FRAMES] per video. */
    private const val FRAME_STEP_MS = 2500L
    private const val MAX_FRAMES = 30
    /** Video frames are blurrier: stricter detection. */
    private const val VIDEO_MIN_SCORE = 0.7f
    private const val VIDEO_MIN_FACE_PX = 48f
    /** Same person within one video. */
    private const val SAME_IN_VIDEO = 0.5f

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "face-scan")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    @Volatile
    private var store: FaceStore? = null

    internal fun store(context: Context): FaceStore = store ?: synchronized(this) {
        store ?: FaceStore(context).also { store = it }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("faces", Context.MODE_PRIVATE)

    fun isPaused(context: Context) = prefs(context).getBoolean("paused", false)

    fun scansVideos(context: Context) = prefs(context).getBoolean("videos", false)

    fun setScanVideos(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("videos", on).apply()
        _status.update { it.copy(videos = on) }
        if (on) start(context)
    }

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean("paused", paused).apply()
        _status.update { it.copy(paused = paused) }
        if (paused) job?.cancel() else start(context)
    }

    /** Called by the UI after changes it made (rename, move, ...). */
    fun changed() = _version.update { it + 1 }

    /** Starts (or continues) scanning, unless paused or already running. */
    fun start(context: Context) {
        val app = context.applicationContext
        val paused = isPaused(app)
        _status.update { it.copy(paused = paused, videos = scansVideos(app)) }
        if (paused || job?.isActive == true) return
        if (!hasPermission(app)) {
            _status.update { it.copy(error = "No access to photos yet") }
            return
        }
        job = scope.launch {
            _status.update { it.copy(running = true, error = null, note = "Looking for new photos…") }
            try {
                run(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.update { it.copy(error = e.toString()) }
            } finally {
                _status.update { it.copy(running = false, current = null) }
            }
        }
    }

    /** Re-groups everything from scratch (keeps the user's own moves). */
    fun regroup(context: Context) {
        val app = context.applicationContext
        scope.launch {
            _status.update { it.copy(note = "Re-grouping…") }
            val store = store(app)
            store.resetGrouping()
            group(store)
            _status.update { it.copy(note = "Re-grouped") }
        }
    }

    /** Forgets everything and scans again. */
    fun reset(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        scope.launch {
            store(app).clearAll()
            _status.update { Status(paused = isPaused(app), note = "Reset - scanning again") }
            changed()
            start(app)
        }
    }

    private fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private class Photo(val id: Long, val modified: Long, val name: String)

    private fun listPhotos(context: Context): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2).orEmpty()
                // Screenshots and tiny images aren't worth scanning.
                if (path.contains("Screenshots", ignoreCase = true)) continue
                val w = c.getInt(3)
                val h = c.getInt(4)
                if (w in 1..199 || h in 1..199) continue
                val mime = c.getString(5).orEmpty()
                if (!mime.startsWith("image/") || mime == "image/gif") continue
                list += Photo(c.getLong(0), c.getLong(1), c.getString(6).orEmpty())
            }
        }
        return list
    }

    private fun listVideos(context: Context): List<Photo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2).orEmpty()
                // Screen recordings aren't worth scanning.
                if (path.contains("Screen", ignoreCase = true) && path.contains("record", ignoreCase = true)) continue
                val duration = c.getLong(3)
                if (duration <= 0 || duration > MAX_VIDEO_MS) continue
                list += Photo(c.getLong(0), c.getLong(1), c.getString(4).orEmpty())
            }
        }
        return list
    }

    private suspend fun run(context: Context) {
        val store = store(context)
        val photos = listPhotos(context)
        val videos = if (scansVideos(context)) listVideos(context) else emptyList()
        val known = store.scanned()
        val existing = photos.mapTo(HashSet()) { it.id }.apply { videos.forEach { add(it.id) } }
        // Forget removed photos (and videos, but only while video scanning is on).
        store.removeMedia(known.keys.filter { it !in existing && (videos.isNotEmpty() || !store.isVideo(it)) })
        val todo = photos.filter { known[it.id] != it.modified }
        _status.update {
            it.copy(
                total = photos.size, scanned = photos.size - todo.size,
                faces = store.faceCount(), note = if (todo.isEmpty()) "Up to date" else "Scanning"
            )
        }
        val videoTodo = videos.filter { known[it.id] != it.modified }
        _status.update { it.copy(videoTotal = videos.size, videoScanned = videos.size - videoTodo.size) }
        if (todo.isEmpty() && videoTodo.isEmpty()) {
            group(store)
            store.dropOrphanCrops()
            return
        }
        val engine = FaceEngine(context)
        try {
            var pending = 0
            var sinceMerge = 0
            var time = 0L
            var done = 0
            for (photo in todo) {
                coroutineContext.ensureActive()
                // Let a "Re-group" request in between photos.
                kotlinx.coroutines.yield()
                val start = SystemClock.elapsedRealtime()
                _status.update { it.copy(current = photo.name) }
                val faces = runCatching { process(context, engine, photo.id) }.getOrDefault(emptyList())
                store.saveScan(photo.id, photo.modified, faces)
                done++
                time += SystemClock.elapsedRealtime() - start
                pending += faces.size
                sinceMerge += faces.size
                _status.update {
                    it.copy(
                        scanned = it.scanned + 1, faces = it.faces + faces.size,
                        msPerPhoto = (time / done).toInt()
                    )
                }
                // Group as we go, so people show up while the scan is still running.
                if (pending >= 40) {
                    // Joining groups compares every pair: only now and then.
                    val merge = sinceMerge >= 600
                    group(store, merge)
                    if (merge) sinceMerge = 0
                    pending = 0
                }
            }
            group(store)
            // Then the videos (only once every photo is done).
            var vTime = 0L
            var vDone = 0
            for (video in videoTodo) {
                coroutineContext.ensureActive()
                kotlinx.coroutines.yield()
                val start = SystemClock.elapsedRealtime()
                _status.update { it.copy(current = video.name, note = "Scanning videos") }
                val faces = runCatching { processVideo(context, engine, video.id) }.getOrDefault(emptyList())
                val ids = store.saveScan(video.id, video.modified, faces.map { it.first }, kind = 1)
                ids.forEachIndexed { i, id -> runCatching { store.saveCrop(id, faces[i].second) } }
                faces.forEach { it.second.recycle() }
                vDone++
                vTime += SystemClock.elapsedRealtime() - start
                _status.update {
                    it.copy(videoScanned = it.videoScanned + 1, faces = it.faces + faces.size,
                        msPerVideo = (vTime / vDone).toInt())
                }
                pending += faces.size
                if (pending >= 20) {
                    group(store, merge = false)
                    pending = 0
                }
            }
            group(store)
            store.dropOrphanCrops()
            _status.update { it.copy(note = "Up to date") }
        } finally {
            engine.close()
        }
    }

    /**
     * Faces in a video: a frame every [FRAME_STEP_MS] (at most [MAX_FRAMES]); the same person's
     * faces across frames become one entry (average embedding, best frame's crop).
     */
    private fun processVideo(context: Context, engine: FaceEngine, id: Long): List<Pair<Pair<RectF, Pair<Float, FloatArray>>, Bitmap>> {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val retriever = android.media.MediaMetadataRetriever()
        class Seen(val box: RectF, val score: Float, val embedding: FloatArray, val crop: Bitmap, val quality: Float)
        val seen = ArrayList<Seen>()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return emptyList()
            val count = (duration / FRAME_STEP_MS).toInt().coerceIn(1, MAX_FRAMES)
            for (i in 0 until count) {
                val t = (duration * (i + 0.5f) / count).toLong()
                val raw = retriever.getScaledFrameAtTime(
                    t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, DECODE, DECODE
                ) ?: continue
                val frame = if (raw.config == Bitmap.Config.ARGB_8888) raw
                else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
                try {
                    val w = frame.width.toFloat()
                    val h = frame.height.toFloat()
                    for (face in engine.detect(frame).take(MAX_FACES)) {
                        if (face.score < VIDEO_MIN_SCORE || min(face.box.width(), face.box.height()) < VIDEO_MIN_FACE_PX) continue
                        val e = engine.embed(frame, face.landmarks)
                        val box = RectF(
                            (face.box.left / w).coerceIn(0f, 1f), (face.box.top / h).coerceIn(0f, 1f),
                            (face.box.right / w).coerceIn(0f, 1f), (face.box.bottom / h).coerceIn(0f, 1f)
                        )
                        seen += Seen(box, face.score, e, crop(frame, face.box), face.score * face.box.width() * face.box.height())
                    }
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        // Group the faces of the same person within this video.
        val groups = ArrayList<MutableList<Seen>>()
        for (s in seen) {
            val g = groups.firstOrNull { grp -> FaceEngine.similarity(grp.first().embedding, s.embedding) >= SAME_IN_VIDEO }
            if (g != null) g += s else groups += mutableListOf(s)
        }
        // Someone seen in a single frame of a longer video is usually a passer-by / misdetection.
        val minFrames = if (seen.size <= 3) 1 else 2
        val result = ArrayList<Pair<Pair<RectF, Pair<Float, FloatArray>>, Bitmap>>()
        for (g in groups) {
            if (g.size < minFrames) continue
            val best = g.maxBy { it.quality }
            val mean = FloatArray(best.embedding.size)
            for (s in g) for (i in mean.indices) mean[i] += s.embedding[i]
            var n = 0f
            for (v in mean) n += v * v
            n = sqrt(n).coerceAtLeast(1e-6f)
            for (i in mean.indices) mean[i] /= n
            result += (best.box to (best.score to mean)) to best.crop
        }
        for (s in seen) if (result.none { it.second === s.crop }) s.crop.recycle()
        return result.take(MAX_FACES)
    }

    /** Square picture around a face (for the person bubbles of video faces). */
    private fun crop(frame: Bitmap, box: RectF): Bitmap {
        val side = (max(box.width(), box.height()) * 1.5f).coerceAtMost(min(frame.width, frame.height).toFloat())
        val left = (box.centerX() - side / 2).coerceIn(0f, frame.width - side)
        val top = (box.centerY() - side / 2).coerceIn(0f, frame.height - side)
        val out = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            frame,
            android.graphics.Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt()),
            android.graphics.Rect(0, 0, 192, 192),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /** Finds the faces in one photo: (box in 0..1, (score, embedding)). */
    private fun process(context: Context, engine: FaceEngine, id: Long): List<Pair<RectF, Pair<Float, FloatArray>>> {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, DECODE.toFloat() / max(w, h))
            if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
        try {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            return engine.detect(bitmap)
                .filter { it.score >= MIN_SCORE && min(it.box.width(), it.box.height()) >= MIN_FACE_PX }
                .take(MAX_FACES)
                .map { face ->
                    val box = RectF(
                        (face.box.left / w).coerceIn(0f, 1f), (face.box.top / h).coerceIn(0f, 1f),
                        (face.box.right / w).coerceIn(0f, 1f), (face.box.bottom / h).coerceIn(0f, 1f)
                    )
                    box to (face.score to engine.embed(bitmap, face.landmarks))
                }
        } finally {
            bitmap.recycle()
        }
    }

    // --- grouping ---------------------------------------------------------------------------------

    private class Centre(val sum: FloatArray, var count: Int) {
        val unit = FloatArray(sum.size)
        fun refresh() {
            var n = 0f
            for (v in sum) n += v * v
            n = sqrt(n).coerceAtLeast(1e-6f)
            for (i in sum.indices) unit[i] = sum[i] / n
        }
    }

    private fun centres(store: FaceStore): HashMap<Long, Centre> {
        val map = HashMap<Long, Centre>()
        for ((person, list) in store.embeddingsByPerson()) {
            val sum = FloatArray(list.first().size)
            for (e in list) for (i in e.indices) sum[i] += e[i]
            map[person] = Centre(sum, list.size).also { it.refresh() }
        }
        return map
    }

    /** Puts new faces into the closest group (or a new one), then joins groups of one person. */
    @Synchronized
    private fun group(store: FaceStore, merge: Boolean = true) {
        val centres = centres(store)
        val ungrouped = store.ungrouped()
        if (ungrouped.isNotEmpty()) store.inTransaction {
            for ((faceId, e) in ungrouped) {
                var best = -1L
                var bestSim = ASSIGN
                for ((person, c) in centres) {
                    val s = FaceEngine.similarity(c.unit, e)
                    if (s >= bestSim) { bestSim = s; best = person }
                }
                if (best < 0) {
                    best = store.newPerson()
                    centres[best] = Centre(e.copyOf(), 1).also { it.refresh() }
                } else {
                    val c = centres.getValue(best)
                    for (i in e.indices) c.sum[i] += e[i]
                    c.count++
                    c.refresh()
                }
                store.assign(faceId, best)
            }
        }
        if (!merge) {
            _status.update { it.copy(people = store.people().count { p -> p.photos >= 3 }) }
            changed()
            return
        }
        // Join groups that are clearly the same person (never two different names).
        val names = store.names()
        val ids = centres.keys.toList()
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            return r
        }
        val groupName = HashMap<Long, String?>()
        for (id in ids) { parent[id] = id; groupName[id] = names[id] }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            if (FaceEngine.similarity(centres.getValue(a).unit, centres.getValue(b).unit) < MERGE) continue
            val ra = find(a); val rb = find(b)
            if (ra == rb) continue
            val na = groupName[ra]; val nb = groupName[rb]
            if (na != null && nb != null && na != nb) continue
            // The bigger group stays.
            val (keep, gone) = if (centres.getValue(ra).count >= centres.getValue(rb).count) ra to rb else rb to ra
            parent[gone] = keep
            groupName[keep] = groupName[keep] ?: groupName[gone]
        }
        for (id in ids) {
            val root = find(id)
            if (root != id) store.merge(id, root)
        }
        store.dropEmptyPersons()
        _status.update { it.copy(people = store.people().count { p -> p.photos >= 3 }) }
        changed()
    }
}
