package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitledger.domain.MacroTolerance
import com.fitledger.domain.RecommendationPolicy

@Composable fun RecommendationSettingsSection(vm: LedgerViewModel, policy: RecommendationPolicy, busy: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    var budget by rememberSaveable(policy.dailyBudgetYuan) { mutableStateOf(policy.dailyBudgetYuan?.toString() ?: "") }
    var carbsUnder by rememberSaveable(policy.carbs.under) { mutableStateOf(policy.carbs.under?.toString() ?: "") }
    var carbsOver by rememberSaveable(policy.carbs.over) { mutableStateOf(policy.carbs.over?.toString() ?: "") }
    var proteinUnder by rememberSaveable(policy.protein.under) { mutableStateOf(policy.protein.under?.toString() ?: "") }
    var proteinOver by rememberSaveable(policy.protein.over) { mutableStateOf(policy.protein.over?.toString() ?: "") }
    var fatUnder by rememberSaveable(policy.fat.under) { mutableStateOf(policy.fat.under?.toString() ?: "") }
    var fatOver by rememberSaveable(policy.fat.over) { mutableStateOf(policy.fat.over?.toString() ?: "") }
    fun value(raw: String): Double? = if (raw.isBlank()) null else raw.trim().toDoubleOrNull() ?: Double.NaN
    val draft = RecommendationPolicy(value(budget), MacroTolerance(value(carbsUnder), value(carbsOver)),
        MacroTolerance(value(proteinUnder), value(proteinOver)), MacroTolerance(value(fatUnder), value(fatOver)))
    val error = runCatching { draft.validate() }.exceptionOrNull()?.message
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("智能推荐约束", style = MaterialTheme.typography.titleMedium)
            Text(policy.dailyBudgetYuan?.let { "每日总预算：¥${it.money()}" } ?: "每日总预算：不限")
            OutlinedButton(onClick = { open = !open }, enabled = !busy) {
                Text(if (open) "收起推荐约束" else "设置预算和营养素范围")
            }
            if (open) {
                Text("保存后用于下一次智能推荐，手动记录可以如实填写。")
                OutlinedTextField(budget, { budget = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("每日食品总预算 元（留空不限）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("全天预算会扣除今天已经记录的费用，再安排锁定食品和其他推荐食品。已有未计价摄入时无法保证预算，可暂时取消预算；食品价格只影响后续记录。0 元预算仅能推荐免费食品。")
                MacroToleranceEditor("碳水", carbsUnder, carbsOver, busy, { carbsUnder = it }, { carbsOver = it })
                MacroToleranceEditor("蛋白质", proteinUnder, proteinOver, busy, { proteinUnder = it }, { proteinOver = it })
                MacroToleranceEditor("脂肪", fatUnder, fatOver, busy, { fatUnder = it }, { fatOver = it })
                Text("范围按今日已摄入加本次推荐后的全天总量核对。留空表示该方向不限，填写 0 表示严格不能低于或超过目标。锁定食品也占用范围。")
                Text("若食品的调整单位或锁定量无法满足全部条件，推荐会提示你调整设置。")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.recommendationPolicy(draft) }, enabled = !busy && error == null) { Text("保存推荐约束") }
                    TextButton(onClick = {
                        budget = ""; carbsUnder = ""; carbsOver = ""; proteinUnder = ""; proteinOver = ""; fatUnder = ""; fatOver = ""
                    }, enabled = !busy) { Text("全部设为不限") }
                }
                Text("清空后仍需点击保存才会生效。")
            }
        }
    }
}

@Composable private fun MacroToleranceEditor(label: String, under: String, over: String, busy: Boolean,
    changeUnder: (String) -> Unit, changeOver: (String) -> Unit) {
    Text("$label 范围", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(under, changeUnder, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("最多少于今日目标 g（留空不限）") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(over, changeOver, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("最多超过今日目标 g（留空不限）") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { changeUnder("0") }, enabled = !busy) { Text("不能低于目标") }
        TextButton(onClick = { changeOver("0") }, enabled = !busy) { Text("不能超过目标") }
    }
    TextButton(onClick = { changeUnder(""); changeOver("") }, enabled = !busy) { Text("$label 两项不限") }
}
