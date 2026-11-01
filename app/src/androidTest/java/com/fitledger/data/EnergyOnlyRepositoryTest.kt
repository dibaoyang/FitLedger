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

/** Actual Room mutation coverage; these tests must be executed on a device before claiming it. */
@RunWith(AndroidJUnit4::class)
class EnergyOnlyRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private lateinit var store:FeatureStore
    private val day get()=LocalDate.now().toEpochDay()
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val bread=Food(8,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,pricePerUnitYuan=3.0)

    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db);store=FeatureStore(db)
    }
    @After fun close() {db.close()}
    private suspend fun seed() {
        repo.dao.saveFood(rice);repo.dao.saveFood(bread)
        for((date,weight) in listOf(day-1 to 60.0,day to 80.0)) {
            repo.dao.insertBody(BodyRecord(date,weight,20.0,weight*0.8,null))
            repo.dao.insertTarget(DailyTarget(date,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan())))
        }
    }
    private suspend fun assertMeasurementsUnchanged(bodies:List<BodyRecord>,targets:List<DailyTarget>) {
        assertEquals(bodies,repo.dao.allBodies());assertEquals(targets,repo.dao.allTargets())
    }

    @Test fun currentAndHistoricalEnergyRequireMeasuredDaysAndNeverInventMissingSnapshots()=runBlocking {
        assertTrue(runCatching {repo.recordEnergy(day,500.0)}.isFailure)
        assertTrue(runCatching {store.addEnergy(day-1,500.0)}.isFailure)
        assertTrue(runCatching {store.addEnergy(day+1,500.0)}.isFailure)
        assertTrue(repo.dao.allBodies().isEmpty());assertTrue(repo.dao.allTargets().isEmpty())
        repo.dao.insertBody(BodyRecord(day,80.0,20.0,64.0,null))
        assertTrue(runCatching {repo.recordEnergy(day,500.0)}.isFailure)
        assertTrue(runCatching {store.addEnergy(day,500.0)}.isFailure)
        assertTrue(repo.dao.allIntakes().isEmpty())
        assertEquals(1,repo.dao.allBodies().size);assertTrue(repo.dao.allTargets().isEmpty())
    }

    @Test fun caloriesAndCostsRemainKnownWhileAnyEnergyOnlyRowMakesEveryMacroTotalUnknown()=runBlocking {
        seed();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        repo.record(day,mapOf(rice.id to 100.0))
        val id=repo.recordEnergy(day,500.0," 外食估计 ",6.25)
        val energy=requireNotNull(repo.dao.intake(id))
        assertEquals("外食估计",energy.name);assertEquals("ENERGY",energy.source)
        assertNull(energy.foodId);assertEquals("次",energy.unit);assertEquals(1.0,energy.quantity,0.0)
        assertFalse(energy.macrosKnown)
        val summary=IntakeNutritionTotals.calculate(repo.dao.allIntakes().filter {it.day==day})
        assertEquals(616.0,summary.kcal,0.0);assertEquals(1,summary.unknownMacroCount)
        assertNull(summary.carbs);assertNull(summary.protein);assertNull(summary.fat);assertNull(summary.completeNutrition)
        assertEquals(26.0,summary.knownNutrition.carbs,0.0)
        assertEquals(2084.0,ExerciseCalculator.gap(2600.0,100.0,summary.kcal)!!,0.0)
        assertEquals(7.25,repo.dao.allIntakes().sumOf {it.costYuan ?: 0.0},0.0)
        assertMeasurementsUnchanged(bodies,targets)
    }

    @Test fun incompleteMacrosBlockNewRecommendationAndStaleAdoptionButNotManualFoodOrDirectRecords()=runBlocking {
        seed()
        val suggestion=repo.recommend(day,mapOf(rice.id to 100.0))
        assertTrue(suggestion.quantities.isNotEmpty())
        val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        repo.recordEnergy(day,600.0)
        val before=repo.dao.allIntakes()
        val failure=runCatching {repo.recommend(day,emptyMap())}.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("三大营养"))
        val stale=runCatching {repo.record(day,suggestion.quantities,recommendation=true)}.exceptionOrNull()
        assertTrue(stale?.message.orEmpty().contains("三大营养"))
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(bread.id to 1.0))
        repo.recordNutrition(day,DirectNutrition.calculate(20.0,10.0,5.0))
        assertEquals(3,repo.dao.allIntakes().size)
        assertEquals(1,IntakeNutritionTotals.calculate(repo.dao.allIntakes()).unknownMacroCount)
        assertMeasurementsUnchanged(bodies,targets)
    }

    @Test fun energyEditAndExplicitCompletionPreserveIdentityCostOtherDaysAndFrozenTargets()=runBlocking {
        seed();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        val oldId=store.addEnergy(day-1,450.0,"昨日外食",12.0)
        val currentId=repo.recordEnergy(day,700.0,"今日外食",null)
        val current=requireNotNull(repo.dao.intake(currentId))
        store.editEnergy(day-1,oldId,650.0,"昨日晚餐",15.0)
        val changed=requireNotNull(repo.dao.intake(oldId))
        assertEquals(oldId,changed.id);assertEquals(day-1,changed.day)
        assertEquals(650.0,changed.nutrition.kcal,0.0);assertFalse(changed.macrosKnown)
        assertEquals(current,repo.dao.intake(currentId))
        assertTrue(runCatching {store.editEnergy(day,oldId,999.0)}.isFailure)
        assertTrue(runCatching {store.completeEnergy(day-1,oldId,-1.0,1.0,1.0)}.isFailure)
        assertEquals(changed,repo.dao.intake(oldId))
        store.completeEnergy(day-1,oldId,20.0,10.0,5.0)
        val completed=requireNotNull(repo.dao.intake(oldId))
        assertEquals("DIRECT",completed.source);assertTrue(completed.macrosKnown)
        assertEquals(DirectNutrition.calculate(20.0,10.0,5.0),completed.nutrition)
        assertEquals(15.0,completed.costYuan!!,0.0);assertEquals(changed.name,completed.name)
        assertEquals(current,repo.dao.intake(currentId))
        assertTrue(runCatching {store.editEnergy(day-1,oldId,500.0)}.isFailure)
        assertMeasurementsUnchanged(bodies,targets)
    }

    @Test fun invalidEnergyInputsAndUnsupportedGenericEditsNeverChangeAnySavedRecord()=runBlocking {
        seed()
        val id=repo.recordEnergy(day,500.0)
        val saved=repo.dao.allIntakes();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        for(kcal in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertTrue(runCatching {repo.recordEnergy(day,kcal)}.isFailure)
            assertTrue(runCatching {store.addEnergy(day-1,kcal)}.isFailure)
            assertTrue(runCatching {store.editEnergy(day,id,kcal)}.isFailure)
        }
        for(cost in listOf(-0.01,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertTrue(runCatching {repo.recordEnergy(day,1.0,costYuan=cost)}.isFailure)
            assertTrue(runCatching {store.editEnergy(day,id,1.0,costYuan=cost)}.isFailure)
        }
        assertTrue(runCatching {repo.recordEnergy(day,100.0,"name\nnew line")}.isFailure)
        assertTrue(runCatching {repo.recordEnergy(day,100.0,"a".repeat(101))}.isFailure)
        assertTrue(runCatching {repo.recordEnergy(day+1,100.0)}.isFailure)
        assertTrue(runCatching {store.editIntake(day,id,2.0)}.isFailure)
        assertTrue(runCatching {store.editDirect(day,id,10.0,0.0,0.0)}.isFailure)
        assertEquals(saved,repo.dao.allIntakes());assertMeasurementsUnchanged(bodies,targets)
    }

    @Test fun undoRestoresTheExactUnknownMacroMarkerAndRejectsForgedKnownValues()=runBlocking {
        seed()
        val id=repo.recordEnergy(day,720.0,"一餐估计",null)
        val saved=store.removeIntake(day,id)
        assertTrue(repo.dao.allIntakes().isEmpty())
        store.restoreIntake(saved)
        assertEquals(saved,repo.dao.intake(id));assertFalse(repo.dao.intake(id)!!.macrosKnown)
        assertTrue(runCatching {store.restoreIntake(saved)}.isFailure)
        store.removeIntake(day,id)
        assertTrue(runCatching {store.restoreIntake(saved.copy(nutrition=Nutrition(720.0,1.0,0.0,0.0)))}.isFailure)
        assertTrue(runCatching {store.restoreIntake(saved.copy(foodId=rice.id))}.isFailure)
        assertTrue(runCatching {store.restoreIntake(saved.copy(quantity=2.0))}.isFailure)
        assertTrue(repo.dao.allIntakes().isEmpty())
        store.restoreIntake(saved);assertEquals(saved,repo.dao.intake(id))
    }

    @Test fun selectedHistoryCopyScalesOnlySavedEnergyAndCostWithoutGuessingMacrosOrChangingHistory()=runBlocking {
        seed()
        val energy=store.addEnergy(day-1,600.0,"昨日估计",12.0)
        val direct=store.addDirect(day-1,20.0,10.0,5.0)
        val food=store.recordFood(day-1,rice.id,100.0)
        val historical=repo.dao.allIntakes();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        repo.saveFood(rice.copy(nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.05))
        assertEquals(3,store.copyIntakesToToday(day-1,linkedMapOf(energy to 1.5,direct to 2.0,food to 200.0)).count)
        val copied=repo.dao.allIntakes().filter {it.day==day}
        val energyCopy=copied.single {it.source=="ENERGY"}
        assertEquals(900.0,energyCopy.nutrition.kcal,0.0);assertEquals(18.0,energyCopy.costYuan!!,0.0)
        assertEquals(1.0,energyCopy.quantity,0.0);assertNull(energyCopy.foodId);assertFalse(energyCopy.macrosKnown)
        assertEquals(DirectNutrition.calculate(40.0,20.0,10.0),copied.single {it.source=="DIRECT"}.nutrition)
        assertEquals(464.0,copied.single {it.source=="FOOD"}.nutrition.kcal,1e-9)
        assertEquals(historical,repo.dao.allIntakes().filter {it.day==day-1})
        assertMeasurementsUnchanged(bodies,targets)
        assertTrue(store.copyIntakesToToday(day-1,mapOf(energy to 0.5)).warnings.isNotEmpty())
    }

    @Test fun defaultCopyKeepsUnknownPriceAndInvalidSecondSelectionRollsBackWholeCopy()=runBlocking {
        seed()
        val energy=store.addEnergy(day-1,500.0,"旧估计",null)
        val food=store.recordFood(day-1,rice.id,100.0)
        val before=repo.dao.allIntakes()
        assertTrue(runCatching {store.copyIntakesToToday(day-1,linkedMapOf(food to 50.0,energy to Double.MAX_VALUE))}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        assertEquals(1,store.copyToToday(day-1,setOf(energy),emptySet()).count)
        val copied=repo.dao.allIntakes().single {it.day==day}
        assertEquals("ENERGY",copied.source);assertEquals(500.0,copied.nutrition.kcal,0.0);assertNull(copied.costYuan)
        assertNull(IntakeNutritionTotals.calculate(listOf(copied)).carbs)
        assertEquals(before,repo.dao.allIntakes().filter {it.day==day-1})
    }

    @Test fun explicitPresetAdoptionAndDailyFixedFoodRemainAllowedWithIncompleteActualMacros()=runBlocking {
        seed();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        val daily=DailyEatingStore(db)
        daily.saveTemplate(DailyFoodTemplate(foodId=bread.id,unit="份",mode="FIXED",quantity=1.0,startDay=day),day)
        val marker=daily.dao.allApplications()
        assertEquals(1,repo.dao.allIntakes().count {it.source=="ROUTINE"})
        repo.recordEnergy(day,500.0)
        repo.saveFood(rice.copy(allowed=false))
        db.foodPresetDao().savePlan(FoodPresetPlan(day,day-1,2500.0,60.0))
        val preset=db.foodPresetDao().insertItem(FoodPresetItem(day=day,foodId=rice.id,name=rice.name,unit=rice.unit,
            quantity=100.0,nutrition=rice.nutrition,costYuan=1.0))
        assertEquals(1,FoodPresetStore(db).apply(day,listOf(preset)))
        assertEquals(3,repo.dao.allIntakes().size)
        assertEquals(marker,daily.dao.allApplications())
        assertNotNull(db.foodPresetDao().item(preset)!!.appliedIntakeId)
        val summary=IntakeNutritionTotals.calculate(repo.dao.allIntakes())
        assertEquals(816.0,summary.kcal,0.0);assertNull(summary.carbs)
        assertMeasurementsUnchanged(bodies,targets)
    }
}
