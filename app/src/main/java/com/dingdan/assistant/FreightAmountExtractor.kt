package com.dingdan.assistant

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/** Evidence-based extraction of the amount attached to "用户出价". */
object FreightAmountExtractor {
    data class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = max(1f, bottom - top)
        val width: Float get() = max(1f, right - left)
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
     * Prefer same-scale engine agreement. If that is unavailable, accept only an exact
     * cross-scale match between 2x and grayscale-2x evidence. A lone enhanced result is
     * never enough to auto-confirm a fare.
     */
    fun resolve(candidates: List<Candidate?>): Result {
        val valid = candidates.filterNotNull()
        fun amountKey(value: Double) = (value * 100.0).toLong()
        fun group(variant: String) = valid.filter { it.variant == variant }
        fun consensus(rows: List<Candidate>): Double? {
            if (rows.size < 2) return null
            val values = rows.map { amountKey(it.amount) }.distinct()
            return if (values.size == 1) rows.first().amount else null
        }
        val original = group("original")
        val twoX = group("upscale_2x")
        val gray = group("gray_enhanced_2x")
        val allDetails = if (valid.isEmpty()) "未找到候选金额"
            else valid.joinToString("；") {
                "${it.source}/${it.variant}=${it.amount}（${it.method}）"
            }
        val originalConsensus = consensus(original)
        val twoXConsensus = consensus(twoX)
        val enhancedConsensus = consensus(gray)
        val transformed = twoX + gray
        val transformedValues = transformed.map { amountKey(it.amount) }.distinct()
        val crossScaleConsensus = if (twoX.isNotEmpty() && gray.isNotEmpty() &&
            transformedValues.size == 1) transformed.first().amount else null

        // Different image variants from the same OCR engine are not independent votes.
        // Count each engine only once per amount. This handles cases such as:
        // ML Kit/original=61.6, PP-OCR/2x=61.6, ML Kit/2x=1.6.
        // The matching amount has support from two engines; the dissent is only one
        // transformed result from an engine that already supplied a direct original result.
        val engineSupport = valid.groupBy { amountKey(it.amount) }
            .mapValues { (_, rows) -> rows.map { it.source }.distinct() }
        val corroboratedAmounts = engineSupport.filterValues { it.size >= 2 }
        val crossEngineConsensus = if (corroboratedAmounts.size == 1) {
            valid.firstOrNull { amountKey(it.amount) == corroboratedAmounts.keys.first() }?.amount
        } else null
        val supportDetails = engineSupport.entries.joinToString("；") { (amount, sources) ->
            "${amount / 100.0}元由[${sources.joinToString(",")}]支持"
        }

        return when {
            originalConsensus != null ->
                Result(originalConsensus, "CONFIRMED_ORIGINAL", valid,
                    "$allDetails；原图双引擎一致，采用原图结果")
            twoXConsensus != null ->
                Result(twoXConsensus, "CONFIRMED_2X", valid,
                    "$allDetails；2倍图双引擎一致，采用2倍图结果")
            enhancedConsensus != null ->
                Result(enhancedConsensus, "CONFIRMED_GRAY_2X", valid,
                    "$allDetails；灰度增强2倍图双引擎一致，采用灰度增强结果")
            crossEngineConsensus != null ->
                Result(crossEngineConsensus, "CONFIRMED_CROSS_ENGINE", valid,
                    "$allDetails；跨尺度独立引擎交叉支持：$supportDetails；少数候选不作为一票否决")
            corroboratedAmounts.size > 1 ->
                Result(null, "CONFLICT", valid,
                    "$allDetails；多个金额均获得至少两个独立引擎支持：$supportDetails；金额待确认")
            crossScaleConsensus != null ->
                Result(crossScaleConsensus, "CONFIRMED_CROSS_SCALE", valid,
                    "$allDetails；普通2倍图与灰度增强2倍图候选一致，跨尺度确认")
            valid.isEmpty() ->
                Result(null, "MISSING", emptyList(), "$allDetails；金额待确认")
            original.size >= 2 && original.map { amountKey(it.amount) }.distinct().size > 1 ->
                Result(null, "CONFLICT", valid, "$allDetails；原图双引擎冲突，金额待确认")
            twoX.size >= 2 && twoX.map { amountKey(it.amount) }.distinct().size > 1 ->
                Result(null, "CONFLICT", valid, "$allDetails；2倍图双引擎冲突，金额待确认")
            transformedValues.size > 1 ->
                Result(null, "CONFLICT", valid, "$allDetails；放大/灰度尺度候选冲突，金额待确认")
            else ->
                Result(null, "INSUFFICIENT_EVIDENCE", valid,
                    "$allDetails；缺少同尺度双引擎一致或2倍与灰度跨尺度一致证据，金额待确认")
        }
    }

    private fun extract(lines: List<Line>, fallbackText: String, variant: String, source: String): Candidate? {
        val labels = lines.filter { it.text.contains("用户出价") && !isReward(it.text) }
        for (label in labels) {
            val tail = label.text.substringAfter("用户出价", "")
            parseNumber(tail)?.let { return Candidate(it, source, variant, "同一文字框") }
        }

        for (label in labels) {
            val nearby = lines.asSequence()
                .filter { it !== label && !isReward(it.text) }
                .mapNotNull { row ->
                    val amount = parseNumber(row.text) ?: return@mapNotNull null
                    val overlap = max(0f, min(label.bottom, row.bottom) - max(label.top, row.top))
                    val overlapRatio = overlap / min(label.height, row.height)
                    val centerDelta = abs((label.top + label.bottom) / 2f - (row.top + row.bottom) / 2f)
                    val gap = row.left - label.right
                    val height = max(label.height, row.height)
                    // OCR may split one visual phrase into overlapping boxes. Permit overlap,
                    // but reject text that is far away or on a clearly different row.
                    val sameRow = overlapRatio >= 0.12f || centerDelta <= height * 0.82f
                    val plausibleHorizontal = gap >= -height * 1.60f && gap <= max(height * 4.0f, 120f)
                    if (!sameRow || !plausibleHorizontal) return@mapNotNull null
                    val score = centerDelta / height + abs(gap) / height * 0.12f
                    Triple(row, amount, score)
                }
                .sortedBy { it.third }
                .firstOrNull()
            if (nearby != null) return Candidate(nearby.second, source, variant,
                "空间关联文字框(overlap/center/gap score=${"%.2f".format(Locale.US, nearby.third)})")
        }

        val compact = fallbackText.replace('．', '.').replace('：', ':')
        val match = Regex("""用户出价[^\d]{0,12}([0-9]{1,7}(?:\.[0-9]{1,2})?)""").find(compact)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?.let { Candidate(it, source, variant, "同一文本行回退") }
    }

    private fun parseNumber(text: String): Double? {
        if (isReward(text)) return null
        val normalized = text.replace('．', '.').replace(',', ' ')
        val match = numberRegex.find(normalized) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun isReward(text: String): Boolean =
        !text.contains("用户出价") && (text.contains("多赚") || text.contains("奖励") || text.contains("红包"))
}
