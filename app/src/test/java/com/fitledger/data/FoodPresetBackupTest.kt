package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class FoodPresetBackupTest {
    private val day=20000L
    private val food=Food(7,"明日面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,
        favorite=true,quickAmounts="1,2",pricePerUnitYuan=2.25)
    private val target=DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))
    private val item=FoodPresetItem(5,day+1,7,food.name,"份",2.0,food.nutrition*2.0,4.5)
    private fun fixture()=Backup(bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,null)),targets=listOf(target),
        foods=listOf(food),intakes=emptyList(),preferences=Preferences(),
        combos=listOf(FoodCombo(3,"常吃",ComboCodec.encode(listOf(ComboItem(7,"份",1.0))))),
        presets=listOf(FoodPresetPlan(day+1,day,2600.0,72.4)),presetItems=listOf(item))
    private fun appliedFixture():Backup {
        val b=fixture()
        return b.copy(bodies=b.bodies+ b.bodies.single().copy(day=day+1),targets=b.targets+target.copy(day=day+1),
            presetItems=listOf(item.copy(appliedIntakeId=900)))
    }

    @Test fun futurePresetRoundTripsWithoutCreatingFutureBodyTargetOrIntake() {
        val original=fixture()
        val restored=BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(17,restored.backupVersion);assertEquals(13,restored.databaseVersion)
        assertEquals(original,restored)
        assertEquals(listOf(day),restored.bodies.map {it.day});assertEquals(listOf(day),restored.targets.map {it.day})
        assertTrue(restored.intakes.isEmpty())
        assertEquals(original.combos,restored.combos);assertTrue(restored.foods.single().favorite)
    }
    @Test fun unknownReferenceEnergyStaysUnknownWithoutZeroOrCurrentProfileBackfill() {
        val original=fixture().let {it.copy(presets=it.presets.map {p->p.copy(referenceExpenditureKcal=null)})}
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        assertNull(BackupCodec.decode(BackupCodec.encode(original)).presets.single().referenceExpenditureKcal)
    }
    @Test fun versionTwelveMissingPresetArraysDefaultsEmptyAndPreservesPriorFeatures() {
        val original=fixture().copy(backupVersion=12,databaseVersion=9,presets=emptyList(),presetItems=emptyList())
        val root=JSONObject(BackupCodec.encode(original))
        edit(root) {it.remove("presets");it.remove("presetItems")}
        val restored=BackupCodec.decode(root.toString())
        assertEquals(original,restored);assertTrue(restored.presets.isEmpty());assertTrue(restored.presetItems.isEmpty())
        assertEquals(original.combos,restored.combos);assertEquals("1,2",restored.foods.single().quickAmounts)
    }
    @Test fun newPresetFieldsAndNumbersAreStrictBeforeRestore() {
        fun bad(change:(JSONObject)->Unit) {
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,change)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
        bad {it.remove("presets")};bad {it.remove("presetItems")}
        bad {it.getJSONArray("presets").getJSONObject(0).remove("referenceExpenditureKcal")}
        bad {it.getJSONArray("presets").getJSONObject(0).put("referenceExpenditureKcal","2600")}
        bad {it.getJSONArray("presets").getJSONObject(0).put("day",day+1.5)}
        bad {it.getJSONArray("presets").getJSONObject(0).put("referenceWeightKg",0)}
        bad {it.getJSONArray("presetItems").getJSONObject(0).put("quantity","2")}
        bad {it.getJSONArray("presetItems").getJSONObject(0).getJSONObject("nutrition").put("kcal","400")}
        bad {it.getJSONArray("presetItems").getJSONObject(0).put("appliedIntakeId",1.5)}
        bad {it.getJSONArray("presetItems").getJSONObject(0).remove("costYuan")}
        bad {it.getJSONArray("presetItems").getJSONObject(0).put("unit","kg")}
    }
    @Test fun rejectsLossyDowngradeAndBrokenPresetReferencesWithoutDroppingRecords() {
        val b=fixture()
        assertTrue(runCatching {b.copy(backupVersion=12).validate()}.isFailure)
        assertTrue(runCatching {b.copy(databaseVersion=9).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presets=b.presets+b.presets).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=b.presetItems+b.presetItems).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=listOf(item.copy(foodId=999))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=listOf(item.copy(day=day+2))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presets=listOf(b.presets.single().copy(referenceDay=day-7))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=listOf(item.copy(appliedIntakeId=900))).validate()}.isFailure)
    }
    @Test fun deletedActualIntakeMarkerSurvivesAndEditedActualQuantityIsAllowed() {
        val b=appliedFixture()
        assertEquals(b,BackupCodec.decode(BackupCodec.encode(b)))
        val actual=Intake(900,day+1,7,food.name,"份",3.0,food.nutrition*3.0,costYuan=6.75)
        val edited=b.copy(intakes=listOf(actual))
        assertEquals(edited,BackupCodec.decode(BackupCodec.encode(edited)))
        assertTrue(runCatching {edited.copy(intakes=listOf(actual.copy(day=day))).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=listOf(item.copy(appliedIntakeId=900),item.copy(id=6,appliedIntakeId=900))).validate()}.isFailure)
    }
    @Test fun sevenDayPresetAndWeightRulesRoundTripAsSeparateUnappliedSnapshots() {
        val second=Food(8,"参考米饭","g",100.0,Nutrition(120.0,25.0,2.0,0.5),10.0,1000.0,true)
        val rules=WeightRatios.encode(listOf(WeightRatio(7,8,0.5)))
        val b=fixture().copy(foods=listOf(food.copy(gramsPerPortion=100.0),second),
            presets=listOf(FoodPresetPlan(day+7,day,null,72.4,rules)),
            presetItems=listOf(item.copy(day=day+7),FoodPresetItem(6,day+7,8,second.name,"g",200.0,second.nutrition*2.0)))
        assertEquals(b,BackupCodec.decode(BackupCodec.encode(b)))
        assertTrue(runCatching {b.copy(backupVersion=15).validate()}.isFailure)
        assertTrue(runCatching {b.copy(databaseVersion=11).validate()}.isFailure)
        assertTrue(runCatching {b.copy(presetItems=b.presetItems.dropLast(1)).validate()}.isFailure)
        assertEquals(listOf(day),b.bodies.map {it.day});assertTrue(b.intakes.isEmpty())
    }
    @Test fun versionFifteenMissingNewRatioFieldsReadsWithEmptyDefaultsAndKeepsOldPreset() {
        val original=fixture().copy(backupVersion=15,databaseVersion=11)
        val root=JSONObject(BackupCodec.encode(original))
        edit(root) {data->
            data.remove("recipes")
            data.getJSONArray("combos").getJSONObject(0).remove("ratioRules")
            data.getJSONArray("presets").getJSONObject(0).remove("ratioRules")
            data.getJSONObject("preferences").remove("recommendationExtras")
        }
        val restored=BackupCodec.decode(root.toString())
        assertEquals(original,restored);assertEquals("",restored.presets.single().ratioRules)
        assertEquals(item,restored.presetItems.single());assertTrue(restored.intakes.isEmpty())
    }
    @Test fun versionSixteenRequiresRatioFieldsAndRejectsMalformedRulesBeforeRestore() {
        listOf<(JSONObject)->Unit>(
            {it.getJSONArray("presets").getJSONObject(0).remove("ratioRules")},
            {it.getJSONArray("presets").getJSONObject(0).put("ratioRules",123)},
            {it.getJSONArray("presets").getJSONObject(0).put("ratioRules","not-json")},
            {it.getJSONArray("presets").getJSONObject(0).put("ratioRules",WeightRatios.encode(listOf(WeightRatio(7,999,0.5))))}
        ).forEach {change->
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,change)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
    }
    private fun edit(root:JSONObject,change:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));change(data);val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
