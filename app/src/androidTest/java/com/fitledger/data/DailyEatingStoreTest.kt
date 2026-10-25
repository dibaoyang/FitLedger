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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailyEatingStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "daily-eating-test-${UUID.randomUUID()}.db"
    private lateinit var db: LedgerDatabase
    private lateinit var repo: Repository
    private lateinit var store: DailyEatingStore
    private val day get() = LocalDate.now().toEpochDay()
    private val profile = BodyProfile(Sex.MALE, 175.0, LocalDate.of(2000, 1, 1).toEpochDay(), 1.55)
    private val rice = Food(7, "米饭", "g", 100.0, Nutrition(116.0, 26.0, 2.6, 0.3), 10.0, 500.0, true)
    private val bread = Food(8, "面包", "份", 1.0, Nutrition(200.0, 35.0, 9.0, 3.0), 1.0, 10.0, true)

    @Before fun setup() = open()
    private fun open() {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
        repo = Repository(db)
        store = DailyEatingStore(db)
    }
    @After fun close() {
        db.close()
        context.deleteDatabase(databaseName)
    }
    private suspend fun seedFoods() { repo.dao.saveFood(rice); repo.dao.saveFood(bread) }
    private suspend fun weigh() { repo.weigh(day, Body(72.4, 15.2, 58.1), Plan(), profile) }
    private fun template(food: Food = rice, mode: String = "FIXED", quantity: Double = 100.0,
        enabled: Boolean = true, startDay: Long = day) = DailyFoodTemplate(foodId = food.id, unit = food.unit,
        mode = mode, quantity = quantity, enabled = enabled, startDay = startDay)

    @Test fun gateAndOnceOnlyMarkersSurviveDeletedIntakeAndReopen() = runBlocking {
        seedFoods()
        store.saveTemplate(template(), day)
        assertTrue(store.apply(day).isEmpty())
        assertTrue(repo.dao.allIntakes().isEmpty()); assertTrue(store.dao.allApplications().isEmpty())
        assertTrue(runCatching { store.setIndulgence(day, true) }.isFailure)
        weigh()
        val original = repo.dao.allIntakes().single()
        val application = store.dao.allApplications().single()
        assertEquals(original.id, application.intakeId); assertEquals("ROUTINE", original.source)
        store.apply(day); assertEquals(listOf(original), repo.dao.allIntakes())
        repo.remove(day, original.id)
        assertEquals(listOf(application), store.dao.allApplications())
        db.close(); open()
        store.apply(day)
        assertTrue(repo.dao.allIntakes().isEmpty())
        assertEquals(listOf(application), store.dao.allApplications())
    }

    @Test fun fixedNativeAmountsAndPerWeightSnapshotsUseTodayStoredKg() = runBlocking {
        seedFoods()
        store.saveTemplate(template(quantity = 200.0), day)
        store.saveTemplate(template(bread, quantity = 2.0), day)
        store.saveTemplate(template(mode = "PER_WEIGHT", quantity = 1.25), day)
        weigh()
        val records = repo.dao.allIntakes()
        assertEquals(listOf(200.0, 2.0, 90.5), records.map { it.quantity })
        assertEquals(listOf("g", "份", "g"), records.map { it.unit })
        assertEquals(232.0, records[0].nutrition.kcal, 1e-8)
        assertEquals(400.0, records[1].nutrition.kcal, 1e-8)
        assertEquals(116.0 * 0.905, records[2].nutrition.kcal, 1e-8)
        val snapshot = repo.dao.body(day)
        store.apply(day)
        assertEquals(snapshot, repo.dao.body(day)); assertEquals(records, repo.dao.allIntakes())
    }

    @Test fun editingOrDeletingATemplateNeverRewritesTodaysAlreadyAppliedSnapshot() = runBlocking {
        seedFoods(); weigh(); store.saveTemplate(template(), day)
        val saved = store.dao.allTemplates().single()
        val intake = repo.dao.allIntakes().single()
        val body = repo.dao.body(day); val target = repo.dao.target(day)
        repo.saveFood(rice.copy(name = "新名称", nutrition = rice.nutrition * 2.0))
        store.saveTemplate(saved.copy(quantity = 300.0), day)
        assertEquals(listOf(intake), repo.dao.allIntakes())
        assertEquals(300.0, store.dao.template(saved.id)!!.quantity, 0.0)
        store.deleteTemplate(saved.id)
        store.apply(day)
        assertEquals(listOf(intake), repo.dao.allIntakes())
        assertEquals(saved.id, store.dao.allApplications().single().templateId)
        assertEquals(body, repo.dao.body(day)); assertEquals(target, repo.dao.target(day))
        assertTrue(runCatching { store.saveTemplate(saved, day) }.isFailure)
    }

    @Test fun historicalApplicationDoesNotPreventTodayAndFutureTemplatesStayPending() = runBlocking {
        seedFoods()
        val id = store.dao.saveTemplate(template(quantity = 50.0, startDay = day - 1))
        val pastBody = BodyRecord(day - 1, 60.0, 20.0, 48.0, null)
        val pastTarget = DailyTarget(day - 1, Nutrition(2000.0, 250.0, 120.0, 57.7777778), PlanCodec.encode(Plan()))
        repo.dao.insertBody(pastBody); repo.dao.insertTarget(pastTarget)
        val pastId = repo.dao.insertIntake(Intake(day = day - 1, foodId = rice.id, name = "旧米饭", unit = "g",
            quantity = 50.0, nutrition = rice.nutrition * 0.5, source = "ROUTINE"))
        store.dao.insertApplication(DailyFoodApplication(day - 1, id, pastId))
        val pastIntake = repo.dao.intake(pastId)
        store.saveTemplate(store.dao.template(id)!!.copy(quantity = 200.0), day)
        store.saveTemplate(template(bread, quantity = 1.0, startDay = day + 1), day)
        store.saveTemplate(template(quantity = 500.0, enabled = false), day)
        weigh()
        val today = repo.dao.allIntakes().filter { it.day == day }
        assertEquals(1, today.size); assertEquals(200.0, today.single().quantity, 0.0)
        assertEquals(pastIntake, repo.dao.intake(pastId))
        assertEquals(pastBody, repo.dao.body(day - 1)); assertEquals(pastTarget, repo.dao.target(day - 1))
        assertTrue(runCatching { store.apply(day - 1) }.isFailure)
        assertTrue(runCatching { store.apply(day + 1) }.isFailure)
    }

    @Test fun deletedAndUnitChangedAndInvalidFoodsAreSkippedWithoutBlockingOtherTemplates() = runBlocking {
        seedFoods()
        store.saveTemplate(template(), day)
        store.saveTemplate(template(bread, quantity = 1.0), day)
        val invalid = rice.copy(id = 9, name = "坏数据")
        repo.dao.saveFood(invalid)
        store.saveTemplate(template(invalid), day)
        repo.deleteFood(rice.id)
        repo.saveFood(bread.copy(unit = "g", basisAmount = 100.0, step = 10.0, maximum = 500.0))
        repo.dao.saveFood(invalid.copy(nutrition = Nutrition(-1.0)))
        val valid = rice.copy(id = 10, name = "可正常添加")
        repo.dao.saveFood(valid); store.saveTemplate(template(valid), day)
        weigh()
        val warnings = store.apply(day)
        assertEquals(3, warnings.size)
        assertEquals(10L, repo.dao.allIntakes().single().foodId)
        assertNotNull(repo.dao.body(day)); assertNotNull(repo.dao.target(day))
        assertEquals(1, store.dao.allApplications().size)
    }

    @Test fun indulgencePreservesEverythingPausesMissingApplicationsAndResumesOnce() = runBlocking {
        seedFoods(); weigh()
        repo.record(day, mapOf(rice.id to 50.0))
        val body = repo.dao.body(day); val target = repo.dao.target(day)
        val before = repo.dao.allIntakes()
        store.setIndulgence(day, true)
        store.saveTemplate(template(bread, quantity = 2.0), day)
        store.apply(day)
        assertEquals(before, repo.dao.allIntakes()); assertTrue(store.dao.allApplications().isEmpty())
        assertTrue(runCatching { repo.recommend(day, emptyMap()) }.isFailure)
        assertTrue(runCatching { repo.record(day, mapOf(rice.id to 50.0), recommendation = true) }.isFailure)
        store.setIndulgence(day, false)
        val withRoutine = repo.dao.allIntakes()
        assertEquals(2, withRoutine.size)
        assertEquals(before.single(), withRoutine.first())
        store.setIndulgence(day, true); store.setIndulgence(day, false)
        assertEquals(withRoutine, repo.dao.allIntakes())
        assertEquals(body, repo.dao.body(day)); assertEquals(target, repo.dao.target(day))
    }

    @Test fun editingDailyQuantityScalesSavedNutritionAndKeepsItsApplication() = runBlocking {
        seedFoods(); weigh(); store.saveTemplate(template(), day)
        val original = repo.dao.allIntakes().single()
        val marker = store.dao.allApplications().single()
        repo.saveFood(rice.copy(nutrition = rice.nutrition * 10.0))
        store.editIntakeQuantity(day, original.id, 250.0)
        val expected = original.copy(quantity = 250.0, nutrition = original.nutrition * 2.5)
        assertEquals(expected, repo.dao.intake(original.id))
        assertEquals(listOf(marker), store.dao.allApplications())
        store.apply(day); assertEquals(listOf(expected), repo.dao.allIntakes())
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertTrue(runCatching { store.editIntakeQuantity(day, original.id, invalid) }.isFailure)
        }
        assertTrue(runCatching { store.editIntakeQuantity(day - 1, original.id, 1.0) }.isFailure)
        assertEquals(expected, repo.dao.intake(original.id))
    }

    @Test fun directNutritionCannotBeChangedUsingFoodQuantityEditor() = runBlocking {
        seedFoods(); weigh()
        repo.recordNutrition(day, Nutrition(carbs = 50.0))
        val original = repo.dao.allIntakes().single()
        assertTrue(runCatching { store.editIntakeQuantity(day, original.id, 2.0) }.isFailure)
        assertEquals(original, repo.dao.intake(original.id))
    }

    @Test fun routineCostIsFrozenAndQuantityEditingScalesTheOldCost() = runBlocking {
        seedFoods(); repo.saveFood(rice.copy(pricePerUnitYuan = 0.01)); weigh()
        store.saveTemplate(template(quantity = 100.0), day)
        val original = repo.dao.allIntakes().single()
        assertEquals(1.0, original.costYuan!!, 1e-8)
        repo.saveFood(rice.copy(pricePerUnitYuan = 1.0, nutrition = rice.nutrition * 10.0))
        store.editIntakeQuantity(day, original.id, 200.0)
        val edited = repo.dao.intake(original.id)!!
        assertEquals(2.0, edited.costYuan!!, 1e-8)
        assertEquals(original.nutrition * 2.0, edited.nutrition)
        store.apply(day); assertEquals(listOf(edited), repo.dao.allIntakes())
    }

    @Test fun unknownRoutineCostStaysUnknownAndExplicitFreeCostStaysZero() = runBlocking {
        seedFoods(); weigh(); store.saveTemplate(template(), day)
        val unknown = repo.dao.allIntakes().single()
        assertNull(unknown.costYuan)
        repo.saveFood(rice.copy(pricePerUnitYuan = 0.5))
        store.editIntakeQuantity(day, unknown.id, 200.0)
        assertNull(repo.dao.intake(unknown.id)!!.costYuan)
        repo.saveFood(bread.copy(pricePerUnitYuan = 0.0))
        store.saveTemplate(template(bread, quantity = 2.0), day)
        val free = repo.dao.allIntakes().single { it.foodId == bread.id }
        assertEquals(0.0, free.costYuan!!, 0.0)
        store.editIntakeQuantity(day, free.id, 3.0)
        assertEquals(0.0, repo.dao.intake(free.id)!!.costYuan!!, 0.0)
    }
}
