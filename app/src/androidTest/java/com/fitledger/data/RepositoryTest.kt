package com.fitledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.fitledger.domain.*
import com.fitledger.domain.Rule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2005,5,20).toEpochDay(),1.55)
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    @Test fun exerciseIsGatedAndNeverRewritesTargetOrIntake()=runBlocking {
        assertTrue(runCatching {repo.exercise(day,Cardio.HILL,30.0,null,true)}.isFailure)
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        val before=repo.exportBackup(Preferences(profile=profile))
        repo.exercise(day,Cardio.HILL,30.0,null,true);repo.exercise(day,null,null,300.0,false)
        val after=repo.exportBackup(before.preferences)
        assertEquals(before.bodies,after.bodies);assertEquals(before.targets,after.targets);assertEquals(before.intakes,after.intakes)
        assertEquals(210.0,after.exercises.filter {it.included}.sumOf {it.kcal},1e-8)
        repo.applyToday(day,Preferences(profile=profile));assertEquals(after.exercises,repo.dao.allExercises())
        repo.restoreBackup(after);assertEquals(after.exercises,repo.dao.allExercises())
        repo.removeExercise(day,after.exercises.first().id);assertEquals(1,repo.dao.allExercises().size)
    }
    @Test fun deficitApplyOnlyChangesTodayTargetAndKeepsExercisesIntakesAndHistory()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        db.dao().insertBody(BodyRecord(day-1,70.0,20.0,56.0,45.0))
        db.dao().insertTarget(DailyTarget(day-1,Nutrition(2000.0,250.0,150.0,400.0/9),PlanCodec.encode(Plan())))
        db.dao().saveFood(Food(1,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true))
        repo.record(day,mapOf(1L to 200.0));repo.exercise(day,null,null,300.0,true)
        val before=repo.exportBackup(Preferences(profile=profile))
        val p=Plan(useExpenditureBudget=true,deficitKcal=500.0,carbs=com.fitledger.domain.Rule(Basis.FIXED,200.0),protein=com.fitledger.domain.Rule(Basis.FIXED,120.0),fat=com.fitledger.domain.Rule(Basis.REMAINDER))
        repo.applyToday(day,Preferences(profile=profile,plan=p))
        val after=repo.exportBackup(Preferences(profile=profile,plan=p))
        assertEquals(before.bodies,after.bodies);assertEquals(before.intakes,after.intakes);assertEquals(before.exercises,after.exercises)
        assertEquals(before.targets.first {it.day==day-1},after.targets.first {it.day==day-1})
        val energy=db.dao().body(day)!!.energy!!.expenditureKcal
        assertEquals(energy-500,db.dao().target(day)!!.nutrition.kcal,1e-8)
        assertEquals((energy-500-800-480)/9,db.dao().target(day)!!.nutrition.fat,1e-8)
    }
    @Test fun initialPlanRepairRequiresNoTodaySnapshotAndLeavesHistoryAlone()=runBlocking {
        val historicalBody=BodyRecord(day-1,70.0,20.0,56.0,45.0)
        val historicalTarget=DailyTarget(day-1,Nutrition(2000.0,250.0,150.0,400.0/9),PlanCodec.encode(Plan()))
        db.dao().insertBody(historicalBody);db.dao().insertTarget(historicalTarget)
        val p=Preferences(profile=profile,plan=Plan(useExpenditureBudget=true,fat=com.fitledger.domain.Rule(Basis.REMAINDER)))
        repo.setInitialPlan(day,p)
        assertNull(db.dao().body(day));assertNull(db.dao().target(day))
        assertEquals(p,PreferencesCodec.decode(db.dao().pendingSettings()!!.payload))
        assertEquals(historicalBody,db.dao().body(day-1));assertEquals(historicalTarget,db.dao().target(day-1))
        repo.weigh(day,Body(70.0,20.0,45.0),p.plan,profile)
        assertTrue(runCatching {repo.setInitialPlan(day,p)}.isFailure)
        assertTrue(runCatching {repo.setInitialPlan(day-1,p)}.isFailure)
    }
    @Test fun completeChainKeepsSnapshots()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        val original=db.dao().target(day)
        db.dao().saveFood(Food(1,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true))
        repo.record(day,mapOf(1L to 200.0))
        db.dao().saveFood(Food(1,"修改后的米饭","g",100.0,Nutrition(200.0,40.0,2.0,1.0),10.0,500.0,true))
        val intake=db.dao().intakes().first().single()
        assertEquals("米饭",intake.name);assertEquals(232.0,intake.nutrition.kcal,0.001)
        assertEquals(original,db.dao().target(day));assertEquals(56.0,db.dao().body(day)!!.lean,0.001)
    }
    @Test fun cannotRecordBeforeWeighing()=runBlocking {
        assertTrue(runCatching {repo.record(day,mapOf(1L to 100.0))}.isFailure)
        assertTrue(db.dao().intakes().first().isEmpty())
    }
    @Test fun invalidTargetRollsBackBody()=runBlocking {
        assertTrue(runCatching {repo.weigh(day,Body(70.0,20.0,45.0),Plan(carbs=Rule(Basis.REMAINDER),fat=Rule(Basis.REMAINDER)),profile)}.isFailure)
        assertNull(db.dao().body(day));assertNull(db.dao().target(day))
    }
    @Test fun repeatedWeighCannotOverwriteSnapshot()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        assertTrue(runCatching {repo.weigh(day,Body(80.0,20.0,45.0),Plan(),profile)}.isFailure)
        assertEquals(70.0,db.dao().body(day)!!.weight,0.001)
    }
    @Test fun batchRecommendationRollsBackOnInvalidFood()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        db.dao().saveFood(Food(1,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true))
        assertTrue(runCatching {repo.record(day,linkedMapOf(1L to 100.0,999L to 1.0),true)}.isFailure)
        assertTrue(db.dao().intakes().first().isEmpty())
    }
    @Test fun yesterdayCannotAcceptNewIntake()=runBlocking {
        assertTrue(runCatching {repo.record(day-1,mapOf(1L to 100.0))}.isFailure)
    }
    @Test fun immediatePlanChangesOnlyTodayAndKeepsRecordedLean()=runBlocking {
        val oldBody=BodyRecord(day-1,70.0,20.0,56.0,45.0)
        val yesterday=DailyTarget(day-1,Nutrition(2200.0,250.0,160.0,62.22),PlanCodec.encode(Plan()))
        db.dao().insertBody(oldBody);db.dao().insertTarget(yesterday)
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        db.dao().saveFood(Food(1,"测试食品","份",1.0,Nutrition(1200.0,200.0,50.0,22.2),1.0,2.0,true))
        repo.record(day,mapOf(1L to 1.0))
        val entries=db.dao().allIntakes();val bodies=db.dao().allBodies()
        val p=Preferences(profile=profile,plan=Plan(energy=Rule(Basis.FIXED,2000.0),carbs=Rule(Basis.REMAINDER),protein=Rule(Basis.LEAN,2.0)))
        repo.applyToday(day,p)
        assertEquals(2000.0,db.dao().target(day)!!.nutrition.kcal,0.00001)
        assertEquals(800.0,db.dao().target(day)!!.nutrition.kcal-entries.sumOf {it.nutrition.kcal},0.00001)
        assertEquals(122.7904,db.dao().target(day)!!.nutrition.protein,0.00001)
        assertEquals(yesterday,db.dao().target(day-1));assertEquals(entries,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies())
        assertNotNull(db.dao().pendingSettings())
    }
    @Test fun invalidImmediatePlanDoesNotChangeTargetsOrStageSettings()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        val original=db.dao().allTargets()
        assertTrue(runCatching {repo.applyToday(day,Preferences(profile=profile,plan=Plan(energy=Rule(Basis.FIXED,1.0),carbs=Rule(Basis.REMAINDER))))}.isFailure)
        assertEquals(original,db.dao().allTargets());assertNull(db.dao().pendingSettings())
    }
    @Test fun backupRestoreAndSettingsCompletionAreIdempotent()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        val settings=Settings(InstrumentationRegistry.getInstrumentation().context)
        val original=repo.exportBackup(Preferences(energyUnit=EnergyUnit.KJ))
        db.dao().saveFood(Food(1,"之后添加","份",1.0,Nutrition(100.0),1.0,2.0,true))
        repo.restoreBackup(original)
        assertEquals(original.bodies,db.dao().allBodies());assertEquals(original.targets,db.dao().allTargets());assertTrue(db.dao().allFoods().isEmpty())
        assertNotNull(db.dao().pendingSettings())
        repo.finishPendingSettings(settings) {}
        repo.finishPendingSettings(settings) {}
        assertNull(db.dao().pendingSettings());assertEquals(EnergyUnit.KJ,settings.flow.first().energyUnit)
    }
    @Test fun invalidBackupNeverClearsExistingData()=runBlocking {
        repo.weigh(day,Body(70.0,20.0,45.0),Plan(),profile)
        val b=repo.exportBackup(Preferences())
        assertTrue(runCatching {repo.restoreBackup(b.copy(bodies=emptyList()))}.isFailure)
        assertEquals(b.bodies,db.dao().allBodies());assertEquals(b.targets,db.dao().allTargets())
    }
    @Test fun interruptedSettingsCompletionRetainsJournalForRetry()=runBlocking {
        val settings=Settings(InstrumentationRegistry.getInstrumentation().context)
        val desired=Preferences(energyUnit=EnergyUnit.KJ,reminder=true,hour=9)
        db.dao().stageSettings(PendingSettings(payload=PreferencesCodec.encode(desired)))
        assertTrue(runCatching {repo.finishPendingSettings(settings) {error("simulated process interruption")}}.isFailure)
        assertNotNull(db.dao().pendingSettings())
        repo.finishPendingSettings(settings) {}
        assertNull(db.dao().pendingSettings());assertEquals(desired,settings.flow.first())
    }
    @Test fun datastoreNormalSavePreservesTodayAndOldUnitAndReminder()=runBlocking {
        val settings=Settings(InstrumentationRegistry.getInstrumentation().context)
        val original=Preferences(reminder=true,hour=6,energyUnit=EnergyUnit.KJ)
        settings.restore(original)
        val replacement=Plan(goal="增肌")
        settings.savePlan(replacement,day)
        val p=settings.flow.first()
        assertEquals(original.plan,p.planFor(day));assertEquals(replacement,p.planFor(day+1))
        assertEquals(EnergyUnit.KJ,p.energyUnit);assertTrue(p.reminder);assertEquals(6,p.hour)
    }
    @Test fun changedActivityAndRatioUpdateOnlyTodayEnergyAndTarget()=runBlocking {
        val oldBody=BodyRecord(day-1,70.0,20.0,56.0,45.0)
        val oldTarget=DailyTarget(day-1,Nutrition(2000.0),PlanCodec.encode(Plan()))
        db.dao().insertBody(oldBody);db.dao().insertTarget(oldTarget)
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        db.dao().saveFood(Food(1,"食物","份",1.0,Nutrition(1200.0,100.0,100.0,44.4),1.0,2.0,true))
        repo.record(day,mapOf(1L to 1.0))
        val original=db.dao().body(day)!!;val entries=db.dao().allIntakes()
        val next=profile.copy(activityFactor=1.9)
        val plan=Plan(mode=PlanMode.RATIO,intakeMode=IntakeMode.EXPENDITURE_ADJUSTMENT,energyAdjustment=-500.0)
        repo.applyToday(day,Preferences(plan=plan,profile=next))
        val updated=db.dao().body(day)!!
        assertEquals(original.copy(energy=updated.energy),updated)
        assertEquals(original.energy!!.bmrKcal*1.9,updated.energy!!.expenditureKcal,1e-8)
        assertEquals(updated.energy!!.expenditureKcal-500,db.dao().target(day)!!.nutrition.kcal,1e-8)
        assertEquals(entries,db.dao().allIntakes());assertEquals(oldBody,db.dao().body(day-1));assertEquals(oldTarget,db.dao().target(day-1))
    }
    @Test fun profileSaveAndPrivacyNeverRewriteSnapshots()=runBlocking {
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        val body=db.dao().body(day);val targets=db.dao().allTargets()
        val settings=Settings(InstrumentationRegistry.getInstrumentation().context)
        settings.saveProfile(profile.copy(heightCm=180.0,activityFactor=1.9));settings.setPrivacy(true)
        assertTrue(BodyField.entries.all {!settings.flow.first().visibility.shows(it)})
        assertEquals(body,db.dao().body(day));assertEquals(targets,db.dao().allTargets())
        settings.setPrivacy(false)
    }
}





