package com.zs.gallery.people

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device face detection + recognition, fully offline.
 *
 * - Detector: SCRFD-500M (InsightFace "buffalo_s" det_500m) - boxes + 5 landmarks.
 * - Embedder: MobileFaceNet trained with ArcFace (buffalo_s w600k_mbf) - 512 numbers per face;
 *   the same person gives similar numbers (cosine similarity).
 *
 * The decoding / alignment here was checked against a Python prototype on a test photo.
 */
internal class FaceEngine(context: Context) : Closeable {

    class Face(val score: Float, val box: RectF, val landmarks: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        // Two threads: scanning runs in the background and must not make the app stutter.
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detector = env.createSession(
        context.assets.open("faces/detector.onnx").use { it.readBytes() }, options
    )
    private val embedder = env.createSession(
        context.assets.open("faces/embedder.onnx").use { it.readBytes() }, options
    )
    private val detInput = detector.inputNames.first()
    private val embInput = embedder.inputNames.first()

    // Reused buffers (one scan thread).
    private val canvasBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SIZE * SIZE)
    private val detBuffer = FloatBuffer.allocate(3 * SIZE * SIZE)
    private val faceBitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
    private val facePixels = IntArray(112 * 112)
    private val embBuffer = FloatBuffer.allocate(3 * 112 * 112)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Faces in [bitmap] (any size), in [bitmap]'s pixel coordinates, best first. */
    fun detect(bitmap: Bitmap, threshold: Float = 0.5f): List<Face> {
        val scale = SIZE.toFloat() / max(bitmap.width, bitmap.height)
        val nw = (bitmap.width * scale).roundToInt().coerceIn(1, SIZE)
        val nh = (bitmap.height * scale).roundToInt().coerceIn(1, SIZE)
        // Letterbox into the top-left of a black 640 x 640 square.
        canvasBitmap.eraseColor(android.graphics.Color.BLACK)
        Canvas(canvasBitmap).drawBitmap(bitmap, null, Rect(0, 0, nw, nh), paint)
        canvasBitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        fill(detBuffer, pixels, SIZE * SIZE, 127.5f, 128f)

        val found = ArrayList<Face>()
        OnnxTensor.createTensor(env, detBuffer, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            detector.run(mapOf(detInput to input)).use { out ->
                // Outputs: scores 8/16/32, boxes 8/16/32, landmarks 8/16/32.
                for ((i, stride) in STRIDES.withIndex()) {
                    @Suppress("UNCHECKED_CAST")
                    val scores = out.get(i).value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val boxes = out.get(i + 3).value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val kps = out.get(i + 6).value as Array<FloatArray>
                    val n = SIZE / stride
                    for (k in scores.indices) {
                        val score = scores[k][0]
                        if (score < threshold) continue
                        // Two anchors per grid cell, row-major.
                        val cell = k / 2
                        val cx = (cell % n) * stride.toFloat()
                        val cy = (cell / n) * stride.toFloat()
                        val b = boxes[k]
                        val box = RectF(
                            (cx - b[0] * stride) / scale, (cy - b[1] * stride) / scale,
                            (cx + b[2] * stride) / scale, (cy + b[3] * stride) / scale
                        )
                        val p = kps[k]
                        val lm = FloatArray(10) { j ->
                            val c = if (j % 2 == 0) cx else cy
                            (c + p[j] * stride) / scale
                        }
                        found += Face(score, box, lm)
                    }
                }
            }
        }
        // Non-maximum suppression.
        found.sortByDescending { it.score }
        val keep = ArrayList<Face>()
        for (f in found) if (keep.none { iou(it.box, f.box) > 0.4f }) keep += f
        return keep
    }

    /** 512 numbers (unit length) describing the face with [landmarks] in [bitmap]. */
    fun embed(bitmap: Bitmap, landmarks: FloatArray): FloatArray {
        // Similarity transform (scale + rotation + shift) of the 5 landmarks onto ArcFace's
        // reference positions in a 112 x 112 crop (closed-form least squares).
        var msx = 0f; var msy = 0f; var mdx = 0f; var mdy = 0f
        for (j in 0 until 5) {
            msx += landmarks[2 * j]; msy += landmarks[2 * j + 1]
            mdx += REF[2 * j]; mdy += REF[2 * j + 1]
        }
        msx /= 5; msy /= 5; mdx /= 5; mdy /= 5
        var den = 0f; var na = 0f; var nb = 0f
        for (j in 0 until 5) {
            val x = landmarks[2 * j] - msx; val y = landmarks[2 * j + 1] - msy
            val u = REF[2 * j] - mdx; val v = REF[2 * j + 1] - mdy
            den += x * x + y * y
            na += x * u + y * v
            nb += x * v - y * u
        }
        val a = if (den > 0f) na / den else 1f
        val b = if (den > 0f) nb / den else 0f
        val tx = mdx - (a * msx - b * msy)
        val ty = mdy - (b * msx + a * msy)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))
        }
        faceBitmap.eraseColor(android.graphics.Color.BLACK)
        Canvas(faceBitmap).drawBitmap(bitmap, matrix, paint)
        faceBitmap.getPixels(facePixels, 0, 112, 0, 0, 112, 112)
        fill(embBuffer, facePixels, 112 * 112, 127.5f, 127.5f)
        OnnxTensor.createTensor(env, embBuffer, longArrayOf(1, 3, 112, 112)).use { input ->
            embedder.run(mapOf(embInput to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val e = (out.get(0).value as Array<FloatArray>)[0]
                var norm = 0f
                for (v in e) norm += v * v
                norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)
                return FloatArray(e.size) { e[it] / norm }
            }
        }
    }

    override fun close() {
        detector.close()
        embedder.close()
        options.close()
    }

    private fun fill(buffer: FloatBuffer, px: IntArray, count: Int, mean: Float, std: Float) {
        buffer.rewind()
        val array = buffer.array()
        for (i in 0 until count) {
            val c = px[i]
            array[i] = (((c shr 16) and 0xFF) - mean) / std               // R
            array[count + i] = (((c shr 8) and 0xFF) - mean) / std        // G
            array[2 * count + i] = ((c and 0xFF) - mean) / std            // B
        }
        buffer.rewind()
    }

    private fun iou(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    companion object {
        const val SIZE = 640
        private val STRIDES = intArrayOf(8, 16, 32)
        private val REF = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f,
            41.5493f, 92.3655f, 70.7299f, 92.2041f
        )

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
