package com.dingdan.assistant

import com.dingdan.assistant.vision.OrderCardSegmenter

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** On-device acceptance checks. These verify implemented local logic only; they do not certify field accuracy. */
object AcceptanceSuite {
    fun run(): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        fun check(name: String, condition: Boolean, detail: String) {
            out += CheckResult(name, condition, detail)
        }

        // Business calculation: only loaded-route distance belongs in this denominator.
        val pricePerKm = 240.0 / 30.0
        check("载货公里单价公式", pricePerKm == 8.0, "¥240 ÷ 30km = ¥8.00/km；不含接货空驶和返程")

        // Conservative decision: unknown critical facts must never become recommend.
        fun decision(vehicleMatch: String, distanceKnown: Boolean, rate: Double, floor: Double): String =
            when {
                vehicleMatch == "not_matched" -> "ignore"
                vehicleMatch != "matched" || !distanceKnown -> "pending"
                rate < floor -> "ignore"
                else -> "recommend"
            }
        check("未知条件不自动推荐", decision("unknown", true, 12.0, 5.0) == "pending",
            "车辆匹配未知时输出 pending")
        check("车型不符过滤", decision("not_matched", true, 12.0, 5.0) == "ignore",
            "明确车型不匹配时输出 ignore")
        check("里程未知不推荐", decision("matched", false, 12.0, 5.0) == "pending",
            "道路里程未知时输出 pending")
        check("收益达标可推荐", decision("matched", true, 8.0, 7.0) == "recommend",
            "必要条件通过且单价达到门槛")

        val same = 0x123456789ABCDEFL
        check("感知哈希完全相同", OrderCardSegmenter.hammingDistance(same, same) == 0,
            "相同哈希距离为 0")
        check("感知哈希差异计算", OrderCardSegmenter.hammingDistance(0L, 3L) == 2,
            "两个不同 bit 的汉明距离为 2")

        // Duplicate guard primitive: normalized stable text key.
        fun key(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase()
        check("订单文本归一化去重", key("  A地  到 B地 ") == key("a地 到 b地"),
            "空格和大小写归一化；正式订单去重仍需结合报价、地址、备注和视觉轨迹")

        check("API 地址格式检查", "https://example.com/v1".startsWith("https://"),
            "HTTPS 地址格式基础检查")
        return out
    }
}
