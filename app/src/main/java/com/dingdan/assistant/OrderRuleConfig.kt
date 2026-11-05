package com.dingdan.assistant

import android.content.SharedPreferences

/**
 * User-editable order rules and the single vehicle profile.
 * Defaults preserve the current conservative one-pickup/one-delivery behavior.
 */
data class OrderRuleConfig(
    val pickupMode: CountMode = CountMode.EXACT,
    val pickupLimit: Int = 1,
    val deliveryMode: CountMode = CountMode.EXACT,
    val deliveryLimit: Int = 1,
    val unknownCountAction: UnknownAction = UnknownAction.PENDING,
    val maxDriverExtraCostYuan: Double? = null,
    val vehicleType: String = "",
    val vehicleLengthM: Double? = null,
    val maxPayloadKg: Double? = null,
    val maxVolumeM3: Double? = null,
    val hasTailboard: Boolean = false
) {
    enum class CountMode { EXACT, AT_MOST, ANY }
    enum class UnknownAction { PENDING, IGNORE }

    fun allowsCount(actual: Int, mode: CountMode, limit: Int): Boolean =
        when (mode) {
            CountMode.EXACT -> actual == limit
            CountMode.AT_MOST -> actual <= limit
            CountMode.ANY -> true
        }

    companion object {
        fun load(p: SharedPreferences) = OrderRuleConfig(
            pickupMode = runCatching { CountMode.valueOf(p.getString("rule_pickup_mode", "EXACT")!!) }.getOrDefault(CountMode.EXACT),
            pickupLimit = p.getInt("rule_pickup_limit", 1).coerceAtLeast(1),
            deliveryMode = runCatching { CountMode.valueOf(p.getString("rule_delivery_mode", "EXACT")!!) }.getOrDefault(CountMode.EXACT),
            deliveryLimit = p.getInt("rule_delivery_limit", 1).coerceAtLeast(1),
            unknownCountAction = runCatching { UnknownAction.valueOf(p.getString("rule_unknown_count", "PENDING")!!) }.getOrDefault(UnknownAction.PENDING),
            maxDriverExtraCostYuan = p.getString("rule_max_extra", null)?.toDoubleOrNull()?.takeIf { it >= 0.0 },
            vehicleType = p.getString("vehicle_type", "") ?: "",
            vehicleLengthM = p.getString("vehicle_length", null)?.toDoubleOrNull()?.takeIf { it > 0.0 },
            maxPayloadKg = p.getString("vehicle_payload", null)?.toDoubleOrNull()?.takeIf { it > 0.0 },
            maxVolumeM3 = p.getString("vehicle_volume", null)?.toDoubleOrNull()?.takeIf { it > 0.0 },
            hasTailboard = p.getBoolean("vehicle_tailboard", false)
        )

        fun save(p: SharedPreferences, config: OrderRuleConfig) {
            p.edit()
                .putString("rule_pickup_mode", config.pickupMode.name)
                .putInt("rule_pickup_limit", config.pickupLimit)
                .putString("rule_delivery_mode", config.deliveryMode.name)
                .putInt("rule_delivery_limit", config.deliveryLimit)
                .putString("rule_unknown_count", config.unknownCountAction.name)
                .putString("rule_max_extra", config.maxDriverExtraCostYuan?.toString().orEmpty())
                .putString("vehicle_type", config.vehicleType)
                .putString("vehicle_length", config.vehicleLengthM?.toString().orEmpty())
                .putString("vehicle_payload", config.maxPayloadKg?.toString().orEmpty())
                .putString("vehicle_volume", config.maxVolumeM3?.toString().orEmpty())
                .putBoolean("vehicle_tailboard", config.hasTailboard)
                .apply()
        }
    }
}
