package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AdvancedRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
    private val base=AdvancedPlan(templates=listOf(PlanTemplate(budget=EnergyBudget(BudgetMode.FIXED,2000.0))))
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db)
    }
    @After fun close() {db.close()}
    private suspend fun seed(plan:Plan) {
        val b=BodyRecord(day-1,70.0,20.0,56.0,45.0)
        db.dao().insertBody(b)
        db.dao().insertTarget(DailyTarget(day-1,Nutrition(2000.0,250.0,150.0,400.0/9),PlanCodec.encode(Plan())))
        repo.weigh(day,Body(70.0,20.0,45.0),plan,profile)
        repo.saveFood(Food(1,"土豆泥","g",100.0,Nutrition(100.0,20.0,2.0,1.3),10.0,500.0,true))
        repo.record(day,mapOf(1L to 200.0))
    }
    @Test fun todayOnlyOverrideNeverStagesPreferencesOrChangesFuturePlanAndHistory()=runBlocking {
        seed(Plan())
        val prefs=Preferences(plan=Plan(),nextPlan=Plan(goal="增肌"),effectiveFrom=day+1,profile=profile)
        val before=repo.exportBackup(prefs)
        repo.applyTodayOnly(day,Plan(advanced=base),profile)
        val after=repo.exportBackup(prefs)
        assertEquals(before.preferences,after.preferences)
        assertEquals(before.intakes,after.intakes);assertEquals(before.bodies,after.bodies)
        assertEquals(before.targets.first(),after.targets.first());assertNull(db.dao().pendingSettings())
        assertEquals(2000.0,db.dao().target(day)!!.nutrition.kcal,1e-6)
        assertNotNull(TargetPlanning.readContext(db.dao().target(day)!!))
        assertTrue(runCatching {repo.applyTodayOnly(day-1,Plan(advanced=base),profile)}.isFailure)
    }
    @Test fun confirmedExerciseUpdatesOnlyTodayAndCanBeReversedWithoutChangingFood()=runBlocking {
        val p=Plan(advanced=base.copy(exercise=ExerciseAllocation(.5,300.0,Macro.CARBS)))
        seed(p)
        val before=repo.exportBackup(Preferences(plan=p,profile=profile))
        repo.exercise(day,null,null,400.0,true,true)
        val after=repo.exportBackup(before.preferences)
        assertEquals(2200.0,db.dao().target(day)!!.nutrition.kcal,1e-6)
        assertEquals(300.0,db.dao().target(day)!!.nutrition.carbs,1e-6)
        assertEquals(before.bodies,after.bodies);assertEquals(before.intakes,after.intakes)
        assertEquals(before.targets.first(),after.targets.first());assertNull(db.dao().pendingSettings())
        repo.removeExercise(day,after.exercises.single().id,true)
        assertEquals(2000.0,db.dao().target(day)!!.nutrition.kcal,1e-6)
        assertEquals(before.intakes,db.dao().allIntakes())
    }
    @Test fun invalidExerciseReturnRollsBackBothExerciseAndTarget()=runBlocking {
        val t=base.templates.single()
        val limited=t.copy(macros=t.macros.copy(carbs=t.macros.carbs.copy(maximum=250.0)))
        val p=Plan(advanced=base.copy(templates=listOf(limited),exercise=ExerciseAllocation(1.0,destination=Macro.CARBS)))
        seed(p);val target=db.dao().target(day);val entries=db.dao().allIntakes()
        assertTrue(runCatching {repo.exercise(day,null,null,100.0,true,true)}.isFailure)
        assertTrue(db.dao().allExercises().isEmpty());assertEquals(target,db.dao().target(day));assertEquals(entries,db.dao().allIntakes())
    }
    @Test fun advancedBackupRestorePreservesSnapshotsAndCanRecalculateAfterRestore()=runBlocking {
        val p=Plan(advanced=base.copy(reference=BodyReference(ReferenceMode.AVERAGE_7_DAYS),exercise=ExerciseAllocation(1.0)))
        seed(p);repo.exercise(day,null,null,200.0,true,true)
        val backup=BackupCodec.decode(BackupCodec.encode(repo.exportBackup(Preferences(plan=p,profile=profile))))
        repo.restoreBackup(backup)
        assertEquals(backup.targets,db.dao().allTargets());assertEquals(backup.intakes,db.dao().allIntakes())
        repo.recalculateExerciseTarget(day)
        assertEquals(backup.targets,db.dao().allTargets())
    }
}
