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
class RecipeBatchStoreTest {
    private lateinit var db:LedgerDatabase
    private lateinit var store:RecipeBatchStore
    private val today get()=LocalDate.now().toEpochDay()
    private val food=Food(1,"土豆","g",100.0,Nutrition(80.0,18.0,2.0,0.0),10.0,1000.0,true,pricePerUnitYuan=0.01)
    @Before fun open() {db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LedgerDatabase::class.java).build();store=RecipeBatchStore(db)}
    @After fun close()=db.close()
    private suspend fun seed():FoodRecipe {
        db.dao().saveFood(food)
        db.dao().insertBody(BodyRecord(today,60.0,20.0,48.0,null))
        db.dao().insertTarget(DailyTarget(today,Nutrition(2000.0,250.0,150.0,60.0),PlanCodec.encode(Plan())))
        val recipe=FoodRecipe(name="自制土豆泥",ingredients=RecipeIngredients.encode(listOf(RecipeIngredients.fromFood(food,600.0))),finishedWeightG=900.0)
        val id=RecipeStore(db).save(recipe,false)
        return db.recipeDao().recipe(id)!!
    }
    private fun draft(recipe:FoodRecipe,yieldG:Double=900.0,name:String="第一批"):RecipeBatch {
        val summary=RecipeCalculator.calculate(RecipeIngredients.decode(recipe.ingredients),yieldG)
        return RecipeBatch(recipeId=recipe.id,name=name,createdDay=today,ingredients=recipe.ingredients,finishedWeightG=yieldG,
            boxWeights=RecipeBoxes.encode(listOf(yieldG/3,yieldG/3,yieldG/3),yieldG),nutrition=summary.totalNutrition,costYuan=summary.totalCostYuan)
    }
    @Test fun distinctCookingBatchesCreateDistinctFoodsAndNeverRewriteEarlierBatchOrConsumption()=runBlocking {
        val recipe=seed();val bodies=db.dao().allBodies();val targets=db.dao().allTargets()
        val firstId=store.save(draft(recipe),recipe,true)
        val first=store.dao.batch(firstId)!!;val firstFood=db.dao().food(first.foodId!!)!!
        FeatureStore(db).recordFood(today,firstFood.id,300.0)
        val intake=db.dao().allIntakes()
        val secondId=store.save(draft(recipe,600.0,"第二批"),recipe,true)
        val second=store.dao.batch(secondId)!!
        assertNotEquals(first.foodId,second.foodId)
        assertEquals(first,store.dao.batch(firstId));assertEquals(firstFood,db.dao().food(firstFood.id))
        assertEquals(firstFood.nutrition*1.5,db.dao().food(second.foodId!!)!!.nutrition)
        assertEquals(intake,db.dao().allIntakes());assertEquals(bodies,db.dao().allBodies());assertEquals(targets,db.dao().allTargets())
    }
    @Test fun batchSnapshotSurvivesRecipeEditDeletionAndChangedIngredientPrice()=runBlocking {
        val recipe=seed();val id=store.save(draft(recipe),recipe,true);val saved=store.dao.batch(id)!!
        db.dao().saveFood(food.copy(nutrition=food.nutrition*10.0,pricePerUnitYuan=1.0))
        RecipeStore(db).save(recipe.copy(finishedWeightG=400.0),false,recipe)
        val updated=db.recipeDao().recipe(recipe.id)!!
        RecipeStore(db).delete(updated)
        assertEquals(saved,store.dao.batch(id));assertEquals(480.0,saved.nutrition.kcal,0.0);assertEquals(6.0,saved.costYuan!!,0.0)
        assertNotNull(db.dao().food(saved.foodId!!))
        val backup=BackupCodec.decode(BackupCodec.encode(Repository(db).exportBackup(Preferences())))
        Repository(db).restoreBackup(backup)
        assertEquals(saved,store.dao.batch(id));assertTrue(db.recipeDao().allRecipes().isEmpty())
    }
    @Test fun staleRecipeBadAllocationAndDuplicateBatchIdentityFailBeforeAnyFoodOrBatchWrite()=runBlocking {
        val recipe=seed();val draft=draft(recipe);val before=db.dao().allFoods()
        assertTrue(runCatching {store.save(draft.copy(boxWeights="[100]"),recipe,true)}.isFailure)
        assertTrue(runCatching {store.save(draft.copy(foodId=food.id),recipe,true)}.isFailure)
        RecipeStore(db).save(recipe.copy(name="更新后的配方"),false,recipe)
        assertTrue(runCatching {store.save(draft,recipe,true)}.isFailure)
        assertTrue(store.dao.allBatches().isEmpty());assertEquals(before,db.dao().allFoods())
    }
    @Test fun missingIngredientCostStaysUnknownAndDeletingBatchKeepsItsFoodAndActualHistory()=runBlocking {
        db.dao().saveFood(food.copy(pricePerUnitYuan=null))
        val recipe=seed().let {old->
            val ingredient=RecipeIngredients.fromFood(db.dao().food(food.id)!!.copy(pricePerUnitYuan=null),600.0)
            db.dao().saveFood(food.copy(pricePerUnitYuan=null))
            val revised=old.copy(ingredients=RecipeIngredients.encode(listOf(ingredient)))
            RecipeStore(db).save(revised,false,old);db.recipeDao().recipe(old.id)!!
        }
        val id=store.save(draft(recipe),recipe,true);val batch=store.dao.batch(id)!!
        assertNull(batch.costYuan);assertNull(db.dao().food(batch.foodId!!)!!.pricePerUnitYuan)
        FeatureStore(db).recordFood(today,batch.foodId!!,100.0)
        val before=db.dao().allIntakes();store.delete(batch)
        assertTrue(store.dao.allBatches().isEmpty());assertNotNull(db.dao().food(batch.foodId!!));assertEquals(before,db.dao().allIntakes())
    }
}
