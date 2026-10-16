package com.fitledger.domain

/** Storage and all algorithms always use kcal. Conversion happens only at UI boundaries. */
enum class EnergyUnit(val label: String, private val perKcal: Double) {
    KCAL("kcal", 1.0), KJ("kJ", 4.184);
    fun fromKcal(kcal: Double): Double = kcal * perKcal
    fun toKcal(value: Double): Double = value / perKcal
}
