package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class FoodPriceBackupTest {
    private val food=Food(7,"面包","份",1.0,Nutrition(200.0,35.0,9.0,3.0),1.0,10.0,true,
        nutritionTags="CARBS,PROTEIN",pricePerUnitYuan=2.5)
    private fun fixture()=Backup(bodies=listOf(BodyRecord(20000,72.4,15.2,61.3952,58.1)),
        targets=listOf(DailyTarget(20000,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(food),intakes=listOf(Intake(9,20000,7,"旧面包名称","份",2.0,Nutrition(400.0,70.0,18.0,6.0),costYuan=3.0)),
        preferences=Preferences(hasReadIntro=true,recommendationPolicy=RecommendationPolicy(20.0,MacroTolerance(10.0,0.0),MacroTolerance(0.0,20.0),MacroTolerance(null,0.0))))

    @Test fun roundTripPreservesMultipleTagsAndHistoricalCostsWithoutRepricing() {
        val original=fixture()
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        assertEquals(3.0,BackupCodec.decode(BackupCodec.encode(original)).intakes.single().costYuan!!,0.0)
        val free=original.copy(foods=listOf(food.copy(pricePerUnitYuan=0.0)),intakes=original.intakes.map {it.copy(costYuan=0.0)})
        assertEquals(free,BackupCodec.decode(BackupCodec.encode(free)))
    }
    @Test fun oldVersionsDefaultUnknownPricesAndTagsWithoutInventingHistory() {
        val original=fixture().copy(foods=listOf(food.copy(nutritionTags="",pricePerUnitYuan=null)),
            intakes=fixture().intakes.map {it.copy(costYuan=null)},preferences=Preferences(hasReadIntro=true))
        for(version in 1..9) {
            val root=JSONObject(BackupCodec.encode(original)).put("backupVersion",version).put("databaseVersion",6)
            edit(root) {data ->
                data.getJSONArray("foods").getJSONObject(0).apply {remove("nutritionTags");remove("pricePerUnitYuan")}
                data.getJSONArray("intakes").getJSONObject(0).remove("costYuan")
                data.getJSONObject("preferences").remove("recommendationPolicy")
            }
            val restored=BackupCodec.decode(root.toString())
            assertEquals(original.copy(backupVersion=version,databaseVersion=6),restored)
            assertEquals("",restored.foods.single().nutritionTags);assertNull(restored.foods.single().pricePerUnitYuan);assertNull(restored.intakes.single().costYuan)
        }
    }
    @Test fun rejectsDowngradesAndInvalidTagsOrCostValues() {
        val original=fixture()
        assertTrue(runCatching {original.copy(backupVersion=9).validate()}.isFailure)
        assertTrue(runCatching {original.copy(databaseVersion=6).validate()}.isFailure)
        for(tags in listOf("CARBS,CARBS","CARBS,","CARBS, FAT","BAD","carbs")) assertTrue(runCatching {food.copy(nutritionTags=tags).validate()}.isFailure)
        for(price in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE)) assertTrue(runCatching {food.copy(pricePerUnitYuan=price).validate()}.isFailure)
        for(cost in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertTrue(runCatching {original.copy(intakes=original.intakes.map {it.copy(costYuan=cost)}).validate()}.isFailure)
        food.copy(nutritionTags="CARBS,PROTEIN,FAT",pricePerUnitYuan=0.0).validate()
    }
    @Test fun newVersionRequiresExplicitFieldsAndStrictTypes() {
        fun bad(block:(JSONObject)->Unit) {
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,block)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
        bad {it.getJSONArray("foods").getJSONObject(0).remove("nutritionTags")}
        bad {it.getJSONArray("foods").getJSONObject(0).remove("pricePerUnitYuan")}
        bad {it.getJSONArray("intakes").getJSONObject(0).remove("costYuan")}
        bad {it.getJSONArray("foods").getJSONObject(0).put("nutritionTags",0)}
        bad {it.getJSONArray("foods").getJSONObject(0).put("pricePerUnitYuan","2.5")}
        bad {it.getJSONArray("intakes").getJSONObject(0).put("costYuan","3")}
    }
    @Test fun foodCodesDoNotTransferLocalPriceOrNutritionTags() {
        val decoded=FoodShareCodec.decode(FoodShareCodec.encode(food))
        assertEquals(food.nutrition,decoded.nutrition)
        assertEquals("",decoded.nutritionTags);assertNull(decoded.pricePerUnitYuan)
    }
    private fun edit(root:JSONObject,block:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));block(data)
        val payload=data.toString();root.put("payload",payload)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
