package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class FeatureBackupTest {
    private val day=20000L
    private fun fixture()=Backup(bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,null)),
        targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(Food(7,"面包","份",1.0,Nutrition(200.0,30.0,10.0,4.0),0.5,10.0,true,favorite=true,quickAmounts="0.5,1.0,2.0")),
        intakes=emptyList(),preferences=Preferences(),
        dailyFoods=listOf(DailyFoodTemplate(1,7,"份","FIXED",1.0,startDay=day,schedule=ScheduleCodec.encode(EatingSchedule("INTERVAL",intervalDays=3)))),
        applications=listOf(DailyFoodApplication(day,55,99)),skips=listOf(DailyFoodSkip(day,100)),
        combos=listOf(FoodCombo(8,"早餐",ComboCodec.encode(listOf(ComboItem(7,"份",1.5))))))
    @Test fun allNewFieldsRoundTripIncludingDeletedTemplateAndIntakeMarkers() {
        val original=fixture()
        assertEquals(17,original.backupVersion);assertEquals(13,original.databaseVersion)
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        val removed=original.copy(foods=original.foods.map {it.copy(isDeleted=true)},dailyFoods=emptyList())
        assertEquals(removed,BackupCodec.decode(BackupCodec.encode(removed)))
    }
    @Test fun rejectDowngradeForEachNewFeatureAndBadReferencesBeforeAnyWrite() {
        val original=fixture()
        assertTrue(runCatching {original.copy(backupVersion=11).validate()}.isFailure)
        assertTrue(runCatching {original.copy(databaseVersion=8).validate()}.isFailure)
        assertTrue(runCatching {original.copy(skips=original.skips+original.skips).validate()}.isFailure)
        assertTrue(runCatching {original.copy(combos=listOf(FoodCombo(1,"bad",ComboCodec.encode(listOf(ComboItem(999,"g",1.0)))))).validate()}.isFailure)
    }
    @Test fun versionTwelveRequiresStrictNewFieldsAndTables() {
        fun bad(change:(JSONObject)->Unit) {
            val root=JSONObject(BackupCodec.encode(fixture()));edit(root,change)
            assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        }
        for(key in listOf("combos","skips")) bad {it.remove(key)}
        bad {it.getJSONArray("foods").getJSONObject(0).remove("favorite")}
        bad {it.getJSONArray("foods").getJSONObject(0).put("favorite","true")}
        bad {it.getJSONArray("foods").getJSONObject(0).put("quickAmounts",1)}
        bad {it.getJSONArray("dailyFoods").getJSONObject(0).remove("schedule")}
        bad {it.getJSONArray("skips").getJSONObject(0).put("templateId",1.5)}
    }
    @Test fun versionTenDefaultsNewFieldsAndEmptyTablesWithoutInventingHistoricalData() {
        val original=fixture().copy(backupVersion=10,databaseVersion=7,foods=fixture().foods.map {it.copy(favorite=false,quickAmounts="")},dailyFoods=fixture().dailyFoods.map {it.copy(schedule="")},skips=emptyList(),combos=emptyList())
        val root=JSONObject(BackupCodec.encode(original));edit(root) {data ->
            data.remove("skips");data.remove("combos")
            data.getJSONArray("foods").getJSONObject(0).apply {remove("favorite");remove("quickAmounts")}
            data.getJSONArray("dailyFoods").getJSONObject(0).remove("schedule")
        }
        assertEquals(original,BackupCodec.decode(root.toString()))
    }
    @Test fun releasedVersionElevenRestoresGramQuotasAndDefaultsOnlyUnreleasedFeatures() {
        val original=fixture().copy(backupVersion=11,databaseVersion=8,
            foods=fixture().foods.map {it.copy(favorite=false,quickAmounts="",zoneId=1,gramsPerPortion=50.0)},
            zones=listOf(FoodZone(1,"面包",150.0,100.0,limitUnit="G")),
            dailyFoods=fixture().dailyFoods.map {it.copy(schedule="")},skips=emptyList(),combos=emptyList())
        val root=JSONObject(BackupCodec.encode(original));edit(root) {data ->
            data.remove("skips");data.remove("combos")
            data.getJSONArray("foods").getJSONObject(0).apply {remove("favorite");remove("quickAmounts")}
            data.getJSONArray("dailyFoods").getJSONObject(0).remove("schedule")
            data.getJSONArray("zones").getJSONObject(0).remove("allowMultipleFoods")
        }
        val restored=BackupCodec.decode(root.toString())
        assertEquals(original,restored)
        assertEquals(50.0,restored.foods.single().gramsPerPortion!!,0.0)
        assertEquals("G",restored.zones.single().limitUnit)
        assertFalse(restored.foods.single().favorite);assertEquals("",restored.foods.single().quickAmounts)
        assertEquals("",restored.dailyFoods.single().schedule);assertTrue(restored.zones.single().allowMultipleFoods)
        assertTrue(restored.skips.isEmpty());assertTrue(restored.combos.isEmpty())
        val current=restored.copy(backupVersion=12,databaseVersion=9)
        assertEquals(current,BackupCodec.decode(BackupCodec.encode(current)))
    }
    private fun edit(root:JSONObject,block:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));block(data);val payload=data.toString();root.put("payload",payload)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
