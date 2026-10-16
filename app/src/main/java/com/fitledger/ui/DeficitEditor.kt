package com.fitledger.ui
import androidx.compose.runtime.*
import com.fitledger.domain.*

@Composable fun DeficitEditor(kcal:Double,change:(Double)->Unit) {
    val unit=LocalEnergyUnit.current
    var raw by remember(kcal,unit) {mutableStateOf(if(kcal.isFinite()) unit.fromKcal(kcal).toString() else "")}
    NumberField("每日热量缺口 ${unit.label}（0 表示维持）",raw) {raw=it;change(it.toDoubleOrNull()?.let(unit::toKcal) ?: Double.NaN)}
}
