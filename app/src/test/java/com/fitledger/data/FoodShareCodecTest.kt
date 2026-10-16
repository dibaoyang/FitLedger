package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class FoodShareCodecTest {
    private val food=Food(72,"土豆泥 🥔","g",100.0,Nutrition(200.0,30.0,5.0,5.5),10.0,500.0,true,"KJ")
    @Test fun gramAndPortionFoodsRoundTripWithoutIdentity() {
        for(f in listOf(food,food.copy(unit="份",basisAmount=1.0,step=1.0,maximum=4.0,allowed=false))) {
            assertEquals(f.copy(id=0),FoodShareCodec.decode(FoodShareCodec.encode(f)))
        }
        val payload=payload(FoodShareCodec.encode(food))
        assertFalse(payload.has("id"));assertFalse(payload.has("isDeleted"))
        assertEquals("KCAL",payload.getString("energyUnit"));assertEquals(200.0,payload.getJSONObject("nutrition").getDouble("kcal"),0.0)
    }
    @Test fun wrappedCodeAndZeroMacrosWork() {
        val f=food.copy(nutrition=Nutrition(900.0,0.0,0.0,100.0))
        assertEquals(f.copy(id=0),FoodShareCodec.decode("  "+FoodShareCodec.encode(f).chunked(50).joinToString("\n")+"\n"))
    }
    @Test fun corruptionTruncationAndFutureVersionAreRejected() {
        val code=FoodShareCodec.encode(food)
        for(invalid in listOf(code.dropLast(3),code.replace("FITFOOD1","FITFOOD2"),code.dropLast(1)+(if(code.last()=='0') "1" else "0"),"x".repeat(8193))) {
            assertTrue(runCatching {FoodShareCodec.decode(invalid)}.isFailure)
        }
    }
    @Test fun invalidValuesTypesUnitsAndUnrecognizedFieldsAreRejectedBeforeImport() {
        val mutations=listOf<(JSONObject)->Unit>(
            {it.put("id",72)}, {it.put("unit","ml")}, {it.put("energyUnit","KJ")},
            {it.put("energyInputUnit","INVALID")}, {it.put("version",2)}, {it.put("allowed","true")},
            {it.put("basisAmount",0)}, {it.put("maximum",1)}, {it.remove("name")},
            {it.put("basisAmount","100")}, {it.getJSONObject("nutrition").put("protein",-5)},
            {it.getJSONObject("nutrition").remove("fat")}, {it.put("name","bad\nname")},
            {it.put("name","x".repeat(201))}, {it.put("basisAmount",1e-310)}
        )
        mutations.forEach {edit -> val j=payload(FoodShareCodec.encode(food));edit(j)
            assertTrue(j.toString(),runCatching {FoodShareCodec.decode(code(j))}.isFailure)
        }
    }
    @Test fun deletedFoodsCannotBeShared() {
        assertTrue(runCatching {FoodShareCodec.encode(food.copy(isDeleted=true))}.isFailure)
    }
    private fun payload(code:String)=JSONObject(String(Base64.getUrlDecoder().decode(code.split('.')[1]),Charsets.UTF_8))
    private fun code(j:JSONObject):String {
        val bytes=j.toString().toByteArray(Charsets.UTF_8)
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
        return "FITFOOD1.${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}.$digest"
    }
}
