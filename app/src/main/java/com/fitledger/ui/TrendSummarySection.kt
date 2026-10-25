package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*
import kotlin.math.abs

@Composable fun TrendSummarySection(s: LedgerState, day: Long) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "累计总结 ▾" else "累计总结 ▸") }
    if (!expanded) return
    val summary = remember(s.bodies, s.intakes, s.targets, s.exercises, s.dayModes, day) {
        val intakesByDay = s.intakes.groupBy { it.day }
        val indulgenceDays = s.dayModes.filter { it.isIndulgence }.map { it.day }.toSet()
        TrendSummaryCalculator.calculate(
            bodies = s.bodies.map { TrendBodyPoint(it.day, it.weight, it.fatPercent) },
            gaps = s.bodies.map { body ->
                val intakes = intakesByDay[body.day].orEmpty()
                DailyGapSample(body.day, runCatching { totalEnergy(s, body.day) }.getOrNull(),
                    if (intakes.isEmpty()) null else intakes.sumOf { it.nutrition.kcal }, intakes.isNotEmpty(), body.day in indulgenceDays)
            }, today = day, minimumIntakeKcal = 1000.0
        )
    }
    val unit = LocalEnergyUnit.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("全部历史累计", style = MaterialTheme.typography.titleMedium)
            if (!s.preferences.visibility.shows(BodyField.WEIGHT)) Text("累计体重变化  ••••")
            else summary.weightLossKg.let { change ->
                Text(when {
                    change == null -> "累计体重变化：至少记录两天后显示"
                    change > 0 -> "累计减重 ${change.f2()} kg"
                    change < 0 -> "增肌加油 · 体重增加 ${abs(change).f2()} kg"
                    else -> "累计体重变化 0.00 kg"
                }, style = MaterialTheme.typography.titleMedium)
                if (change != null && change < 0) Text("体重增加不等同于肌肉增加，可结合体脂率和训练情况观察。")
            }
            if (!s.preferences.visibility.shows(BodyField.BODY_FAT)) Text("累计体脂变化  ••••")
            else summary.bodyFatDropPercentagePoints.let { change ->
                Text(when {
                    change == null -> "累计体脂变化：至少记录两天后显示"
                    change > 0 -> "体脂率下降 ${change.f2()} 个百分点"
                    change < 0 -> "体脂率上升 ${abs(change).f2()} 个百分点 · 继续加油"
                    else -> "体脂率变化 0.00 个百分点"
                }, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            val average = summary.averageDeficitKcal
            Text(if (average != null && average < 0) "平均每日热量盈余" else "平均每日热量缺口", style = MaterialTheme.typography.titleMedium)
            Text(average?.let { abs(it).energy(unit) } ?: "尚无符合条件的完整日期", style = MaterialTheme.typography.headlineSmall)
            Text("已统计 ${summary.includedDays} 天")
            Text("未完整记录 ${summary.excludedMissingDays} 天 · 摄入不足 ${summary.excludedLowIntakeDays} 天 · 放纵日 ${summary.excludedIndulgenceDays} 天")
            Text("日均只统计今天之前的日期：当天已记录饮食、摄入不少于 ${1000.0.energy(unit)}、有总消耗记录，且未标记放纵日。")
            Text("热量缺口 = 当天总消耗 − 当天摄入。无论缺口大小都保留；摄入超过消耗时显示为盈余。累计身体变化取全部有效历史的第一条与最近一条，不受趋势图天数选择影响。")
        }
    }
}
