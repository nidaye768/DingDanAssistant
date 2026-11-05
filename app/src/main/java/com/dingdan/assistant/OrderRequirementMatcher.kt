package com.dingdan.assistant

/**
 * Compares AI-extracted order requirements with the locally saved single-vehicle profile.
 * Missing values remain UNKNOWN; this class never creates a final accept-order verdict.
 */
object OrderRequirementMatcher {
    data class Result(
        val vehicleMatch: OrderDecisionEngine.Match,
        val criticalRequirementsClear: Boolean,
        val reasons: List<String>
    )

    fun evaluate(order: OrderRemarkAnalysis, vehicle: OrderRuleConfig): Result {
        val reasons = mutableListOf<String>()
        var mismatch = false
        var unknown = false

        fun compareText(label: String, required: String?, actual: String?) {
            if (required.isNullOrBlank() || actual.isNullOrBlank()) {
                unknown = true
                reasons += label + "无法匹配：订单要求或车辆档案未填写"
                return
            }
            val req = normalize(required)
            val own = normalize(actual)
            if (!(req.contains(own) || own.contains(req))) {
                mismatch = true
                reasons += label + "不匹配：订单要求“" + required + "”，车辆档案“" + actual + "”"
            } else reasons += label + "匹配"
        }

        if (!order.vehicleType.isNullOrBlank()) compareText("车型", order.vehicleType, vehicle.vehicleType)
        else {
            unknown = true
            reasons += "订单车型要求未识别"
        }

        compareCapacity("车长", order.vehicleLengthM, vehicle.vehicleLengthM, "米", reasons).also {
            if (it == Capacity.MISMATCH) mismatch = true
            if (it == Capacity.UNKNOWN) unknown = true
        }
        compareCapacity("货物重量/车辆额定载重", order.cargoWeightKg, vehicle.maxPayloadKg, "kg", reasons).also {
            if (it == Capacity.MISMATCH) mismatch = true
            if (it == Capacity.UNKNOWN) unknown = true
        }
        compareCapacity("货物体积/车辆可用容积", order.cargoVolumeM3, vehicle.maxVolumeM3, "方", reasons).also {
            if (it == Capacity.MISMATCH) mismatch = true
            if (it == Capacity.UNKNOWN) unknown = true
        }

        val tailboardRequired = listOf(order.vehicleType.orEmpty(), order.loadingUnloading.orEmpty(),
            order.summary).any { text ->
            val s = text.lowercase()
            s.contains("尾板") || s.contains("升降尾板")
        }
        if (tailboardRequired) {
            if (vehicle.hasTailboard) reasons += "尾板要求匹配"
            else {
                mismatch = true
                reasons += "订单要求尾板，但车辆档案未设置有尾板"
            }
        }

        if (order.unclearItems.isNotEmpty()) {
            unknown = true
            reasons += "订单存在" + order.unclearItems.size + "项待确认内容"
        }
        if (order.risks.isNotEmpty()) {
            unknown = true
            reasons += "订单存在潜在风险，需核对原备注"
        }

        val match = when {
            mismatch -> OrderDecisionEngine.Match.MISMATCHED
            unknown -> OrderDecisionEngine.Match.UNKNOWN
            else -> OrderDecisionEngine.Match.MATCHED
        }
        return Result(match, !unknown && !mismatch, reasons)
    }

    private enum class Capacity { MATCH, MISMATCH, UNKNOWN }

    private fun compareCapacity(
        label: String,
        required: Double?,
        actual: Double?,
        unit: String,
        reasons: MutableList<String>
    ): Capacity {
        if (required == null) {
            reasons += label + "未从订单中可靠提取"
            return Capacity.UNKNOWN
        }
        if (actual == null) {
            reasons += label + "车辆参数未设置"
            return Capacity.UNKNOWN
        }
        return if (actual + 1e-6 >= required) {
            reasons += label + "满足：" + actual + unit + " ≥ " + required + unit
            Capacity.MATCH
        } else {
            reasons += label + "不足：车辆" + actual + unit + "，订单需要" + required + unit
            Capacity.MISMATCH
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s·•/\\-]+"), "")
}
