package com.dingdan.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dingdan.assistant.vision.OrderCardSegmenter
import com.dingdan.assistant.vision.YoloOrderDetector
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** V1.3 Demo: full-image segmentation and manual crop share one OCR/API pipeline. */
class ImageTestActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private var source: Bitmap? = null
    private lateinit var preview: CropSelectionView
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var history: TextView
    private lateinit var pick: Button
    private lateinit var auto: Button
    private lateinit var beginCrop: Button
    private lateinit var runCrop: Button
    private lateinit var importModel: Button
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) load(uri)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) installModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshHistory()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun makeLabel(s: String, size: Float = 13f, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(0xFF17212B.toInt())
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
    private fun button(s: String, color: Int, action: () -> Unit) =
        Button(this).apply {
            text = s; setTextColor(-1)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            setOnClickListener { action() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF3F5F7.toInt()) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(0xFF172A3A.toInt())
        }
        header.addView(TextView(this).apply { text = "图片导入测试工作台"; textSize = 21f; setTextColor(-1); setTypeface(null, 1) })
        header.addView(TextView(this).apply { text = "V1.3 Demo｜整图自动分割 + 手动框选"; textSize = 12f; setTextColor(0xFFD5DEE4.toInt()) })
        root.addView(header)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(makeLabel("1. 导入订单图片", 17f, true))
        pick = button("从相册选择图片", 0xFFE45B42.toInt()) { if (!busy) picker.launch("image/*") }
        body.addView(pick)
        body.addView(makeLabel("使用系统图片选择器，不申请整库照片权限。", 12f))
        body.addView(makeLabel("2. 图片预览 / 手动框选", 17f, true))
        preview = CropSelectionView(this)
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(300)))
        body.addView(makeLabel("点击开始框选，然后在图片上拖动圈出一张订单卡片。", 12f))
        beginCrop = button("开始手动框选", 0xFF246B8E.toInt()) {
            if (source == null || busy) { status.text = "请先选择图片" } else {
                preview.beginSelection(); status.text = "请拖动框选一张订单卡片"
            }
        }
        body.addView(beginCrop)
        runCrop = button("识别框选区域", 0xFF426B55.toInt()) {
            if (!busy) {
                val crop = preview.selectedBitmap()
                if (crop == null) status.text = "没有有效框选区域，请重新框选"
                else runPipeline(listOf(crop), "手动框选", source)
            }
        }
        body.addView(runCrop)
        body.addView(makeLabel("3. YOLO 模型", 17f, true))
        body.addView(makeLabel("模型仓库目前为私有仓库。请先从 GitHub 私有 Release 下载 ONNX 文件，再导入本机；不需要公开订单图片或仓库。", 12f))
        importModel = button("导入 YOLO ONNX 模型文件", 0xFF426B55.toInt()) {
            if (!busy) modelPicker.launch(arrayOf("application/octet-stream", "application/onnx", "*/*"))
        }
        body.addView(importModel)
        body.addView(makeLabel("4. 自动识别整张图片", 17f, true))
        auto = button("整图 YOLO 自动检测并识别", 0xFF246B8E.toInt()) {
            val image = source
            if (image == null) status.text = "请先选择图片" else if (!busy) autoSegment(image)
        }
        body.addView(auto)
        body.addView(makeLabel("处理状态", 17f, true))
        status = makeLabel("等待选择图片")
        body.addView(status)
        body.addView(makeLabel("OCR / 模型分析结果", 17f, true))
        output = makeLabel("尚无识别结果")
        output.setPadding(dp(10), dp(10), dp(10), dp(10)); output.setBackgroundColor(-1)
        body.addView(output)
        body.addView(makeLabel("最近测试记录（本机保存）", 17f, true))
        history = makeLabel("暂无记录")
        history.setPadding(dp(10), dp(10), dp(10), dp(10)); history.setBackgroundColor(-1)
        body.addView(history)
        setContentView(root)
    }

    private fun load(uri: Uri) {
        status.text = "正在读取图片…"
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("无法读取图片")
                }
                require(bmp.width >= 100 && bmp.height >= 100) { "图片尺寸过小" }
                source?.recycle(); source = bmp
                preview.setBitmap(bmp)
                preview.setDetections(emptyList())
                output.text = "图片载入成功：" + bmp.width + " × " + bmp.height + "\n请选择自动分割或手动框选识别。"
                status.text = "图片载入成功"
            } catch (e: Exception) { status.text = "图片读取失败：" + e.localizedMessage }
        }
    }

    private fun installModel(uri: Uri) {
        status.text = "正在导入 ONNX 模型…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "models")
                    check(dir.mkdirs() || dir.isDirectory) { "无法创建模型目录" }
                    val target = File(dir, "dingdan_yolo11n.onnx")
                    val temp = File(dir, "dingdan_yolo11n.onnx.tmp")
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选模型文件")
                    check(temp.length() > 1024) { "文件太小，不是有效的 ONNX 模型" }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                }
                status.text = "YOLO 模型导入成功，可以开始图片检测"
                output.text = "模型已安装到本机私有目录。首次推理会初始化 ONNX Runtime。"
            } catch (e: Exception) {
                status.text = "模型导入失败：" + e.localizedMessage
            }
        }
    }

    private fun autoSegment(bitmap: Bitmap) {
        if (busy) return
        setBusy(true)
        status.text = "正在加载 YOLO 模型并检测订单卡片…"
        scope.launch {
            try {
                var yoloError = ""
                val yolo = try {
                    withContext(Dispatchers.IO) { YoloOrderDetector.detect(this@ImageTestActivity, bitmap) }
                } catch (e: Exception) {
                    yoloError = e.localizedMessage ?: e.javaClass.simpleName
                    null
                }
                if (yolo != null && yolo.detections.isNotEmpty()) {
                    val detections = yolo.detections.take(8)
                    preview.setDetections(detections)
                    val targets = detections.map { YoloOrderDetector.crop(bitmap, it) }
                    val note = buildString {
                        appendLine("检测引擎：YOLO11n ONNX（本机推理）")
                        appendLine("模型路径：缓存目录 / models/dingdan_yolo11n.onnx")
                        appendLine("推理耗时：${yolo.inferenceMs} ms")
                        appendLine("检测框数量：${yolo.detections.size}")
                        detections.forEachIndexed { index, item ->
                            appendLine("框 ${index + 1}：${item.className}｜置信度 ${"%.1f".format(item.confidence * 100)}%｜位置 x=${"%.3f".format(item.left)}, y=${"%.3f".format(item.top)}, w=${"%.3f".format(item.right-item.left)}, h=${"%.3f".format(item.bottom-item.top)}")
                        }
                        appendLine("本轮最多处理前8个检测框；部分露出卡片会单独标记，不直接视为完整订单。")
                    }
                    runPipeline(targets, "YOLO 自动检测（${targets.size}张）", bitmap, note)
                } else {
                    preview.setDetections(emptyList())
                    val result = withContext(Dispatchers.Default) {
                        OrderCardSegmenter.process(bitmap, OrderCardSegmenter.Config(
                            screenRoi = OrderCardSegmenter.NormalizedRect(0f, 0f, 1f, 1f)
                        ))
                    }
                    val cards = result.cards.take(8).map { it.bitmap }
                    val targets = if (cards.isEmpty()) listOf(bitmap) else cards
                    val note = "YOLO 未产出检测框，已回退到红色标题栏规则。" +
                        if (yoloError.isNotBlank()) "\nYOLO异常：" + yoloError else "\nYOLO结果：未检测到目标" +
                        "\n分割说明：" + result.reason +
                        "\n红色标题栏：" + result.headerBands.size + " 条；规则卡片：" + result.cards.size +
                        " 张。" + if (cards.isEmpty()) "\n未检测到卡片，改用整图 OCR 兜底。" else
                        "\n本轮最多处理前8张卡片。"
                    runPipeline(targets, if (cards.isEmpty()) "整图 OCR 兜底" else "规则回退分割（" + cards.size + "张）", bitmap, note)
                }
            } catch (e: Exception) {
                status.text = "自动分割失败：" + e.localizedMessage
                output.text = "自动检测未完成。\n异常：" + (e.localizedMessage ?: e.javaClass.simpleName)
                setBusy(false)
            }
        }
    }

    /** Both sources share ML Kit Chinese OCR, optional vision API, and local report saving. */
    private fun runPipeline(targets: List<Bitmap>, kind: String, original: Bitmap?, note: String = "") {
        setBusy(true); status.text = "已进入识别流程，准备 OCR…"
        scope.launch {
            val started = System.currentTimeMillis()
            val report = StringBuilder()
            val saved = mutableListOf<Pair<String, Bitmap>>()
            var failures = 0
            try {
                report.appendLine("测试类型：" + kind)
                report.appendLine("时间：" + formatTime(started))
                if (note.isNotBlank()) report.appendLine(note)
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                var paddle: PaddleOCR? = null
                var paddleInitError = ""
                try {
                    check(OpenCVUtils.init(this@ImageTestActivity)) { "OpenCV 原生库初始化失败" }
                    paddle = PaddleOCR.create(this@ImageTestActivity)
                    report.appendLine("PP-OCRv6 初始化：成功｜模型加载耗时 ${paddle.coldLoadTimeMs} ms")
                } catch (e: Exception) {
                    paddleInitError = e.localizedMessage ?: e.javaClass.simpleName
                    report.appendLine("PP-OCRv6 初始化失败：$paddleInitError")
                }
                val prefs = getSharedPreferences("api_config", MODE_PRIVATE)
                val base = prefs.getString("base", "").orEmpty().trim().trimEnd('/')
                val key = prefs.getString("key", "").orEmpty().trim()
                val model = prefs.getString("model", "").orEmpty().trim()

                targets.forEachIndexed { index, bitmap ->
                    status.text = "正在 OCR：第 ${index + 1}/${targets.size} 个区域…"
                    report.appendLine("\n========== 识别对象 " + (index + 1) + " ==========")
                    saved.add("target_" + (index + 1) + ".jpg" to bitmap)
                    val text = try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().text
                    } catch (e: Exception) {
                        failures++; report.appendLine("OCR失败：" + e.localizedMessage); ""
                    }
                    report.appendLine("【ML Kit 中文 OCR】")
                    report.appendLine("原始文本：\n" + if (text.isBlank()) "（无文字）" else text)
                    try {
                        val pp = paddle
                        if (pp == null) {
                            report.appendLine("\n【PP-OCRv6】不可用：$paddleInitError")
                        } else {
                            status.text = "正在运行 PP-OCRv6：第 ${index + 1}/${targets.size} 个区域…"
                            val ppResult = pp.recognize(bitmap)
                            report.appendLine("\n【PP-OCRv6】")
                            report.appendLine("文本行数：${ppResult.lineCount}")
                            report.appendLine("检测耗时：${ppResult.detectionTimeMs} ms（预处理 ${ppResult.detPreprocessMs} / 推理 ${ppResult.detInferenceMs} / 后处理 ${ppResult.detPostprocessMs} ms）")
                            report.appendLine("识别耗时：${ppResult.recognitionTimeMs} ms（预处理 ${ppResult.recPreprocessMs} / 推理 ${ppResult.recInferenceMs} / 后处理 ${ppResult.recPostprocessMs} ms）")
                            report.appendLine("总耗时：${ppResult.totalTimeMs} ms")
                            ppResult.results.forEachIndexed { lineIndex, line ->
                                val points = line.box.points.joinToString(" ") { point -> "(${point.x.toInt()},${point.y.toInt()})" }
                                report.appendLine("行 ${lineIndex + 1}｜置信度 ${"%.1f".format(line.confidence * 100)}%｜坐标 $points｜文本：${line.text}")
                            }
                            if (ppResult.results.isEmpty()) report.appendLine("（未检测到文字行）")
                        }
                    } catch (e: Exception) {
                        failures++
                        report.appendLine("\n【PP-OCRv6】识别失败：" + (e.localizedMessage ?: e.javaClass.simpleName))
                    }
                    output.text = report.toString() + "\n\n当前进度：双引擎 OCR 已运行，正在继续处理…"
                    if (base.isNotBlank() && key.isNotBlank() && model.isNotBlank() && text.isNotBlank()) {
                        try {
                            status.text = "正在调用视觉模型复核：第 ${index + 1}/${targets.size} 个区域…"
                            val answer = withContext(Dispatchers.IO) { askVision(base, key, model, bitmap, text) }
                            report.appendLine("\n视觉模型分析：\n" + answer)
                        } catch (e: Exception) {
                            failures++; report.appendLine("\n模型调用失败：" + e.localizedMessage)
                        }
                    } else report.appendLine("\n视觉模型未调用：API配置不完整或OCR为空")
                    output.text = report.toString() + "\n\n当前进度：" + (index + 1) + "/" + targets.size
                }
                report.appendLine("\n耗时：" + (System.currentTimeMillis() - started) + "ms")
                report.appendLine("失败项：" + failures)
                report.appendLine("注意：模型提取结果不是最终接单决策；本Demo不自动抢单。")
                val dir = withContext(Dispatchers.IO) {
                    val folder = File(filesDir, "test_records/" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(started)))
                    check(folder.mkdirs() || folder.isDirectory) { "无法创建测试记录目录" }
                    if (original != null) saveImage(original, File(folder, "original.jpg"))
                    saved.forEach { pair -> saveImage(pair.second, File(folder, pair.first)) }
                    File(folder, "report.txt").writeText(report.toString(), Charsets.UTF_8)
                    folder
                }
                output.text = report.toString() + "\n\n记录已保存：" + dir.name
                status.text = if (failures == 0) "本次识别完成" else "完成，但有 " + failures + " 项失败"
                refreshHistory()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = "处理失败：" + e.localizedMessage
                output.text = report.toString() + "\n异常：" + e.localizedMessage
            } finally {
                try { paddle?.release() } catch (_: Exception) { }
                try { recognizer.close() } catch (_: Exception) { }
                setBusy(false)
            }
        }
    }

    private suspend fun askVision(base: String, key: String, model: String, bitmap: Bitmap, ocr: String): String {
        val encoded = withContext(Dispatchers.Default) {
            val scaled = if (bitmap.width > 1600 || bitmap.height > 1600) {
                val ratio = minOf(1600f / bitmap.width, 1600f / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else bitmap
            val bytes = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, bytes)
            if (scaled !== bitmap) scaled.recycle()
            android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        }
        val prompt = "你是货运订单信息提取助手。提取报价、车型、起终点、货物、重量、体积、尺寸、装卸、时间、费用、人员、动力类型和限制。保留备注原文；区分明确事实、推断、歧义、未知。关键数字、车型、地址或限制看不清时标记unknown，不得猜测或擅自纠错。只提取信息，不替司机接单。\nOCR文本：\n" + ocr
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + encoded)))
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0.1)
        val request = Request.Builder().url(base + "/chat/completions")
            .header("Authorization", "Bearer " + key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + ": " + raw.take(1000))
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", raw)
        }
    }

    private fun saveImage(bitmap: Bitmap, file: File) {
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "保存图片失败：" + file.name }
        }
    }
    private fun formatTime(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
    private fun refreshHistory() {
        val folders = File(filesDir, "test_records").listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        history.text = if (folders.isEmpty()) "暂无记录" else folders.take(10).joinToString("\n") {
            val file = File(it, "report.txt")
            "• " + it.name + "  " + if (file.exists()) file.useLines { lines -> lines.firstOrNull().orEmpty() } else "记录不完整"
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        pick.isEnabled = !value; auto.isEnabled = !value; importModel.isEnabled = !value
        beginCrop.isEnabled = !value; runCrop.isEnabled = !value
    }
    override fun onDestroy() {
        super.onDestroy(); scope.cancel()
        source?.recycle(); source = null
    }
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
}

private class CropSelectionView(context: android.content.Context) : View(context) {
    init {
        // Make this custom View an active touch target and stop the parent ScrollView
        // from stealing the vertical drag used to draw a crop rectangle.
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5722.toInt(); style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55333333; style = Paint.Style.FILL }
    private val detectionGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF20B45A.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val detectionOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var detections: List<YoloOrderDetector.Detection> = emptyList()
    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val selection = RectF()
    private var startX = 0f
    private var startY = 0f
    private var selecting = false

    fun setBitmap(b: Bitmap) { bitmap = b; detections = emptyList(); selection.setEmpty(); selecting = false; invalidate() }
    fun setDetections(items: List<YoloOrderDetector.Detection>) { detections = items; invalidate() }
    fun beginSelection() { selection.setEmpty(); selecting = true; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(0xFF202830.toInt())
        val b = bitmap ?: return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale; val h = b.height * scale
        val l = (width - w) / 2f; val t = (height - h) / 2f
        imageRect.set(l, t, l + w, t + h)
        canvas.drawBitmap(b, null, imageRect, imagePaint)
        detections.forEach { detection ->
            val rect = RectF(
                imageRect.left + detection.left * imageRect.width(),
                imageRect.top + detection.top * imageRect.height(),
                imageRect.left + detection.right * imageRect.width(),
                imageRect.top + detection.bottom * imageRect.height()
            )
            val paint = if (detection.classId == 0) detectionGreen else detectionOrange
            canvas.drawRect(rect, paint)
            val label = "${if (detection.classId == 0) "完整订单" else "部分露出"} ${"%.1f".format(detection.confidence * 100)}%"
            val textWidth = labelText.measureText(label)
            val labelTop = (rect.top - 24f * resources.displayMetrics.density).coerceAtLeast(imageRect.top)
            labelBg.color = paint.color
            canvas.drawRect(rect.left, labelTop, rect.left + textWidth + 12f * resources.displayMetrics.density,
                labelTop + 22f * resources.displayMetrics.density, labelBg)
            canvas.drawText(label, rect.left + 6f * resources.displayMetrics.density,
                labelTop + 16f * resources.displayMetrics.density, labelText)
        }
        if (!selection.isEmpty) { canvas.drawRect(selection, fillPaint); canvas.drawRect(selection, borderPaint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!selecting || bitmap == null) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ScrollView otherwise intercepts a vertical finger movement.
                parent?.requestDisallowInterceptTouchEvent(true)
                if (imageRect.isEmpty) invalidate()
                if (!imageRect.contains(e.x, e.y)) return true
                startX = e.x.coerceIn(imageRect.left, imageRect.right)
                startY = e.y.coerceIn(imageRect.top, imageRect.bottom)
                selection.set(startX, startY, startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelection(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateSelection(e.x, e.y)
                selecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    private fun updateSelection(x: Float, y: Float) {
        selection.set(
            minOf(startX, x).coerceIn(imageRect.left, imageRect.right),
            minOf(startY, y).coerceIn(imageRect.top, imageRect.bottom),
            maxOf(startX, x).coerceIn(imageRect.left, imageRect.right),
            maxOf(startY, y).coerceIn(imageRect.top, imageRect.bottom)
        )
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun selectedBitmap(): Bitmap? {
        val b = bitmap ?: return null
        if (selection.width() < 20f || selection.height() < 20f || imageRect.width() <= 0f) return null
        val sx = b.width / imageRect.width(); val sy = b.height / imageRect.height()
        val l = ((selection.left - imageRect.left) * sx).toInt().coerceIn(0, b.width - 1)
        val t = ((selection.top - imageRect.top) * sy).toInt().coerceIn(0, b.height - 1)
        val r = ((selection.right - imageRect.left) * sx).toInt().coerceIn(l + 1, b.width)
        val bottom = ((selection.bottom - imageRect.top) * sy).toInt().coerceIn(t + 1, b.height)
        return Bitmap.createBitmap(b, l, t, r - l, bottom - t)
    }
}
