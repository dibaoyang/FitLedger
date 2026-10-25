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
class DailyEatingBackupRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
    private val food=Food(7,"面包","份",1.0,Nutrition(200.0,35.0,9.0,3.0),1.0,10.0,true)
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    @Test fun retainedMarkerRaisesRestoredTemplateSequenceAndDeletedIntakeStaysDeleted()=runBlocking {
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile);repo.dao.saveFood(food)
        val base=repo.exportBackup(Preferences(profile=profile))
        // This marker is the only survivor of a template previously numbered 100.
        val backup=base.copy(applications=listOf(DailyFoodApplication(day,100,200)),dayModes=listOf(DayMode(day,false)))
        // Restore on a fresh database with no existing SQLite high-water marks.
        db.close()
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        repo=Repository(db)
        repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(backup)))
        assertTrue(repo.dao.allIntakes().isEmpty())
        DailyEatingStore(db).saveTemplate(DailyFoodTemplate(foodId=7,unit="份",mode="FIXED",quantity=1.0),day)
        val template=db.dailyEatingDao().allTemplates().single()
        assertTrue(template.id>100)
        assertEquals(1,repo.dao.allIntakes().size)
        assertTrue(repo.dao.allIntakes().single().id>200)
        assertEquals(2,db.dailyEatingDao().allApplications().size)
        assertEquals(200L,db.dailyEatingDao().allApplications().single {it.templateId==100L}.intakeId)
        DailyEatingStore(db).apply(day)
        assertEquals(1,repo.dao.allIntakes().size)
        val exported=repo.exportBackup(base.preferences)
        assertEquals(exported,BackupCodec.decode(BackupCodec.encode(exported)))
    }
    @Test fun indulgencePersistsThroughRestoreRejectsRecommendationAndAllowsManualRecords()=runBlocking {
        repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile);repo.dao.saveFood(food)
        val suggestion=repo.recommend(day,mapOf(7L to 1.0))
        DailyEatingStore(db).setIndulgence(day,true)
        val backup=repo.exportBackup(Preferences(profile=profile))
        repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(backup)))
        assertTrue(db.dailyEatingDao().dayMode(day)!!.isIndulgence)
        assertTrue(runCatching {repo.recommend(day,emptyMap())}.isFailure)
        assertTrue(runCatching {repo.record(day,suggestion.quantities,true)}.isFailure)
        assertTrue(repo.dao.allIntakes().isEmpty())
        repo.record(day,mapOf(7L to 5.0));assertEquals(1,repo.dao.allIntakes().size)
        assertEquals(backup.targets,repo.dao.allTargets());assertEquals(backup.bodies,repo.dao.allBodies())
    }
}
