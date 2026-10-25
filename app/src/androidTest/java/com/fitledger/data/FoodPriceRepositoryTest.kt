package com.fitledger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import com.fitledger.domain.Rule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FoodPriceRepositoryTest {
    private lateinit var db:LedgerDatabase
    private lateinit var repo:Repository
    private val day get()=LocalDate.now().toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
    private val food=Food(7,"面包","份",1.0,Nutrition(40.0,10.0,0.0,0.0),1.0,10.0,true,pricePerUnitYuan=3.0)
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();repo=Repository(db)}
    @After fun close() {db.close()}
    private suspend fun seed(plan:Plan=Plan()) {repo.weigh(day,Body(72.4,15.2,58.1),plan,profile);repo.dao.saveFood(food)}

    @Test fun recommendationBudgetDeductsTodayAndAdoptionOverBudgetRollsBack()=runBlocking {
        seed();repo.record(day,mapOf(7L to 1.0));val policy=RecommendationPolicy(dailyBudgetYuan=6.0)
        val before=repo.dao.allIntakes();assertEquals(3.0,before.single().costYuan!!,0.0)
        assertTrue(runCatching {repo.record(day,mapOf(7L to 2.0),true,policy)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        val suggestion=repo.recommend(day,mapOf(7L to 1.0),policy)
        assertEquals(1.0,suggestion.quantities.getValue(7),0.0)
        repo.record(day,suggestion.quantities,true,policy)
        assertEquals(6.0,repo.dao.allIntakes().sumOf {it.costYuan!!},0.0)
        repo.record(day,mapOf(7L to 1.0));assertEquals(9.0,repo.dao.allIntakes().sumOf {it.costYuan!!},0.0)
    }
    @Test fun changedPriceKeepsPastSnapshotAndRejectsOldSuggestionUsingNewPrice()=runBlocking {
        seed();repo.saveFood(food.copy(pricePerUnitYuan=2.0));repo.record(day,mapOf(7L to 1.0))
        val policy=RecommendationPolicy(dailyBudgetYuan=6.0)
        val suggestion=repo.recommend(day,mapOf(7L to 2.0),policy)
        val before=repo.dao.allIntakes()
        repo.saveFood(food.copy(pricePerUnitYuan=3.0))
        assertEquals(2.0,repo.dao.allIntakes().single().costYuan!!,0.0)
        assertTrue(runCatching {repo.record(day,suggestion.quantities,true,policy)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(7L to 1.0));assertEquals(listOf(2.0,3.0),repo.dao.allIntakes().map {it.costYuan})
    }
    @Test fun nutrientLimitsAreCheckedAgainAgainstTodayBeforeAdoption()=runBlocking {
        seed(Plan(carbs=Rule(Basis.FIXED,10.0),protein=Rule(Basis.FIXED,0.0),fat=Rule(Basis.FIXED,0.0)))
        val policy=RecommendationPolicy(carbs=MacroTolerance(0.0,0.0))
        val suggestion=repo.recommend(day,mapOf(7L to 1.0),policy)
        repo.record(day,mapOf(7L to 1.0));val before=repo.dao.allIntakes()
        assertTrue(runCatching {repo.record(day,suggestion.quantities,true,policy)}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(7L to 1.0));assertEquals(2,repo.dao.allIntakes().size)
    }
    @Test fun unknownHistoricalCostStaysUnknownAndCannotSilentlyPassBudget()=runBlocking {
        seed();repo.dao.insertIntake(Intake(day=day,foodId=7,name=food.name,unit=food.unit,quantity=1.0,nutrition=food.nutrition))
        val before=repo.dao.allIntakes();assertNull(before.single().costYuan)
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1.0),true,RecommendationPolicy(dailyBudgetYuan=20.0))}.isFailure)
        assertEquals(before,repo.dao.allIntakes())
        repo.record(day,mapOf(7L to 1.0));assertNull(repo.dao.allIntakes().first().costYuan)
    }
    @Test fun manualCostOverflowRejectsAndUnknownOrFreeCostsRemainDistinct()=runBlocking {
        seed();repo.saveFood(food.copy(maximum=1.0,pricePerUnitYuan=1e300))
        assertTrue(runCatching {repo.record(day,mapOf(7L to 1e100))}.isFailure)
        assertTrue(repo.dao.allIntakes().isEmpty())
        repo.saveFood(food.copy(pricePerUnitYuan=0.0));repo.record(day,mapOf(7L to 1.0))
        repo.saveFood(food.copy(pricePerUnitYuan=null));repo.record(day,mapOf(7L to 1.0))
        assertEquals(0.0,repo.dao.allIntakes().first().costYuan!!,0.0);assertNull(repo.dao.allIntakes().last().costYuan)
    }
    @Test fun freeOrUnknownPricesCannotHideManualNutritionOverflow()=runBlocking {
        seed()
        for(price in listOf(0.0,null)) {
            repo.saveFood(food.copy(nutrition=Nutrition(100.0,25.0,0.0,0.0),pricePerUnitYuan=price))
            assertTrue(runCatching {repo.record(day,mapOf(7L to 1e308))}.isFailure)
            assertTrue(repo.dao.allIntakes().isEmpty())
        }
    }
}
