package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class IntakeSearchTest {
    private fun food(id:Long,day:Long,name:String="土豆泥",source:String="FOOD",cost:Double?=2.0,q:Double=100.0)=
        if(source=="DIRECT") Intake(id,day,null,name,"次",1.0,Nutrition(101.0,20.0,3.0,1.0),source,cost)
        else Intake(id,day,7,name,"g",q,Nutrition(100.0,20.0,3.0,1.0),source,cost)
    private val rows=listOf(food(1,20000),food(3,20001),food(2,20001,"米饭"),food(4,19999,"MASHED potato"))

    @Test fun findsHistoricalNamesAcrossDatesWithoutChangingOrRevaluingRows() {
        val before=rows.toList()
        val found=IntakeSearch.find(rows,IntakeSearchFilter("土豆泥"))
        assertEquals(listOf(20001L,20000L),found.map {it.day})
        assertEquals(listOf(3L,1L),found.flatMap {it.entries}.map {it.id})
        assertEquals(before,rows)
        assertEquals(listOf(rows[3]),IntakeSearch.find(rows,IntakeSearchFilter("  mashed  ")).single().entries)
    }

    @Test fun inclusiveDateRangeAndSourceFilterApplyTogether() {
        val entries=rows+food(5,20000,source="ROUTINE")+food(6,20001,source="DIRECT")
        val found=IntakeSearch.find(entries,IntakeSearchFilter(firstDay=20000,lastDay=20000,source=IntakeSearchSource.ROUTINE))
        assertEquals(listOf(5L),found.single().entries.map {it.id})
        assertEquals(setOf(1L,2L,3L,5L,6L),IntakeSearch.find(entries,IntakeSearchFilter(firstDay=20000,lastDay=20001)).flatMap {it.entries}.map {it.id}.toSet())
    }

    @Test fun blankQuerySearchesAllPersistedActualRowsInDeterministicDateAndIdOrder() {
        val found=IntakeSearch.find(rows.reversed(),IntakeSearchFilter("  "))
        assertEquals(listOf(20001L,20000L,19999L),found.map {it.day})
        assertEquals(listOf(3L,2L),found.first().entries.map {it.id})
        assertEquals(rows.size,found.sumOf {it.entries.size})
    }

    @Test fun missingMacroObservationStaysUnknownInSearchDayTotals() {
        val energy=Intake(9,20000,null,"仅热量记录","次",1.0,Nutrition(500.0),"ENERGY",null)
        val result=IntakeSearch.find(listOf(rows[0],energy),IntakeSearchFilter()).single()
        assertEquals(600.0,result.nutrition.kcal,0.0)
        assertEquals(1,result.nutrition.unknownMacroCount)
        assertNull(result.nutrition.carbs);assertNull(result.nutrition.protein);assertNull(result.nutrition.fat)
        assertEquals(20.0,result.nutrition.knownNutrition.carbs,0.0)
        assertEquals(listOf(energy),IntakeSearch.find(listOf(rows[0],energy),IntakeSearchFilter(source=IntakeSearchSource.ENERGY)).single().entries)
    }

    @Test fun unknownCostsAreNotInventedAndHistoricalFractionalPortionsArePreserved() {
        val old=rows[0].copy(unit="份",quantity=0.5,costYuan=null)
        val free=rows[1].copy(day=old.day,costYuan=0.0)
        val day=IntakeSearch.find(listOf(old,free),IntakeSearchFilter()).single()
        assertNull(day.totalCostYuan);assertEquals(1,day.unknownPriceCount);assertEquals(0.0,day.knownCostYuan,0.0)
        assertEquals(0.5,day.entries.last().quantity,0.0)
        assertEquals(0.0,IntakeSearch.find(listOf(free),IntakeSearchFilter()).single().totalCostYuan!!,0.0)
    }

    @Test fun emptyResultsDoNotCreateSyntheticDatesOrZeroIntakes() {
        assertTrue(IntakeSearch.find(rows,IntakeSearchFilter("不存在的食物")).isEmpty())
        assertTrue(IntakeSearch.find(emptyList(),IntakeSearchFilter()).isEmpty())
        assertTrue(IntakeSearch.find(rows,IntakeSearchFilter(firstDay=21000)).isEmpty())
    }

    @Test fun invalidDateRangesAndOversizedQueriesAreRejectedBeforeSearching() {
        for(filter in listOf(IntakeSearchFilter(firstDay=20001,lastDay=20000),IntakeSearchFilter("x".repeat(201)),
            IntakeSearchFilter(firstDay=Long.MIN_VALUE),IntakeSearchFilter(lastDay=Long.MAX_VALUE))) {
            assertTrue(runCatching {IntakeSearch.find(rows,filter)}.isFailure)
        }
    }
}
