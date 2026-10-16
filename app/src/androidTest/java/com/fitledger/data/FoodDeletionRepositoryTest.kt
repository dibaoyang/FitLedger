package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FoodDeletionRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: Repository
    private val day get() = LocalDate.now().toEpochDay()
    private val profile = BodyProfile(Sex.MALE, 175.0, LocalDate.of(2000, 1, 1).toEpochDay(), 1.55)
    private val food = Food(name = "土豆泥", unit = "g", basisAmount = 100.0,
        nutrition = Nutrition(116.0, 26.0, 2.6, 0.3), step = 10.0, maximum = 500.0, allowed = true, energyInputUnit = "KJ")
    private val preferences = Preferences(profile = profile, energyUnit = EnergyUnit.KJ, reminder = true,
        weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        repo = Repository(db)
    }
    @After fun close() { db.close() }

    private suspend fun seed(): Food {
        repo.weigh(day, Body(72.4, 15.2, 58.1), Plan(), profile)
        repo.saveFood(food)
        return repo.dao.foods().first().single()
    }

    @Test fun deletionChangesOnlyFoodFlagAndPreservesTodayAndHistoricalSnapshots() = runBlocking {
        val saved = seed()
        repo.record(day, mapOf(saved.id to 200.0))
        repo.dao.insertBody(BodyRecord(day - 1, 72.4, 15.2, 61.3952, 58.1))
        repo.dao.insertTarget(DailyTarget(day - 1, Nutrition(2000.0), PlanCodec.encode(Plan())))
        repo.dao.insertIntake(Intake(day = day - 1, foodId = saved.id, name = "旧名称", unit = "g", quantity = 100.0, nutrition = Nutrition(90.0, 20.0, 1.0, 0.0)))
        repo.exercise(day, null, null, 300.0, true)
        repo.dao.stageSettings(PendingSettings(payload = PreferencesCodec.encode(preferences)))
        val before = repo.exportBackup(preferences)
        val pending = repo.dao.pendingSettings()

        repo.deleteFood(saved.id)

        val after = repo.exportBackup(preferences)
        assertEquals(before.copy(foods = listOf(saved.copy(isDeleted = true)), exportTime = after.exportTime), after)
        assertEquals(pending, repo.dao.pendingSettings())
        assertTrue(repo.dao.foods().first().isEmpty())
        assertEquals(saved.copy(isDeleted = true), repo.dao.food(saved.id))
        assertEquals(before.targets.first { it.day == day }.nutrition.kcal - before.intakes.filter { it.day == day }.sumOf { it.nutrition.kcal },
            after.targets.first { it.day == day }.nutrition.kcal - after.intakes.filter { it.day == day }.sumOf { it.nutrition.kcal }, 0.0)
        assertEquals(after, BackupCodec.decode(BackupCodec.encode(after)))
    }

    @Test fun deletedFoodCannotBeRecordedManuallyOrThroughStaleRecommendationAndBatchRollsBack() = runBlocking {
        val saved = seed()
        repo.saveFood(food.copy(name = "另外的食品"))
        val other = repo.dao.foods().first().first { it.id != saved.id }
        repo.deleteFood(saved.id)
        assertTrue(runCatching { repo.record(day, mapOf(saved.id to 100.0)) }.isFailure)
        assertTrue(runCatching { repo.record(day, linkedMapOf(other.id to 100.0, saved.id to 100.0), true) }.isFailure)
        assertTrue(repo.dao.allIntakes().isEmpty())
        assertFalse(repo.dao.food(saved.id)!!.candidate().allowed)
        assertEquals(listOf(other), repo.dao.foods().first())
    }

    @Test fun staleEditNeverResurrectsFoodAndSameNameReadditionGetsDifferentIdentity() = runBlocking {
        val saved = seed()
        repo.record(day, mapOf(saved.id to 100.0))
        val history = repo.dao.allIntakes()
        repo.deleteFood(saved.id)
        assertTrue(runCatching { repo.saveFood(saved.copy(name = "陈旧编辑")) }.isFailure)
        assertTrue(runCatching { repo.saveFood(food.copy(id = 999)) }.isFailure)
        assertTrue(runCatching { repo.saveFood(food.copy(isDeleted = true)) }.isFailure)
        repo.saveFood(food)
        val added = repo.dao.foods().first().single()
        assertNotEquals(saved.id, added.id)
        assertEquals(saved.copy(isDeleted = true), repo.dao.food(saved.id))
        assertEquals(history, repo.dao.allIntakes())
        repo.record(day, mapOf(added.id to 60.0))
        assertEquals(setOf(saved.id, added.id), repo.dao.allIntakes().map { it.foodId }.toSet())
        assertEquals(2, repo.dao.allFoods().size)
    }

    @Test fun restoringBackupKeepsDeletedFoodsHiddenAndOldIntakesReadable() = runBlocking {
        val saved = seed()
        repo.record(day, mapOf(saved.id to 100.0))
        repo.deleteFood(saved.id)
        val original = repo.exportBackup(preferences)
        repo.saveFood(food.copy(name = "备份之后添加"))
        repo.restoreBackup(BackupCodec.decode(BackupCodec.encode(original)))
        assertTrue(repo.dao.foods().first().isEmpty())
        assertEquals(original.foods, repo.dao.allFoods())
        assertEquals(original.intakes, repo.dao.allIntakes())
        assertEquals(original.bodies, repo.dao.allBodies())
        assertEquals(original.targets, repo.dao.allTargets())
        assertEquals(preferences, PreferencesCodec.decode(repo.dao.pendingSettings()!!.payload))
        val exported = repo.exportBackup(preferences)
        assertEquals(original.copy(exportTime = exported.exportTime), exported)
        repo.saveFood(food)
        assertNotEquals(saved.id, repo.dao.foods().first().single().id)
        repo.remove(day, original.intakes.single().id)
        assertTrue(repo.dao.allIntakes().isEmpty())
        assertTrue(repo.dao.food(saved.id)!!.isDeleted)
    }

    @Test fun invalidLegacyMetadataNeverClearsCurrentDataDuringRestore() = runBlocking {
        val saved = seed()
        repo.deleteFood(saved.id)
        val before = repo.exportBackup(preferences)
        assertTrue(runCatching { repo.restoreBackup(before.copy(backupVersion = 6, databaseVersion = 4)) }.isFailure)
        assertEquals(before.foods, repo.dao.allFoods())
        assertEquals(before.bodies, repo.dao.allBodies())
        assertEquals(before.targets, repo.dao.allTargets())
        assertNull(repo.dao.pendingSettings())
    }

    @Test fun foodLibraryDeletionDoesNotRequireTodayBodyOrTouchPreferences() = runBlocking {
        repo.saveFood(food)
        val saved = repo.dao.foods().first().single()
        repo.deleteFood(saved.id)
        assertTrue(repo.dao.foods().first().isEmpty())
        assertEquals(listOf(saved.copy(isDeleted = true)), repo.dao.allFoods())
        assertTrue(repo.dao.allBodies().isEmpty())
        assertTrue(repo.dao.allTargets().isEmpty())
        assertNull(repo.dao.pendingSettings())
        assertTrue(runCatching { repo.deleteFood(saved.id) }.isFailure)
        assertTrue(runCatching { repo.deleteFood(999) }.isFailure)
    }
}
