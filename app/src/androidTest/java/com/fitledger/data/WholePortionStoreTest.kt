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
class WholePortionStoreTest {
    private lateinit var db:LedgerDatabase
    private val today get()=LocalDate.now().toEpochDay()
    private val bread=Food(7,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true)
    @Before fun open() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()}
    @After fun close()=db.close()
    private suspend fun seed() {
        db.dao().saveFood(bread)
        for(day in listOf(today-1,today)) {
            db.dao().insertBody(BodyRecord(day,60.0,20.0,48.0,null))
            db.dao().insertTarget(DailyTarget(day,Nutrition(2000.0,250.0,150.0,60.0),PlanCodec.encode(Plan())))
        }
    }
    @Test fun manualComboCopyAndHistoricalEditsRejectNewFractionButPreserveLegacySnapshotAndUndo()=runBlocking {
        seed();val store=FeatureStore(db)
        val old=Intake(day=today-1,foodId=bread.id,name="旧半份面包",unit="份",quantity=0.5,nutrition=bread.nutrition*0.5)
        val id=db.dao().insertIntake(old);val saved=db.dao().intake(id)!!
        assertTrue(runCatching {store.recordFood(today,bread.id,0.5)}.isFailure)
        assertTrue(runCatching {store.editIntake(today-1,id,1.5)}.isFailure)
        assertTrue(runCatching {store.copyIntakesToToday(today-1,mapOf(id to 0.5))}.isFailure)
        assertTrue(runCatching {store.saveCombo(FoodCombo(name="新小数组合",items=ComboCodec.encode(listOf(ComboItem(bread.id,"份",0.5)))))}.isFailure)
        val comboId=db.featureDao().saveCombo(FoodCombo(name="旧半份模板",items=ComboCodec.encode(listOf(ComboItem(bread.id,"份",0.5)))))
        assertTrue(runCatching {store.recordCombo(today,comboId)}.isFailure)
        assertEquals(saved,db.dao().intake(id));assertEquals(1,db.dao().allIntakes().size)
        val removed=store.removeIntake(today-1,id);store.restoreIntake(removed)
        assertEquals(saved,db.dao().intake(id))
        store.copyIntakesToToday(today-1,mapOf(id to 1.0));assertEquals(1.0,db.dao().allIntakes().last().quantity,0.0)
    }
    @Test fun oldFractionRoutineIsSkippedWithWarningWithoutChangingTemplateOrAnyHistory()=runBlocking {
        seed();val routine=DailyEatingStore(db)
        val id=db.dailyEatingDao().saveTemplate(DailyFoodTemplate(foodId=bread.id,unit="份",mode="FIXED",quantity=0.5,startDay=today))
        val original=db.dailyEatingDao().template(id)!!
        val warnings=routine.apply(today)
        assertTrue(warnings.any {it.contains("整数")});assertTrue(db.dao().allIntakes().isEmpty())
        assertEquals(original,db.dailyEatingDao().template(id));assertNull(db.dailyEatingDao().application(today,id))
        routine.saveTemplate(original.copy(quantity=1.0),today)
        assertEquals(1.0,db.dao().allIntakes().single().quantity,0.0)
    }
    @Test fun oldFractionPresetIsRetainedUntilExplicitIntegerRepairAndCannotBecomeNewIntake()=runBlocking {
        seed();val store=FoodPresetStore(db)
        db.foodPresetDao().savePlan(FoodPresetPlan(today,today-1,null,60.0))
        val id=db.foodPresetDao().insertItem(FoodPresetItem(day=today,foodId=bread.id,name=bread.name,unit="份",quantity=0.5,nutrition=bread.nutrition*0.5))
        val original=db.foodPresetDao().item(id)!!
        assertTrue(runCatching {store.apply(today,listOf(id))}.isFailure)
        assertEquals(original,db.foodPresetDao().item(id));assertTrue(db.dao().allIntakes().isEmpty())
        store.saveItem(today,bread.id,1.0,id,expectedUnit="份")
        store.apply(today,listOf(id));assertEquals(1.0,db.dao().allIntakes().single().quantity,0.0)
    }
}
