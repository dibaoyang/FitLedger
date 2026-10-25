package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FoodLibraryOrderingRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val food=Food(name="食品",unit="g",basisAmount=100.0,nutrition=Nutrition(100.0,20.0,4.0,0.4),step=10.0,maximum=500.0,allowed=true)
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    private suspend fun addFood(name:String,zoneId:Long?=null):Food {
        repo.saveFood(food.copy(name=name,zoneId=zoneId));return repo.dao.allFoods().single {it.name==name}
    }
    private suspend fun addZone(name:String):FoodZone {
        repo.saveZone(FoodZone(name=name));return repo.dao.allZones().single {it.name==name}
    }
    @Test fun newTopLevelItemsAppendAndZoneMembershipHasIndependentOrder()=runBlocking {
        val a=addFood("A");val zone=addZone("面包");val b=addFood("B")
        assertEquals(listOf(0L,1L,2L),listOf(a.sortOrder,zone.sortOrder,b.sortOrder))
        val x=addFood("X",zone.id);val y=addFood("Y",zone.id)
        assertEquals(listOf(0L,1L),listOf(x.sortOrder,y.sortOrder))
        repo.reorderLibrary(listOf("food:${b.id}","zone:${zone.id}","food:${a.id}"))
        assertEquals(0L,repo.dao.food(b.id)!!.sortOrder);assertEquals(1L,repo.dao.zone(zone.id)!!.sortOrder);assertEquals(2L,repo.dao.food(a.id)!!.sortOrder)
        assertEquals(x,repo.dao.food(x.id));assertEquals(y,repo.dao.food(y.id))
        repo.reorderZone(zone.id,listOf(y.id,x.id))
        assertEquals(listOf(y.id,x.id),repo.dao.foods().first().filter {it.zoneId==zone.id}.map {it.id})
        assertEquals(2L,repo.dao.food(a.id)!!.sortOrder)
    }
    @Test fun editingNutritionKeepsOrderAndMovingMembershipAppendsToDestination()=runBlocking {
        val zone=addZone("面包");val x=addFood("X",zone.id);val y=addFood("Y",zone.id);val a=addFood("A")
        repo.reorderZone(zone.id,listOf(y.id,x.id))
        repo.saveFood(x.copy(nutrition=Nutrition(200.0,40.0,8.0,0.8)))
        assertEquals(1L,repo.dao.food(x.id)!!.sortOrder)
        repo.saveFood(a.copy(zoneId=zone.id))
        assertEquals(2L,repo.dao.food(a.id)!!.sortOrder)
        repo.saveFood(a.copy(zoneId=null))
        assertEquals(1L,repo.dao.food(a.id)!!.sortOrder)
        repo.saveZone(zone.copy(name="新名称",sortOrder=100))
        assertEquals(0L,repo.dao.zone(zone.id)!!.sortOrder)
    }
    @Test fun staleIncompleteDuplicateOrNestedTopLevelOrdersRejectWithoutAnyWrite()=runBlocking {
        val zone=addZone("面包");val a=addFood("A");val nested=addFood("X",zone.id)
        val before=repo.exportBackup(Preferences())
        val invalid=listOf(emptyList(),listOf("zone:${zone.id}"),listOf("zone:${zone.id}","food:${a.id}","food:${nested.id}"),
            listOf("food:${a.id}","food:${a.id}"),listOf("zone:999","food:${a.id}"))
        invalid.forEach {assertTrue(runCatching {repo.reorderLibrary(it)}.isFailure)}
        assertTrue(runCatching {repo.reorderZone(zone.id,listOf(a.id))}.isFailure)
        assertTrue(runCatching {repo.reorderZone(zone.id,listOf(nested.id,nested.id))}.isFailure)
        assertEquals(before.foods,repo.dao.allFoods());assertEquals(before.zones,repo.dao.allZones())
        repo.saveFood(a.copy(zoneId=zone.id))
        assertTrue(runCatching {repo.reorderLibrary(listOf("food:${a.id}","zone:${zone.id}"))}.isFailure)
    }
    @Test fun sortingAndDeletingZonePreserveNutritionAndHistoricalSnapshots()=runBlocking {
        val day=LocalDate.now().toEpochDay();val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        val zone=addZone("面包");val x=addFood("X",zone.id);val y=addFood("Y",zone.id);val a=addFood("A")
        repo.record(day,mapOf(x.id to 100.0));val before=repo.exportBackup(Preferences(profile=profile))
        repo.reorderZone(zone.id,listOf(y.id,x.id));repo.reorderLibrary(listOf("food:${a.id}","zone:${zone.id}"))
        repo.deleteZone(zone.id)
        val after=repo.exportBackup(before.preferences)
        assertEquals(before.bodies,after.bodies);assertEquals(before.targets,after.targets);assertEquals(before.intakes,after.intakes)
        after.foods.forEach {changed ->assertEquals(before.foods.single {it.id==changed.id}.nutrition,changed.nutrition)}
        assertEquals(listOf(a.id,y.id,x.id),repo.dao.foods().first().map {it.id})
        assertEquals(after,BackupCodec.decode(BackupCodec.encode(after)))
    }
}
