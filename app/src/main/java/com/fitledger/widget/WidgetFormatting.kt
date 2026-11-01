package com.fitledger.widget

import com.fitledger.domain.EnergyUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WidgetStatus { READY, NEED_WEIGHING, SETTINGS_PENDING, READING, READ_ERROR }

/** Intentionally contains no body measurements or other personal profile fields. */
data class WidgetSnapshot(val day: Long, val unit: EnergyUnit = EnergyUnit.KCAL,
    val status: WidgetStatus, val consumedKcal: Double? = null, val targetKcal: Double? = null)
data class WidgetText(val date: String, val consumed: String, val target: String,
    val remaining: String, val message: String, val showEnergy: Boolean)

object WidgetFormatting {
    fun present(snapshot: WidgetSnapshot, currentDay: Long): WidgetText {
        val date = LocalDate.ofEpochDay(currentDay).format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA))
        val status = if (snapshot.day != currentDay) WidgetStatus.NEED_WEIGHING else snapshot.status
        val consumed = snapshot.consumedKcal
        val target = snapshot.targetKcal
        val ready = status == WidgetStatus.READY && consumed != null && target != null &&
            consumed.isFinite() && consumed >= 0 && target.isFinite() && target > 0 && (consumed + target).isFinite() &&
            snapshot.unit.fromKcal(consumed).isFinite() && snapshot.unit.fromKcal(target).isFinite()
        if (!ready) {
            val message = when (status) {
                WidgetStatus.SETTINGS_PENDING -> "请打开轻衡完成数据同步"
                WidgetStatus.READING -> "正在刷新今日记录…"
                WidgetStatus.READ_ERROR -> "暂时无法读取，请打开轻衡查看"
                else -> "请打开轻衡完成今日称重"
            }
            return WidgetText(date, "", "", "", message, false)
        }
        fun energy(value: Double) = "${String.format(Locale.CHINA, "%.1f", snapshot.unit.fromKcal(value))} ${snapshot.unit.label}"
        val remaining = target!! - consumed!!
        return WidgetText(date, energy(consumed), energy(target),
            if (remaining >= 0) "剩余 ${energy(remaining)}" else "已超出 ${energy(-remaining)}", "点击打开轻衡", true)
    }
}
