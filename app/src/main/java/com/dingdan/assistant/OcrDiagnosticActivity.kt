package com.dingdan.assistant

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Standalone, local-only OCR diagnostic workbench.
 * It records every stage and exports a JSON/TXT/images bundle for remote debugging.
 */
class OcrDiagnosticActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var source: Bitmap? = null
    private var lastDiagnosticDir: File? = null
    private lateinit var preview: ProbeSelectionView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var expected: EditText
    private lateinit var runButton: Button
    private lateinit var exportButton: Button

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadImage(uri)
    }
    private val zipPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) exportZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF17212B.toInt())
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
        setOnClickListener { action() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F5F7.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(0xFF172A3A.toInt())
        }
        header.addView(TextView(this).apply {
            text = "OCR 诊断探针中心"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "本地诊断｜PP-OCRv6 + ML Kit｜导出可分析日志"
            textSize = 12f
            setTextColor(0xFFD5DEE4.toInt())
        })
        root.addView(header)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(label("1. 导入订单截图", 17f, true))
        body.addView(button("从相册选择图片", 0xFFE45B42.toInt()) { imagePicker.launch("image/*") })
        body.addView(label("2. 在图片上框选金额所在行（尽量包含“用户出价”和金额）", 13f))
        preview = ProbeSelectionView(this)
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(300)))
        body.addView(button("清除框选", 0xFF687581.toInt()) { preview.clearSelection() })
        body.addView(label("3. 预期运费金额（可留空）", 13f, true))
        expected = EditText(this).apply {
            hint = "例如 61.6"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        body.addView(expected)
        body.addView(label("4. 运行诊断：原图、2倍、3倍、2倍灰度增强；每组都跑 PP-OCR 和 ML Kit。", 12f))
        runButton = button("开始 OCR 诊断", 0xFF246B8E.toInt()) { runDiagnostic() }
        body.addView(runButton)
        status = label("等待导入图片", 13f, true)
        body.addView(status)
        body.addView(label("诊断结论与探针日志", 17f, true))
        result = label("尚无诊断结果")
        result.setPadding(dp(10), dp(10), dp(10), dp(10))
        result.setBackgroundColor(Color.WHITE)
        body.addView(result)
        exportButton = button("导出诊断 ZIP（JSON + TXT + 图片）", 0xFF426B55.toInt()) {
            if (lastDiagnosticDir == null) status.text = "请先完成一次诊断"
            else zipPicker.launch("OCR_Diagnostic_" + timestamp() + ".zip")
        }
        exportButton.isEnabled = false
        body.addView(exportButton)
        body.addView(label("隐私说明：诊断默认只保存在本机，不会上传订单截图或日志。只有你主动分享导出的 ZIP，内容才会离开手机。", 11f))
        setContentView(root)
    }

    private fun loadImage(uri: Uri) {
        status.text = "正在读取图片…"
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("无法读取所选图片")
                }
                require(bmp.width > 100 && bmp.height > 100) { "图片尺寸太小" }
                source?.recycle()
                source = bmp
                preview.setBitmap(bmp)
                result.text = "图片已载入：${bmp.width} × ${bmp.height}\n请在图片上框选金额所在行。"
                status.text = "图片载入成功"
            } catch (e: Exception) {
                status.text = "图片读取失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    private fun runDiagnostic() {
        val original = source ?: run { status.text = "请先导入图片"; return }
        val roi = preview.selectedBitmap() ?: run {
            status.text = "请先在图片上框选金额行，避免整张订单图干扰诊断"
            return
        }
        runButton.isEnabled = false
        exportButton.isEnabled = false
        status.text = "正在执行 P1-P6 探针…"
        result.text = "诊断进行中，请勿退出页面。"
        scope.launch {
            val started = System.currentTimeMillis()
            val id = "OCR_" + timestamp() + "_" + (System.currentTimeMillis() % 10000)
            val dir = File(filesDir, "ocr_diagnostics/$id")
            val imagesDir = File(dir, "images")
            val probes = JSONObject()
            val failures = JSONArray()
            val variants = mutableListOf<Pair<String, Bitmap>>()
            var paddle: PaddleOCR? = null
            var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            try {
                withContext(Dispatchers.IO) {
                    check(imagesDir.mkdirs() || imagesDir.isDirectory) { "无法创建诊断目录" }
                    saveJpeg(original, File(imagesDir, "01_original.jpg"))
                    saveJpeg(roi, File(imagesDir, "02_roi_original.jpg"))
                }

                // P1: source image, ROI geometry and basic image statistics.
                val imageProbe = JSONObject()
                    .put("source_width", original.width)
                    .put("source_height", original.height)
                    .put("roi_width", roi.width)
                    .put("roi_height", roi.height)
                    .put("roi_geometry_in_source", preview.selectionGeometryJson())
                    .put("source_stats", imageStats(original))
                    .put("roi_stats", imageStats(roi))
                probes.put("P1_image", imageProbe)

                // P2: build controlled variants; each output is persisted for visual inspection.
                variants.add("original" to roi)
                variants.add("upscale_2x" to scale(roi, 2))
                variants.add("upscale_3x" to scale(roi, 3))
                variants.add("gray_enhanced_2x" to enhanceGray(variants[1].second))
                val preprocess = JSONArray()
                variants.forEachIndexed { index, pair ->
                    val fileName = "variant_${index + 1}_${pair.first}.jpg"
                    withContext(Dispatchers.IO) { saveJpeg(pair.second, File(imagesDir, fileName)) }
                    preprocess.put(JSONObject()
                        .put("name", pair.first)
                        .put("width", pair.second.width)
                        .put("height", pair.second.height)
                        .put("stats", imageStats(pair.second))
                        .put("file", "images/$fileName"))
                }
                probes.put("P2_preprocess", preprocess)

                // P3/P4/P5: run both engines on every controlled image variant.
                val engineResults = JSONArray()
                var ppInitError: String? = null
                try {
                    val initStarted = System.currentTimeMillis()
                    paddle = withContext(Dispatchers.IO) {
                        System.loadLibrary("opencv_java4")
                        check(OpenCVUtils.init(this@OcrDiagnosticActivity)) { "OpenCVUtils.init returned false" }
                        PaddleOCR.create(this@OcrDiagnosticActivity)
                    }
                    probes.put("pp_ocr_init", JSONObject()
                        .put("status", "success")
                        .put("elapsed_ms", System.currentTimeMillis() - initStarted)
                        .put("cold_load_time_ms", paddle?.coldLoadTimeMs ?: JSONObject.NULL))
                } catch (e: Exception) {
                    ppInitError = e.localizedMessage ?: e.javaClass.simpleName
                    failures.put(JSONObject().put("stage", "PP_OCR_INIT").put("error", ppInitError))
                    probes.put("pp_ocr_init", JSONObject().put("status", "failed").put("error", ppInitError))
                }
                recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

                variants.forEachIndexed { index, pair ->
                    val name = pair.first
                    val bitmap = pair.second
                    status.text = "正在诊断 $name（${index + 1}/${variants.size}）…"
                    val entry = JSONObject().put("variant", name)
                        .put("width", bitmap.width).put("height", bitmap.height)
                    val ppStarted = System.currentTimeMillis()
                    if (paddle != null) {
                        try {
                            val pp = withContext(Dispatchers.IO) { paddle!!.recognize(bitmap) }
                            val lines = JSONArray()
                            pp.results.forEach { line ->
                                val points = JSONArray()
                                line.box.points.forEach { point ->
                                    points.put(JSONObject().put("x", point.x).put("y", point.y))
                                }
                                lines.put(JSONObject()
                                    .put("text", line.text)
                                    .put("confidence", line.confidence)
                                    .put("box_points", points))
                            }
                            entry.put("pp_ocr", JSONObject()
                                .put("status", "success")
                                .put("line_count", pp.lineCount)
                                .put("detection_time_ms", pp.detectionTimeMs)
                                .put("recognition_time_ms", pp.recognitionTimeMs)
                                .put("total_time_ms", pp.totalTimeMs)
                                .put("wall_time_ms", System.currentTimeMillis() - ppStarted)
                                .put("recognized_text", pp.results.joinToString("\n") { it.text })
                                .put("lines", lines))
                            val overlay = drawBoxes(bitmap, lines)
                            withContext(Dispatchers.IO) {
                                saveJpeg(overlay, File(imagesDir, "boxes_$name.jpg"))
                                overlay.recycle()
                            }
                            entry.put("box_overlay_file", "images/boxes_$name.jpg")
                        } catch (e: Exception) {
                            val message = e.localizedMessage ?: e.javaClass.simpleName
                            entry.put("pp_ocr", JSONObject().put("status", "failed").put("error", message)
                                .put("wall_time_ms", System.currentTimeMillis() - ppStarted))
                            failures.put(JSONObject().put("stage", "PP_OCR_$name").put("error", message))
                        }
                    } else {
                        entry.put("pp_ocr", JSONObject().put("status", "unavailable").put("error", ppInitError))
                    }

                    val mlStarted = System.currentTimeMillis()
                    try {
                        val mlResult = recognizer!!.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                        entry.put("ml_kit", mlKitEvidence(mlResult)
                            .put("status", "success")
                            .put("wall_time_ms", System.currentTimeMillis() - mlStarted))
                    } catch (e: Exception) {
                        val message = e.localizedMessage ?: e.javaClass.simpleName
                        entry.put("ml_kit", JSONObject().put("status", "failed").put("error", message)
                            .put("wall_time_ms", System.currentTimeMillis() - mlStarted))
                        failures.put(JSONObject().put("stage", "ML_KIT_$name").put("error", message))
                    }
                    engineResults.put(entry)
                }
                probes.put("P3_P4_P5_engine_results", engineResults)

                // P6: parse each engine against each image variant independently.
                // Upscaled/grayscale OCR is evidence only and never contaminates original-image results.
                val variantParsers = JSONArray()
                var ppOriginalText = ""
                var mlOriginalText = ""
                var ppFare: Double? = null
                var mlFare: Double? = null
                for (engineIndex in 0 until engineResults.length()) {
                    val item = engineResults.optJSONObject(engineIndex) ?: continue
                    val variant = item.optString("variant", "unknown")
                    val pp = item.optJSONObject("pp_ocr")
                    val ml = item.optJSONObject("ml_kit")
                    val ppText = pp?.optString("recognized_text", "").orEmpty()
                    val mlText = ml?.optString("recognized_text", "").orEmpty()
                    val ppValue = if (pp?.optString("status") == "success") extractAmount(ppText, "用户出价") else null
                    val mlValue = if (ml?.optString("status") == "success") extractAmount(mlText, "用户出价") else null
                    variantParsers.put(JSONObject()
                        .put("image_variant", variant)
                        .put("pp_ocr_status", pp?.optString("status") ?: "missing")
                        .put("pp_ocr_freight_yuan", ppValue ?: JSONObject.NULL)
                        .put("pp_ocr_wall_time_ms", pp?.optLong("wall_time_ms") ?: JSONObject.NULL)
                        .put("ml_kit_status", ml?.optString("status") ?: "missing")
                        .put("ml_kit_freight_yuan", mlValue ?: JSONObject.NULL)
                        .put("ml_kit_wall_time_ms", ml?.optLong("wall_time_ms") ?: JSONObject.NULL))
                    if (variant == "original") {
                        ppOriginalText = ppText
                        mlOriginalText = mlText
                        ppFare = ppValue
                        mlFare = mlValue
                    }
                }
                val expectedValue = expected.text.toString().trim().toDoubleOrNull()
                val parser = JSONObject()
                    .put("target_field", "freight_yuan")
                    .put("expected_value", expectedValue ?: JSONObject.NULL)
                    .put("primary_image_variant", "original")
                    .put("pp_ocr_freight_yuan", ppFare ?: JSONObject.NULL)
                    .put("ml_kit_freight_yuan", mlFare ?: JSONObject.NULL)
                    .put("variant_results", variantParsers)
                    .put("regex", "用户出价[\\s:：¥￥]*([0-9]{1,7}(?:[.．][0-9]{1,2})?)")
                    .put("platform_reward", "ignored")
                probes.put("P6_parser", parser)

                val diagnosis = classify(ppOriginalText, mlOriginalText, ppFare, mlFare, expectedValue)
                probes.put("automatic_diagnosis", diagnosis)
                probes.put("failures", failures)
                probes.put("elapsed_total_ms", System.currentTimeMillis() - started)

                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val reportJson = JSONObject()
                    .put("schema", "dingdan_ocr_diagnostic")
                    .put("schema_version", 1)
                    .put("test_id", id)
                    .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()))
                    .put("app", JSONObject()
                        .put("package", packageName)
                        .put("version_name", packageInfo.versionName ?: "unknown")
                        .put("version_code", packageInfo.versionCode)
                        .put("source_revision", "not_embedded_in_this_demo_build"))
                    .put("device", JSONObject()
                        .put("manufacturer", android.os.Build.MANUFACTURER)
                        .put("model", android.os.Build.MODEL)
                        .put("android_release", android.os.Build.VERSION.RELEASE)
                        .put("sdk_int", android.os.Build.VERSION.SDK_INT))
                    .put("models", JSONObject()
                        .put("pp_ocr", "PP-OCRv6 Android SDK bundled AAR; internal model build identifier is not exposed by SDK")
                        .put("ml_kit", "Google ML Kit Chinese text recognition"))
                    .put("probes", probes)

                withContext(Dispatchers.IO) {
                    File(dir, "diagnostic.json").writeText(reportJson.toString(2), Charsets.UTF_8)
                    val textReport = buildString {
                        appendLine("货拉拉盯单助手 OCR 诊断报告")
                        appendLine("测试编号：$id")
                        appendLine("APP：${packageInfo.versionName}（versionCode=${packageInfo.versionCode}）")
                        appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                        appendLine("目标字段：freight_yuan")
                        appendLine("预期运费：${expectedValue ?: "未填写"}")
                        appendLine("自动诊断（仅原图）：$diagnosis")
                        appendLine("PP-OCR 提取用户出价：${ppFare ?: "未提取"}")
                        appendLine("ML Kit 提取用户出价：${mlFare ?: "未提取"}")
                        appendLine("总耗时：${System.currentTimeMillis() - started} ms")
                        appendLine()
                        appendLine("========== 结构化探针 JSON ==========")
                        appendLine(reportJson.toString(2))
                    }
                    File(dir, "report.txt").writeText(textReport, Charsets.UTF_8)
                    val manifest = JSONObject()
                    val files = JSONArray()
                    dir.walkTopDown().filter { it.isFile && it.name != "manifest.json" }.forEach { file ->
                        files.put(JSONObject()
                            .put("path", file.relativeTo(dir).invariantSeparatorsPath)
                            .put("size_bytes", file.length())
                            .put("sha256", sha256(file)))
                    }
                    manifest.put("test_id", id).put("files", files)
                    File(dir, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
                }
                lastDiagnosticDir = dir
                result.text = buildString {
                    appendLine("测试编号：$id")
                    appendLine("自动诊断：$diagnosis")
                    appendLine("PP-OCR 用户出价：${ppFare ?: "未提取"}")
                    appendLine("ML Kit 用户出价：${mlFare ?: "未提取"}")
                    appendLine()
                    appendLine("详细报告与原图、裁剪图、放大图、检测框图已保存。")
                    appendLine("点击下方按钮导出 ZIP 发给我分析。")
                }
                status.text = "诊断完成｜耗时 ${System.currentTimeMillis() - started} ms"
                exportButton.isEnabled = true
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                failures.put(JSONObject().put("stage", "UNHANDLED").put("error", message))
                status.text = "诊断中断：$message"
                result.text = "诊断发生异常。已完成的探针可能仍保存在临时目录。\n错误：$message"
            } finally {
                try { paddle?.release() } catch (_: Exception) { }
                try { recognizer?.close() } catch (_: Exception) { }
                variants.forEach { if (it.second !== roi && !it.second.isRecycled) it.second.recycle() }
                variantsRecycle(roi)
                runButton.isEnabled = true
            }
        }
    }

    private fun variantsRecycle(roi: Bitmap) {
        // The selected ROI is an independent bitmap. Scaled variants are reclaimed by Android
        // after the coroutine completes; recycle only this explicitly-created crop here.
        try { if (!roi.isRecycled) roi.recycle() } catch (_: Exception) { }
    }

    private fun mlKitEvidence(text: com.google.mlkit.vision.text.Text): JSONObject {
        val lines = JSONArray()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val box = line.boundingBox
                val item = JSONObject().put("text", line.text).put("confidence", line.confidence)
                if (box != null) item.put("bounding_box", JSONObject()
                    .put("left", box.left).put("top", box.top)
                    .put("right", box.right).put("bottom", box.bottom))
                lines.put(item)
            }
        }
        return JSONObject().put("recognized_text", text.text).put("lines", lines)
    }

    private fun extractAmount(text: String, label: String): Double? {
        val normalized = text.replace('．', '.').replace('：', ':').replace('　', ' ')
        val pattern = Regex(Regex.escape(label) + "[\\s:：¥￥]*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")
        return pattern.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun classify(pp: String, ml: String, ppFare: Double?, mlFare: Double?, expectedValue: Double?): String {
        val hasFareLabel = pp.contains("用户出价") || ml.contains("用户出价")
        val anyAmountNearLabel = ppFare != null || mlFare != null
        return when {
            !hasFareLabel -> "DETECTION_MISS_OR_LABEL_MISS：两套 OCR 都未识别到“用户出价”标签；结合检测框图检查是否框到目标区域"
            hasFareLabel && !anyAmountNearLabel -> "RECOGNITION_ERROR：标签可见但金额未被规则提取；检查 PP-OCR 检测框及识别文本"
            ppFare != null && expectedValue != null && kotlin.math.abs(ppFare - expectedValue) < 0.001 -> "PP_OCR_MATCH：PP-OCR 金额与人工填写的预期值一致"
            mlFare != null && expectedValue != null && kotlin.math.abs(mlFare - expectedValue) < 0.001 && ppFare != mlFare -> "ENGINE_DISAGREEMENT：ML Kit 与预期值一致，PP-OCR 结果不同"
            ppFare != null && mlFare != null && ppFare != mlFare -> "ENGINE_DISAGREEMENT：两套 OCR 提取的金额不一致"
            ppFare != null && mlFare != null && ppFare == mlFare -> "OCR_AGREEMENT：两套 OCR 的金额一致；仍需人工对照截图"
            else -> "NEED_MANUAL_CHECK：当前日志不足以定位问题"
        }
    }

    private fun imageStats(bitmap: Bitmap): JSONObject {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 180)
        var count = 0
        var sum = 0.0
        var sum2 = 0.0
        var neighborDiff = 0.0
        var neighborCount = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val gray = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c))
                sum += gray
                sum2 += gray * gray
                count++
                if (x + step < bitmap.width) {
                    val n = bitmap.getPixel(x + step, y)
                    val ng = 0.299 * Color.red(n) + 0.587 * Color.green(n) + 0.114 * Color.blue(n)
                    neighborDiff += kotlin.math.abs(gray - ng)
                    neighborCount++
                }
                x += step
            }
            y += step
        }
        val mean = if (count == 0) 0.0 else sum / count
        val variance = if (count == 0) 0.0 else (sum2 / count - mean * mean).coerceAtLeast(0.0)
        return JSONObject()
            .put("sample_step", step)
            .put("mean_brightness_0_255", mean)
            .put("contrast_stddev", kotlin.math.sqrt(variance))
            .put("horizontal_neighbor_mean_abs_diff", if (neighborCount == 0) 0.0 else neighborDiff / neighborCount)
    }

    private fun scale(bitmap: Bitmap, factor: Int): Bitmap =
        Bitmap.createScaledBitmap(bitmap, bitmap.width * factor, bitmap.height * factor, true)

    private fun enhanceGray(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c))
            val enhanced = ((gray - 128.0) * 1.45 + 128.0).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(enhanced, enhanced, enhanced)
        }
        output.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return output
    }

    private fun drawBoxes(bitmap: Bitmap, lines: JSONArray): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        for (i in 0 until lines.length()) {
            val points = lines.optJSONObject(i)?.optJSONArray("box_points") ?: continue
            val xs = mutableListOf<Float>()
            val ys = mutableListOf<Float>()
            for (j in 0 until points.length()) {
                val point = points.optJSONObject(j) ?: continue
                xs.add(point.optDouble("x").toFloat())
                ys.add(point.optDouble("y").toFloat())
            }
            if (xs.isNotEmpty() && ys.isNotEmpty()) {
                canvas.drawRect(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f,
                    xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f, paint)
            }
        }
        return copy
    }

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) { "图片保存失败：${file.name}" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun exportZip(uri: Uri) {
        val dir = lastDiagnosticDir ?: run { status.text = "找不到诊断记录"; return }
        status.text = "正在打包诊断文件…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        ZipOutputStream(output).use { zip ->
                            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entry = ZipEntry(file.relativeTo(dir).invariantSeparatorsPath)
                                zip.putNextEntry(entry)
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法创建导出文件")
                }
                status.text = "ZIP 导出完成，请把文件发送到当前对话"
            } catch (e: Exception) {
                status.text = "导出失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        source?.recycle()
        source = null
    }

    private suspend fun <T> Task<T>.awaitResult(): T = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
}

private class ProbeSelectionView(context: android.content.Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5722.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55333333
        style = Paint.Style.FILL
    }
    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val selection = RectF()
    private var startX = 0f
    private var startY = 0f
    private var selecting = false

    init {
        isClickable = true
        isFocusable = true
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        selection.setEmpty()
        invalidate()
    }

    fun clearSelection() {
        selection.setEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202830.toInt())
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect.set(left, top, left + w, top + h)
        canvas.drawBitmap(image, null, imageRect, imagePaint)
        if (!selection.isEmpty) {
            canvas.drawRect(selection, fillPaint)
            canvas.drawRect(selection, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (imageRect.isEmpty) invalidate()
                if (!imageRect.contains(event.x, event.y)) return true
                startX = event.x.coerceIn(imageRect.left, imageRect.right)
                startY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                selection.set(startX, startY, startX, startY)
                selecting = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) update(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) update(event.x, event.y)
                selecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        selection.set(
            minOf(startX, x).coerceIn(imageRect.left, imageRect.right),
            minOf(startY, y).coerceIn(imageRect.top, imageRect.bottom),
            maxOf(startX, x).coerceIn(imageRect.left, imageRect.right),
            maxOf(startY, y).coerceIn(imageRect.top, imageRect.bottom)
        )
        invalidate()
    }

    fun selectedBitmap(): Bitmap? {
        val image = bitmap ?: return null
        if (selection.width() < 12f || selection.height() < 12f || imageRect.width() <= 0f) return null
        val sx = image.width / imageRect.width()
        val sy = image.height / imageRect.height()
        val left = ((selection.left - imageRect.left) * sx).toInt().coerceIn(0, image.width - 1)
        val top = ((selection.top - imageRect.top) * sy).toInt().coerceIn(0, image.height - 1)
        val right = ((selection.right - imageRect.left) * sx).toInt().coerceIn(left + 1, image.width)
        val bottom = ((selection.bottom - imageRect.top) * sy).toInt().coerceIn(top + 1, image.height)
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    }

    fun selectionGeometryJson(): JSONObject {
        if (selection.isEmpty || imageRect.width() <= 0f) return JSONObject().put("selected", false)
        val image = bitmap ?: return JSONObject().put("selected", false)
        val sx = image.width / imageRect.width()
        val sy = image.height / imageRect.height()
        return JSONObject()
            .put("left", ((selection.left - imageRect.left) * sx).toInt())
            .put("top", ((selection.top - imageRect.top) * sy).toInt())
            .put("right", ((selection.right - imageRect.left) * sx).toInt())
            .put("bottom", ((selection.bottom - imageRect.top) * sy).toInt())
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
