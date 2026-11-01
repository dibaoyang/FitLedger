package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecipeCalculatorTest {
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private val bread=Food(8,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,pricePerUnitYuan=3.0)
    private fun ingredients()=listOf(RecipeIngredients.fromFood(rice,100.0),RecipeIngredients.fromFood(bread,1.5))

    @Test fun finishedWeightRatherThanRawWeightControlsPerHundredGramNutritionAndCost() {
        val items=ingredients()
        val result=RecipeCalculator.calculate(items,400.0)
        assertEquals(Nutrition(416.0,71.0,17.6,6.3),result.totalNutrition)
        assertEquals(result.totalNutrition*0.25,result.per100g)
        assertEquals(5.5,result.totalCostYuan!!,1e-10)
        assertEquals(0.01375,result.pricePerGramYuan!!,1e-10)
        assertEquals(1.375,result.costPer100gYuan!!,1e-10)
        assertEquals(0,result.missingPriceCount)
        val lighter=RecipeCalculator.calculate(items,200.0)
        assertEquals(result.per100g*2.0,lighter.per100g)
        assertEquals(result.totalNutrition,lighter.totalNutrition)
    }

    @Test fun unknownIngredientPriceMakesFinishedPriceUnknownButFreeIsExplicitlyZero() {
        val items=ingredients().map {if(it.foodId==bread.id) it.copy(costYuan=null) else it}
        val result=RecipeCalculator.calculate(items,400.0)
        assertNull(result.totalCostYuan);assertNull(result.pricePerGramYuan);assertNull(result.costPer100gYuan)
        assertEquals(1.0,result.knownCostYuan,0.0);assertEquals(1,result.missingPriceCount)
        val free=RecipeCalculator.calculate(items.map {it.copy(costYuan=0.0)},400.0)
        assertEquals(0.0,free.pricePerGramYuan!!,0.0);assertEquals(0,free.missingPriceCount)
    }

    @Test fun resizingUsesTheFrozenIngredientSnapshotAndRetainsUnknownPrice() {
        val original=RecipeIngredients.fromFood(bread.copy(pricePerUnitYuan=null),1.5)
        val resized=original.resized(3.0)
        assertEquals(original.nutrition*2.0,resized.nutrition)
        assertEquals(original.name,resized.name);assertEquals(original.unit,resized.unit);assertNull(resized.costYuan)
        val current=RecipeIngredients.fromFood(bread.copy(nutrition=bread.nutrition*10.0,name="当前新面包"),3.0)
        assertNotEquals(current.nutrition,resized.nutrition)
        assertEquals(original,resized.resized(1.5))
    }

    @Test fun ingredientCodecRoundTripsNativeQuantitiesPreciseSnapshotsAndNullCosts() {
        val original=ingredients().map {it.copy(quantity=it.quantity*1.23456789,costYuan=null)}
        assertEquals(original,RecipeIngredients.decode(RecipeIngredients.encode(original)))
        val encoded=JSONArray(RecipeIngredients.encode(original))
        assertTrue(encoded.getJSONObject(0).has("costYuan"));assertTrue(encoded.getJSONObject(0).isNull("costYuan"))
        assertEquals("份",encoded.getJSONObject(1).getString("unit"))
    }

    @Test fun malformedIngredientJsonCannotCreateAnEstimatedFood() {
        fun bad(change:(JSONObject)->Unit) {
            val a=JSONArray(RecipeIngredients.encode(ingredients()));change(a.getJSONObject(0))
            assertTrue(a.toString(),runCatching {RecipeIngredients.decode(a.toString())}.isFailure)
        }
        bad {it.put("foodId",7.5)};bad {it.put("foodId","7")};bad {it.put("unit","kg")}
        bad {it.put("quantity",0)};bad {it.put("quantity","100")};bad {it.put("costYuan","0")}
        bad {it.remove("costYuan")};bad {it.put("name","bad\nname")};bad {it.put("name",JSONObject.NULL)}
        bad {it.getJSONObject("nutrition").put("carbs",-1)}
        bad {it.getJSONObject("nutrition").put("fat",JSONObject.NULL)}
        bad {it.getJSONObject("nutrition").put("kcal","116")}
        bad {it.put("unknown",true)}
        assertTrue(runCatching {RecipeIngredients.decode("[]")}.isFailure)
        assertTrue(runCatching {RecipeIngredients.encode(listOf(ingredients().first(),ingredients().first()))}.isFailure)
    }

    @Test fun invalidYieldAndNonFiniteTotalsAreRejectedWithoutClamping() {
        for(weight in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.MIN_VALUE)) {
            assertTrue(runCatching {RecipeCalculator.calculate(ingredients(),weight)}.isFailure)
        }
        val huge=ingredients().map {it.copy(nutrition=Nutrition(Double.MAX_VALUE),costYuan=Double.MAX_VALUE)}
        assertTrue(runCatching {RecipeCalculator.calculate(huge,100.0)}.isFailure)
        assertTrue(runCatching {ingredients().first().resized(Double.POSITIVE_INFINITY)}.isFailure)
        assertTrue(runCatching {RecipeIngredients.fromFood(rice.copy(isDeleted=true),100.0)}.isFailure)
        assertTrue(runCatching {RecipeIngredients.fromFood(rice,0.0)}.isFailure)
    }

    @Test fun recipeValidationPreventsBadNamesSelfReferencesAndInvalidSavedSnapshots() {
        val valid=FoodRecipe(name="自制配方",ingredients=RecipeIngredients.encode(ingredients()),finishedWeightG=400.0)
        valid.validate()
        listOf(valid.copy(name=""),valid.copy(name=" padded "),valid.copy(name="x".repeat(101)),
            valid.copy(foodId=7),valid.copy(foodId=0),valid.copy(ingredients="[]"),valid.copy(finishedWeightG=Double.NaN)).forEach {
            assertTrue(runCatching {it.validate()}.isFailure)
        }
    }

    @Test fun batchPatchChangesOnlyChosenFieldsAndPreservesUnitsPricesAliasesAndNutrients() {
        val original=rice.copy(favorite=true,aliases="[\"常吃的饭\"]",nutritionTags="CARBS",sortOrder=5,quickAmounts="100,200")
        assertEquals(original.copy(allowed=false),FoodBatchPatch(allowed=false).applyTo(original))
        assertEquals(original.copy(favorite=false,nutritionTags=""),FoodBatchPatch(favorite=false,nutritionTags="").applyTo(original))
        val updated=FoodBatchPatch(nutritionTags="CARBS,PROTEIN").applyTo(original)
        assertEquals(original.copy(nutritionTags="CARBS,PROTEIN"),updated)
        assertEquals(original.nutrition,updated.nutrition);assertEquals(original.pricePerUnitYuan,updated.pricePerUnitYuan)
    }

    @Test fun batchPatchRequiresAnExplicitFieldAndValidDistinctNutrientTags() {
        assertTrue(runCatching {FoodBatchPatch().validate()}.isFailure)
        for(tags in listOf("UNKNOWN","CARBS,CARBS","CARBS,","protein"," CARBS")) {
            assertTrue(runCatching {FoodBatchPatch(nutritionTags=tags).validate()}.isFailure)
        }
        FoodBatchPatch(nutritionTags="").validate()
        assertEquals(null,FoodBatchPatch(favorite=true).applyTo(rice.copy(pricePerUnitYuan=null)).pricePerUnitYuan)
    }
}
