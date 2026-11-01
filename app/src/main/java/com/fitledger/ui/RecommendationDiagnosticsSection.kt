package com.fitledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.Food
import com.fitledger.domain.Diagnostic
import com.fitledger.domain.PortionQuantities
import com.fitledger.domain.EnergyUnit
import com.fitledger.domain.MacroTolerance
import com.fitledger.domain.RecommendationPolicy
import com.fitledger.domain.RecommendationTrialOption
import com.fitledger.domain.RecommendationTrialResult

/** Callbacks keep temporary computation separate from explicit, confirmed policy persistence. */
@Composable fun RecommendationDiagnosticsSection(
    diagnostics: List<Diagnostic>, trial: RecommendationTrialResult?, foods: List<Food>, energyUnit: EnergyUnit,
    busy: Boolean, onTrial: (RecommendationTrialOption) -> Unit, onApplyPolicy: (RecommendationTrialOption) -> Unit
) {
    if (diagnostics.isEmpty() && trial == null) return
    var pendingApply by remember { mutableStateOf<RecommendationTrialOption?>(null) }
    LaunchedEffect(trial, diagnostics) { pendingApply = null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("推荐检查与临时试算", style = MaterialTheme.typography.titleMedium)
            diagnostics.forEach { diagnostic ->
                Text(diagnostic.title, style = MaterialTheme.typography.titleSmall)
                Text(diagnostic.detail)
            }
            val options = diagnostics.mapNotNull { it.trial }.distinctBy { it.id }
            if (options.isNotEmpty()) {
                HorizontalDivider()
                Text("选择一个方向进行临时试算。每次仅显示你选定的这一套结果，不会自动修改正式设置。")
                options.forEach { option ->
                    OutlinedButton(onClick = { onTrial(option) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(option.title)
                    }
                }
            }
            trial?.let { result ->
                HorizontalDivider()
                Text(result.option.title, style = MaterialTheme.typography.titleMedium)
                Text("临时试算，未改变设置，不能直接按旧约束采纳", color = MaterialTheme.colorScheme.error)
                Text(result.option.policy.description())
                result.failure?.let { Text("这次临时试算仍未找到组合：$it") }
                result.suggestion?.let { value ->
                    Text("试算热量 ${value.nutrition.kcal.energy(energyUnit)}")
                    Text("碳水 ${value.nutrition.carbs.f()} g　蛋白质 ${value.nutrition.protein.f()} g　脂肪 ${value.nutrition.fat.f()} g")
                    value.costYuan?.let { Text("本次食品预计费用 ¥${it.money()}") }
                    if (value.quantities.isEmpty()) Text("本次试算无需新增食品。")
                    val byId = foods.associateBy { it.id }
                    value.quantities.toSortedMap().forEach { (id, quantity) ->
                        val food = byId[id]
                        Text("${food?.name ?: "食品 #$id"}：${PortionQuantities.format(food?.unit.orEmpty(),quantity)} ${food?.unit.orEmpty()}")
                    }
                    Button(onClick = { pendingApply = result.option }, enabled = !busy) {
                        Text("将这项约束修改设为正式设置…")
                    }
                    Text("正式应用后，需重新点击智能推荐计算，再检查并采纳正式结果。")
                }
            }
        }
    }
    pendingApply?.let { option ->
        AlertDialog(onDismissRequest = { if (!busy) pendingApply = null },
            title = { Text("正式修改推荐约束？") },
            text = { Text("${option.policy.description()}\n\n确认后会保存这组推荐约束。临时试算结果不能直接采纳；请重新正式计算，再检查结果。") },
            confirmButton = { TextButton(onClick = { pendingApply = null; onApplyPolicy(option) }, enabled = !busy) { Text("确认保存约束") } },
            dismissButton = { TextButton(onClick = { pendingApply = null }, enabled = !busy) { Text("取消") } })
    }
}

private fun RecommendationPolicy.description(): String {
    fun MacroTolerance.range(name: String) = "$name：允许少于 ${under?.let { "${it.f()} g" } ?: "不限"}，允许超过 ${over?.let { "${it.f()} g" } ?: "不限"}"
    return listOf(dailyBudgetYuan?.let { "全天预算 ¥${it.money()}" } ?: "全天预算不限",
        carbs.range("碳水"), protein.range("蛋白质"), fat.range("脂肪")).joinToString("\n")
}
