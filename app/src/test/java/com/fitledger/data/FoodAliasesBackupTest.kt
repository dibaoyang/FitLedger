package com.fitledger.data

import com.fitledger.domain.*
import com.fitledger.ui.searchFoods
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class FoodAliasesBackupTest {
    private val day=20000L
    private fun food()=Food(7,"全脂牛奶","g",100.0,Nutrition(62.0,5.0,3.2,3.5),10.0,500.0,true,
        nutritionTags="PROTEIN",pricePerUnitYuan=0.02,aliases=FoodAliases.encode(listOf("常喝的奶","Breakfast Milk")))
    private fun fixture():Backup {
        val f=food()
        return Backup(
            bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,58.1)),
            targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan(start=day)))),
            foods=listOf(f),
            intakes=listOf(
                Intake(9,day,f.id,"原牛奶快照","g",200.0,f.nutrition*2.0,costYuan=4.0),
                Intake(10,day,null,"外食热量估计","次",1.0,Nutrition(700.0),source="ENERGY",costYuan=25.0)),
            preferences=Preferences(energyUnit=EnergyUnit.KJ))
    }

    @Test fun aliasInputNormalizesSeparatorsAndPreservesPersonalNames() {
        val encoded=FoodAliases.parseInput("  常喝的奶，早餐奶\nBreakfast Milk, ")
        assertEquals(listOf("常喝的奶","早餐奶","Breakfast Milk"),FoodAliases.decode(encoded))
        assertEquals("常喝的奶，早餐奶，Breakfast Milk",FoodAliases.toInput(encoded))
        assertEquals(encoded,FoodAliases.encode(FoodAliases.decode(encoded)))
        assertEquals("",FoodAliases.parseInput(" ，, \n"))
        assertEquals(emptyList<String>(),FoodAliases.decode(""))
        assertEquals(emptyList<String>(),FoodAliases.decode("[]"))
    }

    @Test fun aliasesRejectMalformedTypesDuplicatesOversizeAndControlCharacters() {
        val malformed=listOf("not-json","true",JSONArray(listOf("milk","MILK")).toString(),
            JSONArray(listOf("milk",1)).toString(),JSONArray(listOf("milk",JSONObject.NULL)).toString(),
            JSONArray(listOf(" padded ")).toString(),JSONArray(listOf("bad\tlabel")).toString(),
            JSONArray(listOf("x".repeat(41))).toString(),JSONArray((1..11).map {"alias"+it}).toString())
        malformed.forEach {text->assertTrue(text,runCatching {FoodAliases.decode(text)}.isFailure)}
        assertTrue(runCatching {FoodAliases.parseInput("奶,milk,MILK")}.isFailure)
        assertTrue(runCatching {FoodAliases.parseInput("line\u0000name")}.isFailure)
        assertTrue(runCatching {food().copy(aliases=JSONArray(listOf("bad\u007Fname")).toString()).validate()}.isFailure)
    }

    @Test fun searchMatchesAliasesWithoutChangingFormalNameNutritionOrFavoriting() {
        val original=food()
        val other=original.copy(id=8,name="另一个食品",aliases="",favorite=true)
        assertEquals(listOf(original),searchFoods(listOf(original,other),"常喝"))
        assertEquals(listOf(original),searchFoods(listOf(original,other),"breakfast MILK"))
        assertEquals(listOf(original),searchFoods(listOf(original,other),"全脂 常喝"))
        assertTrue(searchFoods(listOf(original,other),"常喝",favoritesOnly=true).isEmpty())
        assertEquals("全脂牛奶",original.name)
        assertEquals(Nutrition(62.0,5.0,3.2,3.5),original.nutrition)
        assertFalse(original.favorite)
    }

    @Test fun portableFoodCodeDoesNotIncludePrivateSearchAliasesOrLocalPrice() {
        val original=food()
        val code=FoodShareCodec.encode(original)
        val payload=JSONObject(Base64.getUrlDecoder().decode(code.split('.')[1]).toString(Charsets.UTF_8))
        assertFalse(payload.has("aliases"))
        assertFalse(payload.toString().contains("常喝的奶"))
        val imported=FoodShareCodec.decode(code)
        assertEquals("",imported.aliases)
        assertEquals(original.name,imported.name)
        assertEquals(original.nutrition,imported.nutrition)
        assertNull(imported.pricePerUnitYuan)
    }

    @Test fun backupFifteenRoundTripsAliasesAndUnknownMacrosAsNullInsteadOfMeasuredZero() {
        val original=fixture()
        assertEquals(17,original.backupVersion);assertEquals(13,original.databaseVersion)
        val encoded=BackupCodec.encode(original)
        val data=JSONObject(JSONObject(encoded).getString("payload"))
        val foods=data.getJSONArray("foods")
        assertEquals(original.foods.single().aliases,foods.getJSONObject(0).getString("aliases"))
        val rows=data.getJSONArray("intakes")
        val known=rows.getJSONObject(0).getJSONObject("nutrition")
        assertEquals(10.0,known.getDouble("carbs"),0.0)
        val unknown=rows.getJSONObject(1).getJSONObject("nutrition")
        assertEquals(700.0,unknown.getDouble("kcal"),0.0)
        listOf("carbs","protein","fat").forEach {key->assertTrue(unknown.has(key));assertTrue(unknown.isNull(key))}
        assertEquals(original,BackupCodec.decode(encoded))
    }

    @Test fun oldFourteenWithoutAliasesKeepsExistingFoodAndIntakeSnapshots() {
        val original=fixture().copy(backupVersion=14,databaseVersion=10,
            foods=fixture().foods.map {it.copy(aliases="")},intakes=fixture().intakes.filter {it.source!="ENERGY"})
        val root=JSONObject(BackupCodec.encode(original))
        edit(root) {it.getJSONArray("foods").getJSONObject(0).remove("aliases")}
        val decoded=BackupCodec.decode(root.toString())
        assertEquals(original,decoded)
        assertEquals("",decoded.foods.single().aliases)
        assertEquals(original.foods.single().name,decoded.foods.single().name)
        assertEquals(original.intakes,decoded.intakes)
        assertEquals(original.bodies,decoded.bodies)
        assertEquals(original.targets,decoded.targets)
    }

    @Test fun aliasFieldsAreRequiredAndStrictBeforeRestore() {
        val values=listOf<Any>(JSONObject.NULL,true,JSONArray(listOf("奶")),1,"not-json",
            JSONArray(listOf("duplicate","DUPLICATE")).toString())
        values.forEach {value->
            assertRejected {data->data.getJSONArray("foods").getJSONObject(0).put("aliases",value)}
        }
        assertRejected {data->data.getJSONArray("foods").getJSONObject(0).remove("aliases")}
    }

    @Test fun unknownMacrosCannotBeReplacedByZeroOmittedOrDecodedUnderOldMetadata() {
        for(key in listOf("carbs","protein","fat")) {
            assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).getJSONObject("nutrition").put(key,0.0)}
            assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).getJSONObject("nutrition").remove(key)}
        }
        for(value in listOf<Any>(0,-1,"700",JSONObject.NULL)) {
            assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).getJSONObject("nutrition").put("kcal",value)}
        }
        val root=JSONObject(BackupCodec.encode(fixture())).put("backupVersion",14).put("databaseVersion",10)
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
        assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).put("foodId",7)}
        assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).put("unit","g")}
        assertRejected {data->data.getJSONArray("intakes").getJSONObject(1).put("quantity",2)}
    }

    @Test fun newDataCannotBeLossilySavedAsAnOlderBackupOrDatabaseVersion() {
        val original=fixture()
        assertTrue(runCatching {original.copy(backupVersion=14).validate()}.isFailure)
        assertTrue(runCatching {original.copy(databaseVersion=10).validate()}.isFailure)
        val aliasesOnly=original.copy(intakes=original.intakes.filter {it.source!="ENERGY"})
        assertTrue(runCatching {BackupCodec.encode(aliasesOnly.copy(backupVersion=14,databaseVersion=10))}.isFailure)
        val energyOnly=original.copy(foods=original.foods.map {it.copy(aliases="")})
        assertTrue(runCatching {BackupCodec.encode(energyOnly.copy(backupVersion=14,databaseVersion=10))}.isFailure)
        val invalidSentinel=original.intakes.last().copy(nutrition=Nutrition(700.0,1.0,0.0,0.0))
        assertTrue(runCatching {original.copy(intakes=listOf(original.intakes.first(),invalidSentinel)).validate()}.isFailure)
    }

    private fun assertRejected(change:(JSONObject)->Unit) {
        val root=JSONObject(BackupCodec.encode(fixture()))
        edit(root,change)
        assertTrue(root.toString(),runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
    private fun edit(root:JSONObject,change:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));change(data)
        val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
}
