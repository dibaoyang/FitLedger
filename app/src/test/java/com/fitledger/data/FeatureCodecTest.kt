package com.fitledger.data

import com.fitledger.domain.EatingSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FeatureCodecTest {
    private fun day(date:String)=LocalDate.parse(date).toEpochDay()
    @Test fun legacyEmptyScheduleIsDailyAndWeekdaysCrossWeekAndLeapYear() {
        assertEquals(EatingSchedule(),ScheduleCodec.decode(""))
        val start=day("2024-02-26")
        val weekly=EatingSchedule("WEEKDAYS",weekdays=setOf(1,4,7),endDay=day("2024-03-04"))
        assertEquals(weekly,ScheduleCodec.decode(ScheduleCodec.encode(weekly)))
        assertTrue(weekly.matches(start,start));assertTrue(weekly.matches(day("2024-02-29"),start))
        assertFalse(weekly.matches(day("2024-03-01"),start));assertTrue(weekly.matches(day("2024-03-03"),start))
        assertTrue(weekly.matches(day("2024-03-04"),start));assertFalse(weekly.matches(day("2024-03-07"),start))
    }
    @Test fun intervalUsesStartDayAndSpecifiedDatesIncludeLeapDayWithoutRounding() {
        val start=day("2024-02-27")
        val interval=EatingSchedule("INTERVAL",intervalDays=2)
        assertFalse(interval.matches(start-1,start));assertTrue(interval.matches(start,start))
        assertFalse(interval.matches(start+1,start));assertTrue(interval.matches(day("2024-02-29"),start))
        assertTrue(interval.matches(day("2024-03-02"),start))
        val dates=EatingSchedule("DATES",dates=setOf(start,day("2024-02-29"),day("2024-03-04")))
        assertEquals(dates,ScheduleCodec.decode(ScheduleCodec.encode(dates)))
        assertTrue(dates.matches(start+2,start));assertFalse(dates.matches(start+3,start))
        assertTrue(runCatching {dates.validate(start+1)}.isFailure)
    }
    @Test fun strictScheduleRejectsFractionalDuplicateUnknownAndOutOfRangeFields() {
        val good="""{"mode":"WEEKDAYS","weekdays":[1,3],"intervalDays":1,"dates":[],"endDay":null}"""
        listOf(good.replace("[1,3]","[1,1]"),good.replace("[1,3]","[1.5]"),good.replace("[1,3]","[8]"),
            good.replace("\"intervalDays\":1","\"intervalDays\":0"),good.replace("WEEKDAYS","BOGUS"),
            good.replace("\"dates\":[]","\"dates\":[20000,20000]"),good.replace("null","1.5"),
            good.replace("\"endDay\":null","\"endDay\":null,\"extra\":1"),good.replace("\"intervalDays\":1","\"intervalDays\":\"1\"")
        ).forEach {assertTrue(it,runCatching {ScheduleCodec.decode(it)}.isFailure)}
        assertTrue(runCatching {EatingSchedule(endDay=19999).validate(20000)}.isFailure)
    }
    @Test fun quickAmountsRemainNativeFiniteUniqueAndAtMostEight() {
        val amounts=listOf(0.25,1.0,1.5,50.0,100.0,150.0,200.0,250.0)
        assertEquals(amounts,QuickAmounts.decode(QuickAmounts.encode(amounts)))
        assertTrue(QuickAmounts.decode("").isEmpty())
        listOf("1,1","1,1.0","0","-2","NaN","Infinity","1,","1,2,3,4,5,6,7,8,9").forEach {assertTrue(runCatching {QuickAmounts.decode(it)}.isFailure)}
    }
    @Test fun comboRejectsDuplicateFoodFractionalIdsInvalidUnitAndNonfiniteQuantity() {
        val items=listOf(ComboItem(7,"g",120.5),ComboItem(8,"份",0.75))
        assertEquals(items,ComboCodec.decode(ComboCodec.encode(items)))
        val valid=ComboCodec.encode(items)
        listOf(valid.replace("\"foodId\":8","\"foodId\":7"),valid.replace("\"foodId\":7","\"foodId\":7.5"),
            valid.replace("\"g\"","\"kg\""),valid.replace("120.5","0"),valid.replace("120.5","\"120.5\""),"[]")
            .forEach {assertTrue(it,runCatching {ComboCodec.decode(it)}.isFailure)}
        assertTrue(runCatching {ComboCodec.encode(listOf(ComboItem(7,"g",Double.POSITIVE_INFINITY)))}.isFailure)
    }
}
