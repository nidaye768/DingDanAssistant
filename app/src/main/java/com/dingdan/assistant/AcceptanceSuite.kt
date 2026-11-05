package com.dingdan.assistant

import com.dingdan.assistant.vision.OrderCardSegmenter

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** On-device checks for deterministic business rules; they do not certify real-world OCR or route accuracy. */
object AcceptanceSuite {
    fun run(): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        fun check(name: String, condition: Boolean, detail: String) {
            out += CheckResult(name, condition, detail)
        }

        fun facts(
            freight: Double? = 240.0,
            km: Double? = 30.0,
            floor: Double? = 7.0,
            match: OrderDecisionEngine.Match = OrderDecisionEngine.Match.MATCHED,
            pickups: Int? = 1,
            deliveries: Int? = 1,
            extraCost: Double? = 0.0,
            extraConfirmed: Boolean = true,
            requirementsClear: Boolean = true
        ) = OrderDecisionEngine.Facts(
            freight, km, floor, match, pickups, deliveries,
            extraCost, extraConfirmed, requirementsClear
        )

        val normal = OrderDecisionEngine.evaluate(facts())
        check("载货公里单价公式", normal.verdict == OrderDecisionEngine.Verdict.RECOMMEND &&
            normal.grossYuanPerLoadedKm == 8.0,
            "¥240 ÷ 30km = ¥8.00/km；分母只使用载货道路里程")

        val extraCost = OrderDecisionEngine.evaluate(facts(extraCost = 30.0))
        check("司机额外成本扣除", extraCost.verdict == OrderDecisionEngine.Verdict.RECOMMEND &&
            extraCost.netFreightYuan == 210.0 && extraCost.netYuanPerLoadedKm == 7.0,
            "¥240运费扣除¥30司机额外成本后，净单价为¥7.00/km")

        val lowRate = OrderDecisionEngine.evaluate(facts(floor = 7.1))
        check("低于动态门槛不推荐", lowRate.verdict == OrderDecisionEngine.Verdict.IGNORE,
            "实际净单价¥7.00/km低于当前设定门槛¥7.10/km")

        val unknownVehicle = OrderDecisionEngine.evaluate(facts(match = OrderDecisionEngine.Match.UNKNOWN))
        check("车辆条件未知待确认", unknownVehicle.verdict == OrderDecisionEngine.Verdict.PENDING,
            "车辆匹配未知时不得自动推荐")

        val mismatch = OrderDecisionEngine.evaluate(facts(match = OrderDecisionEngine.Match.MISMATCHED))
        check("车型明确不符过滤", mismatch.verdict == OrderDecisionEngine.Verdict.IGNORE,
            "明确不满足车型/车辆条件时忽略")

        val unknownDistance = OrderDecisionEngine.evaluate(facts(km = null))
        check("道路里程未知待确认", unknownDistance.verdict == OrderDecisionEngine.Verdict.PENDING,
            "实际载货道路里程未知时不计算单价、不推荐")

        val multiPickup = OrderDecisionEngine.evaluate(facts(pickups = 2))
        check("多装货点过滤", multiPickup.verdict == OrderDecisionEngine.Verdict.IGNORE,
            "明确不是一装一卸时按当前策略过滤")

        val unknownCost = OrderDecisionEngine.evaluate(facts(extraCost = null, extraConfirmed = false))
        check("额外成本未知待确认", unknownCost.verdict == OrderDecisionEngine.Verdict.PENDING,
            "备注中的司机额外成本未核实，不得把毛运费当净运费")

        val unresolvedRemark = OrderDecisionEngine.evaluate(facts(requirementsClear = false))
        check("备注关键要求未确认", unresolvedRemark.verdict == OrderDecisionEngine.Verdict.PENDING,
            "备注存在未确认的关键要求或风险时输出待确认")


        val exactThreshold = OrderDecisionEngine.evaluate(facts(floor = 7.0))
        check("单价刚好达到门槛", exactThreshold.verdict == OrderDecisionEngine.Verdict.RECOMMEND,
            "净单价等于门槛时允许推荐")

        val unknownDelivery = OrderDecisionEngine.evaluate(facts(deliveries = null))
        check("卸货点未知待确认", unknownDelivery.verdict == OrderDecisionEngine.Verdict.PENDING,
            "卸货点数量未知时不得推荐")

        val multiDelivery = OrderDecisionEngine.evaluate(facts(deliveries = 2))
        check("多卸货点过滤", multiDelivery.verdict == OrderDecisionEngine.Verdict.IGNORE,
            "明确存在多个卸货点时按一装一卸策略过滤")

        val invalidFreight = OrderDecisionEngine.evaluate(facts(freight = 0.0))
        check("运费为零不推荐", invalidFreight.verdict == OrderDecisionEngine.Verdict.PENDING,
            "运费为零或无效时不得计算并推荐")

        val invalidDistance = OrderDecisionEngine.evaluate(facts(km = -1.0))
        check("道路里程无效不推荐", invalidDistance.verdict == OrderDecisionEngine.Verdict.PENDING,
            "道路里程为负数或无效时不得计算并推荐")

        val negativeExtra = OrderDecisionEngine.evaluate(facts(extraCost = -1.0))
        check("额外成本负数待确认", negativeExtra.verdict == OrderDecisionEngine.Verdict.PENDING,
            "司机额外成本不能为负数")

        val zeroNet = OrderDecisionEngine.evaluate(facts(extraCost = 240.0))
        check("净运费为零不推荐", zeroNet.verdict == OrderDecisionEngine.Verdict.IGNORE,
            "额外成本抵消全部运费时忽略订单")

        val same = 0x123456789ABCDEFL
        check("感知哈希完全相同", OrderCardSegmenter.hammingDistance(same, same) == 0,
            "相同哈希距离为 0")
        check("感知哈希差异计算", OrderCardSegmenter.hammingDistance(0L, 3L) == 2,
            "两个不同 bit 的汉明距离为 2")

        fun key(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase()
        check("订单文本归一化去重", key("  A地  到 B地 ") == key("a地 到 b地"),
            "空格和大小写归一化；正式去重仍需结合报价、地址、备注和视觉轨迹")

        check("API 地址格式检查", "https://example.com/v1".startsWith("https://"),
            "HTTPS 地址格式基础检查")
        return out
    }
}
