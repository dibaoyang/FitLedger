package com.fitledger.ui

import com.fitledger.data.Intake
import com.fitledger.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class IntakeGroupsTest {
    private val day=20000L
    private fun entry(id:Long,quantity:Double,food:Long=7,unit:String="g",name:String="土豆泥",date:Long=day,kcal:Double=quantity)=
        Intake(id,date,food,name,unit,quantity,Nutrition(kcal,quantity/5,quantity/10,quantity/20))
    @Test fun repeatedFoodHasOneSummaryAndKeepsEveryOriginalRecord() {
        val first=entry(1,100.0);val second=entry(2,200.0);val third=entry(3,50.0)
        val grouped=groupIntakesForDay(listOf(third,second,first),day).single()
        assertEquals(350.0,grouped.quantity,0.0);assertEquals(350.0,grouped.nutrition.kcal,0.0)
        assertEquals(listOf(first,second,third),grouped.entries)
        assertEquals(70.0,grouped.nutrition.carbs,0.0)
    }
    @Test fun differentFoodIdsAndUnitsNeverMergeEvenWithSameName() {
        val groups=groupIntakesForDay(listOf(entry(1,100.0),entry(2,100.0,food=8),entry(3,2.0,unit="份")),day)
        assertEquals(3,groups.size)
        assertEquals(listOf(3L,2L,1L),groups.map {it.entries.single().id})
    }
    @Test fun editedFoodUsesEachIntakeNutritionSnapshotAndShowsItsRecordedName() {
        val original=entry(1,100.0,kcal=80.0)
        val changed=entry(2,100.0,name="土豆泥（新配方）",kcal=160.0)
        val group=groupIntakesForDay(listOf(changed,original),day).single()
        assertEquals(240.0,group.nutrition.kcal,0.0);assertEquals("土豆泥（新配方）",group.name)
        assertEquals(original,group.entries.first())
    }
    @Test fun deleteOneRecomputesSummaryAndEventuallyRemovesGroup() {
        val original=listOf(entry(1,100.0),entry(2,200.0),entry(3,50.0))
        val after=groupIntakesForDay(original.filterNot {it.id==2L},day).single()
        assertEquals(listOf(1L,3L),after.entries.map {it.id});assertEquals(150.0,after.quantity,0.0)
        val one=groupIntakesForDay(original.filter {it.id==3L},day).single()
        assertEquals(1,one.entries.size);assertEquals(50.0,one.quantity,0.0)
        assertTrue(groupIntakesForDay(emptyList(),day).isEmpty())
    }
    @Test fun historicalRowsNeverEnterTodayAndFractionalPortionsRemainAccurate() {
        val group=groupIntakesForDay(listOf(entry(1,5.0,unit="份",date=day-1),entry(2,0.5,unit="份"),entry(3,1.25,unit="份")),day).single()
        assertEquals(1.75,group.quantity,0.0);assertEquals(2,group.entries.size)
    }
}
