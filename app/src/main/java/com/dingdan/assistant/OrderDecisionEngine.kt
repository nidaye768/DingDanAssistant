package com.dingdan.assistant

/**
 * Deterministic order decision core.
 *
 * Only loaded-route distance is used in the denominator. The threshold is supplied
 * by the user/configuration layer; it is deliberately not hard-coded here.
 */
object OrderDecisionEngine {
    enum class Match { MATCHED, MISMATCHED, UNKNOWN }
    enum class Verdict { RECOMMEND, IGNORE, PENDING }

    data class Facts(
        val freightYuan: Double?,
        val loadedRouteKm: Double?,
        val minYuanPerLoadedKm: Double?,
        val vehicleMatch: Match,
        val pickupCount: Int?,
        val deliveryCount: Int?,
        /** Confirmed driver-paid extra cost. Null means not yet verified. */
        val driverExtraCostYuan: Double?,
        /** Whether the remarks were checked and extra-cost amount is reliable. */
        val extraCostConfirmed: Boolean,
        /** True only when the remarks have no unresolved critical requirement/risk. */
        val criticalRequirementsClear: Boolean,
        val rules: OrderRuleConfig = OrderRuleConfig()
    )

    data class Result(
        val verdict: Verdict,
        val grossYuanPerLoadedKm: Double?,
        val netYuanPerLoadedKm: Double?,
        val netFreightYuan: Double?,
        val reasons: List<String>
    )

    fun evaluate(facts: Facts): Result {
        val reasons = mutableListOf<String>()

        if (facts.vehicleMatch == Match.MISMATCHED) {
            return Result(Verdict.IGNORE, null, null, null, listOf("车型或车辆条件明确不匹配"))
        }
        if (facts.vehicleMatch == Match.UNKNOWN) {
            reasons += "车辆适配情况未知"
        }

        fun countProblem(actual: Int?, mode: OrderRuleConfig.CountMode, limit: Int, label: String): Result? {
            if (actual == null) {
                if (facts.rules.unknownCountAction == OrderRuleConfig.UnknownAction.IGNORE)
                    return Result(Verdict.IGNORE, null, null, null, listOf("$label数量未知，当前设置为忽略"))
                reasons += "$label数量未知，待确认"
                return null
            }
            if (!facts.rules.allowsCount(actual, mode, limit)) {
                val message = "$label数量为 $actual，不符合当前设置（${mode.name} $limit）"
                return if (facts.rules.mismatchCountAction == OrderRuleConfig.UnknownAction.IGNORE)
                    Result(Verdict.IGNORE, null, null, null, listOf(message))
                else Result(Verdict.PENDING, null, null, null, listOf(message))
            }
            return null
        }
        countProblem(facts.pickupCount, facts.rules.pickupMode, facts.rules.pickupLimit, "装货点")?.let { return it }
        countProblem(facts.deliveryCount, facts.rules.deliveryMode, facts.rules.deliveryLimit, "卸货点")?.let { return it }

        val freight = facts.freightYuan
        val km = facts.loadedRouteKm
        val floor = facts.minYuanPerLoadedKm
        if (freight == null || !freight.isFinite() || freight <= 0.0) reasons += "运费金额缺失或无效"
        if (km == null || !km.isFinite() || km <= 0.0) reasons += "载货道路里程缺失或无效"
        if (floor == null || !floor.isFinite() || floor < 0.0) reasons += "载货公里单价门槛未设置"

        if (!facts.extraCostConfirmed || facts.driverExtraCostYuan == null ||
            !facts.driverExtraCostYuan.isFinite() || facts.driverExtraCostYuan < 0.0) {
            reasons += "备注中的司机额外成本尚未核实"
        }
        if (!facts.criticalRequirementsClear) {
            reasons += "备注存在未确认的关键要求或风险"
        }
        val extraCostLimit = facts.rules.maxDriverExtraCostYuan
        if (extraCostLimit != null && facts.driverExtraCostYuan != null &&
            facts.driverExtraCostYuan > extraCostLimit) {
            return Result(Verdict.IGNORE, null, null, null,
                listOf("司机额外成本超过当前上限：¥${facts.driverExtraCostYuan} > ¥$extraCostLimit"))
        }

        if (reasons.isNotEmpty()) {
            return Result(Verdict.PENDING, null, null, null, reasons)
        }

        val grossRate = freight!! / km!!
        val netFreight = freight - facts.driverExtraCostYuan!!
        val netRate = netFreight / km
        if (netFreight <= 0.0) {
            return Result(Verdict.IGNORE, grossRate, netRate, netFreight, listOf("扣除司机额外成本后运费不为正"))
        }
        if (netRate < floor!!) {
            return Result(
                Verdict.IGNORE, grossRate, netRate, netFreight,
                listOf("扣除司机额外成本后的载货公里单价低于设定门槛")
            )
        }
        return Result(
            Verdict.RECOMMEND, grossRate, netRate, netFreight,
            listOf("车型适配、一装一卸、备注成本和关键要求均已确认，净载货公里单价达到门槛")
        )
    }
}
