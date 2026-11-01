package com.fitledger.data

import com.fitledger.domain.EatingSchedule
import org.json.JSONArray
import org.json.JSONObject

/** Empty legacy storage means every day; all nonempty payloads have a complete strict shape. */
object ScheduleCodec {
    fun encode(schedule:EatingSchedule):String {
        // Start-independent shape validation; actual date bounds are checked by the template.
        schedule.validate(schedule.dates.minOrNull()?.coerceAtMost(schedule.endDay?:Long.MAX_VALUE)?:0L)
        return JSONObject().put("mode",schedule.mode).put("weekdays",JSONArray(schedule.weekdays.sorted()))
            .put("intervalDays",schedule.intervalDays).put("dates",JSONArray(schedule.dates.sorted()))
            .put("endDay",schedule.endDay?:JSONObject.NULL).toString()
    }
    fun decode(text:String):EatingSchedule {
        if(text.isEmpty()) return EatingSchedule()
        require(text.length<=50000) {"每日规则内容过长"}
        val j=JSONObject(text)
        require(j.keys().asSequence().toSet()==setOf("mode","weekdays","intervalDays","dates","endDay")) {"每日规则字段无效"}
        require(j.get("mode") is String) {"每日规则类型无效"}
        fun exact(value:Any):Long {
            require(value is Number && value.toDouble().isFinite()) {"每日规则日期或间隔须为整数"}
            return requireNotNull(runCatching {value.toString().toBigDecimal().longValueExact()}.getOrNull()) {"每日规则日期或间隔须为整数"}
        }
        fun numbers(key:String):List<Long> {
            val a=j.getJSONArray(key);require(a.length()<=1000) {"每日规则选项过多"}
            val values=(0 until a.length()).map {exact(a.get(it))}
            require(values.distinct().size==values.size) {"每日规则包含重复日期或星期"}
            return values
        }
        val weekdays=numbers("weekdays");require(weekdays.all {it in 1..7}) {"指定星期无效"}
        val interval=exact(j.get("intervalDays"));require(interval in 1..1000000) {"间隔天数无效"}
        val schedule=EatingSchedule(j.getString("mode"),weekdays.map {it.toInt()}.toSet(),interval.toInt(),numbers("dates").toSet(),if(j.isNull("endDay")) null else exact(j.get("endDay")))
        schedule.validate(schedule.dates.minOrNull()?:0L)
        return schedule
    }
}

object QuickAmounts {
    fun encode(amounts:List<Double>):String {
        require(amounts.size<=8 && amounts.distinct().size==amounts.size && amounts.all {it.isFinite() && it>0}) {"常用数量最多 8 项，须为不重复的有效正数"}
        return amounts.joinToString(",")
    }
    fun decode(text:String):List<Double> {
        if(text.isEmpty()) return emptyList()
        require(text.length<=400) {"常用数量内容过长"}
        val amounts=text.split(',').map {requireNotNull(it.toDoubleOrNull()) {"常用数量无效"}}
        encode(amounts)
        return amounts
    }
}
