package com.fitledger.domain

import org.json.JSONArray
import java.util.Locale

/** Personal search labels; they never replace a food's name or travel in public food codes. */
object FoodAliases {
    private fun validate(values:List<String>) {
        require(values.size<=10) {"每种食品最多设置10个别名"}
        require(values.all {it.isNotEmpty() && it==it.trim() && it.length<=40 && it.none {c->c.code<32 || c.code==127}}) {"别名须为40字以内的非空文字，不能包含控制字符"}
        require(values.map {it.lowercase(Locale.ROOT)}.distinct().size==values.size) {"别名不能重复"}
    }
    fun encode(values:List<String>):String {
        val normalized=values.map {it.trim()}
        validate(normalized)
        return if(normalized.isEmpty()) "" else JSONArray(normalized).toString()
    }
    fun decode(encoded:String):List<String> {
        if(encoded.isEmpty()) return emptyList()
        require(encoded.length<=2000) {"食品别名内容过长"}
        val values=JSONArray(encoded).let {array->
            require(array.length()<=10) {"每种食品最多设置10个别名"}
            (0 until array.length()).map {index->
                val value=array.get(index)
                require(value is String) {"食品别名必须为文字"}
                value
            }
        }
        validate(values)
        return values
    }
    fun parseInput(text:String):String {
        require(text.length<=2000) {"食品别名内容过长"}
        return encode(text.split(Regex("[,，\\r\\n]+")).map {it.trim()}.filter {it.isNotEmpty()})
    }
    fun toInput(encoded:String):String=decode(encoded).joinToString("，")
}
