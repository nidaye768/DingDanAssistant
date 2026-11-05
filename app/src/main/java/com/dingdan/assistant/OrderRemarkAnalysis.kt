package com.dingdan.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed, evidence-oriented interpretation of order remarks.
 * Null means the model could not reliably extract the field; it must never be treated as zero.
 */
data class OrderRemarkAnalysis(
    val vehicleType: String?,
    val vehicleLengthM: Double?,
    val vehicleVolumeM3: Double?,
    val cargoName: String?,
    val cargoWeightKg: Double?,
    val cargoVolumeM3: Double?,
    val cargoLengthM: Double?,
    val freightYuan: Double?,
    val extraRewardYuan: Double?,
    val pickupCount: Int?,
    val deliveryCount: Int?,
    val driverExtraCostYuan: Double?,
    val extraCostItems: List<String>,
    val tollRequirement: String?,
    val arrivalTime: String?,
    val personnelRequirement: String?,
    val loadingUnloading: String?,
    val risks: List<String>,
    val unclearItems: List<String>,
    val evidence: List<String>,
    val summary: String
) {
    fun toDisplayText(): String = buildString {
        appendLine("【备注结构化解析】")
        appendLine("车型要求：${vehicleType ?: "未知"}")
        appendLine("车辆尺寸/容积：${vehicleLengthM?.let { "${it}米" } ?: "未知"} / ${vehicleVolumeM3?.let { "${it}方" } ?: "未知"}")
        appendLine("货物：${cargoName ?: "未知"}")
        appendLine("重量/体积/长度：${cargoWeightKg?.let { "${it}kg" } ?: "未知"} / ${cargoVolumeM3?.let { "${it}方" } ?: "未知"} / ${cargoLengthM?.let { "${it}米" } ?: "未知"}")
        appendLine("用户出价/运费：${freightYuan?.let { "¥${it}" } ?: "未知，需核对"}")
        appendLine("装货点/卸货点：${pickupCount?.toString() ?: "未知"} / ${deliveryCount?.toString() ?: "未知"}")
        appendLine("司机额外成本：${driverExtraCostYuan?.let { "¥${it}" } ?: "未知，不能按0计算"}")
        appendLine("额外费用项目：${extraCostItems.ifEmpty { listOf("未提取/未知") }.joinToString("；")}")
        appendLine("高速/费用要求：${tollRequirement ?: "未知"}")
        appendLine("到达时间：${arrivalTime ?: "未知"}")
        appendLine("人员要求：${personnelRequirement ?: "未知"}")
        appendLine("装卸要求：${loadingUnloading ?: "未知"}")
        appendLine("潜在风险：${risks.ifEmpty { listOf("未识别到明确风险；仍需核对原备注") }.joinToString("；")}")
        appendLine("待确认事项：${unclearItems.ifEmpty { listOf("无明确待确认项") }.joinToString("；")}")
        appendLine("原文依据：${evidence.ifEmpty { listOf("模型未提供可核对的原文片段") }.joinToString("；")}")
        appendLine("摘要：${summary.ifBlank { "无"} }")
        appendLine()
        appendLine("注意：模型提取结果不是事实证明。车型适配、司机承担费用和关键要求未核实前，不得自动推荐接单。")
    }

    companion object {
        fun parse(response: String): OrderRemarkAnalysis {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            require(start >= 0 && end > start) { "模型未返回有效 JSON 对象" }
            val json = JSONObject(response.substring(start, end + 1))
            fun string(key: String): String? =
                if (!json.has(key) || json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() && it.lowercase() != "unknown" }
            fun number(key: String): Double? =
                if (!json.has(key) || json.isNull(key)) null else json.optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }
            fun integer(key: String): Int? =
                if (!json.has(key) || json.isNull(key)) null else json.optInt(key, -1).takeIf { it >= 0 }
            fun strings(key: String): List<String> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { i ->
                    val value = array.opt(i)
                    when (value) {
                        is String -> value.trim().takeIf { it.isNotEmpty() }
                        is JSONObject -> value.toString()
                        else -> null
                    }
                }
            }
            return OrderRemarkAnalysis(
                vehicleType = string("vehicle_type"),
                vehicleLengthM = number("vehicle_length_m"),
                vehicleVolumeM3 = number("vehicle_volume_m3"),
                cargoName = string("cargo_name"),
                cargoWeightKg = number("cargo_weight_kg"),
                cargoVolumeM3 = number("cargo_volume_m3"),
                cargoLengthM = number("cargo_length_m"),
                freightYuan = number("freight_yuan"),
                extraRewardYuan = null, // Platform bonus is intentionally ignored.
                pickupCount = integer("pickup_count"),
                deliveryCount = integer("delivery_count"),
                driverExtraCostYuan = number("driver_extra_cost_yuan"),
                extraCostItems = strings("extra_cost_items"),
                tollRequirement = string("toll_requirement"),
                arrivalTime = string("arrival_time"),
                personnelRequirement = string("personnel_requirement"),
                loadingUnloading = string("loading_unloading"),
                risks = strings("risks"),
                unclearItems = strings("unclear_items"),
                evidence = strings("evidence"),
                summary = string("summary") ?: ""
            )
        }
    }
}
