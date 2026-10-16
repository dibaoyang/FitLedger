package com.fitledger.domain

import java.math.BigDecimal

/** Input units only. Body records, plans and energy calculations always use kilograms. */
enum class WeightUnit(val label: String) {
    KG("kg（公斤）"), JIN("斤");

    fun toKg(value: Double): Double = if (this == KG) value else value / 2.0
    fun fromKg(value: Double): Double = if (this == KG) value else value * 2.0

    /** Null means an unfinished/invalid number; never silently relabel such input. */
    fun convertInput(text: String, target: WeightUnit): String? {
        if (text.isBlank()) return ""
        val number = text.trim().toBigDecimalOrNull() ?: return null
        if (!number.toDouble().isFinite()) return null
        if (this == target) return text
        val converted = if (target == KG) number.divide(BigDecimal("2")) else number.multiply(BigDecimal("2"))
        return converted.stripTrailingZeros().toPlainString()
    }
}

object WeightFoodShortcut {
    /** 1 gram of food per kilogram of today's saved weight; independent of food basis/step. */
    fun grams(weightKg: Double, foodUnit: String, enabled: Boolean): Double? =
        weightKg.takeIf { enabled && foodUnit == "g" && it.isFinite() && it in 20.0..400.0 }
}
