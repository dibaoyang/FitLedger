package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class FoodZoneBackupTest {
    private val food=Food(7,"面包","g",100.0,Nutrition(200.0,35.0,9.0,3.0),10.0,500.0,true,"KJ",true,1,50.0,8)
    private fun fixture()=Backup(bodies=listOf(BodyRecord(20000,72.4,15.2,61.3952,58.1)),
        targets=listOf(DailyTarget(20000,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(food),intakes=listOf(Intake(9,20000,7,"旧面包名称","g",100.0,Nutrition(200.0,35.0,9.0,3.0))),
        preferences=Preferences(hasReadIntro=true,energyUnit=EnergyUnit.KJ),
        zones=listOf(FoodZone(1,"面包",3.0,null,2),FoodZone(2,"不限量"),FoodZone(3,"暂不推荐",null,0.0)))

    @Test fun versionTenRoundTripPreservesZonesConversionsSoftDeletionHistoryAndSettings() {
        val original=fixture()
        assertEquals(10,original.backupVersion);assertEquals(7,original.databaseVersion)
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        val data=JSONObject(JSONObject(BackupCodec.encode(original)).getString("payload"))
        assertTrue(data.getJSONArray("zones").getJSONObject(0).isNull("recommendationMaximum"))
        assertEquals(50.0,data.getJSONArray("foods").getJSONObject(0).getDouble("zonePortionSize"),0.0)
    }
    @Test fun allNinePreviousFormatsRestoreAsUnassignedWithNoRecalculation() {
        val original=fixture().copy(zones=emptyList(),foods=listOf(food.copy(isDeleted=false,zoneId=null,zonePortionSize=1.0,sortOrder=0)))
        for(version in 1..9) {
            val root=JSONObject(BackupCodec.encode(original)).put("backupVersion",version).put("databaseVersion",6)
            edit(root) {data ->
                data.remove("zones")
                data.getJSONArray("foods").getJSONObject(0).apply {remove("zoneId");remove("zonePortionSize");remove("sortOrder")}
            }
            val restored=BackupCodec.decode(root.toString())
            assertEquals(original.copy(backupVersion=version,databaseVersion=6),restored)
            assertNull(restored.foods.single().zoneId);assertEquals(1.0,restored.foods.single().zonePortionSize,0.0)
        }
    }
    @Test fun cannotDowngradeZonesOrHideDanglingReferencesAndInvalidConversions() {
        val original=fixture()
        assertTrue(runCatching {original.copy(backupVersion=9).validate()}.isFailure)
        assertTrue(runCatching {original.copy(databaseVersion=6).validate()}.isFailure)
        assertTrue(runCatching {original.copy(zones=emptyList()).validate()}.isFailure)
        assertTrue(runCatching {original.copy(zones=original.zones+original.zones.first()).validate()}.isFailure)
        assertTrue(runCatching {original.copy(foods=listOf(food.copy(zonePortionSize=0.0))).validate()}.isFailure)
        assertTrue(runCatching {original.copy(zones=listOf(FoodZone(1,"面包",-1.0))).validate()}.isFailure)
        val root=JSONObject(BackupCodec.encode(original)).put("backupVersion",9)
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
    @Test fun rejectsMissingOrCoercedNewFieldsBeforeRestore() {
        fun bad(block:(JSONObject)->Unit) {
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,block)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
        bad {it.remove("zones")}
        bad {it.getJSONArray("foods").getJSONObject(0).remove("zonePortionSize")}
        bad {it.getJSONArray("foods").getJSONObject(0).remove("zoneId")}
        bad {it.getJSONArray("foods").getJSONObject(0).put("zoneId",1.5)}
        bad {it.getJSONArray("foods").getJSONObject(0).put("zonePortionSize","50")}
        bad {it.getJSONArray("zones").getJSONObject(0).put("dailyMaximum","3")}
        bad {it.getJSONArray("zones").getJSONObject(0).remove("dailyMaximum")}
        bad {it.getJSONArray("zones").getJSONObject(0).put("id",0)}
        bad {it.getJSONArray("foods").getJSONObject(0).put("sortOrder",-1)}
        bad {it.getJSONArray("foods").getJSONObject(0).put("sortOrder",1.5)}
        bad {it.getJSONArray("foods").getJSONObject(0).put("sortOrder","1")}
        bad {it.getJSONArray("foods").getJSONObject(0).remove("sortOrder")}
        bad {it.getJSONArray("zones").getJSONObject(0).put("sortOrder",-1)}
        bad {it.getJSONArray("zones").getJSONObject(0).put("sortOrder",1.5)}
    }
    @Test fun foodShareCodesKeepNutritionButDoNotTransferPersonalZones() {
        val decoded=FoodShareCodec.decode(FoodShareCodec.encode(food.copy(isDeleted=false)))
        assertNull(decoded.zoneId);assertEquals(1.0,decoded.zonePortionSize,0.0)
        assertEquals(0L,decoded.sortOrder)
        assertEquals(food.nutrition,decoded.nutrition);assertEquals("KJ",decoded.energyInputUnit)
    }
    private fun edit(root:JSONObject,block:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));block(data)
        val payload=data.toString();root.put("payload",payload)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
