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
    @Test fun staleRecommendationUsesFreshLimitsAndOldPortionRulesRequireExplicitZoneReview()=runBlocking {
        seed()
        val before=repo.dao.allIntakes()
        repo.saveZone(FoodZone(1,"面包",3.0,1.0))
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1.0,8L to 1.0),true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.dao.saveFood(bread.copy(zonePortionSize=0.5))
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1.0),true)}.isFailure)
        assertTrue(runCatching {repo.saveFood(bread)}.isFailure)
        repo.record(day,mapOf(7L to 1.0))
        val history=repo.dao.allIntakes();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        repo.saveZone(FoodZone(1,"面包",3.0,1.0))
        assertEquals(1.0,repo.dao.food(7)!!.zonePortionSize,0.0)
        assertEquals(history,repo.dao.allIntakes());assertEquals(bodies,repo.dao.allBodies());assertEquals(targets,repo.dao.allTargets())
        repo.record(day,mapOf(7L to 1.0),true)
        assertEquals(2,repo.dao.allIntakes().size)
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
    @Test fun legacyMixedUnitsBlockAllRecommendationsButManualEntryIsStillPossible()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val wrong=bread.copy(unit="g",basisAmount=50.0,step=50.0,maximum=500.0,zonePortionSize=50.0)
        assertTrue(runCatching {repo.saveFood(wrong)}.isFailure)
        repo.dao.saveFood(wrong)
        assertTrue(repo.recommend(day,emptyMap()).quantities.isEmpty())
        val before=repo.dao.allIntakes()
        assertTrue(runCatching {repo.record(day,mapOf(8L to 1.0),true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(8L to 1.0));assertEquals(2,repo.dao.allIntakes().size)
        assertTrue(runCatching {repo.saveZone(FoodZone(1,"面包",3.0))}.isFailure)
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
    @Test fun gramQuotaUsesNativeGramsAndRevalidatesFreshLimitsBeforeAdoption()=runBlocking {
        seed()
        repo.saveZone(FoodZone(2,"克重食品",100.0,80.0,limitUnit="G"))
        val native=bread.copy(id=17,unit="g",basisAmount=50.0,step=10.0,maximum=500.0,zoneId=2,zonePortionSize=1.0)
        val other=native.copy(id=18)
        repo.saveFood(native);repo.saveFood(other)
        assertTrue(runCatching {repo.saveFood(bread.copy(zoneId=2,gramsPerPortion=40.0))}.isFailure)
        assertTrue(runCatching {repo.saveZone(FoodZone(1,"面包",100.0,80.0,limitUnit="G"))}.isFailure)
        repo.record(day,mapOf(17L to 20.0))
        val suggestion=repo.recommend(day,mapOf(17L to 40.0,18L to 20.0))
        assertEquals(40.0,suggestion.quantities.getValue(17L),0.0);assertEquals(20.0,suggestion.quantities.getValue(18L),0.0)
        repo.saveZone(FoodZone(2,"克重食品",70.0,80.0,limitUnit="G"))
        val before=repo.dao.allIntakes()
        assertTrue(runCatching {repo.record(day,suggestion.quantities,true)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(18L to 50.0),true)
        assertEquals(70.0,repo.dao.allIntakes().filter {it.foodId in setOf(17L,18L)}.sumOf {it.quantity},0.0)
    }
    @Test fun assigningSameUnitFoodsIsAtomicPreservesFreshDetailsAndHistoryAndAppendsInOrder()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        repo.dao.saveZone(FoodZone(2,"份数分区",3.0))
        val member=bread.copy(id=9,name="原有成员",zoneId=2,sortOrder=12,gramsPerPortion=30.0)
        repo.dao.saveFood(member)
        val changed=bread.copy(name="最新食品名",nutrition=Nutrition(230.0,40.0,10.0,3.0),pricePerUnitYuan=6.5,allowed=false,gramsPerPortion=55.0)
        repo.saveFood(changed)
        val fresh=requireNotNull(repo.dao.food(7));val history=repo.dao.allIntakes()
        repo.assignFoodsToZone(2,listOf(ZoneFoodAssignment(9,999.0,null),ZoneFoodAssignment(8,1.0,50.0),ZoneFoodAssignment(7,1.0,40.0)))
        assertEquals(member,repo.dao.food(9))
        assertEquals(fresh.copy(zoneId=2,zonePortionSize=1.0,sortOrder=14),repo.dao.food(7))
        assertEquals(second.copy(zoneId=2,zonePortionSize=1.0,sortOrder=13),repo.dao.food(8))
        assertEquals(history,repo.dao.allIntakes())
        assertEquals(1.0,FoodZoneQuotas.summaries(repo.dao.allZones(),repo.dao.allFoods(),history,day).single {it.zone.id==2L}.consumedPortions,0.0)
    }
    @Test fun assigningAnyWrongUnitInvalidDeletedOrMissingFoodRollsBackWholeSelection()=runBlocking {
        seed();repo.dao.saveZone(FoodZone(2,"份数分区",3.0))
        val wrong=bread.copy(id=9,unit="g",basisAmount=50.0,step=50.0,maximum=500.0,zoneId=null)
        repo.saveFood(wrong)
        val original=repo.dao.allFoods()
        for(assignments in listOf(
            listOf(ZoneFoodAssignment(7,1.0,null),ZoneFoodAssignment(8,0.02,50.0)),
            listOf(ZoneFoodAssignment(7,1.0,null),ZoneFoodAssignment(9,1.0,30.0)),
            listOf(ZoneFoodAssignment(7,1.0,null),ZoneFoodAssignment(999,1.0,30.0)),
            listOf(ZoneFoodAssignment(7,1.0,null),ZoneFoodAssignment(7,1.0,null)))) {
            assertTrue(runCatching {repo.assignFoodsToZone(2,assignments)}.isFailure)
            assertEquals(original,repo.dao.allFoods())
        }
        repo.deleteFood(8)
        val deleted=repo.dao.allFoods()
        assertTrue(runCatching {repo.assignFoodsToZone(2,listOf(ZoneFoodAssignment(7,1.0,null),ZoneFoodAssignment(8,1.0,null)))}.isFailure)
        assertEquals(deleted,repo.dao.allFoods())
        repo.deleteZone(2)
        assertTrue(runCatching {repo.assignFoodsToZone(2,listOf(ZoneFoodAssignment(7,1.0,null)))}.isFailure)
        assertEquals(deleted,repo.dao.allFoods())
    }
    @Test fun explicitZoneUnitReviewCanRepairOldGramMembershipWithoutChangingSnapshots()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val legacy=bread.copy(unit="g",basisAmount=50.0,step=10.0,maximum=500.0,zonePortionSize=50.0)
        repo.dao.saveFood(legacy)
        val history=repo.dao.allIntakes();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        assertTrue(runCatching {repo.saveZone(FoodZone(1,"克重分区",150.0,limitUnit="G"))}.isFailure)
        repo.removeFoodsFromZone(1,listOf(8))
        repo.saveZone(FoodZone(1,"克重分区",150.0,limitUnit="G"))
        assertEquals(legacy.copy(zonePortionSize=1.0),repo.dao.food(7))
        assertNull(repo.dao.food(8)!!.zoneId)
        assertEquals(history,repo.dao.allIntakes());assertEquals(bodies,repo.dao.allBodies());assertEquals(targets,repo.dao.allTargets())
    }
    @Test fun removingSelectedMembersPreservesDetailsHistoryAndUnselectedMembers()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val before=repo.exportBackup(Preferences(profile=profile))
        val original=requireNotNull(repo.dao.food(7));val untouched=requireNotNull(repo.dao.food(8))
        repo.dao.saveZone(FoodZone(2,"其他分区",sortOrder=10))
        repo.removeFoodsFromZone(1,listOf(7))
        assertEquals(original.copy(zoneId=null,sortOrder=11),repo.dao.food(7))
        assertEquals(untouched,repo.dao.food(8))
        assertEquals(before.intakes,repo.dao.allIntakes());assertEquals(before.bodies,repo.dao.allBodies());assertEquals(before.targets,repo.dao.allTargets())
    }
    @Test fun removingStaleDeletedMissingOrDuplicateMembersRollsBackWholeSelection()=runBlocking {
        seed();repo.dao.saveFood(bread.copy(id=9,zoneId=null))
        val original=repo.dao.allFoods()
        for(ids in listOf(listOf(7L,999L),listOf(7L,7L),listOf(7L,9L))) {
            assertTrue(runCatching {repo.removeFoodsFromZone(1,ids)}.isFailure);assertEquals(original,repo.dao.allFoods())
        }
        repo.deleteFood(8)
        val deleted=repo.dao.allFoods()
        assertTrue(runCatching {repo.removeFoodsFromZone(1,listOf(7L,8L))}.isFailure);assertEquals(deleted,repo.dao.allFoods())
        assertTrue(runCatching {repo.removeFoodsFromZone(999,listOf(7L))}.isFailure);assertEquals(deleted,repo.dao.allFoods())
    }
    @Test fun incompatibleLegacyTargetRejectsNewAssignmentsUntilExplicitlyReviewed()=runBlocking {
        seed();repo.dao.saveZone(FoodZone(2,"旧份数分区",3.0))
        repo.dao.saveFood(bread.copy(id=9,zoneId=2,zonePortionSize=2.0))
        val original=repo.dao.allFoods()
        assertTrue(runCatching {repo.assignFoodsToZone(2,listOf(ZoneFoodAssignment(7,1.0,null)))}.isFailure)
        assertEquals(original,repo.dao.allFoods())
        repo.saveZone(FoodZone(2,"新份数分区",4.0))
        repo.assignFoodsToZone(2,listOf(ZoneFoodAssignment(7,1.0,null)))
        assertEquals(2L,repo.dao.food(7)!!.zoneId)
        assertEquals(1.0,repo.dao.food(9)!!.zonePortionSize,0.0)
    }
    @Test fun changingLegacyGramFoodToPortionsCannotBypassExplicitZoneReview()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0))
        val history=repo.dao.allIntakes();val bodies=repo.dao.allBodies();val targets=repo.dao.allTargets()
        for(oldSize in listOf(50.0,1.0)) {
            val legacy=bread.copy(unit="g",basisAmount=50.0,step=10.0,maximum=500.0,zonePortionSize=oldSize)
            repo.dao.saveFood(legacy)
            val before=repo.dao.allFoods()
            assertTrue(runCatching {repo.saveFood(bread.copy(zonePortionSize=1.0))}.isFailure)
            assertEquals(before,repo.dao.allFoods())
            assertEquals(history,repo.dao.allIntakes());assertEquals(bodies,repo.dao.allBodies());assertEquals(targets,repo.dao.allTargets())
        }
        repo.removeFoodsFromZone(1,listOf(7))
        repo.saveZone(FoodZone(1,"已核对份数区",4.0))
        repo.saveFood(bread.copy(zonePortionSize=1.0))
        assertEquals("份",repo.dao.food(7)!!.unit);assertEquals(1L,repo.dao.food(7)!!.zoneId)
        assertEquals(history,repo.dao.allIntakes());assertEquals(bodies,repo.dao.allBodies());assertEquals(targets,repo.dao.allTargets())
    }
}
