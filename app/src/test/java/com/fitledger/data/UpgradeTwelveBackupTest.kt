package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Upgrade/restore contracts cover frozen snapshots rather than recomputing them from today's foods. */
class UpgradeTwelveBackupTest {
    private val day=20000L
    private val rice=Food(7,"当前米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,
        pricePerUnitYuan=0.01,aliases="[\"常吃的饭\"]")
    private val chicken=Food(8,"鸡肉","g",100.0,Nutrition(120.0,0.0,25.0,2.0),10.0,500.0,true)
    private val ratios=WeightRatios.encode(listOf(WeightRatio(8,7,0.5)))
    private fun fixture():Backup {
        val frozenRice=rice.copy(name="配方里保存的旧米饭",nutrition=Nutrition(110.0,24.0,2.0,0.1),pricePerUnitYuan=0.008)
        val ingredients=listOf(RecipeIngredients.fromFood(frozenRice,200.0),RecipeIngredients.fromFood(chicken,100.0))
        return Backup(exportTime="2026-10-30T00:00:00Z",appVersion="1.10",
            bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,null)),
            targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan(start=day)))),
            foods=listOf(rice,chicken,rice.copy(id=9,name="自制鸡肉饭",aliases="",pricePerUnitYuan=null)),
            intakes=listOf(Intake(17,day,rice.id,"原来的食品摄入名","g",150.0,Nutrition(150.0,33.0,3.0,0.15),costYuan=1.0)),
            preferences=Preferences(energyUnit=EnergyUnit.KJ,weightInputUnit=WeightUnit.JIN,
                recommendationExtras=RecommendationExtras(180.0,true,7,setOf(31),WeightRatios.decode(ratios))),
            combos=listOf(FoodCombo(31,"整套鸡肉饭",ComboCodec.encode(listOf(ComboItem(7,"g",200.0),ComboItem(8,"g",100.0))),ratios)),
            presets=listOf(FoodPresetPlan(day+7,day,2635.0,72.4,ratios)),
            presetItems=listOf(FoodPresetItem(71,day+7,7,"预设保存的米饭名","g",200.0,rice.nutrition*2.0,2.0),
                FoodPresetItem(72,day+7,8,"鸡肉","g",100.0,chicken.nutrition,null)),
            recipes=listOf(FoodRecipe(41,"自制鸡肉饭",RecipeIngredients.encode(ingredients),350.0,9)))
    }

    @Test fun sixteenRoundTripsRecipesRatiosFuturePlansAndExtrasWithoutRecalculatingSnapshots() {
        val original=fixture()
        assertEquals(17,original.backupVersion);assertEquals(13,original.databaseVersion)
        val restored=BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original,restored)
        val ingredient=RecipeIngredients.decode(restored.recipes.single().ingredients).first()
        assertEquals("配方里保存的旧米饭",ingredient.name)
        assertEquals(220.0,ingredient.nutrition.kcal,0.0)
        assertNotEquals(restored.foods.first().nutrition*2.0,ingredient.nutrition)
        assertNull(RecipeCalculator.calculate(RecipeIngredients.decode(restored.recipes.single().ingredients),350.0).totalCostYuan)
        assertEquals(original.bodies,restored.bodies);assertEquals(original.targets,restored.targets);assertEquals(original.intakes,restored.intakes)
        assertEquals(7L,restored.presets.single().day-restored.presets.single().referenceDay)
    }

    @Test fun releasedFifteenWithoutNewFieldsGetsOnlySafeDefaultsAndRetainsAllLegacyData() {
        val original=fixture().let {b->b.copy(backupVersion=15,databaseVersion=11,recipes=emptyList(),
            preferences=b.preferences.copy(recommendationExtras=RecommendationExtras()),
            combos=b.combos.map {it.copy(ratioRules="")},presets=b.presets.map {it.copy(day=day+1,ratioRules="")},
            presetItems=b.presetItems.map {it.copy(day=day+1)})}
        val root=JSONObject(BackupCodec.encode(original))
        edit(root) {data->
            data.remove("recipes");data.getJSONObject("preferences").remove("recommendationExtras")
            data.getJSONArray("combos").getJSONObject(0).remove("ratioRules")
            data.getJSONArray("presets").getJSONObject(0).remove("ratioRules")
        }
        val restored=BackupCodec.decode(root.toString())
        assertEquals(original,restored)
        assertEquals(RecommendationExtras(),restored.preferences.recommendationExtras)
        assertEquals(original.foods,restored.foods);assertEquals(original.presetItems,restored.presetItems)
    }

    @Test fun eachNewFeatureIndependentlyRejectsLossyBackupOrDatabaseDowngrade() {
        val clean=fixture().let {b->b.copy(recipes=emptyList(),preferences=b.preferences.copy(recommendationExtras=RecommendationExtras()),
            combos=b.combos.map {it.copy(ratioRules="")},presets=b.presets.map {it.copy(day=day+1,ratioRules="")},
            presetItems=b.presetItems.map {it.copy(day=day+1)})}
        val originals=listOf(clean.copy(recipes=fixture().recipes),clean.copy(combos=fixture().combos),
            clean.copy(presets=fixture().presets,presetItems=fixture().presetItems),
            clean.copy(presets=clean.presets.map {it.copy(ratioRules=ratios)}),
            clean.copy(preferences=clean.preferences.copy(recommendationExtras=RecommendationExtras(100.0))),
            clean.copy(preferences=clean.preferences.copy(recommendationExtras=RecommendationExtras(varietyDays=7))),
            clean.copy(preferences=clean.preferences.copy(recommendationExtras=RecommendationExtras(comboIds=setOf(31)))),
            clean.copy(preferences=clean.preferences.copy(recommendationExtras=RecommendationExtras(ratios=WeightRatios.decode(ratios)))))
        originals.forEach {b->
            assertTrue(runCatching {BackupCodec.encode(b.copy(backupVersion=15))}.isFailure)
            assertTrue(runCatching {BackupCodec.encode(b.copy(databaseVersion=11))}.isFailure)
        }
        assertEquals(clean.copy(backupVersion=15,databaseVersion=11),BackupCodec.decode(BackupCodec.encode(clean.copy(backupVersion=15,databaseVersion=11))))
    }

    @Test fun sixteenRequiresAllNewArraysSettingsAndRatioFieldsBeforeRestore() {
        rejected {it.remove("recipes")}
        rejected {it.getJSONObject("preferences").remove("recommendationExtras")}
        rejected {it.getJSONArray("combos").getJSONObject(0).remove("ratioRules")}
        rejected {it.getJSONArray("presets").getJSONObject(0).remove("ratioRules")}
        rejected {it.getJSONArray("recipes").getJSONObject(0).remove("foodId")}
        for(value in listOf<Any>(JSONObject.NULL,1,true,JSONArray())) {
            rejected {it.getJSONArray("combos").getJSONObject(0).put("ratioRules",value)}
            rejected {it.getJSONArray("presets").getJSONObject(0).put("ratioRules",value)}
            rejected {it.getJSONObject("preferences").put("recommendationExtras",value)}
        }
    }

    @Test fun recipesRejectCorruptIdsDuplicateLinksMissingIngredientsAndImpossibleYields() {
        for(key in listOf("id","foodId")) for(value in listOf<Any>("41",0,-1,1.5,true)) {
            rejected {it.getJSONArray("recipes").getJSONObject(0).put(key,value)}
        }
        for(value in listOf<Any>("350",0,-1,JSONObject.NULL)) {
            rejected {it.getJSONArray("recipes").getJSONObject(0).put("finishedWeightG",value)}
        }
        rejected {it.getJSONArray("recipes").getJSONObject(0).put("foodId",999)}
        rejected {it.getJSONArray("recipes").getJSONObject(0).put("ingredients","[]")}
        rejected {data->
            val recipe=data.getJSONArray("recipes").getJSONObject(0)
            val ingredients=JSONArray(recipe.getString("ingredients"));ingredients.getJSONObject(0).put("foodId",999)
            recipe.put("ingredients",ingredients.toString())
        }
        val b=fixture()
        assertTrue(runCatching {b.copy(recipes=b.recipes+b.recipes.single().copy(id=42)).validate()}.isFailure)
        assertTrue(runCatching {b.copy(recipes=b.recipes+b.recipes).validate()}.isFailure)
    }

    @Test fun deletedFoodAndUnlinkedRecipeRemainRecoverableWithoutInventingCurrentNutrition() {
        val b=fixture().let {it.copy(foods=it.foods.map {f->f.copy(isDeleted=true)},recipes=it.recipes.map {r->r.copy(foodId=null)})}
        val restored=BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(b,restored);assertNull(restored.recipes.single().foodId)
        assertTrue(restored.foods.all {it.isDeleted})
        val data=JSONObject(JSONObject(BackupCodec.encode(b)).getString("payload"))
        assertTrue(data.getJSONArray("recipes").getJSONObject(0).isNull("foodId"))
    }

    @Test fun extrasReferencesAndPresetRatioReferencesMustExistBeforeAnyRestoreWrite() {
        val b=fixture()
        assertTrue(runCatching {b.copy(preferences=b.preferences.copy(recommendationExtras=RecommendationExtras(comboIds=setOf(999)))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(preferences=b.preferences.copy(recommendationExtras=RecommendationExtras(ratios=listOf(WeightRatio(999,7,0.5))))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=b.presetItems.take(1)).validate()}.isFailure)
        assertTrue(runCatching {b.copy(combos=b.combos.map {it.copy(ratioRules=WeightRatios.encode(listOf(WeightRatio(9,7,0.5))))}).validate()}.isFailure)
        rejected {it.getJSONObject("preferences").getJSONObject("recommendationExtras").put("weeklyBudgetYuan","180")}
        rejected {it.getJSONObject("preferences").getJSONObject("recommendationExtras").put("comboIds",JSONArray(listOf(31,31)))}
        rejected {it.getJSONObject("preferences").getJSONObject("recommendationExtras").put("shareWeekRemainder",1)}
        rejected {it.getJSONObject("preferences").getJSONObject("recommendationExtras").put("varietyDays",1.5)}
        rejected {it.getJSONObject("preferences").getJSONObject("recommendationExtras").put("unknown",true)}
    }

    @Test fun unversionedLegacySettingsJournalDefaultsExtensionsOffAndKeepsExistingPreferences() {
        val original=fixture().preferences.copy(recommendationExtras=RecommendationExtras())
        val legacy=JSONObject(PreferencesCodec.encode(original)).apply {remove("recommendationExtras")}
        assertEquals(original,PreferencesCodec.decode(legacy.toString()))
        val extras=fixture().preferences.recommendationExtras
        assertEquals(extras,RecommendationExtrasCodec.decode(RecommendationExtrasCodec.encode(extras)))
        assertEquals(fixture().preferences,PreferencesCodec.decode(PreferencesCodec.encode(fixture().preferences)))
    }

    private fun rejected(change:(JSONObject)->Unit) {
        val root=JSONObject(BackupCodec.encode(fixture()));edit(root,change)
        assertTrue(root.toString(),runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
    private fun edit(root:JSONObject,change:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));change(data);val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
