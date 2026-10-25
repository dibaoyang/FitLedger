package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DailyEatingBackupTest {
    private val day=20000L
    private val food=Food(7,"鸡胸肉","g",100.0,Nutrition(120.0,0.0,24.0,2.0),10.0,500.0,true)
    private fun fixture()=Backup(bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,58.1)),
        targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(food),intakes=listOf(Intake(9,day,7,"鸡胸肉","g",144.8,Nutrition(173.76,0.0,34.752,2.896),"ROUTINE")),
        preferences=Preferences(hasReadIntro=true),
        dailyFoods=listOf(DailyFoodTemplate(1,7,"g","PER_WEIGHT",2.0,true,day)),
        applications=listOf(DailyFoodApplication(day,1,9)),dayModes=listOf(DayMode(day,true)))

    @Test fun templatesExecutedNutritionAndIndulgenceRoundTripWithoutRecalculation() {
        val original=fixture()
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        assertEquals(144.8,BackupCodec.decode(BackupCodec.encode(original)).intakes.single().quantity,0.0)
    }
    @Test fun deletedIntakeAndDeletedTemplateKeepMarkersDuringBackupRestore() {
        val deleted=fixture().copy(intakes=emptyList(),dailyFoods=emptyList())
        assertEquals(deleted,BackupCodec.decode(BackupCodec.encode(deleted)))
        val changed=fixture().copy(dailyFoods=listOf(DailyFoodTemplate(1,7,"g","FIXED",100.0,false,day+1)))
        assertEquals(changed,BackupCodec.decode(BackupCodec.encode(changed)))
        val removedFood=fixture().copy(foods=listOf(food.copy(isDeleted=true)))
        assertEquals(removedFood,BackupCodec.decode(BackupCodec.encode(removedFood)))
    }
    @Test fun routineNeedsMatchingMarkerButCurrentTemplateMayBeAbsent() {
        val original=fixture()
        val invalid=listOf(original.copy(applications=emptyList()),
            original.copy(applications=listOf(DailyFoodApplication(day+1,1,9))),
            original.copy(applications=original.applications+DailyFoodApplication(day,2,9)),
            original.copy(intakes=listOf(original.intakes.single().copy(source="FOOD"))),
            original.copy(dailyFoods=listOf(original.dailyFoods.single().copy(foodId=999))),
            original.copy(dailyFoods=listOf(original.dailyFoods.single().copy(unit="份"))),
            original.copy(dailyFoods=listOf(original.dailyFoods.single().copy(quantity=Double.NaN))))
        invalid.forEach {assertTrue(runCatching {it.validate()}.isFailure)}
        assertEquals(original.copy(dailyFoods=emptyList()),BackupCodec.decode(BackupCodec.encode(original.copy(dailyFoods=emptyList()))))
    }
    @Test fun rejectsDowngradeEvenWhenOnlyDeletedExecutionMarkerOrDayModeRemains() {
        val original=fixture()
        val variants=listOf(original,original.copy(dailyFoods=emptyList(),intakes=emptyList()),
            original.copy(dailyFoods=emptyList(),applications=emptyList(),intakes=emptyList()))
        variants.forEach {
            assertTrue(runCatching {it.copy(backupVersion=9).validate()}.isFailure)
            assertTrue(runCatching {it.copy(databaseVersion=6).validate()}.isFailure)
        }
    }
    @Test fun legacyVersionsWithoutNewArraysKeepHistoryAndDefaultDailyFeatures() {
        val original=fixture().copy(dailyFoods=emptyList(),applications=emptyList(),dayModes=emptyList(),
            intakes=fixture().intakes.map {it.copy(source="FOOD")})
        for(version in 1..9) {
            val root=JSONObject(BackupCodec.encode(original)).put("backupVersion",version).put("databaseVersion",6)
            edit(root) {listOf("dailyFoods","applications","dayModes","zones").forEach(it::remove)}
            assertEquals(original.copy(backupVersion=version,databaseVersion=6),BackupCodec.decode(root.toString()))
        }
    }
    @Test fun rejectsMissingOrMalformedVersionTenFields() {
        fun bad(block:(JSONObject)->Unit) {
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,block)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
        for(key in listOf("dailyFoods","applications","dayModes")) bad {it.remove(key)}
        bad {it.getJSONArray("dailyFoods").getJSONObject(0).put("enabled","true")}
        bad {it.getJSONArray("dailyFoods").getJSONObject(0).put("quantity","2")}
        bad {it.getJSONArray("dailyFoods").getJSONObject(0).put("mode","BAD")}
        bad {it.getJSONArray("applications").getJSONObject(0).put("templateId",1.5)}
        bad {it.getJSONArray("dayModes").getJSONObject(0).put("isIndulgence","true")}
        bad {it.getJSONArray("dayModes").getJSONObject(0).put("day",20000.5)}
    }
    private fun edit(root:JSONObject,block:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));block(data)
        val payload=data.toString();root.put("payload",payload)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
