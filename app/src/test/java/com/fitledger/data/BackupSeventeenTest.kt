package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class BackupSeventeenTest {
    private val day=20000L
    private val rice=Food(1,"米饭","g",100.0,Nutrition(120.0,25.0,3.0,0.8),10.0,500.0,true,
        zoneId=7,pricePerUnitYuan=0.01,minimum=100.0,preparationTags="微波,下锅")
    private val bread=Food(2,"旧面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,5.0,true)
    private val rules=RecommendationRules(listOf(FoodCycleRule(1,5,2,300.0)),
        listOf(ConditionalFoodRule("rule","FOOD",1,"REQUIRE_ZONE",7)),setOf("微波"))
    private fun fixture():Backup {
        val ingredient=RecipeIngredients.fromFood(rice,200.0)
        val ingredients=RecipeIngredients.encode(listOf(ingredient))
        val summary=RecipeCalculator.calculate(listOf(ingredient),300.0)
        val extras=RecommendationExtras(rules=rules)
        return Backup(exportTime="2026-11-01T00:00:00Z",appVersion="1.10",
            bodies=listOf(BodyRecord(day,60.0,20.0,48.0,null)),
            targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,150.0,60.0),PlanCodec.encode(Plan(start=day)))),
            foods=listOf(rice,bread,Food(3,"第一批米饭","g",100.0,summary.per100g,10.0,500.0,true,pricePerUnitYuan=summary.pricePerGramYuan)),
            intakes=listOf(Intake(10,day,2,"旧的半份记录","份",0.5,bread.nutrition*0.5,costYuan=null),
                Intake(11,day,1,rice.name,"g",100.0,rice.nutrition,costYuan=1.0)),
            zones=listOf(FoodZone(7,"主食",limitUnit="G")),
            preferences=Preferences(energyUnit=EnergyUnit.KJ,hasReadIntro=true,recommendationExtras=extras,
                recommendationProfiles=listOf(RecommendationProfile("dorm","宿舍日",RecommendationPolicy(dailyBudgetYuan=30.0),extras))),
            pendingFoods=listOf(PendingFood(20,day,"group",1,rice.name,"g",100.0,rice.nutrition,1.0,11,1),
                PendingFood(21,day,"group",2,bread.name,"份",2.0,bread.nutrition*2.0,null,null,1)),
            recipeBatches=listOf(RecipeBatch(30,999,"第一批",day,ingredients,300.0,RecipeBoxes.encode(listOf(150.0,150.0),300.0),summary.totalNutrition,summary.totalCostYuan,3)))
    }

    @Test fun completeSeventeenBackupRoundTripPreservesPendingProfilesBatchesAndLegacyFractions() {
        val b=fixture();assertEquals(17,b.backupVersion);assertEquals(13,b.databaseVersion)
        val restored=BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(b,restored);assertEquals(0.5,restored.intakes.first().quantity,0.0)
        assertNull(restored.intakes.first().costYuan)
        assertEquals(999L,restored.recipeBatches.single().recipeId)
        assertEquals(b.targets,restored.targets);assertEquals(b.bodies,restored.bodies)
    }

    @Test fun oldSixteenFormatDoesNotNeedNewKeysAndRetainsLegacyIntakePrecisely() {
        val old=fixture().let {it.copy(backupVersion=16,databaseVersion=12,pendingFoods=emptyList(),recipeBatches=emptyList(),
            foods=it.foods.map {f->f.copy(minimum=0.0,preparationTags="")},preferences=it.preferences.copy(recommendationProfiles=emptyList(),recommendationExtras=RecommendationExtras(varietyDays=3)))}
        val encoded=BackupCodec.encode(old)
        val data=JSONObject(JSONObject(encoded).getString("payload"))
        assertFalse(data.has("pendingFoods"));assertFalse(data.has("recipeBatches"))
        assertFalse(data.getJSONObject("preferences").has("recommendationProfiles"))
        assertFalse(data.getJSONObject("preferences").getJSONObject("recommendationExtras").has("rules"))
        assertFalse(data.getJSONArray("foods").getJSONObject(0).has("minimum"))
        assertEquals(old,BackupCodec.decode(encoded))
    }

    @Test fun deletedActualIntakeKeepsTheConfirmedPendingMarkerWithoutMakingItPendingAgain() {
        val b=fixture().let {it.copy(intakes=it.intakes.filterNot {row->row.id==11L})}
        val restored=BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(11L,restored.pendingFoods.first().appliedIntakeId)
        assertFalse(restored.intakes.any {it.id==11L})
    }

    @Test fun everyNewFeatureRejectsMetadataDowngradeInsteadOfLosingUserData() {
        val clean=fixture().let {it.copy(pendingFoods=emptyList(),recipeBatches=emptyList(),
            foods=it.foods.map {f->f.copy(minimum=0.0,preparationTags="")},preferences=Preferences())}
        val cases=listOf(clean.copy(pendingFoods=fixture().pendingFoods),clean.copy(recipeBatches=fixture().recipeBatches),
            clean.copy(foods=clean.foods.map {if(it.id==1L) it.copy(minimum=100.0) else it}),
            clean.copy(foods=clean.foods.map {if(it.id==1L) it.copy(preparationTags="自己设的条件") else it}),
            clean.copy(preferences=fixture().preferences),clean.copy(preferences=Preferences(recommendationProfiles=fixture().preferences.recommendationProfiles)))
        cases.forEach {b->
            assertTrue(runCatching {BackupCodec.encode(b.copy(backupVersion=16))}.isFailure)
            assertTrue(runCatching {BackupCodec.encode(b.copy(databaseVersion=12))}.isFailure)
        }
    }

    @Test fun seventeenRequiresAllNewDataAndSettingsFields() {
        rejects {it.remove("pendingFoods")};rejects {it.remove("recipeBatches")}
        rejects {it.getJSONObject("preferences").remove("recommendationProfiles")}
        rejects {it.getJSONObject("preferences").getJSONObject("recommendationExtras").remove("rules")}
        rejects {it.getJSONArray("foods").getJSONObject(0).remove("minimum")}
        rejects {it.getJSONArray("foods").getJSONObject(0).remove("preparationTags")}
        rejects {it.getJSONArray("pendingFoods").getJSONObject(0).remove("costYuan")}
        rejects {it.getJSONArray("recipeBatches").getJSONObject(0).remove("recipeId")}
    }

    @Test fun malformedAndDanglingReferencesFailBeforeAnyRestoreWrite() {
        rejects {it.getJSONArray("pendingFoods").getJSONObject(0).put("foodId",999)}
        rejects {it.getJSONArray("pendingFoods").getJSONObject(1).put("quantity",0.5)}
        rejects {it.getJSONArray("pendingFoods").getJSONObject(1).put("appliedIntakeId",11)}
        rejects {it.getJSONArray("pendingFoods").getJSONObject(0).put("createdAt","1")}
        rejects {it.getJSONArray("recipeBatches").getJSONObject(0).put("foodId",999)}
        rejects {it.getJSONArray("recipeBatches").getJSONObject(0).put("boxWeights","[150,100]")}
        rejects {it.getJSONArray("recipeBatches").getJSONObject(0).getJSONObject("nutrition").put("carbs",999)}
        rejects {it.getJSONObject("preferences").getJSONObject("recommendationExtras").getJSONObject("rules").getJSONArray("cycleRules").getJSONObject(0).put("foodId",999)}
    }

    @Test fun changedCurrentFoodDataNeverRecalculatesBatchOrPendingSnapshotsOnRestore() {
        val b=fixture().let {it.copy(foods=it.foods.map {f->if(f.id==1L) f.copy(name="后来改名",nutrition=f.nutrition*3.0,pricePerUnitYuan=9.0) else f})}
        val restored=BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(b.recipeBatches,restored.recipeBatches);assertEquals(b.pendingFoods,restored.pendingFoods)
        assertEquals(1.0,restored.pendingFoods.first().costYuan!!,0.0)
    }

    private fun rejects(change:(JSONObject)->Unit) {
        val root=JSONObject(BackupCodec.encode(fixture()));val data=JSONObject(root.getString("payload"));change(data)
        val payload=data.toString();root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
}
