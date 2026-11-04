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
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Demo dataset collector for YOLO object detection.
 * Class 0 = complete order card; class 1 = partial order card.
 * This page collects/exports YOLO-format data; model training runs externally.
 */
class YoloTrainingActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bitmap: Bitmap? = null
    private lateinit var canvasView: AnnotationView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var classSpinner: Spinner
    private lateinit var saveButton: Button
    private val rootDir by lazy { File(filesDir, "yolo_dataset") }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadImage(uri)
    }
    private val createZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) exportZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStats()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 14f, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFF17212B.toInt())
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            setBackgroundColor(0xFFF3F5F7.toInt())
        }
        root.addView(label("YOLO 训练中心", 23f, true))
        root.addView(label("数据采集与人工标注｜本页保存 YOLO 格式数据，模型训练在云端执行。", 12f))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(button("从相册添加订单图片") { picker.launch("image/*") })
        body.addView(label("标注类别"))
        classSpinner = Spinner(this)
        classSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("完整订单卡片（class 0）", "部分露出订单卡片（class 1）"))
        body.addView(classSpinner)
        canvasView = AnnotationView(this)
        classSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                canvasView.setClassId(position)
            }
        }
        canvasView.setBackgroundColor(0xFF202830.toInt())
        body.addView(canvasView, LinearLayout.LayoutParams(-1, dp(360)))
        body.addView(label("操作：在图片上按住并拖动，框出一张订单卡片；一张图可以画多个框。", 12f))
        body.addView(button("撤销最后一个框") {
            canvasView.undo()
            status.text = "已撤销最后一个标注框"
        })
        saveButton = button("保存图片和 YOLO 标注") { saveSample() }
        saveButton.isEnabled = false
        body.addView(saveButton)
        body.addView(button("导出 YOLO 数据集 ZIP") {
            createZip.launch("dingdan_yolo_dataset.zip")
        })
        body.addView(label("数据集统计", 17f, true))
        stats = label("正在统计…", 13f)
        body.addView(stats)
        body.addView(label("标注说明：框要紧贴订单卡片外边缘；不要把连点器悬浮按钮、页面空白或相邻订单包含进去。部分露出的订单标为 class 1。", 12f))
        status = label("请先选择图片。", 13f, true)
        body.addView(status)
        setContentView(root)
    }

    private fun loadImage(uri: Uri) {
        status.text = "正在读取图片…"
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("图片读取失败")
                }
                require(loaded.width > 100 && loaded.height > 100) { "图片尺寸过小" }
                bitmap?.recycle()
                bitmap = loaded
                canvasView.setBitmap(loaded)
                saveButton.isEnabled = true
                status.text = "图片已载入：${loaded.width} × ${loaded.height}，请框选订单卡片。"
            } catch (e: Exception) {
                status.text = "载入失败：${e.localizedMessage}"
            }
        }
    }

    private fun saveSample() {
        val source = bitmap ?: run { status.text = "请先选择图片"; return }
        val boxes = canvasView.getBoxes()
        if (boxes.isEmpty()) { status.text = "请至少框选一张订单卡片"; return }
        saveButton.isEnabled = false
        status.text = "正在保存图片和标注…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val count = countSamples()
                    val split = when (count % 10) { 8 -> "val"; 9 -> "test"; else -> "train" }
                    val id = "order_${System.currentTimeMillis()}"
                    val imageDir = File(rootDir, "images/$split").apply { mkdirs() }
                    val labelDir = File(rootDir, "labels/$split").apply { mkdirs() }
                    val imageFile = File(imageDir, "$id.jpg")
                    FileOutputStream(imageFile).use { out ->
                        check(source.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "图片写入失败" }
                    }
                    val text = boxes.joinToString("\n") { box ->
                        val cls = box.classId
                        String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                            cls, box.cx, box.cy, box.width, box.height)
                    } + "\n"
                    File(labelDir, "$id.txt").writeText(text, Charsets.UTF_8)
                    File(rootDir, "classes.txt").writeText("order_card\npartial_order\n", Charsets.UTF_8)
                    "$id 已保存到 $split，标注框：${boxes.size}"
                }
                status.text = result
                refreshStats()
                canvasView.clearBoxes()
            } catch (e: Exception) {
                status.text = "保存失败：${e.localizedMessage}"
            } finally { saveButton.isEnabled = true }
        }
    }

    private fun countSamples(): Int =
        listOf("train", "val", "test").sumOf { split ->
            File(rootDir, "images/$split").listFiles()?.count { it.extension.equals("jpg", true) } ?: 0
        }

    private fun refreshStats() {
        val lines = listOf("train", "val", "test").map { split ->
            val n = File(rootDir, "images/$split").listFiles()?.count { it.extension.equals("jpg", true) } ?: 0
            "$split：$n 张"
        }
        stats.text = lines.joinToString("    ") + "\n总计：${countSamples()} 张"
    }

    private fun exportZip(uri: Uri) {
        status.text = "正在打包数据集…"
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(countSamples() > 0) { "数据集为空，请先保存标注样本" }
                    val yaml = "path: .\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n  0: order_card\n  1: partial_order\n"
                    contentResolver.openOutputStream(uri)?.use { output ->
                        ZipOutputStream(output).use { zip ->
                            zip.putNextEntry(ZipEntry("dataset.yaml")); zip.write(yaml.toByteArray()); zip.closeEntry()
                            rootDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                                zip.putNextEntry(ZipEntry(relative))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: error("无法创建导出文件")
                    countSamples()
                }
                status.text = "导出成功：$count 张图片及 YOLO 标注。可用于后续云端训练。"
            } catch (e: Exception) {
                status.text = "导出失败：${e.localizedMessage}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        bitmap?.recycle()
        bitmap = null
    }
}

private data class YoloBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val classId: Int
) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val width get() = right - left
    val height get() = bottom - top
}

private class AnnotationView(context: android.content.Context) : View(context) {
    init { isClickable = true; isFocusable = true }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00C853.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14f * resources.displayMetrics.density
    }
    private var image: Bitmap? = null
    private val imageRect = RectF()
    private val boxes = mutableListOf<YoloBox>()
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var dragging = false
    private var currentClass = 0

    fun setBitmap(value: Bitmap) { image = value; boxes.clear(); invalidate() }
    fun setClassId(value: Int) { currentClass = value }
    fun getBoxes(): List<YoloBox> = boxes.toList()
    fun undo() { if (boxes.isNotEmpty()) boxes.removeAt(boxes.lastIndex); invalidate() }
    fun clearBoxes() { boxes.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202830.toInt())
        val b = image ?: return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale; val h = b.height * scale
        val l = (width - w) / 2f; val t = (height - h) / 2f
        imageRect.set(l, t, l + w, t + h)
        canvas.drawBitmap(b, null, imageRect, imagePaint)
        boxes.forEachIndexed { index, box ->
            val r = toViewRect(box.left, box.top, box.right, box.bottom)
            canvas.drawRect(r, boxPaint)
            canvas.drawText("${box.classId}:#${index + 1}", r.left, (r.top - 5f).coerceAtLeast(18f), textPaint)
        }
        if (dragging) {
            val r = RectF(minOf(startX, currentX), minOf(startY, currentY),
                maxOf(startX, currentX), maxOf(startY, currentY))
            canvas.drawRect(r, boxPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = image ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!imageRect.contains(event.x, event.y)) return true
                startX = event.x.coerceIn(imageRect.left, imageRect.right)
                startY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                currentX = startX; currentY = startY; dragging = true
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) { currentX = event.x; currentY = event.y; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    currentX = event.x.coerceIn(imageRect.left, imageRect.right)
                    currentY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                    val rect = RectF(minOf(startX, currentX), minOf(startY, currentY),
                        maxOf(startX, currentX), maxOf(startY, currentY))
                    if (rect.width() >= 12f && rect.height() >= 12f) {
                        val left = ((rect.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                        val top = ((rect.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                        val right = ((rect.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                        val bottom = ((rect.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                        boxes.add(YoloBox(left, top, right, bottom, currentClass))
                    }
                    dragging = false
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate(); performClick(); return true
            }
        }
        return true
    }

    private fun toViewRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(imageRect.left + left * imageRect.width(), imageRect.top + top * imageRect.height(),
            imageRect.left + right * imageRect.width(), imageRect.top + bottom * imageRect.height())

    override fun performClick(): Boolean { super.performClick(); return true }
}
