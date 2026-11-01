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
class FeatureStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var store:FeatureStore
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val bread=Food(8,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,pricePerUnitYuan=3.0)
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        store=FeatureStore(db);repo=Repository(db)
    }
    @After fun close() {db.close()}
    private suspend fun seed() {
        db.dao().saveFood(rice);db.dao().saveFood(bread)
        for((date,weight) in listOf(day-1 to 60.0,day to 80.0)) {
            db.dao().insertBody(BodyRecord(date,weight,20.0,weight*0.8,null))
            db.dao().insertTarget(DailyTarget(date,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan())))
        }
    }
    @Test fun historicalFoodDirectAndExerciseChangesPreserveAllBodyAndTargetSnapshots()=runBlocking {
        seed();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        val intake=store.recordFood(day-1,7,100.0)
        repo.saveFood(rice.copy(nutrition=rice.nutrition*10.0,pricePerUnitYuan=5.0))
        store.editIntake(day-1,intake,250.0)
        val edited=db.dao().intake(intake)!!
        assertEquals(rice.nutrition*2.5,edited.nutrition);assertEquals(2.5,edited.costYuan!!,0.0)
        val direct=store.addDirect(day-1,50.0,25.0,10.0)
        store.editDirect(day-1,direct,40.0,20.0,5.0)
        assertEquals(DirectNutrition.calculate(40.0,20.0,5.0),db.dao().intake(direct)!!.nutrition)
        val exercise=store.saveExercise(day-1,type=Cardio.HILL,minutes=30.0,manualKcal=null)
        assertEquals(60.0,db.dao().exercise(exercise)!!.weight!!,0.0)
        assertEquals(180.0,db.dao().exercise(exercise)!!.kcal,0.0)
        store.saveExercise(day-1,exercise,type=Cardio.RUN,minutes=10.0,manualKcal=null,included=false)
        assertFalse(db.dao().exercise(exercise)!!.included)
        assertEquals(70.0,db.dao().exercise(exercise)!!.kcal,0.0)
        store.removeExercise(day-1,exercise);store.removeIntake(day-1,direct)
        assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }
    @Test fun undoRestoresExactIdentityCostNutritionAndRoutineMarkerEvenAfterFoodRemoval()=runBlocking {
        seed();val daily=DailyEatingStore(db)
        daily.saveTemplate(DailyFoodTemplate(foodId=7,unit="g",mode="FIXED",quantity=100.0,startDay=day),day)
        val original=db.dao().allIntakes().single();val markers=daily.dao.allApplications()
        val removed=store.removeIntake(day,original.id);assertEquals(original,removed)
        daily.apply(day);assertTrue(db.dao().allIntakes().isEmpty())
        repo.deleteFood(7);store.restoreIntake(removed)
        assertEquals(original,db.dao().intake(original.id));assertEquals(markers,daily.dao.allApplications())
        assertTrue(runCatching {store.restoreIntake(removed)}.isFailure)
        store.restoreFood(7);assertFalse(db.dao().food(7)!!.isDeleted)
        val ex=store.saveExercise(day-1,type=null,minutes=null,manualKcal=123.0)
        val old=store.removeExercise(day-1,ex);store.restoreExercise(old)
        assertEquals(old,db.dao().exercise(ex))
    }
    @Test fun comboUsesFreshPricesSupportsSubsetAndRejectsStaleUnitsAtomically()=runBlocking {
        seed();val beforeBody=db.dao().allBodies();val beforeTarget=db.dao().allTargets()
        val id=store.saveCombo(FoodCombo(name="早餐",items=ComboCodec.encode(listOf(ComboItem(7,"g",100.0),ComboItem(8,"份",1.0)))))
        repo.saveFood(rice.copy(nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.02))
        assertEquals(1,store.recordCombo(day,id,mapOf(7L to 150.0)))
        val saved=db.dao().allIntakes().single()
        assertEquals(348.0,saved.nutrition.kcal,1e-8);assertEquals(3.0,saved.costYuan!!,1e-8)
        repo.saveFood(bread.copy(unit="g",basisAmount=100.0,step=10.0,maximum=500.0))
        assertTrue(runCatching {store.recordCombo(day,id)}.isFailure)
        assertEquals(listOf(saved),db.dao().allIntakes())
        assertTrue(runCatching {store.recordCombo(day,id,mapOf(999L to 1.0))}.isFailure)
        assertTrue(runCatching {store.recordCombo(day,id,emptyMap())}.isFailure)
        assertEquals(beforeBody,db.dao().allBodies());assertEquals(beforeTarget,db.dao().allTargets())
    }
    @Test fun copyingSelectedIntakesUsesCurrentFoodValuesAndDirectMultiplierWithoutRoutineMarkers()=runBlocking {
        seed()
        val original=store.recordFood(day-1,7,100.0)
        val direct=store.addDirect(day-1,20.0,10.0,5.0)
        db.dao().updateIntake(db.dao().intake(original)!!.copy(source="ROUTINE"))
        db.dailyEatingDao().insertApplication(DailyFoodApplication(day-1,555,original))
        val old=db.dao().allIntakes();val targets=db.dao().allTargets();val bodies=db.dao().allBodies()
        repo.saveFood(rice.copy(nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.04))
        val result=store.copyIntakesToToday(day-1,mapOf(original to 200.0,direct to 1.5))
        assertEquals(2,result.count)
        val today=db.dao().allIntakes().filter {it.day==day}
        assertEquals("FOOD",today[0].source);assertEquals(464.0,today[0].nutrition.kcal,1e-8);assertEquals(8.0,today[0].costYuan!!,0.0)
        assertEquals(1.0,today[1].quantity,0.0);assertEquals(DirectNutrition.calculate(30.0,15.0,7.5),today[1].nutrition)
        assertEquals(old,db.dao().allIntakes().filter {it.day==day-1});assertEquals(1,db.dailyEatingDao().allApplications().size)
        assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
        assertTrue(store.copyIntakesToToday(day-1,mapOf(original to 50.0)).warnings.isNotEmpty())
    }
    @Test fun copyAndComboFailuresCannotPartiallyInsertAndMissingDaysAreNeverInvented()=runBlocking {
        seed();val first=store.recordFood(day-1,7,100.0);val second=store.recordFood(day-1,8,1.0)
        val before=repo.exportBackup(Preferences())
        repo.deleteFood(8)
        val deleted=repo.exportBackup(Preferences())
        assertTrue(runCatching {store.copyIntakesToToday(day-1,linkedMapOf(first to 50.0,second to 1.0))}.isFailure)
        assertEquals(deleted,repo.exportBackup(deleted.preferences).copy(exportTime=deleted.exportTime))
        for(date in listOf(day+1,day-100)) {
            assertTrue(runCatching {store.recordFood(date,7,50.0)}.isFailure)
            assertTrue(runCatching {store.addDirect(date,10.0,0.0,0.0)}.isFailure)
            assertTrue(runCatching {store.saveExercise(date,type=null,minutes=null,manualKcal=100.0)}.isFailure)
        }
        for(q in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertTrue(runCatching {store.editIntake(day-1,first,q)}.isFailure)
        assertEquals(before.bodies,db.dao().allBodies());assertEquals(before.targets,db.dao().allTargets())
    }
    @Test fun selectedExerciseCopyUsesTodaysWeightAndLeavesTargetsFrozen()=runBlocking {
        seed();val targets=db.dao().allTargets()
        val original=store.saveExercise(day-1,type=Cardio.HILL,minutes=30.0,manualKcal=null)
        val manual=store.saveExercise(day-1,type=null,minutes=null,manualKcal=111.0,included=false)
        store.copyToToday(day-1,emptySet(),setOf(original,manual))
        val today=db.dao().allExercises().filter {it.day==day}
        assertEquals(80.0,today.first().weight!!,0.0);assertEquals(240.0,today.first().kcal,0.0)
        assertEquals(111.0,today.last().kcal,0.0);assertFalse(today.last().included)
        assertEquals(targets,db.dao().allTargets())
    }
    @Test fun skipsSurviveTemplateDeletionAndBackupRestoreWithoutReusingTemplateOrDeletedIntakeIds()=runBlocking {
        seed();val daily=DailyEatingStore(db)
        val id=daily.dao.saveTemplate(DailyFoodTemplate(foodId=7,unit="g",mode="FIXED",quantity=100.0,startDay=day))
        daily.setSkipped(day,id,true);daily.apply(day);assertTrue(db.dao().allIntakes().isEmpty())
        daily.setSkipped(day,id,false);val intake=db.dao().allIntakes().single()
        store.removeIntake(day,intake.id);daily.setSkipped(day,id,true);daily.setSkipped(day,id,false)
        assertTrue(db.dao().allIntakes().isEmpty());assertEquals(1,daily.dao.allApplications().size)
        daily.dao.saveSkip(DailyFoodSkip(day,5000));daily.deleteTemplate(id)
        val combo=store.saveCombo(FoodCombo(name="备份组合",items=ComboCodec.encode(listOf(ComboItem(7,"g",100.0)))))
        val backup=repo.exportBackup(Preferences());repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(backup)))
        val next=daily.dao.saveTemplate(DailyFoodTemplate(foodId=8,unit="份",mode="FIXED",quantity=1.0,startDay=day))
        assertTrue(next>5000);assertEquals(backup.skips,daily.dao.allSkips());assertNotNull(db.featureDao().combo(combo))
        val nextIntake=store.recordFood(day,7,50.0);assertTrue(nextIntake>intake.id)
        daily.apply(day);assertEquals(1,db.dao().allIntakes().count {it.foodId==8L})
    }
    @Test fun restoredFoodsUseUnassignedWhenOriginalZoneIsNoLongerCompatibleAndKeepAllHistory()=runBlocking {
        seed();store.recordFood(day-1,bread.id,1.0)
        val history=db.dao().allIntakes();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        for((index,scenario) in listOf("wrong-unit","legacy-factor","invalid-target","missing-zone").withIndex()) {
            val zone=FoodZone(id=100L+index,name=scenario,limitUnit=if(scenario=="wrong-unit") "G" else "PORTION",sortOrder=20L+index)
            if(scenario!="missing-zone") db.dao().saveZone(zone)
            val deleted=bread.copy(zoneId=zone.id,isDeleted=true,zonePortionSize=if(scenario=="legacy-factor") 2.0 else 1.0)
            db.dao().saveFood(deleted)
            if(scenario=="invalid-target") db.dao().saveFood(rice.copy(id=900,zoneId=zone.id))
            val max=(db.dao().allZones().map {it.sortOrder}+db.dao().allFoods().filter {!it.isDeleted && it.zoneId==null}.map {it.sortOrder}).maxOrNull()?:-1L
            assertTrue(store.restoreFood(bread.id))
            assertEquals(deleted.copy(isDeleted=false,zoneId=null,sortOrder=max+1),db.dao().food(bread.id))
            assertEquals(history,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
        }
        val compatible=FoodZone(id=200,name="兼容份数区",sortOrder=50)
        db.dao().saveZone(compatible)
        val deleted=bread.copy(zoneId=compatible.id,isDeleted=true,zonePortionSize=1.0)
        db.dao().saveFood(deleted)
        assertFalse(store.restoreFood(bread.id))
        assertEquals(deleted.copy(isDeleted=false,sortOrder=0),db.dao().food(bread.id))
        assertEquals(history,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }
}
