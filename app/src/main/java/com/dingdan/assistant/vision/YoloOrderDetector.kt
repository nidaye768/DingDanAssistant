package com.dingdan.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * On-device YOLO11n order-card detector.
 * Model is downloaded once from the project's versioned GitHub Release and cached locally.
 * Input classes: 0=完整订单卡片, 1=部分露出订单卡片.
 */
object YoloOrderDetector {
    private const val MODEL_URL = "https://github.com/nidaye768/dingdan-yolo-dataset/releases/download/yolo-model-v1/dingdan_yolo11n.onnx"
    private const val INPUT_SIZE = 640
    private const val CONFIDENCE = 0.30f
    private const val IOU_THRESHOLD = 0.45f

    data class Detection(
        val classId: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val className: String get() = if (classId == 0) "完整订单卡片" else "部分露出订单卡片"
    }

    data class DetectionResult(
        val detections: List<Detection>,
        val modelPath: String,
        val inferenceMs: Long
    )

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var cachedSession: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        cachedSession?.let { return it }
        val model = File(context.filesDir, "models/dingdan_yolo11n.onnx")
        if (!model.exists() || model.length() < 1024) {
            model.parentFile?.mkdirs()
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DingDanAssistant-Android")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("模型下载失败：HTTP ${connection.responseCode}。请先在 GitHub Actions 完成 ONNX 导出。")
                }
                val temp = File(model.parentFile, model.name + ".download")
                connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                check(temp.length() > 1024) { "下载的模型文件无效" }
                if (!temp.renameTo(model)) {
                    temp.copyTo(model, overwrite = true)
                    temp.delete()
                }
            } finally { connection.disconnect() }
        }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        return env.createSession(model.absolutePath, options).also { cachedSession = it }
    }

    fun detect(context: Context, bitmap: Bitmap): DetectionResult {
        val modelSession = session(context.applicationContext)
        val start = System.currentTimeMillis()
        val scale = min(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val resizedW = max(1, (bitmap.width * scale).toInt())
        val resizedH = max(1, (bitmap.height * scale).toInt())
        val padX = (INPUT_SIZE - resizedW) / 2
        val padY = (INPUT_SIZE - resizedH) / 2
        val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inputBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), paint)
        if (resized !== bitmap) resized.recycle()

        val input = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = inputBitmap.getPixel(x, y)
                val index = y * INPUT_SIZE + x
                input[index] = Color.red(pixel) / 255f
                input[plane + index] = Color.green(pixel) / 255f
                input[2 * plane + index] = Color.blue(pixel) / 255f
            }
        }
        inputBitmap.recycle()

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        inputBuffer.put(input)
        inputBuffer.rewind()
        val tensor = OnnxTensor.createTensor(env, inputBuffer, shape)
        val outputs = modelSession.run(mapOf(modelSession.inputNames.iterator().next() to tensor))
        try {
            val raw = outputs[0].value as? Array<*>
                ?: throw IllegalStateException("YOLO 输出格式不正确")
            val channels = raw.firstOrNull() as? Array<*>
                ?: throw IllegalStateException("YOLO 输出通道缺失")
            if (channels.size < 6) throw IllegalStateException("YOLO 输出类别数不足")
            val values = channels.map { it as FloatArray }
            val count = values[0].size
            val candidates = ArrayList<Detection>()
            for (i in 0 until count) {
                var classId = 0
                var score = values[4][i]
                if (values[5][i] > score) { classId = 1; score = values[5][i] }
                if (score < CONFIDENCE) continue
                val cx = values[0][i]
                val cy = values[1][i]
                val w = values[2][i]
                val h = values[3][i]
                val x1 = ((cx - w / 2f - padX) / scale / bitmap.width).coerceIn(0f, 1f)
                val y1 = ((cy - h / 2f - padY) / scale / bitmap.height).coerceIn(0f, 1f)
                val x2 = ((cx + w / 2f - padX) / scale / bitmap.width).coerceIn(0f, 1f)
                val y2 = ((cy + h / 2f - padY) / scale / bitmap.height).coerceIn(0f, 1f)
                if (x2 - x1 >= 0.04f && y2 - y1 >= 0.04f) {
                    candidates += Detection(classId, score, x1, y1, x2, y2)
                }
            }
            val final = mutableListOf<Detection>()
            candidates.sortedByDescending { it.confidence }.forEach { candidate ->
                if (final.none { it.classId == candidate.classId && iou(it, candidate) > IOU_THRESHOLD }) {
                    final += candidate
                }
            }
            return DetectionResult(final, File(context.filesDir, "models/dingdan_yolo11n.onnx").absolutePath,
                System.currentTimeMillis() - start)
        } finally {
            outputs.close()
            tensor.close()
        }
    }

    fun crop(bitmap: Bitmap, detection: Detection): Bitmap {
        val left = (detection.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (detection.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (detection.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (detection.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun iou(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return intersection / (areaA + areaB - intersection + 1e-6f)
    }
}
