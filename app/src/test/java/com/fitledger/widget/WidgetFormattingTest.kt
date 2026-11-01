package com.fitledger.widget

import com.fitledger.domain.EnergyUnit
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WidgetFormattingTest {
    private val day = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun readyWidgetShowsEnergyWithTheSavedPresentationUnit() {
        val text = WidgetFormatting.present(WidgetSnapshot(day, EnergyUnit.KCAL, WidgetStatus.READY, 1200.0, 2000.0), day)
        assertTrue(text.showEnergy)
        assertEquals("1200.0 kcal", text.consumed)
        assertEquals("2000.0 kcal", text.target)
        assertEquals("剩余 800.0 kcal", text.remaining)
        val kj = WidgetFormatting.present(WidgetSnapshot(day, EnergyUnit.KJ, WidgetStatus.READY, 100.0, 200.0), day)
        assertEquals("418.4 kJ", kj.consumed)
        assertEquals("836.8 kJ", kj.target)
        assertEquals("剩余 418.4 kJ", kj.remaining)
    }

    @Test fun exceedingTheTargetRemainsVisibleWithoutNegativeRemainingCopy() {
        val text = WidgetFormatting.present(WidgetSnapshot(day, status = WidgetStatus.READY, consumedKcal = 2100.0, targetKcal = 2000.0), day)
        assertEquals("已超出 100.0 kcal", text.remaining)
    }

    @Test fun aNewLocalDayNeverReusesOldNutritionEvenWhenTheOldSnapshotWasReady() {
        val text = WidgetFormatting.present(WidgetSnapshot(day, status = WidgetStatus.READY, consumedKcal = 1200.0, targetKcal = 2000.0), day + 1)
        assertFalse(text.showEnergy)
        assertEquals("", text.consumed)
        assertEquals("", text.target)
        assertEquals("", text.remaining)
        assertTrue(text.message.contains("今日称重"))
        assertTrue(text.date.startsWith("10月26日"))
    }

    @Test fun missingTargetsPendingSettingsAndErrorsClearAllEnergyFields() {
        for (status in listOf(WidgetStatus.NEED_WEIGHING, WidgetStatus.SETTINGS_PENDING, WidgetStatus.READING, WidgetStatus.READ_ERROR)) {
            val text = WidgetFormatting.present(WidgetSnapshot(day, status = status, consumedKcal = 1200.0, targetKcal = 2000.0), day)
            assertFalse(text.showEnergy)
            assertEquals("", text.consumed)
            assertEquals("", text.target)
            assertEquals("", text.remaining)
        }
        val pending = WidgetFormatting.present(WidgetSnapshot(day, status = WidgetStatus.SETTINGS_PENDING), day)
        assertTrue(pending.message.contains("完成数据同步"))
    }

    @Test fun invalidOrMissingValuesNeverRenderAsNumbers() {
        val values = listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)
        values.forEach { value ->
            assertFalse(WidgetFormatting.present(WidgetSnapshot(day, status = WidgetStatus.READY, consumedKcal = value, targetKcal = 2000.0), day).showEnergy)
            assertFalse(WidgetFormatting.present(WidgetSnapshot(day, status = WidgetStatus.READY, consumedKcal = 1000.0, targetKcal = value), day).showEnergy)
        }
    }
}
