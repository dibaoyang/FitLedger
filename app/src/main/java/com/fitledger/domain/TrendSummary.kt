package com.fitledger.domain

data class TrendBodyPoint(val day: Long, val weight: Double, val fatPercent: Double)

/** A supplied calendar day; an absent intake is different from a recorded zero intake. */
data class DailyGapSample(
    val day: Long,
    val expenditureKcal: Double?,
    val intakeKcal: Double?,
    val hasIntake: Boolean,
    val isIndulgence: Boolean = false
)

data class TrendSummary(
    val startDay: Long?,
    val endDay: Long?,
    val weightLossKg: Double?,
    val bodyFatDropPercentagePoints: Double?,
    val averageDeficitKcal: Double?,
    val includedDays: Int,
    val excludedMissingDays: Int,
    val excludedLowIntakeDays: Int,
    val excludedIndulgenceDays: Int
)

object TrendSummaryCalculator {
    fun calculate(
        bodies: List<TrendBodyPoint>,
        gaps: List<DailyGapSample>,
        today: Long,
        minimumIntakeKcal: Double = 1000.0
    ): TrendSummary {
        require(minimumIntakeKcal.isFinite() && minimumIntakeKcal >= 0) { "最低摄入统计标准须为非负有限数" }
        require(bodies.map { it.day }.distinct().size == bodies.size) { "身体记录日期不能重复" }
        require(gaps.map { it.day }.distinct().size == gaps.size) { "热量缺口记录日期不能重复" }

        val validBodies = bodies.filter {
            it.day <= today && it.weight.isFinite() && it.weight in 20.0..400.0 &&
                it.fatPercent.isFinite() && it.fatPercent > 0 && it.fatPercent < 100
        }.sortedBy { it.day }
        val first = validBodies.firstOrNull()
        val last = validBodies.lastOrNull()
        val hasChange = validBodies.size >= 2
        var included = 0
        var missing = 0
        var lowIntake = 0
        var indulgence = 0
        var average = 0.0
        // Only supplied completed days are counted. Do not invent zero intakes for calendar gaps.
        gaps.filter { it.day < today }.sortedBy { it.day }.forEach { sample ->
            val expenditure = sample.expenditureKcal
            val intake = sample.intakeKcal
            // Counts are mutually exclusive: an explicitly excluded indulgence day takes priority.
            when {
                sample.isIndulgence -> indulgence++
                !sample.hasIntake || expenditure == null || intake == null ||
                    !expenditure.isFinite() || !intake.isFinite() || expenditure < 0 || intake < 0 -> missing++
                intake < minimumIntakeKcal -> lowIntake++
                else -> {
                    included++
                    // Weighted averaging also handles opposite signed finite extremes without
                    // overflowing the subtraction or summing every day's deficit first.
                    val count = included.toDouble()
                    average = average * ((count - 1) / count) + (expenditure - intake) / count
                }
            }
        }
        return TrendSummary(
            startDay = first?.day,
            endDay = last?.day,
            weightLossKg = if (hasChange) first!!.weight - last!!.weight else null,
            bodyFatDropPercentagePoints = if (hasChange) first!!.fatPercent - last!!.fatPercent else null,
            averageDeficitKcal = if (included > 0) average else null,
            includedDays = included,
            excludedMissingDays = missing,
            excludedLowIntakeDays = lowIntake,
            excludedIndulgenceDays = indulgence
        )
    }
}
