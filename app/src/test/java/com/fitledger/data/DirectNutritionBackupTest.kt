package com.fitledger.data

import com.fitledger.domain.*
import com.fitledger.ui.groupIntakesForDay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DirectNutritionBackupTest {
    private val first=Intake(9,20000,null,"直接添加营养","次",1.0,DirectNutrition.calculate(100.0,25.0,10.0),"DIRECT")
    private fun fixture()=Backup(bodies=listOf(BodyRecord(20000,72.4,15.2,61.3952,null)),
        targets=listOf(DailyTarget(20000,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=emptyList(),intakes=listOf(first),preferences=Preferences(hasReadIntro=true,skipMuscleInput=true))

    @Test fun directRecordsNeedNoSyntheticFoodAndRestoreWithPreciseNutrition() {
        val original=fixture()
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        assertTrue(original.foods.isEmpty())
        assertEquals(1410.0,(original.targets.single().nutrition-first.nutrition).kcal,0.0)
    }
    @Test fun repeatedDirectRecordsGroupWithoutLosingIndividualDeletionOrMixingFoods() {
        val second=first.copy(id=10,nutrition=DirectNutrition.calculate(0.0,10.0,0.0))
        val food=Intake(11,20000,7,"直接添加营养","g",100.0,Nutrition(100.0,20.0,4.0,0.4))
        val groups=groupIntakesForDay(listOf(first,second,food),20000)
        assertEquals(2,groups.size)
        val direct=groups.single {it.key.source=="DIRECT"}
        assertEquals(630.0,direct.nutrition.kcal,0.0);assertEquals(listOf(first,second),direct.entries)
        val remaining=groupIntakesForDay(listOf(second,food),20000).single {it.key.source=="DIRECT"}
        assertEquals(second.nutrition,remaining.nutrition)
    }
    @Test fun backupRejectsMislabelledOrInconsistentManualRecordsAndDowngrades() {
        val original=fixture()
        val invalid=listOf(first.copy(foodId=7),first.copy(quantity=2.0),first.copy(unit="g"),
            first.copy(source="FOOD"),first.copy(source="BAD"),first.copy(nutrition=first.nutrition.copy(kcal=999.0)))
        invalid.forEach {assertTrue(runCatching {original.copy(intakes=listOf(it)).validate()}.isFailure)}
        assertTrue(runCatching {original.copy(backupVersion=8).validate()}.isFailure)
        assertTrue(runCatching {original.copy(databaseVersion=5).validate()}.isFailure)
        val root=JSONObject(BackupCodec.encode(original))
        val data=JSONObject(root.getString("payload"))
        data.getJSONArray("intakes").getJSONObject(0).remove("source")
        val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
}
