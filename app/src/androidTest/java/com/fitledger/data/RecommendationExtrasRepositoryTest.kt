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
class RecommendationExtrasRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val a=Food(1,"A","g",100.0,Nutrition(100.0,20.0,5.0,0.0),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val b=Food(2,"B","g",100.0,Nutrition(200.0,30.0,20.0,0.0),10.0,500.0,true,pricePerUnitYuan=0.02)
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db)
    }
    @After fun close()=db.close()
    private suspend fun seed() {
        db.dao().saveFood(a);db.dao().saveFood(b)
        db.dao().insertBody(BodyRecord(day,60.0,20.0,48.0,null))
        db.dao().insertTarget(DailyTarget(day,Nutrition(2000.0,250.0,150.0,60.0),PlanCodec.encode(Plan())))
    }
    private fun ratios()=RecommendationExtras(ratios=listOf(WeightRatio(a.id,b.id,0.5)))

    @Test fun recommendationAdoptionPreservesHistoryAndRevalidatesRoundedWeightRatio()=runBlocking {
        seed();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        repo.record(day,mapOf(1L to 50.0,2L to 100.0),true,extras=ratios())
        val before=db.dao().allIntakes()
        assertEquals(2,before.size)
        assertTrue(runCatching {repo.record(day,mapOf(1L to 60.0,2L to 100.0),true,extras=ratios())}.isFailure)
        assertEquals(before,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }

    @Test fun manualIntakeIgnoresRecommendationRatiosAndBudgets()=runBlocking {
        seed()
        repo.record(day,mapOf(1L to 600.0),false,extras=ratios().copy(weeklyBudgetYuan=0.0))
        assertEquals(600.0,db.dao().allIntakes().single().quantity,0.0)
    }

    @Test fun changedCurrentPriceCannotBypassWeeklyBudgetDuringAdoption()=runBlocking {
        seed();val extra=RecommendationExtras(weeklyBudgetYuan=2.0)
        repo.record(day,mapOf(1L to 100.0),true,extras=extra)
        val before=db.dao().allIntakes()
        db.dao().saveFood(b.copy(pricePerUnitYuan=0.5))
        assertTrue(runCatching {repo.record(day,mapOf(2L to 10.0),true,extras=extra)}.isFailure)
        assertEquals(before,db.dao().allIntakes());assertEquals(1.0,before.single().costYuan!!,0.0)
    }

    @Test fun missingRecordedPriceBlocksWeeklyCapAndNeverTreatsItAsZero()=runBlocking {
        seed();db.dao().saveFood(a.copy(pricePerUnitYuan=null))
        repo.record(day,mapOf(a.id to 100.0))
        db.dao().saveFood(a)
        val before=db.dao().allIntakes()
        assertTrue(before.single().macrosKnown);assertNull(before.single().costYuan)
        assertTrue(runCatching {repo.record(day,mapOf(1L to 10.0),true,extras=RecommendationExtras(weeklyBudgetYuan=30.0))}.isFailure)
        assertEquals(before,db.dao().allIntakes())
    }

    @Test fun atomicComboCannotBeSplitAtAdoptionButManualRecordingRemainsFlexible()=runBlocking {
        seed()
        db.featureDao().saveCombo(FoodCombo(name="整套",items=ComboCodec.encode(listOf(ComboItem(1,"g",100.0),ComboItem(2,"g",200.0)))))
        val combo=db.featureDao().allCombos().single()
        val extras=RecommendationExtras(comboIds=setOf(combo.id))
        assertTrue(runCatching {repo.record(day,mapOf(1L to 50.0),true,extras=extras)}.isFailure)
        assertTrue(db.dao().allIntakes().isEmpty())
        repo.record(day,mapOf(1L to 50.0,2L to 100.0),true,extras=extras)
        assertEquals(2,db.dao().allIntakes().size)
        repo.record(day,mapOf(1L to 10.0),false,extras=extras)
        assertEquals(3,db.dao().allIntakes().size)
    }

    @Test fun newExtrasStillEnforceDailyBodyGateAndExistingNativeZoneQuota()=runBlocking {
        db.dao().saveFood(a);db.dao().saveFood(b)
        assertTrue(runCatching {repo.record(day,mapOf(1L to 50.0,2L to 100.0),true,extras=ratios())}.isFailure)
        seed()
        db.dao().saveZone(FoodZone(7,"克数区",dailyMaximum=120.0,limitUnit="G"))
        db.dao().saveFood(a.copy(zoneId=7));db.dao().saveFood(b.copy(zoneId=7))
        assertTrue(runCatching {repo.record(day,mapOf(1L to 50.0,2L to 100.0),true,extras=ratios())}.isFailure)
        assertTrue(db.dao().allIntakes().isEmpty())
    }
}
