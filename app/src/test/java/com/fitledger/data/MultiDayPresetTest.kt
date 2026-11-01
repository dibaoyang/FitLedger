package com.fitledger.data

import com.fitledger.domain.*
import org.junit.Assert.*
import org.junit.Test

class MultiDayPresetTest {
    private val day=20000L
    private val a=Food(1,"土豆泥","g",100.0,Nutrition(100.0,20.0,3.0,1.0),30.0,500.0,true,pricePerUnitYuan=0.02)
    private val b=Food(2,"米饭","g",100.0,Nutrition(120.0,25.0,2.0,0.5),10.0,1000.0,true,pricePerUnitYuan=null)
    private val rules=WeightRatios.encode(listOf(WeightRatio(a.id,b.id,0.5)))
    private fun plan()=FoodPresetPlan(day+7,day,2600.0,72.4,rules)
    private fun item(food:Food,id:Long,quantity:Double,applied:Long?=null)=FoodPresetItem(id,day+7,food.id,food.name,food.unit,
        quantity,food.nutrition*(quantity/food.basisAmount),FoodPricing.cost(food.pricePerUnitYuan,quantity),applied)
    private fun items()=listOf(item(a,10,70.0),item(b,11,150.0))

    @Test fun allowsOneThroughSevenDayReferencesButRejectsSamePastAndEighthDay() {
        for(offset in 1L..7L) FoodPresetPlan(day+offset,day,null,60.0).validate()
        for(offset in listOf(-1L,0L,8L)) assertTrue(runCatching {FoodPresetPlan(day+offset,day,null,60.0).validate()}.isFailure)
        assertTrue(runCatching {plan().copy(ratioRules="not-json").validate()}.isFailure)
        assertTrue(runCatching {plan().copy(referenceExpenditureKcal=0.0).validate()}.isFailure)
    }

    @Test fun previewRoundsOnlyDependentAndShowsAbsoluteAndPercentageDeviation() {
        val original=items();val plan=plan()
        val preview=FoodPresetRatios.preview(plan,original,listOf(a,b))
        assertEquals(listOf(90.0,150.0),preview.updated.map {it.quantity})
        val delta=preview.rounding.deviations.single()
        assertEquals(75.0,delta.targetGrams,0.0);assertEquals(90.0,delta.actualGrams,0.0)
        assertEquals(15.0,delta.deltaGrams,0.0);assertEquals(20.0,delta.percent,0.0)
        assertEquals(plan,preview.plan);assertEquals(original,preview.before)
        assertEquals(listOf(10L,11L),preview.updated.map {it.id})
        assertEquals(1.8,preview.updated[0].costYuan!!,1e-8);assertNull(preview.updated[1].costYuan)
        assertEquals(a.nutrition*0.9,preview.updated[0].nutrition)
        assertTrue(preview.updated.all {it.appliedIntakeId==null})
    }

    @Test fun portionMassMustBeKnownAndNeverTurnsNativePortionsIntoGrams() {
        val portion=a.copy(unit="份",basisAmount=1.0,step=0.5,maximum=10.0,pricePerUnitYuan=3.0)
        val rows=listOf(item(portion,10,0.5),item(b,11,200.0))
        assertTrue(runCatching {FoodPresetRatios.preview(plan(),rows,listOf(portion,b))}.isFailure)
        val preview=FoodPresetRatios.preview(plan(),rows,listOf(portion.copy(gramsPerPortion=100.0),b))
        assertEquals(1.0,preview.updated.first().quantity,0.0);assertEquals("份",preview.updated.first().unit)
        assertEquals(100.0,preview.rounding.deviations.single().actualGrams,0.0)
    }

    @Test fun duplicateMissingDeletedChangedUnitAndAppliedRatioRowsAreRejected() {
        val rows=items()
        listOf(rows.drop(1),rows+rows.first().copy(id=12),rows.map {if(it.foodId==a.id) it.copy(appliedIntakeId=900) else it}).forEach {
            assertTrue(runCatching {FoodPresetRatios.preview(plan(),it,listOf(a,b))}.isFailure)
        }
        listOf(listOf(b),listOf(a.copy(isDeleted=true),b),listOf(a.copy(unit="份",basisAmount=1.0,step=1.0,maximum=10.0),b)).forEach {
            assertTrue(runCatching {FoodPresetRatios.preview(plan(),rows,it)}.isFailure)
        }
    }

    @Test fun unrelatedAppliedSnapshotsStayExactlyAsSavedAndFreshRatioFoodsAreReviewed() {
        val extra=FoodPresetItem(13,day+7,3,"已添加的旧快照","份",1.0,Nutrition(200.0,20.0,10.0,5.0),9.0,900)
        val changed=a.copy(name="新名称",nutrition=a.nutrition*2.0,pricePerUnitYuan=0.03)
        val preview=FoodPresetRatios.preview(plan(),items()+extra,listOf(changed,b))
        assertEquals(extra,preview.updated.last());assertEquals("新名称",preview.updated.first().name)
        assertEquals(2.7,preview.updated.first().costYuan!!,1e-8)
        assertEquals(changed.nutrition*0.9,preview.updated.first().nutrition)
        assertNotEquals(FoodPresetRatios.preview(plan(),items()+extra,listOf(a,b)),preview)
    }

    @Test fun manualPresetRatiosDoNotImposeRecommendationMaximums() {
        val rows=listOf(item(a,10,70.0),item(b,11,1200.0))
        val preview=FoodPresetRatios.preview(plan(),rows,listOf(a,b))
        assertEquals(600.0,preview.updated.first().quantity,0.0)
        assertTrue(preview.updated.first().quantity>a.maximum)
    }

    @Test fun chainsUseRoundedReferenceAndKeepFrozenEnergyAndWeight() {
        val c=a.copy(id=3,name="第三食品",step=10.0)
        val plan=plan().copy(ratioRules=WeightRatios.encode(listOf(WeightRatio(1,2,0.5),WeightRatio(3,1,0.5))))
        val preview=FoodPresetRatios.preview(plan,items()+item(c,12,20.0),listOf(a,b,c))
        assertEquals(listOf(90.0,150.0,50.0),preview.updated.map {it.quantity})
        assertEquals(45.0,preview.rounding.deviations.first {it.foodId==3L}.targetGrams,0.0)
        assertEquals(day,preview.plan.referenceDay);assertEquals(2600.0,preview.plan.referenceExpenditureKcal!!,0.0)
        assertEquals(72.4,preview.plan.referenceWeightKg,0.0)
    }
}
