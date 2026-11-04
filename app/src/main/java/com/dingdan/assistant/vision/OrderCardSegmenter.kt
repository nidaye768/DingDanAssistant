package com.dingdan.assistant.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V1.1 first-pass segmenter for the photographed Huolala order-list screen.
 * It detects ONLY the red "即时 / 距您xx公里" header strips and crops each card.
 * Green/blue elements seen inside cards are content labels, NOT header anchors.
 * from one red header to the next. It deliberately does NOT call any network API.
 *
 * Important: input must first be cropped to the phone display area. This class
 * supports a manually calibrated rectangular screen ROI; perspective correction
 * is a separate next step.
 */
object OrderCardSegmenter {
    data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun clamp(): NormalizedRect = NormalizedRect(
            left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
        )
    }

    data class Config(
        // Initial estimate from the supplied 720x1600 video. User must calibrate if framing changes.
        val screenRoi: NormalizedRect = NormalizedRect(0.12f, 0.18f, 0.89f, 0.97f),
        val redRatioThreshold: Float = 0.48f,
        val minHeaderHeightRatio: Float = 0.008f,
        val maxHeaderHeightRatio: Float = 0.075f,
        val minCardHeightRatio: Float = 0.12f,
        val mergeGapRows: Int = 8,
        val rowStep: Int = 2
    )

    data class Card(
        val bitmap: Bitmap,
        val topInScreen: Float,
        val bottomInScreen: Float,
        val isPartialTop: Boolean,
        val isPartialBottom: Boolean,
        val perceptualHash: Long
    )

    data class Result(
        val screenCrop: Bitmap,
        val headerBands: List<IntRange>,
        val cards: List<Card>,
        val looksLikeOrderList: Boolean,
        val reason: String
    )

    fun process(frame: Bitmap, config: Config = Config()): Result {
        val roi = config.screenRoi.clamp()
        val l = (frame.width * roi.left).toInt().coerceIn(0, frame.width - 1)
        val t = (frame.height * roi.top).toInt().coerceIn(0, frame.height - 1)
        val r = (frame.width * roi.right).toInt().coerceIn(l + 1, frame.width)
        val b = (frame.height * roi.bottom).toInt().coerceIn(t + 1, frame.height)
        val screen = Bitmap.createBitmap(frame, l, t, r - l, b - t)

        val bands = findRedHeaderBands(screen, config)
        val minCardHeight = (screen.height * config.minCardHeightRatio).toInt()
        val cards = mutableListOf<Card>()

        // The card starts at its red header and ends at the next red header.
        // The last visible card is retained as a partial-bottom card.
        bands.forEachIndexed { index, band ->
            val y1 = band.first.coerceIn(0, screen.height - 1)
            val y2 = if (index < bands.lastIndex) bands[index + 1].first else screen.height
            if (y2 - y1 >= minCardHeight) {
                val crop = Bitmap.createBitmap(screen, 0, y1, screen.width, y2 - y1)
                cards += Card(
                    bitmap = crop,
                    topInScreen = y1.toFloat() / screen.height,
                    bottomInScreen = y2.toFloat() / screen.height,
                    isPartialTop = false,
                    isPartialBottom = index == bands.lastIndex,
                    perceptualHash = differenceHash(crop)
                )
            }
        }

        val listLike = bands.isNotEmpty() && cards.isNotEmpty()
        val reason = when {
            bands.size >= 2 -> "检测到多个红色订单标题栏"
            bands.size == 1 -> "只检测到一个红色标题栏；可能是单卡片列表或不完整画面，需结合OCR复核"
            else -> "未检测到红色订单标题栏；可能是详情页、地图页或屏幕区域未校准"
        }
        return Result(screen, bands, cards, listLike, reason)
    }

    private fun findRedHeaderBands(bitmap: Bitmap, config: Config): List<IntRange> {
        val w = bitmap.width
        val h = bitmap.height
        val step = config.rowStep.coerceAtLeast(1)
        val rows = mutableListOf<Pair<Int, Boolean>>()
        var y = 0
        while (y < h) {
            var red = 0
            var sampled = 0
            // Ignore narrow edges where phone bezel/reflections are common.
            val x0 = (w * 0.04f).toInt()
            val x1 = (w * 0.96f).toInt()
            var x = x0
            while (x < x1) {
                val c = bitmap.getPixel(x, y)
                if (isHeaderRed(c)) red++
                sampled++
                x += 2
            }
            val ratio = if (sampled == 0) 0f else red.toFloat() / sampled
            rows += y to (ratio >= config.redRatioThreshold)
            y += step
        }

        val raw = mutableListOf<IntRange>()
        var start: Int? = null
        var last = 0
        rows.forEach { (row, active) ->
            if (active) {
                if (start == null) start = row
                last = row
            } else if (start != null) {
                raw += start!!..last
                start = null
            }
        }
        if (start != null) raw += start!!..last

        val merged = mutableListOf<IntRange>()
        raw.forEach { band ->
            val previous = merged.lastOrNull()
            if (previous != null && band.first - previous.last <= config.mergeGapRows) {
                merged[merged.lastIndex] = previous.first..band.last
            } else merged += band
        }
        return merged.filter {
            val heightRatio = (it.last - it.first + 1).toFloat() / h
            heightRatio in config.minHeaderHeightRatio..config.maxHeaderHeightRatio
        }
    }

    private fun isHeaderRed(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // The supplied video shows red order header strips only. Green/blue in-card
        // labels are deliberately NOT treated as card boundaries. Reject pale skin,
        // white screen and dark bezel.
        return r > 145 && r > g * 1.38 && r > b * 1.28 && g < 145
    }

    /** 64-bit difference hash, suitable as one signal in a multi-signal deduper. */
    fun differenceHash(bitmap: Bitmap): Long {
        val sample = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val left = Color.red(sample.getPixel(x, y))
            val right = Color.red(sample.getPixel(x + 1, y))
            if (left > right) hash = hash or (1L shl bit)
            bit++
        }
        if (sample !== bitmap) sample.recycle()
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
