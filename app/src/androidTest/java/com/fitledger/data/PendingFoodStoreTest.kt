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
class PendingFoodStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var store:PendingFoodStore
    private val ledger get()=db.dao()
    private val day get()=LocalDate.now().toEpochDay()
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        store=PendingFoodStore(db)
    }
    @After fun close()=db.close()
    private suspend fun seed() {
        ledger.saveFood(rice)
        for(date in listOf(day-1,day)) {
            ledger.insertBody(BodyRecord(date,60.0,20.0,48.0,null))
            ledger.insertTarget(DailyTarget(date,Nutrition(2000.0,250.0,150.0,60.0),PlanCodec.encode(Plan())))
        }
        FeatureStore(db).recordFood(day-1,rice.id,100.0)
    }
    private suspend fun queue():PendingFood {
        store.saveRecommendation(day,mapOf(rice.id to 200.0))
        return store.dao.allPendingFoods().last()
    }

    @Test fun recommendationWaitingWritesNoActualConsumptionBodyTargetOrCostHistory()=runBlocking {
        seed();val bodies=ledger.allBodies();val targets=ledger.allTargets();val actual=ledger.allIntakes()
        val item=queue()
        assertEquals(200.0,item.quantity,0.0);assertEquals(2.0,item.costYuan!!,0.0);assertNull(item.appliedIntakeId)
        assertEquals(actual,ledger.allIntakes());assertEquals(bodies,ledger.allBodies());assertEquals(targets,ledger.allTargets())
        assertEquals(item,PendingFoodStore(db).dao.pendingFood(item.id))
        assertNull(ledger.body(day+1));assertNull(ledger.target(day+1))
    }

    @Test fun clickingEatenTwiceCreatesOneActualAndDeletingActualNeverPermitsAnotherApplication()=runBlocking {
        seed();val item=queue();val old=ledger.allIntakes()
        val first=store.markEaten(item);assertEquals(first,store.markEaten(item))
        assertEquals(old.size+1,ledger.allIntakes().size)
        val saved=store.dao.pendingFood(item.id)!!
        assertEquals(first,saved.appliedIntakeId!!)
        val actual=ledger.intake(first)!!
        assertEquals(item.nutrition,actual.nutrition);assertEquals(item.costYuan,actual.costYuan)
        assertEquals(item.quantity,actual.quantity,0.0)
        ledger.deleteIntake(first,day)
        assertEquals(first,store.markEaten(item));assertEquals(old,ledger.allIntakes())
        assertTrue(runCatching {store.remove(saved)}.isFailure)
        assertEquals(saved,store.dao.pendingFood(item.id))
    }

    @Test fun changedFoodNeedsReviewedRefreshAndItsOldPreviewCannotOverwriteNewMetadata()=runBlocking {
        seed();val item=queue();val originalActual=ledger.allIntakes()
        val current=rice.copy(name="米饭新资料",nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.02)
        ledger.saveFood(current)
        assertTrue(runCatching {store.markEaten(item)}.isFailure)
        val preview=PendingFoodUpdates.preview(item,current,125.0)
        ledger.saveFood(current.copy(favorite=true))
        assertTrue(runCatching {store.update(preview)}.isFailure)
        assertEquals(item,store.dao.pendingFood(item.id));assertEquals(originalActual,ledger.allIntakes())
        val fresh=PendingFoodUpdates.preview(item,ledger.food(rice.id)!!,125.0)
        store.update(fresh)
        assertTrue(runCatching {store.markEaten(item)}.isFailure)
        val id=store.markEaten(fresh.after)
        assertEquals(fresh.after.nutrition,ledger.intake(id)!!.nutrition)
        assertEquals(2.5,ledger.intake(id)!!.costYuan!!,0.0)
    }

    @Test fun manualEatenConfirmationCanExceedCurrentRecommendationLimitsWithoutChangingSnapshots()=runBlocking {
        seed();val item=queue();val targets=ledger.allTargets();val bodies=ledger.allBodies()
        ledger.saveFood(rice.copy(allowed=false,maximum=50.0))
        val id=store.markEaten(item)
        assertEquals(200.0,ledger.intake(id)!!.quantity,0.0)
        assertEquals(targets,ledger.allTargets());assertEquals(bodies,ledger.allBodies())
    }

    @Test fun unavailableOrChangedUnitsNeverBecomeConsumptionAndAnExpiredPendingCanOnlyBeDiscarded()=runBlocking {
        seed();val item=queue();val before=ledger.allIntakes()
        ledger.markFoodDeleted(rice.id)
        assertTrue(runCatching {store.markEaten(item)}.isFailure)
        assertEquals(before,ledger.allIntakes())
        ledger.saveFood(rice.copy(unit="份",basisAmount=1.0,step=1.0,maximum=5.0))
        assertTrue(runCatching {store.markEaten(item)}.isFailure)
        assertTrue(runCatching {store.update(PendingFoodUpdates.preview(item,ledger.food(rice.id)!!,1.0))}.isFailure)
        val expired=item.copy(id=0,day=day-1,groupId="old")
        val oldId=store.dao.insertPendingFood(expired)
        val old=store.dao.pendingFood(oldId)!!
        assertTrue(runCatching {store.markEaten(old)}.isFailure)
        store.remove(old);assertNull(store.dao.pendingFood(oldId))
        assertEquals(before,ledger.allIntakes())
    }

    @Test fun savingWaitingStillRequiresTodayBodyTargetAndFreshRecommendationBudgetValidation()=runBlocking {
        ledger.saveFood(rice)
        assertTrue(runCatching {store.saveRecommendation(day,mapOf(rice.id to 100.0))}.isFailure)
        seed()
        assertTrue(runCatching {store.saveRecommendation(day+1,mapOf(rice.id to 100.0))}.isFailure)
        assertTrue(runCatching {store.saveRecommendation(day,mapOf(rice.id to 100.0),RecommendationPolicy(dailyBudgetYuan=0.5))}.isFailure)
        assertTrue(store.dao.allPendingFoods().isEmpty())
        assertEquals(1,ledger.allIntakes().size)
    }

    @Test fun oldCalorieOnlyRowsAndNewFractionalPortionsCannotCreateAFalseKnownRecommendation()=runBlocking {
        seed();Repository(db).recordEnergy(day,100.0,costYuan=0.0)
        assertTrue(runCatching {store.saveRecommendation(day,mapOf(rice.id to 100.0))}.isFailure)
        assertTrue(store.dao.allPendingFoods().isEmpty())
        val energy=ledger.allIntakes().single {it.day==day}
        ledger.deleteIntake(energy.id,day)
        val bread=rice.copy(id=8,unit="份",basisAmount=1.0,step=0.5,maximum=10.0)
        ledger.saveFood(bread)
        assertTrue(runCatching {store.saveRecommendation(day,mapOf(bread.id to 1.5))}.isFailure)
        assertTrue(store.dao.allPendingFoods().isEmpty())
        store.saveRecommendation(day,mapOf(bread.id to 2.0))
        assertEquals(2.0,store.dao.allPendingFoods().single().quantity,0.0)
    }

    @Test fun removingPendingNeverChangesFoodOrActualAndAnyStaleMutationIsRejected()=runBlocking {
        seed();val item=queue();val before=ledger.allIntakes();val food=ledger.food(rice.id)
        val updated=PendingFoodUpdates.preview(item,rice,100.0)
        store.update(updated)
        assertTrue(runCatching {store.remove(item)}.isFailure)
        assertEquals(updated.after,store.dao.pendingFood(item.id))
        store.remove(updated.after)
        assertNull(store.dao.pendingFood(item.id));assertEquals(before,ledger.allIntakes());assertEquals(food,ledger.food(rice.id))
    }
}
