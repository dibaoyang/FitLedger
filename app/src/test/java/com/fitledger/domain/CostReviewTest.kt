package com.fitledger.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CostReviewTest {
    private fun entry(day:Long,cost:Double?,food:Long?=1,source:String="FOOD")=CostReviewEntry(day,food,source,"食品",cost)
    @Test fun unknownAndAbsentDatesNeverBecomeZeroCostAverageDays() {
        val result=CostReview.calculate(listOf(entry(1,10.0),entry(2,5.0),entry(2,null),entry(4,0.0),entry(5,100.0)),1L..5L,5)
        assertEquals(115.0,result.knownYuan,0.0)
        assertEquals(listOf(1L,4L,5L),result.completeDays)
        assertEquals(listOf(2L),result.unknownDays)
        assertEquals(listOf(3L),result.emptyDays)
        assertEquals(5.0,result.averageYuan!!,0.0)
        assertEquals(2,result.averageDays)
        assertEquals(1,result.unknownEntries)
        assertTrue(result.includesToday)
    }
    @Test fun allUnknownAndDirectNutritionHaveNoAverage() {
        val result=CostReview.calculate(listOf(entry(1,null,null,"DIRECT")),1L..2L,3)
        assertNull(result.averageYuan)
        assertEquals(0,result.averageDays)
        assertEquals(1,result.foods.single().unknownEntries)
        assertEquals(1,result.foods.single().entries)
        assertEquals(listOf(1L),result.unknownDays)
        assertEquals(listOf(2L),result.emptyDays)
    }
    @Test fun foodAndRoutineCostsAreSeparateAndOnlySelectedRangeIsSummed() {
        val result=CostReview.calculate(listOf(entry(0,100.0),entry(1,4.0),entry(1,6.0,source="ROUTINE"),entry(2,null,null,"DIRECT"),entry(4,200.0)),1L..3L,4)
        assertEquals(10.0,result.knownYuan,0.0)
        assertEquals(3,result.foods.size)
        assertEquals("ROUTINE",result.foods.first().source)
        assertEquals(10.0,result.averageYuan!!,0.0)
        assertFalse(result.includesToday)
    }
    @Test fun recentRangesIncludeTodayAndNaturalWeeksStartOnMonday() {
        val today=LocalDate.parse("2026-10-25").toEpochDay()
        val seven=CostReview.range(today,CostReviewPeriod.LAST_7)
        assertEquals(7,seven.count())
        assertEquals(today,seven.last)
        val week=CostReview.range(today,CostReviewPeriod.WEEK)
        assertEquals(LocalDate.parse("2026-10-19").toEpochDay(),week.first)
        assertEquals(today,week.last)
        val last=CostReview.range(today,CostReviewPeriod.WEEK,-1)
        assertEquals(LocalDate.parse("2026-10-12").toEpochDay(),last.first)
        assertEquals(LocalDate.parse("2026-10-18").toEpochDay(),last.last)
    }
    @Test fun currentPeriodEndsTodayAndPreviousMonthUsesCalendarLength() {
        val today=LocalDate.parse("2024-03-03").toEpochDay()
        assertEquals(today,CostReview.range(today,CostReviewPeriod.MONTH).last)
        val feb=CostReview.range(today,CostReviewPeriod.MONTH,-1)
        assertEquals(LocalDate.parse("2024-02-01").toEpochDay(),feb.first)
        assertEquals(LocalDate.parse("2024-02-29").toEpochDay(),feb.last)
        assertTrue(runCatching {CostReview.range(today,CostReviewPeriod.MONTH,1)}.isFailure)
    }
}
