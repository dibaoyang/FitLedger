package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RecipeBatchCalculatorTest {
    private val potato=Food(7,"土豆泥食材","g",100.0,Nutrition(80.0,18.0,2.0,0.0),10.0,1000.0,true,pricePerUnitYuan=0.01)
    private val dairy=Food(8,"牛奶","份",1.0,Nutrition(120.0,10.0,8.0,5.0),1.0,5.0,true,pricePerUnitYuan=3.0)
    private fun batch(yield:Double=900.0,costMissing:Boolean=false):RecipeBatch {
        val ingredients=listOf(RecipeIngredients.fromFood(potato,600.0),RecipeIngredients.fromFood(if(costMissing)dairy.copy(pricePerUnitYuan=null)else dairy,1.0))
        val result=RecipeCalculator.calculate(ingredients,yield)
        return RecipeBatch(recipeId=41,name="土豆泥第一批",createdDay=20000,ingredients=RecipeIngredients.encode(ingredients),finishedWeightG=yield,
            boxWeights=RecipeBoxes.encode(listOf(yield/3,yield/3,yield/3),yield),nutrition=result.totalNutrition,costYuan=result.totalCostYuan)
    }
    @Test fun eachBatchUsesItsOwnYieldAndBoxWeightsWithoutChangingItsIngredientsOrTotal() {
        val first=batch();val second=batch(600.0)
        assertEquals(Nutrition(600.0,118.0,20.0,5.0),first.nutrition)
        assertEquals(first.ingredients,second.ingredients);assertEquals(first.nutrition,second.nutrition)
        (first.summary().per100g*1.5).values().zip(second.summary().per100g.values()).forEach {(a,b)->assertEquals(a,b,1e-9)}
        assertEquals(9.0,first.costYuan!!,0.0)
        first.boxNutrition().forEach {assertEquals(first.nutrition*(1.0/3),it)}
        assertEquals(3,first.boxNutrition().size)
    }
    @Test fun unequalBoxesGetWeightProportionalNutritionAndAllMassMustBeAllocated() {
        val batch=batch().copy(boxWeights=RecipeBoxes.encode(listOf(200.0,300.0,400.0),900.0))
        assertEquals(batch.nutrition*(200.0/900),batch.boxNutrition()[0])
        assertEquals(batch.nutrition*(400.0/900),batch.boxNutrition()[2])
        assertTrue(runCatching {RecipeBoxes.encode(listOf(200.0,300.0),900.0)}.isFailure)
        assertTrue(runCatching {RecipeBoxes.encode(listOf(500.0,500.0),900.0)}.isFailure)
    }
    @Test fun unknownCostRemainsUnknownAndFreeIsAnExplicitKnownZero() {
        val batch=batch(costMissing=true);assertNull(batch.costYuan);assertNull(batch.summary().pricePerGramYuan)
        assertTrue(runCatching {batch.copy(costYuan=0.0).validate()}.isFailure)
        val items=RecipeIngredients.decode(batch.ingredients).map {it.copy(costYuan=0.0)}
        val free=batch.copy(ingredients=RecipeIngredients.encode(items),costYuan=0.0)
        assertEquals(0.0,free.summary().pricePerGramYuan!!,0.0)
    }
    @Test fun boxCodecRejectsMalformedNonFiniteNegativeAndExcessiveInputs() {
        listOf("[\"900\"]","[0]","[-1]","[null]","[1,2]").forEach {assertTrue(runCatching {RecipeBoxes.decode(it,900.0)}.isFailure)}
        assertTrue(runCatching {RecipeBoxes.encode(listOf(Double.NaN),900.0)}.isFailure)
        assertTrue(runCatching {RecipeBoxes.encode(List(1001){1.0},1001.0)}.isFailure)
        assertEquals(emptyList<Double>(),RecipeBoxes.decode("[]",900.0))
    }
    @Test fun frozenBatchCannotContainInconsistentTotalsCostOrItsOwnGeneratedFood() {
        val original=batch()
        listOf(original.copy(nutrition=original.nutrition*2.0),original.copy(costYuan=0.0),original.copy(name=" padded "),original.copy(foodId=7),
            original.copy(recipeId=0),original.copy(finishedWeightG=0.0),original.copy(boxWeights=JSONArray(listOf(100.0)).toString())).forEach {
            assertTrue(runCatching {it.validate()}.isFailure)
        }
    }
    @Test fun deletedRecipeProvenanceAndLegacyFrozenIngredientsAreSelfContained() {
        val original=batch()
        original.copy(recipeId=999999).validate()
        original.copy(recipeId=null).validate()
        val legacy=RecipeIngredients.decode(original.ingredients).map {if(it.unit=="份")it.resized(1.5)else it}
        val result=RecipeCalculator.calculate(legacy,900.0)
        original.copy(ingredients=RecipeIngredients.encode(legacy),nutrition=result.totalNutrition,costYuan=result.totalCostYuan).validate()
    }
}
