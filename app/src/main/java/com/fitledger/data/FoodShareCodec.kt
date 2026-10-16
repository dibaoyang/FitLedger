package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Portable, self-contained food data. The digest detects copying errors, not authenticity. */
object FoodShareCodec {
    const val MAX_CODE_CHARS=8192
    private const val MAX_PAYLOAD_BYTES=4096
    private const val PREFIX="FITFOOD1"
    private val fields=setOf("version","name","unit","basisAmount","nutrition","step","maximum","allowed","energyInputUnit","energyUnit")
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun validate(food:Food) {
        food.validate()
        require(!food.isDeleted) {"已删除的食品不能生成分享码"}
        require(food.name.length<=200 && food.name.none {it.code<32 || it.code==127}) {"食品名称需在 200 字以内，且不含控制字符"}
        require((food.nutrition*(1/food.basisAmount)).values().all {it.isFinite()} &&
            (food.nutrition*(food.maximum/food.basisAmount)).values().all {it.isFinite()}) {"食品计量或营养数值过大，请检查"}
    }
    fun encode(food:Food):String {
        validate(food)
        val payload=JSONObject().apply {
            put("version",1);put("name",food.name);put("unit",food.unit);put("basisAmount",food.basisAmount)
            put("energyUnit","KCAL") // Nutritional energy is canonical, irrespective of input/display preference.
            put("nutrition",JSONObject().put("kcal",food.nutrition.kcal).put("carbs",food.nutrition.carbs)
                .put("protein",food.nutrition.protein).put("fat",food.nutrition.fat))
            put("step",food.step);put("maximum",food.maximum);put("allowed",food.allowed);put("energyInputUnit",food.energyInputUnit)
        }.toString().toByteArray(Charsets.UTF_8)
        require(payload.size<=MAX_PAYLOAD_BYTES) {"食品资料过长"}
        return "$PREFIX.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.${digest(payload)}"
    }
    fun decode(code:String):Food {
        require(code.length<=MAX_CODE_CHARS) {"食品代码过长，请只粘贴食品代码"}
        val parts=code.filterNot {it.isWhitespace()}.split('.')
        require(parts.size==3 && parts[0]==PREFIX) {"食品代码格式或版本不支持，请检查是否完整复制，或更新 App"}
        require(parts[1].matches(Regex("[A-Za-z0-9_-]+")) && parts[2].matches(Regex("[0-9a-fA-F]{64}"))) {"食品代码格式不正确"}
        val bytes=try {Base64.getUrlDecoder().decode(parts[1])} catch(_:IllegalArgumentException) {throw IllegalArgumentException("食品代码内容无效")}
        require(bytes.size<=MAX_PAYLOAD_BYTES && digest(bytes).equals(parts[2],ignoreCase=true)) {"食品代码校验失败，请重新完整复制"}
        val text=bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {"食品代码文字编码无效"}
        try {
            val j=JSONObject(text)
            require(j.keys().asSequence().toSet()==fields) {"食品代码字段不完整或不支持"}
            fun number(o:JSONObject,key:String):Double {
                val value=o.get(key)
                require(value is Number && value.toDouble().isFinite()) {"食品代码中的数值无效"}
                return value.toDouble()
            }
            fun string(key:String):String {
                val value=j.get(key);require(value is String) {"食品代码中的文字字段无效"};return value
            }
            require(number(j,"version")==1.0 && string("energyUnit")=="KCAL") {"食品代码版本或热量单位不支持"}
            require(j.get("allowed") is Boolean) {"食品推荐设置无效"}
            val n=j.getJSONObject("nutrition")
            require(n.keys().asSequence().toSet()==setOf("kcal","carbs","protein","fat")) {"食品营养字段不完整"}
            return Food(name=string("name"),unit=string("unit"),basisAmount=number(j,"basisAmount"),
                nutrition=Nutrition(number(n,"kcal"),number(n,"carbs"),number(n,"protein"),number(n,"fat")),
                step=number(j,"step"),maximum=number(j,"maximum"),allowed=j.getBoolean("allowed"),energyInputUnit=string("energyInputUnit"))
                .also(::validate)
        } catch(e:org.json.JSONException) {
            throw IllegalArgumentException("食品代码内容不完整或无效",e)
        }
    }
}
