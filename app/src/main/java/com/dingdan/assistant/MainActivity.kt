package com.dingdan.assistant

import android.Manifest
import android.content.pm.PackageManager
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
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var resultView: TextView
    private lateinit var apiBase: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences("api_config", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "盯单助手 · V1.0 Alpha"
            textSize = 21f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)
        status = TextView(this).apply {
            text = "等待摄像头权限"; textSize = 13f
            setTextColor(0xFFB8D0C5.toInt()); setPadding(0, 6, 0, 8)
        }
        root.addView(status)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val scan = Button(this).apply {
            text = "拍摄并识别当前订单画面"
            setOnClickListener { captureAndRecognize() }
        }
        root.addView(scan, LinearLayout.LayoutParams(-1, -2))

        val configTitle = TextView(this).apply {
            text = "大模型 API 配置（OpenAI 兼容接口）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            setPadding(0, 10, 0, 4)
        }
        root.addView(configTitle)
        apiBase = edit("API 地址，例如 https://api.openai.com/v1")
        apiKey = edit("API Key")
        apiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        model = edit("模型名称，例如 gpt-4o-mini")
        root.addView(apiBase); root.addView(apiKey); root.addView(model)
        val save = Button(this).apply {
            text = "保存 API 配置"
            setOnClickListener {
                prefs.edit().putString("base", apiBase.text.toString().trim())
                    .putString("key", apiKey.text.toString().trim())
                    .putString("model", model.text.toString().trim()).apply()
                status.text = "API 配置已保存在本机"
            }
        }
        root.addView(save)
        resultView = TextView(this).apply {
            text = "识别结果将在这里显示"
            textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 8, 0, 8)
        }
        val scroll = ScrollView(this).apply { addView(resultView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 150))
        setContentView(root)

        apiBase.setText(prefs.getString("base", "https://api.openai.com/v1"))
        apiKey.setText(prefs.getString("key", ""))
        model.setText(prefs.getString("model", "gpt-4o-mini"))
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 13f
        setSingleLine(true)
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF9AA8B2.toInt())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(90)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                status.text = "摄像头已启动。请将镜头对准主手机订单列表。"
            } catch (e: Exception) {
                status.text = "摄像头启动失败：${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndRecognize() {
        val capture = imageCapture ?: run {
            status.text = "摄像头尚未就绪"; return
        }
        status.text = "正在拍摄……"
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                runOnUiThread { status.text = "拍摄完成，正在本地 OCR……" }
                if (bitmap == null) {
                    runOnUiThread { status.text = "图片读取失败" }; return
                }
                val recognizer = TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
                // V1.1: 先用红色标题栏把整屏切成单张订单卡片，OCR 与上传都以卡片为单位。
                val segmentation = OrderCardSegmenter.process(bitmap)
                val cards = segmentation.cards
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        val plainText = text.text
                        val cardLines = if (cards.isEmpty()) {
                            "未切出完整卡片：${segmentation.reason}"
                        } else {
                            cards.mapIndexed { i, card ->
                                val tag = when {
                                    card.isPartialBottom -> "（底部截断，需滚动后重拍）"
                                    card.isPartialTop -> "（顶部截断）"
                                    else -> ""
                                }
                                "卡片 ${i + 1}$tag  hash=${"%016x".format(card.perceptualHash)}" +
                                    "  占屏 ${"%.1f".format(card.topInScreen * 100)}%-" +
                                    "${"%.1f".format(card.bottomInScreen * 100)}%"
                            }.joinToString("\n")
                        }
                        runOnUiThread {
                            resultView.text = buildString {
                                appendLine("OCR 原文：")
                                appendLine(plainText)
                                appendLine()
                                appendLine("红条切割：检测到 ${segmentation.headerBands.size} 条红色标题栏，" +
                                    "切出 ${cards.size} 张卡片（${segmentation.reason}）")
                                append(cardLines)
                            }
                            status.text = "本地识别完成，准备调用大模型……"
                        }
                        uploadForAnalysis(bitmap, plainText, cards.size)
                    }
                    .addOnFailureListener { e ->
                        runOnUiThread { status.text = "OCR 失败：${e.localizedMessage}" }
                    }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { status.text = "拍摄失败：${exception.localizedMessage}" }
            }
        })
    }

    // V1.1 卡片边界改由 OrderCardSegmenter 的红条锚点决定，旧的 OCR 行距启发式已移除。
    private fun uploadForAnalysis(bitmap: Bitmap, ocr: String, cardCount: Int) {
        val base = apiBase.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        val modelName = model.text.toString().trim()
        if (base.isBlank() || key.isBlank() || modelName.isBlank()) {
            runOnUiThread { status.text = "请先填写 API 地址、Key 和模型名称" }
            return
        }
        scope.launch {
            status.text = "正在上传图片并请求模型分析……"
            try {
                val response = withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    val prompt = """
                        你是货运订单审核助手。请根据截图和 OCR 文本识别订单，不要自动接单。
                        任务：
                        1. 尽可能逐条识别画面中的订单卡片。
                        2. 提取报价、车型要求、发货地、收货地、平台显示距离、备注。
                        3. 备注必须拆解为具体履约要求，识别重量、体积、尺寸、时间、装卸、
                           费用承担、人员、车辆动力类型、通行限制和其他未知要求。
                        4. 不确定的信息标记 unknown，不得猜测。
                        5. 只返回 JSON，顶层格式：
                        {"orders":[{"raw_text":"","price":"","vehicle":"","pickup":"","dropoff":"",
                        "remark":"","requirements":[{"original":"","interpretation":"","status":"unknown|clear"}],
                        "uncertainties":[]}],"screen_notes":""}

                        本地 OCR 文本：
                        $ocr

                        候选卡片区域数量（启发式估计）：$cardCount
                    """.trimIndent()
                    val content = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
                    val messages = JSONArray().put(JSONObject()
                        .put("role", "user").put("content", content))
                    val body = JSONObject().put("model", modelName)
                        .put("messages", messages).put("temperature", 0.1)
                    val request = Request.Builder()
                        .url("$base/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .header("Content-Type", "application/json")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw Exception("HTTP ${r.code}: $text")
                        val json = JSONObject(text)
                        json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", text)
                    }
                }
                resultView.append("\n\n===== 大模型分析结果 =====\n$response")
                status.text = "模型分析完成"
            } catch (e: Exception) {
                status.text = "API 调用失败：${e.localizedMessage}"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else status.text = "未获得摄像头权限，无法采集画面"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
    }
}
