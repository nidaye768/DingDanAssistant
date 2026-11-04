package com.dingdan.assistant

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * YOLO dataset collector.
 * class 0 = complete order card; class 1 = partial order card.
 * This activity collects and exports YOLO-format data. Training is external.
 */
class YoloTrainingActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bitmap: Bitmap? = null
    private lateinit var canvasView: AnnotationView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var progress: TextView
    private lateinit var classSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var saveNextButton: Button
    private lateinit var helpText: TextView
    private val rootDir by lazy { File(filesDir, "yolo_dataset") }
    private val sourceMapDir by lazy { File(rootDir, "source_map").apply { mkdirs() } }
    private val imageQueue = mutableListOf<Uri>()
    private var currentIndex = -1
    private var currentSourceName = "image"

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) setQueue(listOf(uri), "单张图片")
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) loadFolder(uri)
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
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(0xFFF3F5F7.toInt())
        }
        root.addView(label("YOLO 训练中心", 22f, true))
        root.addView(label("批量导入 · 连续标注 · 数据集导出", 12f))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(button("选择文件夹：导入文件夹内全部图片") { folderPicker.launch(null) })
        body.addView(button("只添加一张图片") { imagePicker.launch("image/*") })
        progress = label("尚未导入图片", 13f, true)
        body.addView(progress)

        body.addView(label("标注类别"))
        classSpinner = Spinner(this)
        classSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("完整订单卡片（class 0）", "部分露出订单卡片（class 1）"))
        body.addView(classSpinner)

        canvasView = AnnotationView(this)
        canvasView.setBackgroundColor(0xFF202830.toInt())
        body.addView(canvasView, LinearLayout.LayoutParams(-1, dp(390)))
        classSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                canvasView.setClassId(position)
            }
        }
        body.addView(label("框选方法：手指按住订单卡片左上角附近，拖到右下角后松开。一张图片可画多个框。框选可从图片边缘开始。", 12f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(button("上一张") { moveTo(currentIndex - 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(button("下一张") { moveTo(currentIndex + 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(nav)

        body.addView(button("撤销最后一个框") {
            canvasView.undo()
            status.text = "已撤销最后一个标注框"
        })
        body.addView(button("清空当前图片全部标注框") {
            canvasView.clearBoxes()
            status.text = "当前图片标注框已清空；尚未保存的数据不会受影响"
        })
        body.addView(button("删除当前图片已保存的样本") { deleteCurrentSample() })
        body.addView(button("批量删除已保存的错误样本") { showBatchDeleteDialog() })
        saveButton = button("只保存当前图片标注") { saveCurrent(false) }
        saveButton.isEnabled = false
        body.addView(saveButton)
        saveNextButton = button("保存并自动切换下一张") { saveCurrent(true) }
        saveNextButton.isEnabled = false
        body.addView(saveNextButton)

        body.addView(button("显示 / 隐藏详细操作说明") {
            helpText.visibility = if (helpText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        helpText = label(
            "【YOLO训练数据操作说明】\n" +
            "1. 点击“选择文件夹”，在系统文件选择器中选中存放订单截图的文件夹并确认。系统会扫描该文件夹及其子文件夹中的 JPG、PNG、WEBP、BMP 图片。\n" +
            "2. 图片按文件名排序，显示当前序号。可用“上一张/下一张”切换；建议按顺序逐张标注。\n" +
            "3. 选择标注类别：完整订单卡片选 class 0；只露出一部分的订单卡片选 class 1。切换类别后再画框。\n" +
            "4. 在预览图上按住订单卡片左上角附近，向右下拖动到卡片右下边缘后松手。框应尽量贴合卡片外边缘，不要包含相邻卡片、空白区域或连点器悬浮按钮。\n" +
            "5. 一张图有多张订单时，逐张画多个框；画错可点“撤销最后一个框”。\n" +
            "6. 点“保存并自动切换下一张”，当前原图和全部框选会保存，再自动打开下一张。若暂时不想切图，可点“只保存当前图片标注”。\n" +
            "7. 图片和标注保存在 APP 私有目录。完成一批后点“导出 YOLO 数据集 ZIP”，通过系统保存窗口存到手机或云盘。建议每标注20～30张就导出备份。\n" +
            "8. 数据集按约80%训练集、10%验证集、10%测试集分配。当前页面只负责采集、标注和导出，不在手机本地训练模型。\n" +
            "9. 标注原则：边界一致、类别正确、不要漏掉卡片；同一订单不要重复画框。部分卡片如无法确认边界，先不要标注或记录后复核。\n" +
            "10. 卸载 APP 会删除私有目录中的未导出数据，务必及时导出备份。",
            12f
        ).apply { visibility = View.GONE }
        body.addView(helpText)

        body.addView(button("导出 YOLO 数据集 ZIP") { createZip.launch("dingdan_yolo_dataset.zip") })
        body.addView(label("数据集统计", 17f, true))
        stats = label("正在统计…", 13f)
        body.addView(stats)
        status = label("请先选择文件夹或图片。", 13f, true)
        body.addView(status)
        setContentView(root)
    }

    private fun setQueue(items: List<Uri>, source: String) {
        imageQueue.clear()
        imageQueue.addAll(items.distinct())
        currentSourceName = source
        if (imageQueue.isEmpty()) {
            status.text = "没有找到支持的图片。"
            updateProgress()
            return
        }
        moveTo(0)
    }

    private fun loadFolder(treeUri: Uri) {
        status.text = "正在扫描文件夹图片…"
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    collectImages(treeUri)
                }
                setQueue(items, "文件夹")
                status.text = "扫描完成：找到 ${items.size} 张图片。"
            } catch (e: Exception) {
                status.text = "扫描文件夹失败：${e.localizedMessage}"
            }
        }
    }

    private fun collectImages(treeUri: Uri): List<Uri> {
        val result = mutableListOf<Uri>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        fun walk(parentId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id)
                    } else if (mime.startsWith("image/") ||
                        name.lowercase(Locale.ROOT).let { it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                            it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".bmp") }) {
                        result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }
        }
        walk(rootId)
        return result
    }

    private fun moveTo(index: Int) {
        if (index !in imageQueue.indices) {
            status.text = if (imageQueue.isEmpty()) "请先导入图片" else "已到图片队列末尾。"
            return
        }
        currentIndex = index
        canvasView.clearBoxes()
        saveButton.isEnabled = false
        saveNextButton.isEnabled = false
        status.text = "正在读取第 ${index + 1} 张图片…"
        updateProgress()
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(imageQueue[index])?.use { BitmapFactory.decodeStream(it) }
                        ?: error("图片读取失败")
                }
                require(loaded.width > 100 && loaded.height > 100) { "图片尺寸过小" }
                bitmap?.recycle()
                bitmap = loaded
                canvasView.setBitmap(loaded)
                saveButton.isEnabled = true
                saveNextButton.isEnabled = true
                status.text = "图片已载入：${loaded.width} × ${loaded.height}。请开始标注。"
            } catch (e: Exception) {
                status.text = "第 ${index + 1} 张载入失败：${e.localizedMessage}"
                updateProgress()
            }
        }
    }

    private fun updateProgress() {
        progress.text = if (imageQueue.isEmpty()) "尚未导入图片"
            else "图片队列：${currentIndex + 1}/${imageQueue.size}（$currentSourceName）"
    }

    private fun saveCurrent(advance: Boolean) {
        val source = bitmap ?: run { status.text = "当前没有图片"; return }
        val boxes = canvasView.getBoxes()
        if (boxes.isEmpty()) { status.text = "当前图片没有标注框，请至少框选一张订单卡片"; return }
        saveButton.isEnabled = false
        saveNextButton.isEnabled = false
        status.text = "正在保存当前图片和标注…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val sourceKey = sourceKey(imageQueue[currentIndex])
                    val mapping = File(sourceMapDir, "$sourceKey.txt")
                    val existing = if (mapping.exists()) mapping.readText(Charsets.UTF_8).trim().split("|") else emptyList()
                    val split: String
                    val id: String
                    if (existing.size == 2 && existing[0] in listOf("train", "val", "test")) {
                        split = existing[0]
                        id = existing[1]
                    } else {
                        val count = countSamples()
                        split = when (count % 10) { 8 -> "val"; 9 -> "test"; else -> "train" }
                        id = "order_${System.currentTimeMillis()}"
                        mapping.writeText("$split|$id", Charsets.UTF_8)
                    }
                    val imageDir = File(rootDir, "images/$split").apply { mkdirs() }
                    val labelDir = File(rootDir, "labels/$split").apply { mkdirs() }
                    val imageFile = File(imageDir, "$id.jpg")
                    FileOutputStream(imageFile).use { out ->
                        check(source.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "图片写入失败" }
                    }
                    val text = boxes.joinToString("\n") { box ->
                        String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                            box.classId, box.cx, box.cy, box.width, box.height)
                    } + "\n"
                    File(labelDir, "$id.txt").writeText(text, Charsets.UTF_8)
                    File(rootDir, "classes.txt").writeText("order_card\npartial_order\n", Charsets.UTF_8)
                    "$id 已保存到 $split，标注框：${boxes.size}"
                }
                status.text = result
                refreshStats()
                if (advance) {
                    if (currentIndex + 1 < imageQueue.size) moveTo(currentIndex + 1)
                    else status.text = "$result；已完成全部图片。"
                } else {
                    canvasView.clearBoxes()
                    saveButton.isEnabled = true
                    saveNextButton.isEnabled = true
                }
            } catch (e: Exception) {
                status.text = "保存失败：${e.localizedMessage}"
                saveButton.isEnabled = true
                saveNextButton.isEnabled = true
            }
        }
    }


    private fun sourceKey(uri: Uri): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sampleFiles(): List<File> =
        listOf("train", "val", "test").flatMap { split ->
            File(rootDir, "images/$split").listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", true) }
                ?.sortedBy { it.name } ?: emptyList()
        }

    private fun deleteSample(imageFile: File): Boolean {
        val split = imageFile.parentFile?.name ?: return false
        val id = imageFile.nameWithoutExtension
        val labelFile = File(rootDir, "labels/$split/$id.txt")
        val imageDeleted = !imageFile.exists() || imageFile.delete()
        val labelDeleted = !labelFile.exists() || labelFile.delete()
        if (imageDeleted && labelDeleted) {
            sourceMapDir.listFiles()?.forEach { map ->
                if (runCatching { map.readText(Charsets.UTF_8).trim() == "$split|$id" }.getOrDefault(false)) {
                    map.delete()
                }
            }
            return true
        }
        return false
    }

    private fun deleteCurrentSample() {
        if (currentIndex !in imageQueue.indices) {
            status.text = "请先打开一张图片"
            return
        }
        val key = sourceKey(imageQueue[currentIndex])
        val mapping = File(sourceMapDir, "$key.txt")
        if (!mapping.exists()) {
            status.text = "当前图片还没有已保存的样本，无需删除"
            return
        }
        val parts = mapping.readText(Charsets.UTF_8).trim().split("|")
        if (parts.size != 2) {
            status.text = "样本索引异常，未执行删除"
            return
        }
        val imageFile = File(rootDir, "images/\${parts[0]}/\${parts[1]}.jpg")
        AlertDialog.Builder(this)
            .setTitle("删除当前图片样本？")
            .setMessage("将同时删除这张图片和对应的 YOLO 标注文件，不能撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ok = deleteSample(imageFile)
                status.text = if (ok) "当前图片已保存样本删除成功" else "删除失败，请检查文件权限"
                refreshStats()
            }.show()
    }

    private fun showBatchDeleteDialog() {
        val files = sampleFiles()
        if (files.isEmpty()) {
            status.text = "数据集中没有已保存样本"
            return
        }
        val names = files.map { file ->
            val split = file.parentFile?.name ?: ""
            "\${file.nameWithoutExtension}（$split）"
        }.toTypedArray()
        val checked = BooleanArray(files.size)
        AlertDialog.Builder(this)
            .setTitle("选择要删除的错误样本")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("取消", null)
            .setPositiveButton("删除所选") { _, _ ->
                val selected = files.indices.filter { checked[it] }
                if (selected.isEmpty()) {
                    status.text = "未选择样本"
                } else {
                    val deleted = selected.count { deleteSample(files[it]) }
                    status.text = "已删除 \${deleted}/\${selected.size} 个样本（图片及对应标签）"
                    refreshStats()
                }
            }.show()
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
                status.text = "导出成功：$count 张图片及 YOLO 标注。"
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
    private val edgeTolerance get() = 18f * resources.displayMetrics.density

    fun setBitmap(value: Bitmap) { image = value; boxes.clear(); calculateImageRect(); invalidate() }
    fun setClassId(value: Int) { currentClass = value }
    fun getBoxes(): List<YoloBox> = boxes.toList()
    fun undo() { if (boxes.isNotEmpty()) boxes.removeAt(boxes.lastIndex); invalidate() }
    fun clearBoxes() { boxes.clear(); invalidate() }

    private fun calculateImageRect() {
        val b = image ?: return
        if (width <= 0 || height <= 0) return
        val inset = 2f * resources.displayMetrics.density
        val scale = minOf((width - inset * 2) / b.width, (height - inset * 2) / b.height)
        val w = b.width * scale; val h = b.height * scale
        val l = (width - w) / 2f; val t = (height - h) / 2f
        imageRect.set(l, t, l + w, t + h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateImageRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202830.toInt())
        val b = image ?: return
        calculateImageRect()
        canvas.drawBitmap(b, null, imageRect, imagePaint)
        boxes.forEachIndexed { index, box ->
            val r = toViewRect(box.left, box.top, box.right, box.bottom)
            canvas.drawRect(r, boxPaint)
            canvas.drawText("${box.classId}:#${index + 1}", r.left, (r.top - 5f).coerceAtLeast(18f), textPaint)
        }
        if (dragging) {
            canvas.drawRect(RectF(minOf(startX, currentX), minOf(startY, currentY),
                maxOf(startX, currentX), maxOf(startY, currentY)), boxPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                calculateImageRect()
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = RectF(imageRect).apply { inset(-edgeTolerance, -edgeTolerance) }
                if (!hit.contains(event.x, event.y)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                startX = event.x.coerceIn(imageRect.left, imageRect.right)
                startY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                currentX = startX; currentY = startY; dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    currentX = event.x.coerceIn(imageRect.left, imageRect.right)
                    currentY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                    invalidate()
                }
                return dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    currentX = event.x.coerceIn(imageRect.left, imageRect.right)
                    currentY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                    val rect = RectF(minOf(startX, currentX), minOf(startY, currentY),
                        maxOf(startX, currentX), maxOf(startY, currentY))
                    if (rect.width() >= 8f && rect.height() >= 8f) {
                        val left = ((rect.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                        val top = ((rect.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                        val right = ((rect.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                        val bottom = ((rect.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                        boxes.add(YoloBox(left, top, right, bottom, currentClass))
                    }
                    dragging = false
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    private fun toViewRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(imageRect.left + left * imageRect.width(), imageRect.top + top * imageRect.height(),
            imageRect.left + right * imageRect.width(), imageRect.top + bottom * imageRect.height())

    override fun performClick(): Boolean { super.performClick(); return true }
}
