package com.dingdan.assistant

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity

class OrderRuleSettingsActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("api_config", MODE_PRIVATE) }
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 12, 0, 4)
    }
    private fun field(hintText: String, value: String = "", decimal: Boolean = true) =
        EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            inputType = if (decimal)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else android.text.InputType.TYPE_CLASS_NUMBER
            setText(value)
        }
    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@OrderRuleSettingsActivity,
            android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = OrderRuleConfig.load(prefs)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(0xFFF3F5F7.toInt())
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "订单规则与车辆参数"
            textSize = 22f
            setTextColor(0xFF17212B.toInt())
        })
        root.addView(label("装货点数量规则"))
        val pickupMode = spinner(listOf("必须等于指定数量", "最多不超过指定数量", "不限制"), config.pickupMode.ordinal)
        val pickupLimit = field("装货点数量", config.pickupLimit.toString(), false)
        root.addView(pickupMode); root.addView(pickupLimit)
        root.addView(label("卸货点数量规则"))
        val deliveryMode = spinner(listOf("必须等于指定数量", "最多不超过指定数量", "不限制"), config.deliveryMode.ordinal)
        val deliveryLimit = field("卸货点数量", config.deliveryLimit.toString(), false)
        root.addView(deliveryMode); root.addView(deliveryLimit)
        root.addView(label("装卸点数量明确不符合规则时"))
        val mismatchAction = spinner(listOf("忽略订单（默认）", "待确认"),
            if (config.mismatchCountAction == OrderRuleConfig.UnknownAction.PENDING) 1 else 0)
        root.addView(mismatchAction)
        root.addView(label("装卸点数量无法识别时"))
        val unknownAction = spinner(listOf("待确认（默认）", "忽略订单"),
            if (config.unknownCountAction == OrderRuleConfig.UnknownAction.IGNORE) 1 else 0)
        root.addView(unknownAction)
        val minRate = field("最低载货公里单价（元/km）", config.minYuanPerLoadedKm?.toString().orEmpty())
        root.addView(label("收益门槛"))
        root.addView(minRate)
        val maxExtra = field("司机额外成本上限（元，留空不限制）", config.maxDriverExtraCostYuan?.toString().orEmpty())
        root.addView(label("司机额外成本限制"))
        root.addView(maxExtra)
        root.addView(label("我的车辆参数"))
        val vehicleType = field("车型，例如中面/厢式高栏", config.vehicleType, false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val vehicleLength = field("车辆长度（米）", config.vehicleLengthM?.toString().orEmpty())
        val payload = field("车辆额定载重（kg）", config.maxPayloadKg?.toString().orEmpty())
        val volume = field("车辆可用容积（方）", config.maxVolumeM3?.toString().orEmpty())
        root.addView(vehicleType); root.addView(vehicleLength)
        root.addView(payload); root.addView(volume)
        val tailboard = Switch(this).apply {
            text = "我的车辆有尾板"
            isChecked = config.hasTailboard
        }
        root.addView(tailboard)
        root.addView(TextView(this).apply {
            text = "说明：空白数值表示未设置。车辆参数先保存为车辆档案，下一阶段再与AI提取的订单要求逐项自动匹配。"
            textSize = 12f
            setTextColor(0xFF687581.toInt())
            setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener {
                val next = OrderRuleConfig(
                    pickupMode = OrderRuleConfig.CountMode.values()[pickupMode.selectedItemPosition],
                    pickupLimit = pickupLimit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    deliveryMode = OrderRuleConfig.CountMode.values()[deliveryMode.selectedItemPosition],
                    deliveryLimit = deliveryLimit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    mismatchCountAction = if (mismatchAction.selectedItemPosition == 1)
                        OrderRuleConfig.UnknownAction.PENDING else OrderRuleConfig.UnknownAction.IGNORE,
                    unknownCountAction = if (unknownAction.selectedItemPosition == 1)
                        OrderRuleConfig.UnknownAction.IGNORE else OrderRuleConfig.UnknownAction.PENDING,
                    minYuanPerLoadedKm = minRate.text.toString().toDoubleOrNull()?.takeIf { it >= 0.0 },
                    maxDriverExtraCostYuan = maxExtra.text.toString().toDoubleOrNull()?.takeIf { it >= 0.0 },
                    vehicleType = vehicleType.text.toString().trim(),
                    vehicleLengthM = vehicleLength.text.toString().toDoubleOrNull()?.takeIf { it > 0.0 },
                    maxPayloadKg = payload.text.toString().toDoubleOrNull()?.takeIf { it > 0.0 },
                    maxVolumeM3 = volume.text.toString().toDoubleOrNull()?.takeIf { it > 0.0 },
                    hasTailboard = tailboard.isChecked
                )
                OrderRuleConfig.save(prefs, next)
                Toast.makeText(this@OrderRuleSettingsActivity, "设置已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        setContentView(scroll)
    }
}
