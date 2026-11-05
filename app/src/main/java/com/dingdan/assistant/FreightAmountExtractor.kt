package com.dingdan.assistant

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Evidence-based extraction of the amount attached to "用户出价". */
object FreightAmountExtractor {
    data class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = max(1f, bottom - top)
    }
    data class Candidate(val amount: Double, val source: String, val variant: String, val method: String)
    data class Result(val confirmedAmount: Double?, val state: String, val candidates: List<Candidate>, val details: String)

    private val numberRegex = Regex("""(?<![\d.])([0-9]{1,7}(?:[.．][0-9]{1,2})?)(?=\s*(?:元|¥|￥|\b|$))""")

    fun fromPp(text: String, evidence: String, variant: String, source: String): Candidate? {
        val lines = mutableListOf<Line>()
        evidence.split(Regex("""\s*\|\|\s*|\n""")).forEach { chunk ->
            val value = Regex("""text=(.*?);\s*confidence=""").find(chunk)?.groupValues?.getOrNull(1) ?: return@forEach
            val points = Regex("""\((-?[0-9.]+),\s*(-?[0-9.]+)\)""").findAll(chunk)
                .map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()
            if (points.size >= 2) lines += Line(value, points.minOf { it.first }, points.minOf { it.second },
                points.maxOf { it.first }, points.maxOf { it.second })
        }
        return extract(lines, text, variant, source)
    }

    fun fromMl(jsonText: String, variant: String, source: String = "ML Kit"): Candidate? {
        val root = try { JSONObject(jsonText) } catch (_: Exception) { return null }
        val lines = mutableListOf<Line>()
        val blocks = root.optJSONArray("blocks")
        if (blocks != null) for (i in 0 until blocks.length()) {
            val rows = blocks.optJSONObject(i)?.optJSONArray("lines") ?: continue
            for (j in 0 until rows.length()) {
                val row = rows.optJSONObject(j) ?: continue
                val b = row.optJSONObject("bounding_box") ?: continue
                lines += Line(row.optString("text"), b.optDouble("left").toFloat(), b.optDouble("top").toFloat(),
                    b.optDouble("right").toFloat(), b.optDouble("bottom").toFloat())
            }
        }
        if (lines.isEmpty()) {
            val rows = root.optJSONArray("lines")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val b = row.optJSONObject("bounding_box") ?: continue
                lines += Line(row.optString("text"), b.optDouble("left").toFloat(), b.optDouble("top").toFloat(),
                    b.optDouble("right").toFloat(), b.optDouble("bottom").toFloat())
            }
        }
        return extract(lines, root.optString("recognized_text"), variant, source)
    }

    fun fromDiagnosticPp(ocr: JSONObject?, variant: String): Candidate? {
        if (ocr == null || ocr.optString("status") != "success") return null
        val rows = ocr.optJSONArray("lines") ?: return null
        val lines = mutableListOf<Line>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val pts = row.optJSONArray("box_points") ?: continue
            val xy = (0 until pts.length()).mapNotNull { j ->
                val p = pts.optJSONObject(j) ?: return@mapNotNull null
                p.optDouble("x").toFloat() to p.optDouble("y").toFloat()
            }
            if (xy.size >= 2) lines += Line(row.optString("text"), xy.minOf { it.first }, xy.minOf { it.second },
                xy.maxOf { it.first }, xy.maxOf { it.second })
        }
        return extract(lines, ocr.optString("recognized_text"), variant, "PP-OCR")
    }

    fun fromDiagnosticMl(ml: JSONObject?, variant: String): Candidate? =
        if (ml == null || ml.optString("status") != "success") null else fromMl(ml.toString(), variant, "ML Kit")

    /**
     * Resolve evidence within each image scale first. Low-resolution mistakes must not veto
     * a same-scale agreement from the 2x image.
     */
    fun resolve(candidates: List<Candidate?>): Result {
        val valid = candidates.filterNotNull()
        fun amountKey(value: Double) = (value * 100.0).toLong()
        fun group(variant: String) = valid.filter { it.variant == variant }
        fun consensus(rows: List<Candidate?>): Double? {
            val present = rows.filterNotNull()
            if (present.size < 2) return null
            val values = present.map { amountKey(it.amount) }.distinct()
            return if (values.size == 1) present.first().amount else null
        }
        val original = group("original")
        val twoX = group("upscale_2x")
        val details = if (valid.isEmpty()) "未找到与“用户出价”标签可靠关联的金额"
            else valid.joinToString("；") {
                it.source + "/" + it.variant + "=" + it.amount + "（" + it.method + "）"
            }
        val originalConsensus = consensus(original)
        val twoXConsensus = consensus(twoX)
        return when {
            originalConsensus != null ->
                Result(originalConsensus, "CONFIRMED_ORIGINAL", valid,
                    details + "；原图同尺度双引擎一致，采用原图结果；其他尺度不参与否决")
            twoXConsensus != null ->
                Result(twoXConsensus, "CONFIRMED_2X", valid,
                    details + "；原图证据不一致或不足，2倍图同尺度双引擎一致，采用2倍图结果")
            valid.isEmpty() ->
                Result(null, "MISSING", emptyList(), details + "；运费待确认")
            original.size >= 2 && original.map { amountKey(it.amount) }.distinct().size > 1 ->
                Result(null, "CONFLICT", valid, details + "；原图双引擎冲突且2倍图没有一致结论，运费待确认")
            twoX.size >= 2 && twoX.map { amountKey(it.amount) }.distinct().size > 1 ->
                Result(null, "CONFLICT", valid, details + "；2倍图双引擎冲突，运费待确认")
            else ->
                Result(null, "INSUFFICIENT_EVIDENCE", valid,
                    details + "；同一尺度缺少两个引擎的一致证据，运费待确认")
        }
    }

    private fun extract(lines: List<Line>, fallbackText: String, variant: String, source: String): Candidate? {
        val labels = lines.filter { it.text.contains("用户出价") && !isReward(it.text) }
        for (label in labels) {
            val tail = label.text.substringAfter("用户出价", "")
            parseNumber(tail)?.let { return Candidate(it, source, variant, "同一文字框") }
        }
        for (label in labels) {
            val nearby = lines.asSequence().filter { it !== label && !isReward(it.text) }.filter { row ->
                val overlap = max(0f, min(label.bottom, row.bottom) - max(label.top, row.top))
                val ratio = overlap / min(label.height, row.height)
                val centerDelta = abs((label.top + label.bottom) / 2f - (row.top + row.bottom) / 2f)
                val gap = row.left - label.right
                ratio >= 0.25f && centerDelta <= max(label.height, row.height) * 0.65f &&
                    gap >= -label.height * 0.40f && gap <= max(label.height * 2.5f, 80f)
            }.sortedBy { it.left }.mapNotNull { parseNumber(it.text) }.firstOrNull()
            if (nearby != null) return Candidate(nearby, source, variant, "标签右侧同水平文字框")
        }
        val compact = fallbackText.replace('．', '.').replace('：', ':')
        val match = Regex("""用户出价[^\d]{0,8}([0-9]{1,7}(?:\.[0-9]{1,2})?)""").find(compact)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { Candidate(it, source, variant, "同一文本行") }
    }

    private fun parseNumber(text: String): Double? {
        if (isReward(text)) return null
        val match = numberRegex.find(text.replace('．', '.').replace(',', ' ')) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun isReward(text: String): Boolean =
        !text.contains("用户出价") && (text.contains("多赚") || text.contains("奖励") || text.contains("红包"))
}
