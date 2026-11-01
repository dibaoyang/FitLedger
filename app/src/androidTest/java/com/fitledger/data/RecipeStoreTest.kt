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
class RecipeStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var recipes:RecipeStore
    private lateinit var batch:BatchFoodStore
    private val day get()=LocalDate.now().toEpochDay()
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val bread=Food(8,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,pricePerUnitYuan=3.0)
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build()
        recipes=RecipeStore(db);batch=BatchFoodStore(db)
    }
    @After fun close()=db.close()
    private suspend fun seed() {
        db.dao().saveFood(rice);db.dao().saveFood(bread)
        for(date in listOf(day-1,day)) {
            db.dao().insertBody(BodyRecord(date,72.4,15.2,61.3952,null))
            db.dao().insertTarget(DailyTarget(date,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan())))
        }
        FeatureStore(db).recordFood(day-1,rice.id,200.0)
    }
    private fun draft()=FoodRecipe(name="自制配方",ingredients=RecipeIngredients.encode(listOf(
        RecipeIngredients.fromFood(rice,100.0),RecipeIngredients.fromFood(bread,2.0))),finishedWeightG=400.0)

    @Test fun publishingRecipeCreatesOneIndependentFoodAndNoConsumptionOrHistoricalChanges()=runBlocking {
        seed();val bodies=db.dao().allBodies();val targets=db.dao().allTargets();val intakes=db.dao().allIntakes()
        val id=recipes.save(draft(),true)
        val saved=recipes.dao.recipe(id)!!;val output=db.dao().food(saved.foodId!!)!!
        assertEquals("g",output.unit);assertEquals(100.0,output.basisAmount,0.0)
        assertEquals(129.0,output.nutrition.kcal,1e-9);assertEquals(0.0175,output.pricePerUnitYuan!!,1e-10)
        assertNotEquals(rice.id,output.id);assertNotEquals(bread.id,output.id)
        assertEquals(rice,db.dao().food(rice.id));assertEquals(bread,db.dao().food(bread.id))
        assertEquals(intakes,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }

    @Test fun frozenRecipeCanKeepEditedOrDeletedIngredientsUntilExplicitRefresh()=runBlocking {
        seed();val id=recipes.save(draft(),false);val original=recipes.dao.recipe(id)!!
        db.dao().saveFood(rice.copy(nutrition=rice.nutrition*10.0,pricePerUnitYuan=5.0))
        db.dao().markFoodDeleted(bread.id)
        val edited=original.copy(name="只改成品重量",finishedWeightG=200.0)
        recipes.save(edited,false,original)
        assertEquals(original.ingredients,recipes.dao.recipe(id)!!.ingredients)
        assertEquals(258.0,RecipeCalculator.calculate(RecipeIngredients.decode(edited.ingredients),edited.finishedWeightG).per100g.kcal,1e-9)
        val snapshot=recipes.dao.recipe(id)!!
        val refreshed=RecipeIngredients.decode(snapshot.ingredients).map {if(it.foodId==rice.id) RecipeIngredients.fromFood(db.dao().food(rice.id)!!,it.quantity) else it}
        recipes.save(snapshot.copy(ingredients=RecipeIngredients.encode(refreshed)),false,snapshot)
        assertEquals(1560.0,RecipeCalculator.calculate(refreshed,200.0).totalNutrition.kcal,1e-8)
        assertTrue(db.dao().food(bread.id)!!.isDeleted)
    }

    @Test fun explicitLinkedFoodUpdateKeepsOtherFoodPreferencesAndHistoricalNutrition()=runBlocking {
        seed();val id=recipes.save(draft(),true);val original=recipes.dao.recipe(id)!!
        val food=db.dao().food(original.foodId!!)!!.copy(favorite=true,allowed=false,step=20.0,aliases="[\"常做的\"]")
        db.dao().saveFood(food)
        FeatureStore(db).recordFood(day-1,food.id,100.0)
        val intakes=db.dao().allIntakes();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        recipes.save(original.copy(name="新配方名称",finishedWeightG=200.0),true,original,food)
        val output=db.dao().food(food.id)!!
        assertEquals(food.copy(name="新配方名称",nutrition=food.nutrition*2.0,pricePerUnitYuan=food.pricePerUnitYuan!!*2.0),output)
        assertEquals(intakes,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }

    @Test fun staleFoodRecipeOrIngredientFailsAtomicallyWithoutOverwritingAnotherFood()=runBlocking {
        seed();val originalDraft=draft()
        assertTrue(runCatching {recipes.save(originalDraft.copy(foodId=999),true)}.isFailure)
        val before=db.dao().allFoods()
        db.dao().saveFood(rice.copy(pricePerUnitYuan=2.0))
        assertTrue(runCatching {recipes.save(originalDraft,true)}.isFailure)
        assertTrue(recipes.dao.allRecipes().isEmpty());assertEquals(2,db.dao().allFoods().size)
        db.dao().saveFood(rice)
        val id=recipes.save(draft(),true);val saved=recipes.dao.recipe(id)!!;val food=db.dao().food(saved.foodId!!)!!
        db.dao().saveFood(food.copy(favorite=true))
        assertTrue(runCatching {recipes.save(saved.copy(name="不应写入"),true,saved,food)}.isFailure)
        assertEquals(saved,recipes.dao.recipe(id));assertTrue(db.dao().food(food.id)!!.favorite)
        assertTrue(runCatching {recipes.save(saved.copy(name="不应覆盖"),false,saved.copy(name="过期预览"))}.isFailure)
        assertEquals(saved,recipes.dao.recipe(id));assertEquals(before.take(2),db.dao().allFoods().take(2))
    }

    @Test fun deletingRecipeLeavesGeneratedFoodHistoryAndOtherDefinitionsAvailable()=runBlocking {
        seed();val id=recipes.save(draft(),true);val saved=recipes.dao.recipe(id)!!
        FeatureStore(db).recordFood(day-1,saved.foodId!!,50.0)
        val history=db.dao().allIntakes();val foods=db.dao().allFoods()
        recipes.delete(saved)
        assertTrue(recipes.dao.allRecipes().isEmpty());assertEquals(foods,db.dao().allFoods());assertEquals(history,db.dao().allIntakes())
        assertTrue(runCatching {recipes.delete(saved)}.isFailure)
    }

    @Test fun incompleteIngredientCostSurvivesRecipeFoodAndFullBackupRestoreAsUnknown()=runBlocking {
        seed();db.dao().saveFood(bread.copy(pricePerUnitYuan=null))
        val ingredient=RecipeIngredients.fromFood(db.dao().food(bread.id)!!,2.0)
        val id=recipes.save(draft().copy(ingredients=RecipeIngredients.encode(listOf(RecipeIngredients.fromFood(rice,100.0),ingredient))),true)
        val recipe=recipes.dao.recipe(id)!!
        assertNull(db.dao().food(recipe.foodId!!)!!.pricePerUnitYuan)
        val repo=Repository(db);val backup=repo.exportBackup(Preferences())
        val decoded=BackupCodec.decode(BackupCodec.encode(backup));assertEquals(backup.recipes,decoded.recipes)
        repo.restoreBackup(decoded)
        assertEquals(recipe,recipes.dao.recipe(id));assertNull(db.dao().food(recipe.foodId!!)!!.pricePerUnitYuan)
        assertEquals(backup.intakes,db.dao().allIntakes());assertEquals(backup.bodies,db.dao().allBodies())
    }

    @Test fun batchChangesOnlySelectedFieldsAndKeepsEveryOtherFoodAndHistorySnapshot()=runBlocking {
        seed();val original=db.dao().allFoods();val intakes=db.dao().allIntakes();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        val patch=FoodBatchPatch(favorite=true,allowed=false,nutritionTags="CARBS,PROTEIN")
        assertEquals(2,batch.apply(original,patch))
        assertEquals(original.map {patch.applyTo(it)},db.dao().allFoods())
        assertEquals(intakes,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
        val selected=listOf(db.dao().food(rice.id)!!)
        batch.apply(selected,FoodBatchPatch(nutritionTags=""))
        assertEquals("",db.dao().food(rice.id)!!.nutritionTags);assertEquals("CARBS,PROTEIN",db.dao().food(bread.id)!!.nutritionTags)
    }

    @Test fun oldFractionRecipeSurvivesAndOnlyNewOrChangedPortionCountsMustBeWhole()=runBlocking {
        seed()
        val legacy=draft().copy(ingredients=RecipeIngredients.encode(listOf(RecipeIngredients.fromFood(rice,100.0),RecipeIngredients.fromFood(bread,1.5))))
        val id=recipes.dao.insertRecipe(legacy)
        val saved=recipes.dao.recipe(id)!!
        recipes.save(saved.copy(name="旧配方保留",finishedWeightG=450.0),false,saved)
        assertEquals(legacy.ingredients,recipes.dao.recipe(id)!!.ingredients)
        val before=recipes.dao.allRecipes()
        assertTrue(runCatching {recipes.save(legacy,false)}.isFailure)
        assertEquals(before,recipes.dao.allRecipes())
        assertTrue(db.dao().allIntakes().all {it.unit!="份"})
    }

    @Test fun anyStaleDeletedDuplicateOrInvalidBatchSelectionRollsBackTheWholeOperation()=runBlocking {
        seed();val old=db.dao().allFoods()
        db.dao().saveFood(bread.copy(favorite=true))
        val before=db.dao().allFoods()
        assertTrue(runCatching {batch.apply(old,FoodBatchPatch(allowed=false))}.isFailure)
        assertEquals(before,db.dao().allFoods());assertTrue(db.dao().food(rice.id)!!.allowed)
        val current=db.dao().allFoods()
        assertTrue(runCatching {batch.apply(current,FoodBatchPatch(nutritionTags="INVALID"))}.isFailure)
        assertTrue(runCatching {batch.apply(listOf(current.first(),current.first()),FoodBatchPatch(favorite=true))}.isFailure)
        assertTrue(runCatching {batch.apply(emptyList(),FoodBatchPatch(favorite=true))}.isFailure)
        assertEquals(before,db.dao().allFoods())
        db.dao().markFoodDeleted(bread.id)
        val deleted=db.dao().allFoods()
        assertTrue(runCatching {batch.apply(current,FoodBatchPatch(favorite=true))}.isFailure)
        assertEquals(deleted,db.dao().allFoods())
    }
}
