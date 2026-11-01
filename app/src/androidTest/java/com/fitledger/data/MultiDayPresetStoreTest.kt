package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Actual SQLite transactions: future plans and reviewed ratio changes never rewrite the ledger. */
@RunWith(AndroidJUnit4::class)
class MultiDayPresetStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var store:FoodPresetStore
    private val ledger get()=db.dao()
    private val presets get()=db.foodPresetDao()
    private val day get()=LocalDate.now().toEpochDay()
    private val a=Food(1,"土豆泥","g",100.0,Nutrition(100.0,20.0,3.0,1.0),30.0,500.0,true,pricePerUnitYuan=0.02)
    private val b=Food(2,"米饭","g",100.0,Nutrition(120.0,25.0,2.0,0.5),10.0,1000.0,true)
    private val ratios=WeightRatios.encode(listOf(WeightRatio(1,2,0.5)))

    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        store=FoodPresetStore(db)
    }
    @After fun close() {db.close()}
    private suspend fun seed() {
        ledger.saveFood(a);ledger.saveFood(b)
        val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
        val body=BodyRecord(day,72.4,15.2,61.3952,null,EnergyExpenditureCalculator.snapshot(72.4,profile,day))
        ledger.insertBody(body)
        ledger.insertTarget(TargetPlanning.create(Plan(),body,ledger.allBodies(),ledger.allTargets(),ledger.allExercises()))
    }
    private suspend fun arrangement(date:Long=day+7):Pair<Long,Long> {
        val first=store.saveItem(date,a.id,70.0)
        val second=store.saveItem(date,b.id,150.0)
        store.saveRatios(date,ratios)
        return first to second
    }
    private suspend fun arrived():Pair<Long,Long> {
        presets.savePlan(FoodPresetPlan(day,day-7,2600.0,70.0,ratios))
        val first=presets.insertItem(FoodPresetItem(day=day,foodId=a.id,name=a.name,unit=a.unit,quantity=90.0,
            nutrition=a.nutrition*0.9,costYuan=1.8))
        val second=presets.insertItem(FoodPresetItem(day=day,foodId=b.id,name=b.name,unit=b.unit,quantity=150.0,
            nutrition=b.nutrition*1.5))
        return first to second
    }

    @Test fun allSevenDatesRemainSeparateAndDoNotCreateFutureMeasurementsOrConsumption()=runBlocking {
        seed();val bodies=ledger.allBodies();val targets=ledger.allTargets()
        for(offset in 1L..7L) store.saveItem(day+offset,a.id,offset*30.0)
        assertEquals((1L..7L).map {day+it},presets.allPlans().map {it.day})
        assertEquals((1L..7L).map {it*30.0},presets.allItems().map {it.quantity})
        assertTrue(presets.allPlans().all {it.referenceDay==day && it.referenceWeightKg==72.4})
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets());assertTrue(ledger.allIntakes().isEmpty())
        assertTrue(runCatching {store.saveItem(day+8,a.id,30.0)}.isFailure)
        assertNull(presets.plan(day+8));assertNull(ledger.body(day+7));assertNull(ledger.target(day+7))
    }

    @Test fun savingRulesChangesNoAmountsAndConfirmedPreviewUpdatesOnlyPlannedSnapshots()=runBlocking {
        seed();val (first,second)=arrangement()
        val before=presets.allItems();val plan=presets.plan(day+7)
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        assertEquals(70.0,presets.item(first)!!.quantity,0.0)
        val expected=store.previewRatios(day+7)
        assertEquals(75.0,expected.rounding.deviations.single().targetGrams,0.0)
        assertEquals(90.0,expected.rounding.deviations.single().actualGrams,0.0)
        assertEquals(before,presets.allItems())
        store.applyRatios(expected)
        assertEquals(90.0,presets.item(first)!!.quantity,0.0)
        assertEquals(a.nutrition*0.9,presets.item(first)!!.nutrition)
        assertEquals(1.8,presets.item(first)!!.costYuan!!,1e-8)
        assertEquals(before.first {it.id==second},presets.item(second));assertNull(presets.item(second)!!.costYuan)
        assertEquals(plan,presets.plan(day+7));assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun staleFoodAndQuantityAndRulePreviewsFailWithoutPartialWrites()=runBlocking {
        seed();val (first,_)=arrangement()
        val staleFood=store.previewRatios(day+7)
        ledger.saveFood(a.copy(nutrition=a.nutrition*2.0))
        var before=presets.allItems()
        assertTrue(runCatching {store.applyRatios(staleFood)}.isFailure);assertEquals(before,presets.allItems())
        val staleQuantity=store.previewRatios(day+7)
        store.saveItem(day+7,a.id,60.0,first)
        before=presets.allItems()
        assertTrue(runCatching {store.applyRatios(staleQuantity)}.isFailure);assertEquals(before,presets.allItems())
        val staleRule=store.previewRatios(day+7)
        store.saveRatios(day+7,WeightRatios.encode(listOf(WeightRatio(1,2,0.75))))
        before=presets.allItems()
        assertTrue(runCatching {store.applyRatios(staleRule)}.isFailure);assertEquals(before,presets.allItems())
        assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun refreshingReferenceRetainsRatiosAndAmountsAndChangesOnlyFutureEstimate()=runBlocking {
        seed();arrangement();val before=presets.allItems();val old=presets.plan(day+7)!!
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        ledger.insertExercise(Exercise(day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=125.0))
        store.refreshReference(day+7)
        val fresh=presets.plan(day+7)!!
        assertEquals(ratios,fresh.ratioRules);assertEquals(old.referenceExpenditureKcal!!+125.0,fresh.referenceExpenditureKcal!!,1e-8)
        assertEquals(before,presets.allItems());assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun removingReferencePrunesRulesButNeverDeletesOtherFoodsOrTheirQuantity()=runBlocking {
        seed();val (first,second)=arrangement();val before=presets.item(first)!!
        store.removeItem(day+7,second)
        assertEquals("",presets.plan(day+7)!!.ratioRules);assertEquals(before,presets.item(first))
        assertNull(presets.item(second));assertEquals(listOf(a,b),ledger.allFoods())
        assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun changingInvolvedFoodRequiresRemovingItsRulesFirst()=runBlocking {
        seed();val (first,_)=arrangement();val before=presets.allItems()
        assertTrue(runCatching {store.saveItem(day+7,b.id,100.0,first)}.isFailure)
        assertEquals(before,presets.allItems())
        store.saveRatios(day+7,"")
        store.saveItem(day+7,b.id,100.0,first)
        assertEquals(b.id,presets.item(first)!!.foodId);assertEquals(100.0,presets.item(first)!!.quantity,0.0)
    }

    @Test fun arrivalNeverReappliesDeletedConsumptionAndCannotRewriteAppliedRatioMembers()=runBlocking {
        seed();val (first,second)=arrived();val forecast=presets.plan(day)
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        assertEquals(1,store.apply(day,listOf(first)))
        val applied=presets.item(first)!!;ledger.deleteIntake(applied.appliedIntakeId!!,day)
        assertTrue(runCatching {store.previewRatios(day)}.isFailure)
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
        assertEquals(1,store.apply(day,listOf(second)))
        assertEquals(applied,presets.item(first));assertEquals(1,ledger.allIntakes().size)
        assertEquals(forecast,presets.plan(day));assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        store.clear(day)
        assertEquals(2,presets.itemsForDay(day).size);assertEquals(forecast,presets.plan(day))
    }

    @Test fun arrivalRejectsUnreviewedRatioAmountsWithoutCreatingAnyIntake()=runBlocking {
        seed();val (first,second)=arrived();val wrong=presets.item(first)!!.copy(quantity=70.0,nutrition=a.nutrition*0.7,costYuan=1.4)
        presets.updateItem(wrong);val before=presets.allItems()
        assertTrue(runCatching {store.apply(day,listOf(first,second))}.isFailure)
        assertEquals(before,presets.allItems());assertTrue(ledger.allIntakes().isEmpty())
        store.applyRatios(store.previewRatios(day))
        assertEquals(2,store.apply(day,listOf(first,second)))
        assertEquals(listOf(90.0,150.0),ledger.allIntakes().map {it.quantity})
    }
}
