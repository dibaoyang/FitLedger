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

@RunWith(AndroidJUnit4::class)
class FoodPresetStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var store:FoodPresetStore
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val bread=Food(8,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,pricePerUnitYuan=3.0)
    private val ledger get()=db.dao()
    private val presets get()=db.foodPresetDao()

    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        store=FoodPresetStore(db)
    }
    @After fun close() {db.close()}

    private suspend fun foods() {ledger.saveFood(rice);ledger.saveFood(bread)}
    private suspend fun measured(date:Long,weight:Double=60.0,plan:Plan=Plan(),withEnergy:Boolean=true) {
        val energy=if(withEnergy) EnergyExpenditureCalculator.snapshot(weight,profile,date) else null
        val body=BodyRecord(date,weight,20.0,weight*0.8,null,energy)
        ledger.insertBody(body)
        ledger.insertTarget(TargetPlanning.create(plan,body,ledger.allBodies(),ledger.allTargets(),ledger.allExercises()))
    }
    private fun item(food:Food,quantity:Double,date:Long=day)=FoodPresetItem(
        day=date,foodId=food.id,name=food.name,unit=food.unit,quantity=quantity,
        nutrition=food.nutrition*(quantity/food.basisAmount),costYuan=FoodPricing.cost(food.pricePerUnitYuan,quantity))
    private suspend fun arrived(withToday:Boolean=true):Pair<Long,Long> {
        foods();measured(day-1)
        if(withToday) measured(day,weight=80.0)
        presets.savePlan(FoodPresetPlan(day,day-1,2500.0,60.0))
        return presets.insertItem(item(rice,100.0)) to presets.insertItem(item(bread,1.0))
    }

    @Test fun savingTomorrowPersistsFrozenFoodSnapshotsWithoutFutureBodyTargetOrActualIntake()=runBlocking {
        foods();measured(day)
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        val first=store.saveItem(day+1,rice.id,125.0)
        val second=FoodPresetStore(db).saveItem(day+1,bread.id,2.0)
        val planned=presets.itemsForDay(day+1)
        assertEquals(listOf(first,second),planned.map {it.id})
        assertEquals(rice.nutrition*1.25,planned[0].nutrition)
        assertEquals(1.25,planned[0].costYuan!!,1e-8)
        assertEquals(bread.nutrition*2.0,planned[1].nutrition)
        assertTrue(planned.all {it.appliedIntakeId==null})
        val plan=presets.plan(day+1)!!
        assertEquals(day,plan.referenceDay);assertEquals(60.0,plan.referenceWeightKg,0.0)
        assertEquals(TargetPlanning.baseExpenditure(targets.single(),bodies.single())!!,plan.referenceExpenditureKcal!!,1e-8)
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertNull(ledger.body(day+1));assertNull(ledger.target(day+1));assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun referenceUsesManualBasePlusIncludedExerciseAndOnlyExplicitRefreshChangesIt()=runBlocking {
        foods()
        val plan=Plan(advanced=AdvancedPlan(templates=listOf(PlanTemplate(budget=EnergyBudget(
            expenditureSource=ExpenditureSource.MANUAL,manualExpenditure=2600.0)))))
        measured(day,plan=plan)
        ledger.insertExercise(Exercise(day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=100.0))
        ledger.insertExercise(Exercise(day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=999.0,included=false))
        store.saveItem(day+1,rice.id,100.0)
        assertEquals(2700.0,presets.plan(day+1)!!.referenceExpenditureKcal!!,1e-8)
        ledger.insertExercise(Exercise(day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=200.0))
        store.saveItem(day+1,bread.id,1.0)
        val before=presets.plan(day+1)!!;val items=presets.itemsForDay(day+1)
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        assertEquals(2700.0,before.referenceExpenditureKcal!!,1e-8)
        store.refreshReference(day+1)
        assertEquals(2900.0,presets.plan(day+1)!!.referenceExpenditureKcal!!,1e-8)
        assertEquals(items,presets.itemsForDay(day+1))
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertNull(ledger.target(day+1));assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun missingEnergyRemainsUnknownWhileFoodPlanningStillWorks()=runBlocking {
        foods();measured(day,withEnergy=false)
        val bodies=ledger.allBodies();val targets=ledger.allTargets()
        store.saveItem(day+1,rice.id,100.0)
        assertNull(presets.plan(day+1)!!.referenceExpenditureKcal)
        assertNull(ledger.body(day)!!.energy)
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertTrue(ledger.allIntakes().isEmpty())
    }

    @Test fun draftEditRemoveAndClearLeaveActualRecordsUnchanged()=runBlocking {
        foods();measured(day)
        val actual=ledger.insertIntake(Intake(day=day,foodId=rice.id,name=rice.name,unit=rice.unit,quantity=40.0,nutrition=rice.nutrition*0.4,costYuan=0.4))
        val before=ledger.allIntakes();val bodies=ledger.allBodies();val targets=ledger.allTargets()
        val first=store.saveItem(day+1,rice.id,100.0)
        val second=store.saveItem(day+1,bread.id,1.0)
        assertEquals(first,store.saveItem(day+1,rice.id,150.0,first))
        assertEquals(rice.nutrition*1.5,presets.item(first)!!.nutrition)
        store.removeItem(day+1,second);assertNull(presets.item(second))
        store.clear(day+1);assertTrue(presets.itemsForDay(day+1).isEmpty())
        assertEquals(before,ledger.allIntakes());assertNotNull(ledger.intake(actual))
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
    }

    @Test fun invalidDraftInputsNeverCreatePartialPlansOrFutureMeasurements()=runBlocking {
        foods();measured(day);ledger.markFoodDeleted(bread.id)
        for(quantity in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertTrue(runCatching {store.saveItem(day+1,rice.id,quantity)}.isFailure)
        }
        for(foodId in listOf(999L,bread.id)) assertTrue(runCatching {store.saveItem(day+1,foodId,100.0)}.isFailure)
        assertTrue(runCatching {store.saveItem(day,rice.id,100.0)}.isFailure)
        for(date in listOf(day-1,day+8)) assertTrue(runCatching {store.saveItem(date,rice.id,100.0)}.isFailure)
        assertTrue(presets.allPlans().isEmpty());assertTrue(presets.allItems().isEmpty())
        assertTrue(ledger.allIntakes().isEmpty());assertNull(ledger.body(day+1));assertNull(ledger.target(day+1))
    }

    @Test fun actualDayRequiresExistingPresetBodyAndTargetAndRejectsDuplicateSelection()=runBlocking {
        val (first,_)=arrived(withToday=false)
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
        val body=BodyRecord(day,80.0,20.0,64.0,null,EnergyExpenditureCalculator.snapshot(80.0,profile,day))
        ledger.insertBody(body)
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
        ledger.insertTarget(TargetPlanning.create(Plan(),body,ledger.allBodies(),ledger.allTargets(),ledger.allExercises()))
        assertTrue(runCatching {store.apply(day,listOf(first,first))}.isFailure)
        assertTrue(runCatching {store.apply(day,emptyList())}.isFailure)
        assertNull(presets.item(first)!!.appliedIntakeId);assertTrue(ledger.allIntakes().isEmpty())
        assertTrue(runCatching {store.saveItem(day,rice.id,100.0,999L)}.isFailure)
    }

    @Test fun explicitSubsetAdoptionWritesFoodIntakeAndKeepsTargetsAndPreviousDayEstimateFrozen()=runBlocking {
        val (first,second)=arrived()
        val bodies=ledger.allBodies();val targets=ledger.allTargets();val forecast=presets.plan(day)!!
        assertEquals(1,store.apply(day,listOf(first)))
        val marker=presets.item(first)!!.appliedIntakeId!!
        val actual=ledger.intake(marker)!!
        assertEquals(day,actual.day);assertEquals("FOOD",actual.source)
        assertEquals(rice.id,actual.foodId);assertEquals(rice.name,actual.name);assertEquals(rice.unit,actual.unit)
        assertEquals(100.0,actual.quantity,0.0);assertEquals(rice.nutrition,actual.nutrition);assertEquals(1.0,actual.costYuan!!,1e-8)
        assertNull(presets.item(second)!!.appliedIntakeId)
        assertEquals(forecast,presets.plan(day));assertEquals(2500.0,presets.plan(day)!!.referenceExpenditureKcal!!,0.0)
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
    }

    @Test fun deletedActualIntakeDoesNotPermitReapplicationAndClearRetainsAppliedMarker()=runBlocking {
        val (first,second)=arrived()
        store.apply(day,listOf(first))
        val applied=presets.item(first)!!;val forecast=presets.plan(day)
        ledger.deleteIntake(applied.appliedIntakeId!!,day)
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
        assertTrue(runCatching {store.removeItem(day,first)}.isFailure)
        assertTrue(runCatching {store.saveItem(day,rice.id,150.0,first)}.isFailure)
        store.clear(day)
        assertEquals(applied,presets.item(first));assertNull(presets.item(second))
        assertEquals(forecast,presets.plan(day));assertTrue(ledger.allIntakes().isEmpty())
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
    }

    @Test fun changedFoodInAnySelectedRowRollsBackEveryIntakeAndMarker()=runBlocking {
        val (first,second)=arrived()
        val changed=listOf(
            bread.copy(name="改名面包"),bread.copy(pricePerUnitYuan=4.0),
            bread.copy(nutrition=bread.nutrition*2.0),
            bread.copy(unit="g",basisAmount=100.0,step=10.0,maximum=500.0),bread.copy(isDeleted=true)
        )
        val before=presets.allItems();val bodies=ledger.allBodies();val targets=ledger.allTargets()
        for(food in changed) {
            ledger.saveFood(food)
            assertTrue(runCatching {store.apply(day,listOf(first,second))}.isFailure)
            assertEquals(before,presets.allItems());assertTrue(ledger.allIntakes().isEmpty())
        }
        ledger.saveFood(bread)
        assertEquals(2,store.apply(day,listOf(first,second)))
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
    }

    @Test fun refreshingFoodOnArrivalUsesNewReviewedValuesButPreservesReferenceAndDailyTarget()=runBlocking {
        val (first,_)=arrived()
        val forecast=presets.plan(day);val target=ledger.target(day)
        val changed=rice.copy(nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.02)
        ledger.saveFood(changed)
        assertTrue(runCatching {store.apply(day,listOf(first))}.isFailure)
        assertEquals(first,store.saveItem(day,rice.id,100.0,first))
        assertEquals(changed.nutrition,presets.item(first)!!.nutrition)
        assertEquals(2.0,presets.item(first)!!.costYuan!!,1e-8)
        store.apply(day,listOf(first))
        assertEquals(changed.nutrition,ledger.intake(presets.item(first)!!.appliedIntakeId!!)!!.nutrition)
        assertEquals(forecast,presets.plan(day));assertEquals(target,ledger.target(day))
        assertTrue(runCatching {store.refreshReference(day)}.isFailure)
    }

    @Test fun changingFoodFromGramsToPortionsCannotReinterpretOldQuantityOrStaleNewSelection()=runBlocking {
        val (first,_)=arrived()
        ledger.saveFood(rice.copy(unit="份",basisAmount=1.0,step=1.0,maximum=5.0))
        val items=presets.allItems();val plans=presets.allPlans()
        val bodies=ledger.allBodies();val targets=ledger.allTargets();val intakes=ledger.allIntakes()
        assertTrue(runCatching {store.saveItem(day,rice.id,100.0,first)}.isFailure)
        assertTrue(runCatching {store.saveItem(day,rice.id,100.0,expectedUnit="g")}.isFailure)
        assertTrue(runCatching {store.saveItem(day+1,rice.id,100.0,expectedUnit="g")}.isFailure)
        assertEquals(items,presets.allItems());assertEquals(plans,presets.allPlans())
        assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets());assertEquals(intakes,ledger.allIntakes())
        assertNull(presets.plan(day+1));assertNull(presets.item(first)!!.appliedIntakeId)
    }

    @Test fun manualPresetIgnoresRecommendationLimitsAndUnknownPriceStaysUnknown()=runBlocking {
        foods();measured(day-1);measured(day)
        ledger.saveZone(FoodZone(1,"米饭区",dailyMaximum=10.0,recommendationMaximum=5.0,limitUnit="G"))
        val manualFood=rice.copy(allowed=false,zoneId=1,pricePerUnitYuan=null)
        ledger.saveFood(manualFood)
        val tomorrowId=store.saveItem(day+1,rice.id,600.5)
        val future=presets.item(tomorrowId)!!
        assertNull(future.costYuan);assertEquals(600.5,future.quantity,0.0)
        presets.savePlan(FoodPresetPlan(day,day-1,2500.0,60.0))
        val todayId=presets.insertItem(future.copy(id=0,day=day))
        assertEquals(1,store.apply(day,listOf(todayId)))
        val actual=ledger.intake(presets.item(todayId)!!.appliedIntakeId!!)!!
        assertEquals(600.5,actual.quantity,0.0);assertNull(actual.costYuan)
        assertEquals(manualFood,ledger.food(rice.id));assertNull(ledger.target(day+1))
    }

    @Test fun routineAndExistingIntakeRemainSeparateAndUserExplicitlyChoosesWhetherToAddDuplicate()=runBlocking {
        val (first,second)=arrived()
        val daily=DailyEatingStore(db)
        daily.saveTemplate(DailyFoodTemplate(foodId=rice.id,unit="g",mode="FIXED",quantity=100.0,startDay=day),day)
        val routine=ledger.allIntakes().single();val marker=daily.dao.allApplications()
        assertEquals("ROUTINE",routine.source)
        assertEquals(1,store.apply(day,listOf(second)))
        assertEquals(routine,ledger.intake(routine.id));assertNull(presets.item(first)!!.appliedIntakeId)
        assertEquals(1,store.apply(day,listOf(first)))
        val riceIntakes=ledger.allIntakes().filter {it.foodId==rice.id}
        assertEquals(2,riceIntakes.size);assertEquals(200.0,riceIntakes.sumOf {it.quantity},0.0)
        assertEquals(marker,daily.dao.allApplications())
        assertEquals(1,riceIntakes.count {it.source=="FOOD"});assertEquals(1,riceIntakes.count {it.source=="ROUTINE"})
    }

    @Test fun staleDatesAndForeignPresetRowsCannotChangeAnyActualRecords()=runBlocking {
        val (first,_)=arrived()
        val tomorrow=store.saveItem(day+1,rice.id,100.0)
        val before=presets.allItems();val plans=presets.allPlans()
        assertTrue(runCatching {store.apply(day+1,listOf(tomorrow))}.isFailure)
        assertTrue(runCatching {store.apply(day-1,listOf(first))}.isFailure)
        assertTrue(runCatching {store.apply(day,listOf(tomorrow))}.isFailure)
        assertTrue(runCatching {store.removeItem(day,tomorrow)}.isFailure)
        assertTrue(runCatching {store.clear(day-1)}.isFailure)
        assertTrue(runCatching {store.refreshReference(day)}.isFailure)
        assertEquals(before,presets.allItems());assertEquals(plans,presets.allPlans())
        assertTrue(ledger.allIntakes().isEmpty())
    }
}
