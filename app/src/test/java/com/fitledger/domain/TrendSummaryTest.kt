package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class TrendSummaryTest {
    private fun body(day: Long, weight: Double = 70.0, fat: Double = 20.0) = TrendBodyPoint(day, weight, fat)
    private fun gap(day: Long, deficit: Double) = DailyGapSample(
        day, if (deficit >= 0) deficit + 1000 else 1000.0,
        if (deficit >= 0) 1000.0 else 1000.0 - deficit, true
    )
    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)

    @Test fun cumulativeBodyChangeUsesAllValidHistoryChronologicallyAndIncludesToday() {
        val summary = TrendSummaryCalculator.calculate(
            listOf(body(100, 68.5, 17.2), body(1, 75.0, 25.0), body(40, 73.0, 22.0)), emptyList(), 100
        )
        assertEquals(1L, summary.startDay)
        assertEquals(100L, summary.endDay)
        assertEquals(6.5, summary.weightLossKg!!, 1e-12)
        assertEquals(7.8, summary.bodyFatDropPercentagePoints!!, 1e-12)
        assertNull(summary.averageDeficitKcal)
        assertEquals(0, summary.includedDays)
    }

    @Test fun bodyGainsRemainSignedAndBodyFatChangeIsPercentagePoints() {
        val summary = TrendSummaryCalculator.calculate(listOf(body(1, 60.0, 10.0), body(2, 62.0, 12.0)), emptyList(), 3)
        assertEquals(-2.0, summary.weightLossKg!!, 0.0)
        assertEquals(-2.0, summary.bodyFatDropPercentagePoints!!, 0.0)
    }

    @Test fun fewerThanTwoValidBodyDaysCannotProduceAChange() {
        val empty = TrendSummaryCalculator.calculate(emptyList(), emptyList(), 3)
        assertNull(empty.startDay)
        assertNull(empty.endDay)
        assertNull(empty.weightLossKg)
        assertNull(empty.bodyFatDropPercentagePoints)
        val one = TrendSummaryCalculator.calculate(listOf(body(2)), emptyList(), 3)
        assertEquals(2L, one.startDay)
        assertEquals(2L, one.endDay)
        assertNull(one.weightLossKg)
        assertNull(one.bodyFatDropPercentagePoints)
    }

    @Test fun invalidAndFutureBodyRecordsCannotBecomeEndpoints() {
        val summary = TrendSummaryCalculator.calculate(listOf(
            body(-3, Double.NaN), body(-2, 19.0), body(-1, fat = 0.0),
            body(0, 72.0, 21.0), body(1, 71.0, 20.0),
            body(2, fat = Double.POSITIVE_INFINITY), body(3, fat = 100.0), body(5, 60.0, 10.0)
        ), emptyList(), 4)
        assertEquals(0L, summary.startDay)
        assertEquals(1L, summary.endDay)
        assertEquals(1.0, summary.weightLossKg!!, 0.0)
        assertEquals(1.0, summary.bodyFatDropPercentagePoints!!, 0.0)
    }

    @Test fun averageIncludesCompletedDaysAndPreservesNegativeSurpluses() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(gap(3, -500.0), gap(1, 500.0), gap(2, -600.0)), 4)
        assertEquals(-200.0, summary.averageDeficitKcal!!, 1e-9)
        assertEquals(3, summary.includedDays)
        assertEquals(0, summary.excludedMissingDays)
        assertEquals(0, summary.excludedLowIntakeDays)
        assertEquals(0, summary.excludedIndulgenceDays)
    }

    @Test fun todayAndFutureDaysAreUnfinishedAndDoNotInflateExclusionCounts() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            gap(1, 400.0), gap(2, 5000.0), DailyGapSample(3, null, null, false, true)
        ), 2)
        assertEquals(400.0, summary.averageDeficitKcal!!, 0.0)
        assertEquals(1, summary.includedDays)
        assertEquals(0, summary.excludedMissingDays + summary.excludedLowIntakeDays + summary.excludedIndulgenceDays)
    }

    @Test fun missingNonfiniteNegativeAndAbsentIntakeDaysAreExcluded() {
        val samples = listOf(
            DailyGapSample(1, null, 1200.0, true),
            DailyGapSample(2, 2200.0, null, true),
            DailyGapSample(3, Double.NaN, 1200.0, true),
            DailyGapSample(4, 2200.0, Double.POSITIVE_INFINITY, true),
            DailyGapSample(5, -1.0, 1200.0, true),
            DailyGapSample(6, 2200.0, -1.0, true),
            DailyGapSample(7, 2200.0, 1200.0, false)
        )
        val summary = TrendSummaryCalculator.calculate(emptyList(), samples, 8)
        assertNull(summary.averageDeficitKcal)
        assertEquals(7, summary.excludedMissingDays)
        assertEquals(0, summary.includedDays)
    }

    @Test fun lowIntakeExclusionIncludesExactlyOneThousandAndDoesNotFilterByGap() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            DailyGapSample(1, 2500.0, 999.999, true),
            DailyGapSample(2, 2500.0, 1000.0, true),
            DailyGapSample(3, 9000.0, 1000.0, true),
            DailyGapSample(4, 1000.0, 9000.0, true)
        ), 6)
        assertEquals(3, summary.includedDays)
        assertEquals(1, summary.excludedLowIntakeDays)
        assertEquals(500.0, summary.averageDeficitKcal!!, 1e-9)
    }

    @Test fun exclusionReasonsAreMutuallyExclusiveAndIndulgenceHasPriority() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            DailyGapSample(1, null, null, false, true),
            DailyGapSample(2, 8000.0, 500.0, true, true),
            DailyGapSample(3, null, null, false),
            DailyGapSample(4, 2500.0, 500.0, true), gap(5, 100.0)
        ), 6)
        assertEquals(2, summary.excludedIndulgenceDays)
        assertEquals(1, summary.excludedMissingDays)
        assertEquals(1, summary.excludedLowIntakeDays)
        assertEquals(1, summary.includedDays)
        assertEquals(100.0, summary.averageDeficitKcal!!, 0.0)
    }

    @Test fun aRecordedZeroIsValidWhileAnUnrecordedDayIsNotInvented() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            DailyGapSample(1, 0.0, 0.0, true), DailyGapSample(100, 0.0, 0.0, false)
        ), 101, minimumIntakeKcal = 0.0)
        assertEquals(1, summary.includedDays)
        assertEquals(1, summary.excludedMissingDays)
        assertEquals(0.0, summary.averageDeficitKcal!!, 0.0)
    }

    @Test fun minimumIntakeCanBeConfiguredButMustBeNonnegativeAndFinite() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            DailyGapSample(1, 2000.0, 799.0, true), DailyGapSample(2, 2000.0, 800.0, true)
        ), 3, 800.0)
        assertEquals(1, summary.includedDays)
        assertEquals(1, summary.excludedLowIntakeDays)
        assertEquals(1200.0, summary.averageDeficitKcal!!, 0.0)
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { threshold ->
            rejected { TrendSummaryCalculator.calculate(emptyList(), emptyList(), 1, threshold) }
        }
    }

    @Test fun duplicateCalendarDaysAreRejectedRatherThanDoubleCounted() {
        rejected { TrendSummaryCalculator.calculate(listOf(body(1), body(1)), emptyList(), 2) }
        rejected { TrendSummaryCalculator.calculate(emptyList(), listOf(gap(1, 100.0), gap(1, 200.0)), 2) }
    }

    @Test fun averagingLargeOppositeFiniteValuesDoesNotOverflow() {
        val summary = TrendSummaryCalculator.calculate(emptyList(), listOf(
            DailyGapSample(1, 1e308, 0.0, true), DailyGapSample(2, 0.0, 1e308, true)
        ), 3, minimumIntakeKcal = 0.0)
        assertEquals(2, summary.includedDays)
        assertEquals(0.0, summary.averageDeficitKcal!!, 0.0)
    }
}
