package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DirectNutritionRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day=LocalDate.now().toEpochDay()
    private val body=Body(72.4,15.2,null)
    private val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db)
    }
    @After fun close() {db.close()}

    @Test fun optionalMuscleCreatesCompleteDailySnapshotsButRemainsNull()=runBlocking {
        repo.weigh(day,body,Plan(),profile,omitMuscle=true)
        val saved=requireNotNull(repo.dao.body(day))
        assertNull(saved.muscle);assertEquals(61.3952,saved.lean,1e-8)
        assertEquals(EnergyExpenditureCalculator.snapshot(body.weight,profile,day),saved.energy)
        assertEquals(TargetCalculator.calculate(Plan(),body,day),repo.dao.target(day)?.nutrition)
        repo.applyTodayOnly(day,Plan(),profile)
        assertNull(repo.dao.body(day)?.muscle)
    }
    @Test fun dailyGateStillRequiresWeightAndExplicitMuscleOmission()=runBlocking {
        assertTrue(runCatching {repo.weigh(day,body,Plan(),profile)}.isFailure)
        assertTrue(runCatching {repo.weigh(day,body.copy(weight=Double.NaN),Plan(),profile,omitMuscle=true)}.isFailure)
        assertTrue(runCatching {repo.recordNutrition(day,DirectNutrition.calculate(1.0,0.0,0.0))}.isFailure)
        assertTrue(repo.dao.allBodies().isEmpty());assertTrue(repo.dao.allIntakes().isEmpty())
    }
    @Test fun directRecordingDeletionAndTargetChangesPreserveHistoryAndOtherIntakes()=runBlocking {
        val yesterday=BodyRecord(day-1,72.4,15.2,61.3952,58.1)
        repo.dao.insertBody(yesterday)
        val oldTarget=DailyTarget(day-1,TargetCalculator.calculate(Plan(),body,day-1),PlanCodec.encode(Plan()))
        repo.dao.insertTarget(oldTarget)
        repo.weigh(day,body,Plan(),profile,omitMuscle=true)
        val todayTarget=repo.dao.target(day)
        val first=DirectNutrition.calculate(100.0,25.0,10.0)
        val second=DirectNutrition.calculate(0.0,10.0,0.0)
        repo.recordNutrition(day,first);repo.recordNutrition(day,second)
        val entries=repo.dao.allIntakes()
        assertEquals(2,entries.size);assertTrue(entries.all {it.foodId==null && it.source=="DIRECT"})
        assertTrue(repo.dao.allFoods().isEmpty());assertEquals(todayTarget,repo.dao.target(day))
        assertEquals(630.0,entries.sumOf {it.nutrition.kcal},0.0)
        repo.applyTodayOnly(day,Plan(fat=Rule(Basis.FIXED,50.0)),profile)
        assertEquals(entries,repo.dao.allIntakes())
        repo.remove(day,entries.first().id)
        assertEquals(listOf(entries.last()),repo.dao.allIntakes())
        assertEquals(yesterday,repo.dao.body(day-1));assertEquals(oldTarget,repo.dao.target(day-1))
        assertTrue(runCatching {repo.recordNutrition(day-1,first)}.isFailure)
    }
    @Test fun backupRestoresDirectIntakesAndMissingMuscleWithoutInventingFoods()=runBlocking {
        repo.weigh(day,body,Plan(),profile,omitMuscle=true)
        repo.recordNutrition(day,DirectNutrition.calculate(100.0,25.0,10.0))
        val preferences=Preferences(profile=profile,hasReadIntro=true,skipMuscleInput=true)
        val original=repo.exportBackup(preferences)
        val restored=BackupCodec.decode(BackupCodec.encode(original))
        repo.restoreBackup(restored)
        assertEquals(original.bodies,repo.dao.allBodies());assertEquals(original.targets,repo.dao.allTargets())
        assertEquals(original.intakes,repo.dao.allIntakes());assertTrue(repo.dao.allFoods().isEmpty())
    }
}
