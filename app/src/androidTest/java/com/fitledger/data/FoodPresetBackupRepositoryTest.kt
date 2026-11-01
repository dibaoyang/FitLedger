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
class FoodPresetBackupRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
    private val preferences=Preferences(profile=profile,energyUnit=EnergyUnit.KJ,reminder=true)
    private val food=Food(7,"预设米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.02,favorite=true,quickAmounts="100,200")
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db)
    }
    @After fun close() {db.close()}
    private suspend fun seed() {repo.weigh(day,Body(72.4,15.2,null),Plan(),profile,omitMuscle=true);repo.dao.saveFood(food)}

    @Test fun futurePresetBackupRestoresExactSnapshotsWithoutInventingFutureMeasurements()=runBlocking {
        seed()
        db.foodPresetDao().savePlan(FoodPresetPlan(day+1,day,2700.0,72.4))
        db.foodPresetDao().insertItem(FoodPresetItem(5,day+1,7,food.name,"g",200.0,food.nutrition*2.0,4.0))
        val original=repo.exportBackup(preferences)
        repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(original)))
        val restored=repo.exportBackup(preferences)
        assertEquals(original.copy(exportTime=restored.exportTime),restored)
        assertNull(repo.dao.body(day+1));assertNull(repo.dao.target(day+1));assertTrue(repo.dao.allIntakes().isEmpty())
        val added=db.foodPresetDao().insertItem(FoodPresetItem(day=day+1,foodId=7,name=food.name,unit="g",quantity=100.0,nutrition=food.nutrition,costYuan=2.0))
        assertTrue(added>5)
        assertEquals(preferences,PreferencesCodec.decode(repo.dao.pendingSettings()!!.payload))
    }

    @Test fun freshDatabaseRestoreProtectsDeletedAppliedIntakeIdentityAndPresetIdentity()=runBlocking {
        seed()
        val originalBody=repo.dao.body(day)!!;val originalTarget=repo.dao.target(day)!!
        repo.dao.insertBody(originalBody.copy(day=day-1,energy=null))
        repo.dao.insertTarget(originalTarget.copy(day=day-1))
        db.foodPresetDao().savePlan(FoodPresetPlan(day,day-1,null,72.4))
        db.foodPresetDao().insertItem(FoodPresetItem(50,day,7,food.name,"g",200.0,food.nutrition*2.0,4.0,7000))
        val original=repo.exportBackup(preferences)
        val fresh=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        try {
            val restored=Repository(fresh)
            restored.restoreBackup(BackupCodec.decode(BackupCodec.encode(original)))
            assertEquals(7000L,fresh.foodPresetDao().allItems().single().appliedIntakeId)
            restored.record(day,mapOf(7L to 100.0))
            assertTrue(restored.dao.allIntakes().single().id>7000)
            assertEquals(original.bodies,restored.dao.allBodies());assertEquals(original.targets,restored.dao.allTargets())
            fresh.foodPresetDao().savePlan(FoodPresetPlan(day+1,day,2700.0,72.4))
            val next=fresh.foodPresetDao().insertItem(FoodPresetItem(day=day+1,foodId=7,name=food.name,unit="g",quantity=100.0,nutrition=food.nutrition))
            assertTrue(next>50)
        } finally {fresh.close()}
    }

    @Test fun invalidPresetRestoreNeverClearsExistingRecordsOrSettings()=runBlocking {
        seed()
        db.foodPresetDao().savePlan(FoodPresetPlan(day+1,day,2700.0,72.4))
        db.foodPresetDao().insertItem(FoodPresetItem(5,day+1,7,food.name,"g",200.0,food.nutrition*2.0,4.0))
        val before=repo.exportBackup(preferences)
        assertTrue(runCatching {repo.restoreBackup(before.copy(backupVersion=12))}.isFailure)
        val bad=before.copy(presetItems=before.presetItems.map {it.copy(foodId=999)})
        assertTrue(runCatching {repo.restoreBackup(bad)}.isFailure)
        val after=repo.exportBackup(preferences)
        assertEquals(before.copy(exportTime=after.exportTime),after)
        assertNull(repo.dao.pendingSettings())
    }
}
