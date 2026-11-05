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
                if (yolo == null) {
                    preview.setDetections(emptyList())
                    output.text = "YOLO检测失败，已阻止自动 OCR/AI 审核。\n原因：$yoloError\n请检查模型文件或网络后重试。"
                    status.text = "YOLO不可用｜自动审核已暂停"
                    setBusy(false)
                    return@launch
                }
                val detections = yolo.detections
                preview.setDetections(detections)
                val complete = detections.filter { it.classId == 0 }.take(8)
                val partialCount = detections.count { it.classId == 1 }
                val note = buildString {
                    appendLine("检测引擎：YOLO11n ONNX（本机推理）")
                    appendLine("模型路径：缓存目录 / models/dingdan_yolo11n.onnx")
                    appendLine("推理耗时：${yolo.inferenceMs} ms")
                    appendLine("检测框总数：${detections.size}")
                    appendLine("完整订单：${complete.size} 张｜部分露出：$partialCount 张")
                    detections.forEachIndexed { index, item ->
                        appendLine("框 ${index + 1}：${item.className}｜置信度 ${"%.1f".format(item.confidence * 100)}%｜位置 x=${"%.3f".format(item.left)}, y=${"%.3f".format(item.top)}, w=${"%.3f".format(item.right-item.left)}, h=${"%.3f".format(item.bottom-item.top)}")
                    }
                    appendLine("仅 class 0（完整订单）允许进入 OCR/AI；class 1（部分露出）只显示检测框，不审核。")
                }
                if (complete.isEmpty()) {
                    output.text = note + "\n本次没有完整订单，已跳过 OCR 和 AI 审核。"
                    status.text = if (partialCount > 0) "仅有部分露出订单｜已跳过审核" else "未检测到订单｜已跳过审核"
                    setBusy(false)
                    return@launch
                }
                val targets = complete.map { YoloOrderDetector.crop(bitmap, it) }
                runPipeline(targets, "YOLO完整订单审核（${targets.size}张）", bitmap, note)
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
            val trace = PipelineTrace(this@ImageTestActivity, "image_test", JSONObject()
                .put("kind", kind).put("target_count", targets.size)
                .put("original_width", original?.width ?: JSONObject.NULL)
                .put("original_height", original?.height ?: JSONObject.NULL))
            val saved = mutableListOf<Pair<String, Bitmap>>()
            var failures = 0
            var paddle: PaddleOCR? = null
            var paddleInitError = ""
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                report.appendLine("执行追踪编号：" + trace.runId)
                report.appendLine("本机流程日志：" + trace.path())
                report.appendLine("测试类型：" + kind)
                report.appendLine("时间：" + formatTime(started))
                report.appendLine("OCR策略：PP-OCRv6 + ML Kit 双引擎交叉核对；两者原始结果都提供给视觉模型，不再只把 PP 结果当作 AI 的 OCR 依据。")
                if (note.isNotBlank()) report.appendLine(note)
                val initStarted = trace.mark()
                trace.event("OCR_ENGINE_INIT", "START")
                try {
                    // Load the native library directly first so Android's real linker error is preserved.
                    try {
                        System.loadLibrary("opencv_java4")
                    } catch (e: UnsatisfiedLinkError) {
                        throw IllegalStateException("加载 opencv_java4 失败：" + (e.message ?: "未知原生库错误"), e)
                    }
                    check(OpenCVUtils.init(this@ImageTestActivity)) { "OpenCVUtils 初始化失败（opencv_java4 已尝试加载）" }
                    val loadedPaddle = PaddleOCR.create(this@ImageTestActivity)
                    paddle = loadedPaddle
                    trace.event("PP_OCR_INIT", "SUCCESS", initStarted,
                        JSONObject().put("cold_load_time_ms", loadedPaddle.coldLoadTimeMs))
                    report.appendLine("PP-OCRv6 初始化：成功｜模型加载耗时 ${loadedPaddle.coldLoadTimeMs} ms")
                } catch (e: Exception) {
                    paddleInitError = e.localizedMessage ?: e.javaClass.simpleName
                    trace.error("PP_OCR_INIT", initStarted, e)
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
                    val mlStarted = trace.mark()
                    trace.event("OCR_ML_ORIGINAL_" + (index + 1), "START",
                        details = JSONObject().put("width", bitmap.width).put("height", bitmap.height))
                    val mlKitResult = try {
                        val mlResult = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                        trace.event("OCR_ML_ORIGINAL_" + (index + 1), "SUCCESS", mlStarted,
                            JSONObject().put("text_length", mlResult.text.length).put("text_preview", mlResult.text.take(400))
                                .put("line_count", mlResult.textBlocks.sumOf { block -> block.lines.size }))
                        mlResult
                    } catch (e: Exception) {
                        failures++; trace.error("OCR_ML_ORIGINAL_" + (index + 1), mlStarted, e)
                        report.appendLine("ML Kit OCR失败：" + e.localizedMessage); null
                    }
                    val text = mlKitResult?.text.orEmpty()
                    val mlKitEvidence = mlKitResult?.let { serializeMlKitEvidence(it) }.orEmpty()
                    report.appendLine("【ML Kit 中文 OCR｜辅助校验，不覆盖主引擎】")
                    report.appendLine("原始文本：\n" + if (text.isBlank()) "（无文字）" else text)
                    report.appendLine("文字框/置信度 JSON：\n" + if (mlKitEvidence.isBlank()) "不可用" else mlKitEvidence)
                    var ppPrimaryText = ""
                    var ppPrimaryEvidence = ""
                    val ppStarted = trace.mark()
                    trace.event("OCR_PP_ORIGINAL_" + (index + 1), "START")
                    try {
                        val pp = paddle
                        if (pp == null) {
                            report.appendLine("\n【PP-OCRv6】不可用：$paddleInitError")
                        } else {
                            status.text = "正在运行 PP-OCRv6：第 ${index + 1}/${targets.size} 个区域…"
                            val ppResult = pp.recognize(bitmap)
                            trace.event("OCR_PP_ORIGINAL_" + (index + 1), "SUCCESS", ppStarted,
                                JSONObject().put("wall_time_ms", ppResult.totalTimeMs)
                                    .put("detection_ms", ppResult.detectionTimeMs)
                                    .put("recognition_ms", ppResult.recognitionTimeMs)
                                    .put("line_count", ppResult.lineCount))
                            ppPrimaryText = ppResult.results.joinToString("\n") { it.text }.trim()
                            ppPrimaryEvidence = ppResult.results.joinToString("\n") { line ->
                                val points = line.box.points.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
                                "text=" + line.text + "; confidence=" + "%.3f".format(Locale.US, line.confidence) + "; points=" + points
                            }
                            report.appendLine("\n【PP-OCRv6｜主引擎结果】")
                            report.appendLine("主引擎提取文本：\n" + if (ppPrimaryText.isBlank()) "（无文字）" else ppPrimaryText)
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
                        trace.error("OCR_PP_ORIGINAL_" + (index + 1), ppStarted, e)
                        report.appendLine("\n【PP-OCRv6】识别失败：" + (e.localizedMessage ?: e.javaClass.simpleName))
                    }
                    val scaleStarted = trace.mark()
                    val twoX = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
                    trace.event("UPSCALE_2X_" + (index + 1), "SUCCESS", scaleStarted,
                        JSONObject().put("width", twoX.width).put("height", twoX.height)
                            .put("allocation_bytes_estimate", twoX.width.toLong() * twoX.height * 4L))
                    var pp2xText = ""
                    var pp2xEvidence = ""
                    val pp2xStarted = trace.mark()
                    trace.event("OCR_PP_2X_" + (index + 1), "START")
                    val pp2xError = try {
                        val pp = paddle ?: throw IllegalStateException(paddleInitError.ifBlank { "PP-OCR未初始化" })
                        val result2x = pp.recognize(twoX)
                        trace.event("OCR_PP_2X_" + (index + 1), "SUCCESS", pp2xStarted,
                            JSONObject().put("wall_time_ms", result2x.totalTimeMs)
                                .put("detection_ms", result2x.detectionTimeMs)
                                .put("recognition_ms", result2x.recognitionTimeMs)
                                .put("line_count", result2x.lineCount))
                        pp2xText = result2x.results.joinToString("\n") { row -> row.text }.trim()
                        pp2xEvidence = result2x.results.joinToString(" || ") { row ->
                            val points = row.box.points.joinToString(" ") { point -> "(" + point.x.toInt() + "," + point.y.toInt() + ")" }
                            "text=" + row.text + "; confidence=" + "%.3f".format(Locale.US, row.confidence) + "; points=" + points
                        }
                        ""
                    } catch (e: Exception) {
                        trace.error("OCR_PP_2X_" + (index + 1), pp2xStarted, e)
                        e.localizedMessage ?: e.javaClass.simpleName
                    }
                    var ml2xText = ""
                    var ml2xEvidence = "{}"
                    val ml2xStarted = trace.mark()
                    trace.event("OCR_ML_2X_" + (index + 1), "START")
                    val ml2xError = try {
                        val result2x = recognizer.process(InputImage.fromBitmap(twoX, 0)).awaitResult()
                        ml2xText = result2x.text
                        ml2xEvidence = serializeMlKitEvidence(result2x)
                        trace.event("OCR_ML_2X_" + (index + 1), "SUCCESS", ml2xStarted,
                            JSONObject().put("text_length", ml2xText.length).put("text_preview", ml2xText.take(400)))
                        ""
                    } catch (e: Exception) {
                        trace.error("OCR_ML_2X_" + (index + 1), ml2xStarted, e)
                        e.localizedMessage ?: e.javaClass.simpleName
                    }
                    twoX.recycle()
                    val parseStarted = trace.mark()
                    trace.event("FREIGHT_PARSE_" + (index + 1), "START")
                    val fareCheck = FreightAmountExtractor.resolve(listOf(
                        FreightAmountExtractor.fromPp(ppPrimaryText, ppPrimaryEvidence, "original", "PP-OCR"),
                        FreightAmountExtractor.fromMl(mlKitEvidence, "original", "ML Kit"),
                        FreightAmountExtractor.fromPp(pp2xText, pp2xEvidence, "upscale_2x", "PP-OCR"),
                        FreightAmountExtractor.fromMl(ml2xEvidence, "upscale_2x", "ML Kit")
                    ))
                    trace.event("FREIGHT_PARSE_" + (index + 1), fareCheck.state, parseStarted,
                        JSONObject().put("confirmed_amount", fareCheck.confirmedAmount ?: JSONObject.NULL)
                            .put("candidate_count", fareCheck.candidates.size).put("details", fareCheck.details)
                            .put("pp2x_error", pp2xError).put("ml2x_error", ml2xError))
                    output.text = report.toString() + "\n\n当前进度：四路 OCR 金额核验已运行…"
                    val visionOcrText = buildString {
                        appendLine("【原图 PP-OCRv6】")
                        appendLine(if (ppPrimaryText.isBlank()) "（无有效文本）" else ppPrimaryText)
                        appendLine(ppPrimaryEvidence)
                        appendLine("【原图 ML Kit】")
                        appendLine(if (text.isBlank()) "（无有效文本）" else text)
                        appendLine(mlKitEvidence)
                        appendLine("【2倍图 PP-OCRv6】")
                        appendLine(pp2xText)
                        appendLine(pp2xEvidence)
                        appendLine("【2倍图 ML Kit】")
                        appendLine(ml2xText)
                        appendLine(ml2xEvidence)
                        appendLine("【本地运费核验】")
                        appendLine(fareCheck.details)
                        appendLine("2倍图错误：PP=" + pp2xError + "；ML=" + ml2xError)
                    }.trim()
                    if (base.isNotBlank() && key.isNotBlank() && model.isNotBlank() &&
                        (ppPrimaryText.isNotBlank() || text.isNotBlank() || pp2xText.isNotBlank() || ml2xText.isNotBlank())) {
                        try {
                            status.text = "正在调用视觉模型复核：第 " + (index + 1) + "/" + targets.size + " 个区域…"
                            val aiStarted = trace.mark()
                            trace.event("VISION_REQUEST_" + (index + 1), "START",
                                details = JSONObject().put("image_width", bitmap.width).put("image_height", bitmap.height)
                                    .put("ocr_chars", visionOcrText.length).put("model", model))
                            val answer = withContext(Dispatchers.IO) { askVision(base, key, model, bitmap, visionOcrText, fareCheck, trace, "VISION_REQUEST_" + (index + 1)) }
                            trace.event("VISION_REQUEST_" + (index + 1), "SUCCESS", aiStarted,
                                JSONObject().put("response_chars", answer.length))
                            val jsonParseStarted = trace.mark()
                            val parsed = try { OrderRemarkAnalysis.parse(answer) } catch (e: Exception) {
                                trace.error("AI_JSON_PARSE_" + (index + 1), jsonParseStarted, e); null
                            }
                            trace.event("AI_JSON_PARSE_" + (index + 1), if (parsed == null) "FAILED" else "SUCCESS",
                                jsonParseStarted, JSONObject().put("parsed", parsed != null))
                            val structured = parsed?.copy(freightYuan = fareCheck.confirmedAmount)
                            report.appendLine("\nAI备注解析原始响应：\n" + answer)
                            if (structured != null) {
                                report.appendLine("\n本地运费核验：" + fareCheck.details + "\n" + structured.toDisplayText())
                                val vehicleConfig = OrderRuleConfig.load(getSharedPreferences("api_config", MODE_PRIVATE))
                                val matchReport = OrderRequirementMatcher.evaluate(structured, vehicleConfig)
                                report.appendLine("\n【订单要求与车辆档案匹配】")
                                report.appendLine("车辆匹配状态：" + matchReport.vehicleMatch.name)
                                matchReport.reasons.forEach { report.appendLine("• " + it) }
                                report.appendLine("说明：这是车辆条件初步匹配，不是最终接单结论；里程、额外成本和所有可调规则仍需完整联动。")
                            } else report.appendLine("\n结构化解析失败：模型未返回有效JSON，本次不生成自动接单结论。")
                        } catch (e: Exception) {
                            failures++; report.appendLine("\n模型调用失败：" + e.localizedMessage)
                        }
                    } else report.appendLine("\n视觉模型未调用：API配置不完整或OCR为空")
                    output.text = report.toString() + "\n\n当前进度：" + (index + 1) + "/" + targets.size
                }
                trace.finish(if (failures == 0) "SUCCESS" else "COMPLETED_WITH_ERRORS",
                    JSONObject().put("target_count", targets.size).put("failures", failures))
                report.appendLine("\n执行追踪编号：" + trace.runId)
                report.appendLine("本机流程日志：" + trace.path())
                report.appendLine("\n耗时：" + (System.currentTimeMillis() - started) + "ms")
                report.appendLine("失败项：" + failures)
                report.appendLine("注意：模型提取结果不是最终接单决策；本Demo不自动抢单。")
                val dir = withContext(Dispatchers.IO) {
                    val folder = File(filesDir, "test_records/" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(started)))
                    check(folder.mkdirs() || folder.isDirectory) { "无法创建测试记录目录" }
                    if (original != null) saveImage(original, File(folder, "original.jpg"))
                    saved.forEach { pair -> saveImage(pair.second, File(folder, pair.first)) }
                    File(folder, "report.txt").writeText(report.toString(), Charsets.UTF_8)
                    runCatching { File(trace.path()).copyTo(File(folder, "pipeline_trace.jsonl"), overwrite = true) }
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

    private fun serializeMlKitEvidence(text: com.google.mlkit.vision.text.Text): String {
        val blocks = JSONArray()
        text.textBlocks.forEach { block ->
            val lines = JSONArray()
            block.lines.forEach { line ->
                val box = line.boundingBox
                val item = JSONObject().put("text", line.text).put("confidence", line.confidence)
                if (box != null) item.put("bounding_box", JSONObject()
                    .put("left", box.left).put("top", box.top)
                    .put("right", box.right).put("bottom", box.bottom))
                lines.put(item)
            }
            blocks.put(JSONObject().put("text", block.text).put("lines", lines))
        }
        return JSONObject().put("recognized_text", text.text).put("blocks", blocks).toString()
    }

    private suspend fun askVision(base: String, key: String, model: String, bitmap: Bitmap, ocr: String,
        fareCheck: FreightAmountExtractor.Result, trace: PipelineTrace? = null, traceLabel: String = "VISION"): String {
        val encodeStarted = trace?.mark()
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
        trace?.event(traceLabel + "_IMAGE_ENCODE", "SUCCESS", encodeStarted,
            JSONObject().put("base64_chars", encoded.length).put("original_width", bitmap.width)
                .put("original_height", bitmap.height))
        val promptStarted = trace?.mark()
        val prompt = """
你是货运订单图片识别与备注结构化解析器。必须查看原始订单图片，并把 PP-OCRv6 与 ML Kit 的原始文本及文字框证据作为辅助。两套OCR都可能出错，不能把任一OCR文本当成事实；以图片中清晰可见的内容为准。
核心字段包括用户出价/订单运费、车型车长、地址、时间、装卸数量。运费只认“用户出价”对应的金额；平台奖励（例如“多赚XX元”）完全忽略，不得写入运费，也不需要提取或比较。仔细核对金额小数点；图片无法确认用户出价时 freight_yuan 必须为null，并在unclear_items中注明。
如果图片本身也无法清楚确认，相关字段必须保持null，并在unclear_items中写明冲突原文及“需人工核实”，不得自行猜测或按某一OCR引擎强行定值。
必须只返回一个合法JSON对象，不要Markdown代码围栏或JSON以外的说明。
无法确认的字符串、数字和数量一律返回null；数组没有明确内容时返回空数组。
司机额外成本只有在明确说明由司机承担且金额明确时才填写driver_extra_cost_yuan，否则必须为null，不能把高速费、过路费或其他费用默认成0。
装货点/卸货点数量只有明确能数清时才填写；“一装一卸”可分别填1。
风险和不确定项必须单独列出；每个关键结论尽可能提供原文依据。
字段必须严格使用以下结构：
{
  "vehicle_type": null,
  "vehicle_length_m": null,
  "vehicle_volume_m3": null,
  "cargo_name": null,
  "cargo_weight_kg": null,
  "cargo_volume_m3": null,
  "cargo_length_m": null,
  "freight_yuan": null,
  "pickup_count": null,
  "delivery_count": null,
  "driver_extra_cost_yuan": null,
  "extra_cost_items": [],
  "toll_requirement": null,
  "arrival_time": null,
  "personnel_requirement": null,
  "loading_unloading": null,
  "risks": [],
  "unclear_items": [],
  "evidence": [],
  "summary": ""
}
单位统一：重量kg、长度米、体积立方米、金额人民币元。不能把货物重量误认为车辆额定载重，也不能把货物体积误认为车辆容积。
OCR原文：
$ocr
""".trimIndent()
        trace?.event(traceLabel + "_PROMPT_BUILD", "SUCCESS", promptStarted,
            JSONObject().put("prompt_chars", prompt.length).put("ocr_chars", ocr.length))
        val bodyStarted = trace?.mark()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + encoded)))
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0.1)
        val bodyText = body.toString()
        trace?.event(traceLabel + "_REQUEST_BODY", "READY", bodyStarted,
            JSONObject().put("body_chars", bodyText.length).put("image_base64_chars", encoded.length)
                .put("model", model).put("contains_image", true))
        val request = Request.Builder().url(base + "/chat/completions")
            .header("Authorization", "Bearer " + key)
            .post(bodyText.toRequestBody("application/json".toMediaType())).build()
        val networkStarted = trace?.mark()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            trace?.event(traceLabel + "_HTTP_RESPONSE", if (response.isSuccessful) "SUCCESS" else "HTTP_ERROR",
                networkStarted, JSONObject().put("http_code", response.code).put("response_chars", raw.length)
                    .put("content_type", response.header("Content-Type") ?: "unknown"))
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + ": " + raw.take(1000))
            val parseStarted = trace?.mark()
            val answer = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", raw)
            trace?.event(traceLabel + "_RESPONSE_PARSE", "SUCCESS", parseStarted,
                JSONObject().put("answer_chars", answer.length))
            answer
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
