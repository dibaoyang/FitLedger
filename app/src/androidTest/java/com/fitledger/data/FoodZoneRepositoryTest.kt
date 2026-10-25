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
class FoodZoneRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
    private val bread=Food(7,"面包一","份",1.0,Nutrition(200.0,35.0,9.0,3.0),1.0,10.0,true,zoneId=1)
    private val second=bread.copy(id=8,name="面包二")
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    private suspend fun seed() {
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        repo.dao.saveZone(FoodZone(1,"面包",3.0))
        repo.dao.saveFood(bread);repo.dao.saveFood(second)
    }
    @Test fun dailyIntakeAcrossTwoFoodsReducesRecommendationButManualRecordCanExceed()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val result=repo.recommend(day,emptyMap())
        assertTrue(result.quantities.values.sum()<=2.0)
        repo.record(day,mapOf(8L to 5.0))
        assertEquals(6.0,repo.dao.allIntakes().sumOf {it.quantity},0.0)
        assertTrue(repo.recommend(day,emptyMap()).quantities.isEmpty())
        assertTrue(runCatching {repo.recommend(day,mapOf(7L to 1.0))}.isFailure)
    }
    @Test fun staleRecommendationAdoptionUsesFreshDailyRemainingAndRollsBackWholeBatch()=runBlocking {
        seed()
        val suggestion=repo.recommend(day,mapOf(7L to 2.0,8L to 1.0))
        repo.record(day,mapOf(7L to 1.0))
        val before=repo.dao.allIntakes()
        assertTrue(runCatching {repo.record(day,suggestion.quantities,true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(7L to 1.0,8L to 1.0),true)
        assertEquals(3.0,repo.dao.allIntakes().sumOf {it.quantity},0.0)
    }
    @Test fun staleRecommendationAdoptionUsesFreshZoneSettingsAndConversion()=runBlocking {
        seed()
        val before=repo.dao.allIntakes()
        repo.saveZone(FoodZone(1,"面包",3.0,1.0))
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1.0,8L to 1.0),true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.saveFood(bread.copy(zonePortionSize=0.5))
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1.0),true)}.isFailure)
        repo.record(day,mapOf(7L to 1.0))
        assertEquals(1,repo.dao.allIntakes().size)
    }
    @Test fun deletingZoneUnassignsActiveAndDeletedFoodsAndKeepsAllHistoryAndSettings()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0));repo.deleteFood(7)
        val before=repo.exportBackup(Preferences(profile=profile,energyUnit=EnergyUnit.KJ))
        repo.dao.stageSettings(PendingSettings(payload="existing journal"))
        repo.deleteZone(1)
        val after=repo.exportBackup(before.preferences)
        assertTrue(after.zones.isEmpty())
        assertEquals(before.foods.map {it.copy(zoneId=null)},after.foods)
        assertEquals(before.intakes,after.intakes);assertEquals(before.bodies,after.bodies);assertEquals(before.targets,after.targets)
        assertEquals("existing journal",repo.dao.pendingSettings()!!.payload)
        assertTrue(runCatching {repo.saveZone(FoodZone(1,"stale"))}.isFailure)
        assertTrue(runCatching {repo.saveFood(second)}.isFailure)
        assertTrue(runCatching {repo.deleteZone(1)}.isFailure)
    }
    @Test fun backupRestoreKeepsZonesAndInvalidBackupCannotClearExistingData()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val before=repo.exportBackup(Preferences(profile=profile))
        assertTrue(runCatching {repo.restoreBackup(before.copy(zones=emptyList()))}.isFailure)
        assertEquals(before.foods,repo.dao.allFoods());assertEquals(before.zones,repo.dao.allZones())
        repo.deleteZone(1)
        repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(before)))
        assertEquals(before.zones,repo.dao.allZones());assertEquals(before.foods,repo.dao.allFoods());assertEquals(before.intakes,repo.dao.allIntakes())
        assertEquals(before.targets,repo.dao.allTargets());assertEquals(before.bodies,repo.dao.allBodies())
        assertEquals(before.preferences,PreferencesCodec.decode(repo.dao.pendingSettings()!!.payload))
    }
    @Test fun changedIntakeUnitBlocksOnlyLimitedGroupAndManualEntryIsStillPossible()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0));repo.saveFood(bread.copy(unit="g",basisAmount=50.0,step=50.0,maximum=500.0,zonePortionSize=50.0))
        assertTrue(repo.recommend(day,emptyMap()).quantities.isEmpty())
        val before=repo.dao.allIntakes()
        assertTrue(runCatching {repo.record(day,mapOf(8L to 1.0),true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(8L to 1.0));assertEquals(2,repo.dao.allIntakes().size)
    }
    @Test fun importedFoodIsUnassignedAndExistingZoneDoesNotTransferThroughCode()=runBlocking {
        seed();repo.importFoodCode(FoodShareCodec.encode(bread),true)
        val imported=repo.dao.allFoods().single {it.id!=7L && it.id!=8L}
        assertNull(imported.zoneId);assertEquals(1.0,imported.zonePortionSize,0.0)
        assertEquals(1,repo.dao.allZones().size)
    }
    @Test fun creatingZonesNeedsNoWeighingButRecommendationStillRequiresTodayBody()=runBlocking {
        repo.saveZone(FoodZone(name="面包",dailyMaximum=3.0))
        val saved=repo.dao.allZones().single();assertTrue(saved.id>0)
        assertTrue(runCatching {repo.recommend(day,emptyMap())}.isFailure)
        assertTrue(runCatching {repo.saveFood(bread.copy(zoneId=999))}.isFailure)
        assertTrue(repo.dao.allFoods().isEmpty())
    }
}
