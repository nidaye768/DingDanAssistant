package com.dingdan.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dingdan.assistant.vision.OrderCardSegmenter
import com.dingdan.assistant.vision.YoloOrderDetector
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var preview: PreviewView
    private lateinit var output: TextView
    private lateinit var modeLabel: TextView
    private lateinit var apiBase: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var cameraExecutor: ExecutorService
    private var capture: ImageCapture? = null
    @Volatile private var paddleOcr: PaddleOCR? = null
    private var mode = "auto"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("api_config", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun buildUi() {
        val bg = 0xFFF3F5F7.toInt()
        val ink = 0xFF17212B.toInt()
        val muted = 0xFF687581.toInt()
        val accent = 0xFFE45B42.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setBackgroundColor(0xFF172A3A.toInt())
        }
        header.addView(text("盯单助手", 25f, -1, true))
        header.addView(text("V1.2 · 双模式测试验收版", 13f, 0xFFB9C7D1.toInt()))
        header.addView(text("只监测、分析和提醒，不自动抢单", 12f, 0xFFD5DEE4.toInt()))
        root.addView(header)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("工作模式", 17f, ink, true))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fast = Button(this).apply { text = "极速模式"; setOnClickListener { setMode("fast") } }
        val auto = Button(this).apply { text = "全自动模式"; setOnClickListener { setMode("auto") } }
        modes.addView(fast, LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(auto, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(modes)
        modeLabel = text("当前：全自动模式｜仅最终符合策略的订单提醒", 13f, accent, true)
        body.addView(modeLabel)

        body.addView(section("采集状态", ink))
        status = text("等待摄像头权限", 13f, muted)
        body.addView(status)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(210)))
        body.addView(actionButton("拍摄并识别订单画面", accent) { captureAndRecognize() })
        body.addView(actionButton("打开图片导入测试工作台", 0xFF246B8E.toInt()) { startActivity(Intent(this, ImageTestActivity::class.java)) })
        body.addView(actionButton("打开 OCR 诊断探针中心", 0xFF8A5A9E.toInt()) { startActivity(Intent(this, OcrDiagnosticActivity::class.java)) })
        body.addView(actionButton("导出最近一次流程日志", 0xFF246B8E.toInt()) { exportLatestPipelineTrace() })
        body.addView(actionButton("打开 YOLO 训练中心", 0xFF426B55.toInt()) { startActivity(Intent(this, YoloTrainingActivity::class.java)) })

        body.addView(section("验收中心", ink))
        body.addView(text("本机自检覆盖：单价公式、保守决策、哈希差异、文本归一化。真实识别率和提醒准确率需用标注订单/视频验收。", 12f, muted))
        body.addView(actionButton("运行本机自动验收", 0xFF246B8E.toInt()) { runSelfTests() })
        body.addView(actionButton("测试大模型 API 连通性", 0xFF426B55.toInt()) { testApi() })
        body.addView(actionButton("清空测试输出", 0xFF687581.toInt()) { output.text = "等待测试或识别结果…" })

        body.addView(actionButton("规则设置", 0xFF426B55.toInt()) { startActivity(Intent(this, OrderRuleSettingsActivity::class.java)) })
        body.addView(section("订单判断模拟测试", ink))
        body.addView(text("手动输入订单数据，单独验证规则引擎；不会接触真实订单，也不会自动抢单。", 12f, muted))
        fun numberField(hint: String, value: String, decimal: Boolean): EditText =
            edit(hint).apply {
                setText(value)
                inputType = if (decimal)
                    android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                else android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        val simFreight = numberField("运费（元）", "240", true)
        val simKm = numberField("载货道路里程（km）", "30", true)
        val simExtra = numberField("司机额外成本（元）", "20", true)
        val simFloor = numberField("最低载货公里单价（元/km）", "6", true)
        val simPickup = numberField("装货点数量", "1", false)
        val simDelivery = numberField("卸货点数量", "1", false)
        listOf(simFreight, simKm, simExtra, simFloor, simPickup, simDelivery).forEach { body.addView(it) }
        val simVehicle = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("车辆匹配", "车辆不匹配", "车辆未知"))
        }
        body.addView(text("车辆适配情况", 12f, muted))
        body.addView(simVehicle)
        val simCostConfirmed = Switch(this).apply { text = "司机额外成本已核实"; isChecked = true }
        val simRequirementsClear = Switch(this).apply { text = "备注关键要求已确认"; isChecked = true }
        body.addView(simCostConfirmed)
        body.addView(simRequirementsClear)
        body.addView(actionButton("开始模拟判断", accent) {
            val freight = simFreight.text.toString().toDoubleOrNull()
            val km = simKm.text.toString().toDoubleOrNull()
            val extra = simExtra.text.toString().toDoubleOrNull()
            val floor = simFloor.text.toString().toDoubleOrNull()
            val pickup = simPickup.text.toString().toIntOrNull()
            val delivery = simDelivery.text.toString().toIntOrNull()
            val match = when (simVehicle.selectedItemPosition) {
                0 -> OrderDecisionEngine.Match.MATCHED
                1 -> OrderDecisionEngine.Match.MISMATCHED
                else -> OrderDecisionEngine.Match.UNKNOWN
            }
            val result = OrderDecisionEngine.evaluate(
                OrderDecisionEngine.Facts(
                    freight, km, floor, match, pickup, delivery, extra,
                    simCostConfirmed.isChecked, simRequirementsClear.isChecked,
                    OrderRuleConfig.load(prefs)
                )
            )
            val verdictText = when (result.verdict) {
                OrderDecisionEngine.Verdict.RECOMMEND -> "推荐接单"
                OrderDecisionEngine.Verdict.IGNORE -> "忽略订单"
                OrderDecisionEngine.Verdict.PENDING -> "待确认"
            }
            output.text = buildString {
                appendLine("订单模拟判断结果：$verdictText")
                appendLine("毛载货公里单价：${result.grossYuanPerLoadedKm?.let { "%.2f".format(it) } ?: "无法计算"} 元/km")
                appendLine("扣除额外成本后净运费：${result.netFreightYuan?.let { "%.2f".format(it) } ?: "无法计算"} 元")
                appendLine("净载货公里单价：${result.netYuanPerLoadedKm?.let { "%.2f".format(it) } ?: "无法计算"} 元/km")
                appendLine("判断依据：")
                result.reasons.forEach { appendLine("• $it") }
                appendLine()
                appendLine("提示：结果仅验证当前规则引擎；里程、备注解析和车辆数据仍需真实来源确认。")
            }
            status.text = "模拟判断完成：$verdictText"
        })


        body.addView(section("API 配置", ink))
        apiBase = edit("API 基址，例如 https://apihub.example/v1")
        apiKey = edit("API Key").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = edit("模型名称")
        body.addView(apiBase); body.addView(apiKey); body.addView(model)
        apiBase.setText(prefs.getString("base", "https://api.openai.com/v1"))
        apiKey.setText(prefs.getString("key", ""))
        model.setText(prefs.getString("model", "gpt-4o-mini"))
        body.addView(actionButton("保存 API 配置", accent) {
            prefs.edit().putString("base", apiBase.text.toString().trim())
                .putString("key", apiKey.text.toString().trim())
                .putString("model", model.text.toString().trim()).apply()
            status.text = "API 配置已保存到本机（当前版本尚未接入 Keystore 加密）"
        })

        body.addView(section("测试日志 / 识别结果", ink))
        output = text("等待测试或识别结果…", 12f, ink)
        output.setPadding(dp(12), dp(10), dp(12), dp(10))
        output.setBackgroundColor(0xFFFFFFFF.toInt())
        body.addView(output)
        setContentView(root)
    }

    private fun exportLatestPipelineTrace() {
        val dir = File(filesDir, "pipeline_traces")
        val latest = dir.listFiles()?.filter { it.isFile && it.extension == "jsonl" }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            status.text = "暂无流程日志，请先运行一次订单识别"
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, latest.name)
        }
        pendingTraceExport = latest
        startActivityForResult(intent, 901)
    }

    private var pendingTraceExport: File? = null

    @Deprecated("Deprecated in Android API; retained for compatibility with the current Activity UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 901 || resultCode != RESULT_OK) return
        val source = pendingTraceExport
        val uri = data?.data
        if (source == null || uri == null) {
            status.text = "日志导出取消或文件不可用"
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法写入所选文件")
                }
                status.text = "流程日志已导出：" + source.name
            } catch (e: Exception) {
                status.text = "日志导出失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
            } finally {
                pendingTraceExport = null
            }
        }
    }

    private fun section(s: String, color: Int) = text(s, 17f, color, true).apply {
        setPadding(0, dp(17), 0, dp(7))
    }
    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 13f; setSingleLine(true)
    }
    private fun actionButton(label: String, color: Int, click: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(-1)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            setOnClickListener { click() }
        }
    private fun setMode(next: String) {
        mode = next
        modeLabel.text = if (mode == "fast")
            "当前：极速模式｜基础条件初筛后快速提醒，深度分析后台继续"
        else "当前：全自动模式｜完整判断后才提醒；未知条件不推荐"
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(90).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, capture)
                status.text = "摄像头运行中｜请对准主手机订单列表"
            } catch (e: Exception) { status.text = "摄像头启动失败：${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndRecognize() {
        val c = capture ?: run { status.text = "摄像头尚未就绪"; return }
        status.text = "正在拍摄并进行 YOLO 订单分类…"
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) { runOnUiThread { status.text = "图片读取失败" }; return }
                    val trace = PipelineTrace(this@MainActivity, "live_camera", JSONObject()
                        .put("image_width", bitmap.width).put("image_height", bitmap.height).put("mode", mode))
                    val yoloStarted = trace.mark()
                    trace.event("YOLO_DETECT", "START")
                    val yolo = try { YoloOrderDetector.detect(this@MainActivity, bitmap).also {
                        trace.event("YOLO_DETECT", "SUCCESS", yoloStarted, JSONObject()
                            .put("inference_ms", it.inferenceMs).put("detection_count", it.detections.size)
                            .put("class0_complete", it.detections.count { d -> d.classId == 0 })
                            .put("class1_partial", it.detections.count { d -> d.classId == 1 }))
                    } } catch (e: Exception) { trace.error("YOLO_DETECT", yoloStarted, e);
                        runOnUiThread {
                            output.text = "YOLO检测失败，已阻止 OCR/AI 审核，避免把不完整或未知订单送去审核。\n原因：${e.localizedMessage ?: e.javaClass.simpleName}"
                            status.text = "YOLO不可用｜自动审核已暂停"
                        }
                        bitmap.recycle()
                        return
                    }
                    val complete = yolo.detections.filter { it.classId == 0 }
                    val partialCount = yolo.detections.count { it.classId == 1 }
                    if (complete.isEmpty()) {
                        trace.event("ORDER_FILTER", "SKIPPED", details = JSONObject()
                            .put("reason", "no_complete_order").put("partial_count", partialCount))
                        trace.finish("NO_COMPLETE_ORDER")
                        runOnUiThread {
                            output.text = buildString {
                                appendLine("YOLO检测完成｜推理 ${yolo.inferenceMs} ms")
                                appendLine("完整订单：0 张")
                                appendLine("部分露出订单：$partialCount 张")
                                appendLine("本次未发现完整订单，已跳过 OCR 和 AI 审核。")
                            }
                            status.text = if (partialCount > 0) "仅检测到部分订单｜等待完整订单" else "未检测到订单｜未启动审核"
                        }
                        bitmap.recycle()
                        return
                    }
                    scope.launch {
                        val report = StringBuilder()
                        report.appendLine("执行追踪编号：" + trace.runId)
                        report.appendLine("本机流程日志：" + trace.path())
                        report.appendLine("YOLO订单筛选：完整订单 ${complete.size} 张｜部分露出订单 $partialCount 张")
                        report.appendLine("YOLO推理耗时：${yolo.inferenceMs} ms")
                        complete.forEachIndexed { index, detection ->
                            val cropStarted = trace.mark()
                            val orderBitmap = YoloOrderDetector.crop(bitmap, detection)
                            trace.event("ORDER_CROP_" + (index + 1), "SUCCESS", cropStarted, JSONObject()
                                .put("width", orderBitmap.width).put("height", orderBitmap.height)
                                .put("confidence", detection.confidence))
                            report.appendLine("\n========== 完整订单 ${index + 1} ==========")
                            report.appendLine("YOLO置信度：${"%.1f".format(detection.confidence * 100)}%")
                            status.text = "完整订单 ${index + 1}/${complete.size}：PP-OCRv6识别中…"
                            var ppDiagnostic = ""
                            val ppStarted = trace.mark()
                            trace.event("OCR_PP_ORIGINAL_" + (index + 1), "START")
                            val ppText = try {
                                val recognized = withContext(Dispatchers.IO) {
                                    recognizeWithPaddle(orderBitmap).also { ppDiagnostic = it.second }.first
                                }
                                trace.event("OCR_PP_ORIGINAL_" + (index + 1), "SUCCESS", ppStarted,
                                    JSONObject().put("text_length", recognized.length).put("text_preview", recognized.take(300)))
                                recognized
                            } catch (e: Exception) {
                                trace.error("OCR_PP_ORIGINAL_" + (index + 1), ppStarted, e)
                                ppDiagnostic = "PP-OCRv6失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
                                ""
                            }
                            status.text = "完整订单 ${index + 1}/${complete.size}：ML Kit辅助校验中…"
                            var mlKitError = ""
                            val mlStarted = trace.mark()
                            trace.event("OCR_ML_ORIGINAL_" + (index + 1), "START")
                            val mlKitResult = try {
                                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                                val result = try { recognizer.process(InputImage.fromBitmap(orderBitmap, 0)).awaitResult() }
                                finally { recognizer.close() }
                                trace.event("OCR_ML_ORIGINAL_" + (index + 1), "SUCCESS", mlStarted,
                                    JSONObject().put("text_length", result.text.length).put("text_preview", result.text.take(300))
                                        .put("line_count", result.textBlocks.sumOf { block -> block.lines.size }))
                                result
                            } catch (e: Exception) {
                                trace.error("OCR_ML_ORIGINAL_" + (index + 1), mlStarted, e)
                                mlKitError = e.localizedMessage ?: e.javaClass.simpleName
                                null
                            }
                            val usableMlKitText = mlKitResult?.text.orEmpty()
                            val ppLineEvidence = ppDiagnostic.substringAfter("文字行证据：", "")
                            val mlLineEvidence = mlKitResult?.let { serializeMlKitEvidence(it) } ?: "{}"
                            val twoX = Bitmap.createScaledBitmap(orderBitmap, orderBitmap.width * 2, orderBitmap.height * 2, true)
                            var pp2xText = ""
                            var pp2xEvidence = ""
                            val pp2xStarted = trace.mark()
                            trace.event("OCR_PP_2X_" + (index + 1), "START", details = JSONObject()
                                .put("width", twoX.width).put("height", twoX.height))
                            val pp2xError = try {
                                val recognized = withContext(Dispatchers.IO) {
                                    recognizeWithPaddle(twoX).also { pp2xEvidence = it.second }.first
                                }
                                trace.event("OCR_PP_2X_" + (index + 1), "SUCCESS", pp2xStarted,
                                    JSONObject().put("text_length", recognized.length).put("text_preview", recognized.take(300)))
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
                                val recognizer2x = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                                try {
                                    val result2x = recognizer2x.process(InputImage.fromBitmap(twoX, 0)).awaitResult()
                                    ml2xText = result2x.text
                                    ml2xEvidence = serializeMlKitEvidence(result2x)
                                } finally { recognizer2x.close() }
                                trace.event("OCR_ML_2X_" + (index + 1), "SUCCESS", ml2xStarted,
                                    JSONObject().put("text_length", ml2xText.length).put("text_preview", ml2xText.take(300)))
                                ""
                            } catch (e: Exception) {
                                trace.error("OCR_ML_2X_" + (index + 1), ml2xStarted, e)
                                e.localizedMessage ?: e.javaClass.simpleName
                            }
                            twoX.recycle()
                            val parseStarted = trace.mark()
                            val fareCheck = FreightAmountExtractor.resolve(listOf(
                                FreightAmountExtractor.fromPp(ppText, ppLineEvidence, "original", "PP-OCR"),
                                FreightAmountExtractor.fromMl(mlLineEvidence, "original", "ML Kit"),
                                FreightAmountExtractor.fromPp(pp2xText, pp2xEvidence.substringAfter("文字行证据：", ""), "upscale_2x", "PP-OCR"),
                                FreightAmountExtractor.fromMl(ml2xEvidence, "upscale_2x", "ML Kit")
                            ))
                            trace.event("FREIGHT_RESOLVE_" + (index + 1), fareCheck.state, parseStarted,
                                JSONObject().put("confirmed_amount", fareCheck.confirmedAmount ?: JSONObject.NULL)
                                    .put("candidate_count", fareCheck.candidates.size)
                                    .put("details", fareCheck.details)
                                    .put("pp2x_error", pp2xError).put("ml2x_error", ml2xError))
                            val ocrEvidence = buildString {
                                appendLine("【原图 PP-OCRv6】")
                                appendLine(if (ppText.isBlank()) "（无有效文本）" else ppText)
                                appendLine(ppLineEvidence)
                                appendLine("【原图 ML Kit】")
                                appendLine(if (usableMlKitText.isBlank()) "（无有效文本）" else usableMlKitText)
                                appendLine(mlLineEvidence)
                                appendLine("【2倍图 PP-OCRv6】")
                                appendLine(if (pp2xText.isBlank()) "（无有效文本）" else pp2xText)
                                appendLine(pp2xEvidence)
                                appendLine("【2倍图 ML Kit】")
                                appendLine(if (ml2xText.isBlank()) "（无有效文本）" else ml2xText)
                                appendLine(ml2xEvidence)
                                appendLine("【本地运费候选核验】")
                                appendLine(fareCheck.details)
                                appendLine("2倍图错误：PP=" + pp2xError + "；ML=" + ml2xError)
                            }.trim()
                            report.appendLine("PP-OCRv6主引擎：\n" + if (ppText.isBlank()) "无有效文本" else ppText)
                            report.appendLine("PP-OCRv6运行信息：$ppDiagnostic")
                            report.appendLine("ML Kit辅助校验：\n" + if (mlKitError.isBlank()) usableMlKitText else "失败：" + mlKitError)
                            report.appendLine("本地运费核验：" + fareCheck.details)
                            if (ppText.isNotBlank() || usableMlKitText.isNotBlank() || pp2xText.isNotBlank() || ml2xText.isNotBlank()) {
                                status.text = "完整订单 " + (index + 1) + "/" + complete.size + "：提交四路 OCR 证据供 AI 对照…"
                                requestVision(orderBitmap, ocrEvidence, fareCheck, trace, "VISION_" + (index + 1))
                                report.appendLine("已提交原图 + 2倍图、PP-OCRv6 + ML Kit 证据供 AI 对照；运费由本地核验结果约束")
                            } else {
                                trace.event("VISION_" + (index + 1), "SKIPPED", details = JSONObject().put("reason", "all_ocr_empty"))
                                report.appendLine("四路OCR均为空：跳过AI调用")
                            }
                            output.text = report.toString()
                        }
                        trace.finish("SUCCESS", JSONObject().put("complete_orders", complete.size)
                            .put("partial_orders", partialCount))
                        report.appendLine("\n流程日志文件：" + trace.path())
                        output.text = report.toString()
                        status.text = "完整订单识别完成：${complete.size} 张｜部分订单已跳过"
                        bitmap.recycle()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { status.text = "拍摄失败：${e.localizedMessage}" }
                }
            })
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

    private suspend fun recognizeWithPaddle(bitmap: Bitmap): Pair<String, String> {
        // PaddleOCR.create、recognize 均为 suspend API；调用方在 Dispatchers.IO 中执行。
        val engine = paddleOcr ?: run {
            System.loadLibrary("opencv_java4")
            check(OpenCVUtils.init(this)) { "OpenCVUtils 初始化失败" }
            PaddleOCR.create(this).also { paddleOcr = it }
        }
        val result = engine.recognize(bitmap)
        val recognizedText = result.results.joinToString("\n") { it.text }.trim()
        val lineEvidence = result.results.joinToString(" || ") { line ->
            val points = line.box.points.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
            "text=${line.text}; confidence=${"%.3f".format(java.util.Locale.US, line.confidence)}; points=$points"
        }
        val details = "模型已加载｜检测 ${result.detectionTimeMs}ms｜识别 ${result.recognitionTimeMs}ms｜总计 ${result.totalTimeMs}ms｜文本行 ${result.lineCount}｜文字行证据：$lineEvidence"
        return recognizedText to details
    }

    private suspend fun <T> Task<T>.awaitResult(): T = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    private fun runSelfTests() {
        val results = AcceptanceSuite.run()
        val passed = results.count { it.passed }
        output.text = buildString {
            appendLine("本机自动验收：$passed / ${results.size} 通过")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            results.forEach { appendLine("${if (it.passed) "✓" else "✗"} ${it.name} — ${it.detail}") }
            appendLine()
            appendLine("范围说明：这是本机逻辑自检，不代表 OCR 准确率、订单切割准确率、地图里程、模型备注理解或真实提醒质量已达标。")
        }
        status.text = "本机自检完成：$passed / ${results.size} 通过"
    }

    private fun testApi() {
        val base = apiBase.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        val modelName = model.text.toString().trim()
        if (base.isBlank() || key.isBlank() || modelName.isBlank()) {
            status.text = "请先填写 API 地址、Key 和模型"; return
        }
        status.text = "正在测试 API…"
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    val body = JSONObject().put("model", modelName)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "只回复 OK")))
                        .put("max_tokens", 8)
                    val req = Request.Builder().url("$base/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    client.newCall(req).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $raw")
                        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "请求成功")
                    }
                }
                status.text = "API 测试成功"
                output.append("\n\nAPI 连通性：成功｜模型回复：$answer")
            } catch (e: Exception) {
                status.text = "API 测试失败：${e.localizedMessage}"
                output.append("\n\nAPI 连通性：失败｜${e.localizedMessage}")
            }
        }
    }

    private fun requestVision(bitmap: Bitmap, ocr: String, fareCheck: FreightAmountExtractor.Result,
        trace: PipelineTrace? = null, traceLabel: String = "VISION") {
        val base = apiBase.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        val modelName = model.text.toString().trim()
        if (base.isBlank() || key.isBlank() || modelName.isBlank()) {
            status.text = "OCR 已完成；填写 API 配置后可调用视觉模型"; return
        }
        scope.launch {
            try {
                val encodeStarted = trace?.mark()
                val answer = withContext(Dispatchers.IO) {
                    val bytes = ByteArrayOutputStream()
                    val compressOk = bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
                    val jpegBytes = bytes.toByteArray()
                    val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    trace?.event(traceLabel + "_IMAGE_ENCODE", "SUCCESS", encodeStarted,
                        JSONObject().put("compress_ok", compressOk).put("jpeg_bytes", jpegBytes.size)
                            .put("base64_chars", encoded.length).put("width", bitmap.width).put("height", bitmap.height))
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
注意单位统一：重量用kg，长度用米，体积用立方米，金额用人民币元。不能把货物重量误认为车辆载重，也不能把货物体积误认为车辆容积。
OCR原文：
$ocr
""".trimIndent()
                    trace?.event(traceLabel + "_PROMPT_BUILD", "SUCCESS", promptStarted,
                        JSONObject().put("prompt_chars", prompt.length).put("ocr_chars", ocr.length))
                    val bodyStarted = trace?.mark()
                    val content = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
                    val body = JSONObject().put("model", modelName)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                        .put("temperature", 0.1)
                    val bodyText = body.toString()
                    trace?.event(traceLabel + "_REQUEST_BODY", "READY", bodyStarted,
                        JSONObject().put("body_chars", bodyText.length).put("image_base64_chars", encoded.length)
                            .put("model", modelName).put("contains_image", true))
                    val req = Request.Builder().url("$base/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(bodyText.toRequestBody("application/json".toMediaType())).build()
                    val networkStarted = trace?.mark()
                    client.newCall(req).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        trace?.event(traceLabel + "_HTTP_RESPONSE", if (response.isSuccessful) "SUCCESS" else "HTTP_ERROR",
                            networkStarted, JSONObject().put("http_code", response.code).put("response_chars", raw.length)
                                .put("content_type", response.header("Content-Type") ?: "unknown"))
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $raw")
                        val parseStarted = trace?.mark()
                        val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", raw)
                        trace?.event(traceLabel + "_RESPONSE_PARSE", "SUCCESS", parseStarted,
                            JSONObject().put("answer_chars", content.length))
                        content
                    }
                }
                val jsonStarted = trace?.mark()
                val parsed = try { OrderRemarkAnalysis.parse(answer) } catch (e: Exception) {
                    trace?.error(traceLabel + "_JSON_PARSE", jsonStarted, e); null
                }
                trace?.event(traceLabel + "_JSON_PARSE", if (parsed == null) "FAILED" else "SUCCESS",
                    jsonStarted, JSONObject().put("parsed", parsed != null))
                val structured = parsed?.copy(freightYuan = fareCheck.confirmedAmount)
                output.append("\n\nAI备注解析原始响应：\n$answer")
                if (structured != null) {
                    trace?.event(traceLabel + "_FIELD_ASSIGNMENT", "SUCCESS", details = JSONObject()
                        .put("local_freight_confirmed", fareCheck.confirmedAmount ?: JSONObject.NULL)
                        .put("ai_freight_candidate", parsed.freightYuan ?: JSONObject.NULL)
                        .put("final_freight", structured.freightYuan ?: JSONObject.NULL)
                        .put("final_source", if (fareCheck.confirmedAmount != null) "LOCAL_OCR" else "UNCONFIRMED"))
                    output.append("\n\n本地运费核验：" + fareCheck.details + "\n" + structured.toDisplayText())
                    val vehicleConfig = OrderRuleConfig.load(getSharedPreferences("api_config", MODE_PRIVATE))
                    val matchReport = OrderRequirementMatcher.evaluate(structured, vehicleConfig)
                    output.append("\n\n【订单要求与车辆档案匹配】")
                    output.append("\n车辆匹配状态：" + matchReport.vehicleMatch.name)
                    matchReport.reasons.forEach { output.append("\n• " + it) }
                    output.append("\n说明：这只是车辆条件初步匹配；装卸数量、额外成本、实际道路里程和最终规则仍需完整联动。")
                    status.text = "AI备注解析及车辆初步匹配完成｜请核对待确认项"
                } else {
                    output.append("\n\n结构化解析失败：模型未按要求返回有效JSON。本次不生成自动接单结论。")
                    status.text = "模型分析完成，但结构化JSON解析失败"
                }
            } catch (e: Exception) {
                trace?.error(traceLabel + "_REQUEST", null, e)
                status.text = "模型分析失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text = "未获得摄像头权限"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // release() 是 suspend API；Activity 销毁时转到独立 IO 协程释放，避免非法同步调用。
        val engineToRelease = paddleOcr
        paddleOcr = null
        if (engineToRelease != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { engineToRelease.release() } catch (_: Exception) { }
            }
        }
        cameraExecutor.shutdown()
    }
}
