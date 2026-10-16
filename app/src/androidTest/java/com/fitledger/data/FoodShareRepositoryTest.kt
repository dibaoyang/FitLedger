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
class FoodShareRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    @Test fun importAlwaysInsertsWithoutChangingExistingFoodHistoryOrTargets()=runBlocking {
        val day=LocalDate.now().toEpochDay()
        val profile=BodyProfile(Sex.MALE,175.0,10957,1.2)
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
        val old=Food(7,"土豆泥","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true)
        db.dao().saveFood(old);repo.record(day,mapOf(7L to 200.0))
        val before=repo.exportBackup(Preferences(profile=profile))
        val code=FoodShareCodec.encode(old.copy(nutrition=Nutrition(200.0,30.0,10.0,5.0)))
        repo.importFoodCode(code,false)
        val after=repo.exportBackup(before.preferences)
        assertEquals(before.bodies,after.bodies);assertEquals(before.targets,after.targets);assertEquals(before.intakes,after.intakes)
        assertEquals(old,db.dao().food(7));assertEquals(2,after.foods.size)
        val imported=after.foods.single {it.id!=7L};assertFalse(imported.allowed);assertEquals(200.0,imported.nutrition.kcal,0.0)
        assertTrue(runCatching {repo.importFoodCode(code.dropLast(1),true)}.isFailure)
        assertEquals(after,repo.exportBackup(after.preferences).copy(exportTime=after.exportTime))
    }
}
