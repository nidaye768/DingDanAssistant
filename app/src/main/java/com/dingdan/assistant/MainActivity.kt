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
        body.addView(actionButton("打开 YOLO 训练中心", 0xFF426B55.toInt()) { startActivity(Intent(this, YoloTrainingActivity::class.java)) })

        body.addView(section("验收中心", ink))
        body.addView(text("本机自检覆盖：单价公式、保守决策、哈希差异、文本归一化。真实识别率和提醒准确率需用标注订单/视频验收。", 12f, muted))
        body.addView(actionButton("运行本机自动验收", 0xFF246B8E.toInt()) { runSelfTests() })
        body.addView(actionButton("测试大模型 API 连通性", 0xFF426B55.toInt()) { testApi() })
        body.addView(actionButton("清空测试输出", 0xFF687581.toInt()) { output.text = "等待测试或识别结果…" })

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
        status.text = "正在拍摄…"
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) { runOnUiThread { status.text = "图片读取失败" }; return }
                    val segmentation = OrderCardSegmenter.process(bitmap)
                    val yolo = try { YoloOrderDetector.detect(this@MainActivity, bitmap) } catch (e: Exception) { null }
                    scope.launch {
                        status.text = "PP-OCRv6 主引擎识别中…"
                        var ppDiagnostic = ""
                        val ppText = try {
                            withContext(Dispatchers.IO) { recognizeWithPaddle(bitmap).also { ppDiagnostic = it.second }.first }
                        } catch (e: Exception) {
                            ppDiagnostic = "PP-OCRv6 主引擎失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
                            ""
                        }
                        status.text = "ML Kit 辅助校验中…"
                        val mlKitText = try {
                            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                            try { recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().text }
                            finally { recognizer.close() }
                        } catch (e: Exception) {
                            "ML Kit 辅助校验失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
                        }
                        val primaryText = ppText.ifBlank { mlKitText }
                        val report = buildString {
                            appendLine("模式：${if (mode == "fast") "极速" else "全自动"}")
                            appendLine("OCR 主引擎：PP-OCRv6")
                            appendLine(if (ppText.isNotBlank()) "主引擎结果（用于后续分析）：\n$ppText" else "主引擎不可用，临时回退 ML Kit；本次结果需谨慎使用。")
                            appendLine("\nPP-OCRv6 运行信息：$ppDiagnostic")
                            appendLine("\nML Kit 辅助校验结果：\n$mlKitText")
                            appendLine()
                            if (yolo != null) {
                                appendLine("YOLO11n ONNX：本机推理 ${yolo.inferenceMs} ms｜检测框 ${yolo.detections.size} 个")
                                yolo.detections.forEachIndexed { i, d ->
                                    appendLine("YOLO框 ${i + 1}：${d.className}｜置信度 ${"%.1f".format(d.confidence * 100)}%")
                                }
                            } else appendLine("YOLO模型暂不可用：保留规则分割结果")
                            appendLine("红色标题栏（规则回退）：${segmentation.headerBands.size} 条")
                            appendLine("规则卡片：${segmentation.cards.size} 张｜${segmentation.reason}")
                            segmentation.cards.forEachIndexed { i, card ->
                                appendLine("卡片 ${i + 1}：${if (card.isPartialBottom) "底部部分卡片" else "已截取"}｜hash=${"%016x".format(card.perceptualHash)}")
                            }
                            appendLine("\n说明：PP-OCRv6 是主识别结果；ML Kit 只用于对照，不覆盖主结果。若关键数字或地址存在冲突，后续字段提取应标记待确认。")
                            appendLine("本Demo不自动抢单。")
                        }
                        output.text = report
                        status.text = if (ppText.isNotBlank()) "PP-OCRv6 主识别完成，ML Kit 辅助校验完成" else "PP-OCRv6失败，已回退ML Kit（需人工确认）"
                        if (primaryText.isNotBlank()) requestVision(bitmap, primaryText)
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { status.text = "拍摄失败：${e.localizedMessage}" }
                }
            })
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
        val details = "模型已加载｜检测 ${result.detectionTimeMs}ms｜识别 ${result.recognitionTimeMs}ms｜总计 ${result.totalTimeMs}ms｜文本行 ${result.lineCount}"
        recognizedText to details
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

    private fun requestVision(bitmap: Bitmap, ocr: String) {
        val base = apiBase.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        val modelName = model.text.toString().trim()
        if (base.isBlank() || key.isBlank() || modelName.isBlank()) {
            status.text = "OCR 已完成；填写 API 配置后可调用视觉模型"; return
        }
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
                    val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    val prompt = "你是货运订单审核助手。请识别报价、车型、起终点和备注要求；将重量、体积、尺寸、装卸、时间、费用、人员、动力类型等逐项拆解。不确定标记 unknown，不得猜测，不自动接单。OCR：$ocr"
                    val content = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
                    val body = JSONObject().put("model", modelName)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                        .put("temperature", 0.1)
                    val req = Request.Builder().url("$base/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    client.newCall(req).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $raw")
                        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", raw)
                    }
                }
                output.append("\n\n视觉模型初步分析：\n$answer")
                status.text = "本地识别及模型分析完成"
            } catch (e: Exception) { status.text = "模型分析失败：${e.localizedMessage}" }
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
