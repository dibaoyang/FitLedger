package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class OptionalMuscleBackupTest {
    private fun fixture(muscle:Double?=null)=Backup(
        bodies=listOf(BodyRecord(20000,72.4,15.2,61.3952,muscle)),
        targets=listOf(DailyTarget(20000,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,isDeleted=true)),
        intakes=listOf(Intake(9,20000,7,"旧米饭名称","g",200.0,Nutrition(232.0,52.0,5.2,0.6))),
        preferences=Preferences(hasReadIntro=true,skipMuscleInput=true,energyUnit=EnergyUnit.KJ))

    @Test fun absentAndMeasuredMuscleRoundTripWithSettingsAndAllSnapshots() {
        for(muscle in listOf(null,58.1)) {
            val backup=fixture(muscle)
            assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
        }
        val data=JSONObject(JSONObject(BackupCodec.encode(fixture())).getString("payload"))
        val body=data.getJSONArray("bodies").getJSONObject(0)
        assertTrue(body.has("muscle"));assertTrue(body.isNull("muscle"))
    }

    @Test fun everyOlderBackupVersionPreservesMeasuredMuscleAndDefaultsNewSettings() {
        val original=fixture(58.1).let {it.copy(foods=it.foods.map {f->f.copy(isDeleted=false)},preferences=Preferences(energyUnit=EnergyUnit.KJ))}
        for(version in 1..8) {
            val root=JSONObject(BackupCodec.encode(original)).put("backupVersion",version).put("databaseVersion",5)
            edit(root) {it.getJSONObject("preferences").apply {remove("hasReadIntro");remove("skipMuscleInput")}}
            val restored=BackupCodec.decode(root.toString())
            assertEquals(original.copy(backupVersion=version,databaseVersion=5),restored)
            assertEquals(58.1,restored.bodies.single().muscle!!,0.0)
        }
    }

    @Test fun absenceCannotBeDowngradedAndMissingMuscleKeyIsNotSilentlyAccepted() {
        val backup=fixture()
        assertTrue(runCatching {BackupCodec.encode(backup.copy(backupVersion=8))}.isFailure)
        assertTrue(runCatching {BackupCodec.encode(backup.copy(databaseVersion=5))}.isFailure)
        val root=JSONObject(BackupCodec.encode(backup))
        root.put("backupVersion",8)
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        root.put("backupVersion",9)
        edit(root) {it.getJSONArray("bodies").getJSONObject(0).remove("muscle")}
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }

    private fun edit(root:JSONObject,block:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));block(data)
        val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
