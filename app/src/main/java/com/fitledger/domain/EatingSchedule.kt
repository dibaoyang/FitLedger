package com.fitledger.domain

import java.time.LocalDate

/** Dates are local epoch days; weekdays follow ISO Monday=1 through Sunday=7. */
data class EatingSchedule(
    val mode:String = "DAILY",
    val weekdays:Set<Int> = emptySet(),
    val intervalDays:Int = 1,
    val dates:Set<Long> = emptySet(),
    val endDay:Long? = null
) {
    fun validate(startDay:Long) {
        require(startDay>=0 && runCatching {LocalDate.ofEpochDay(startDay)}.isSuccess) {"开始日期无效"}
        require(mode in setOf("DAILY","WEEKDAYS","INTERVAL","DATES")) {"每日规则类型无效"}
        require(intervalDays in 1..1000000) {"间隔天数须为 1 至 1000000 的整数"}
        endDay?.let {end->require(end>=startDay && runCatching {LocalDate.ofEpochDay(end)}.isSuccess) {"结束日期不能早于开始日期"}}
        require(weekdays.all {it in 1..7} && (mode=="WEEKDAYS" || weekdays.isEmpty())) {"指定星期无效"}
        require(mode!="WEEKDAYS" || weekdays.isNotEmpty()) {"请至少选择一个星期"}
        require(mode=="INTERVAL" || intervalDays==1) {"此规则不能包含间隔天数"}
        require(dates.size<=1000 && (mode=="DATES" || dates.isEmpty())) {"指定日期无效或超过 1000 天"}
        require(mode!="DATES" || dates.isNotEmpty()) {"请至少选择一个日期"}
        require(dates.all {it>=startDay && (endDay==null || it<=endDay) && runCatching {LocalDate.ofEpochDay(it)}.isSuccess}) {"指定日期超出规则起止范围"}
    }

    fun matches(day:Long,startDay:Long):Boolean {
        validate(startDay)
        LocalDate.ofEpochDay(day)
        if(day<startDay || (endDay!=null && day>endDay)) return false
        return when(mode) {
            "DAILY" -> true
            "WEEKDAYS" -> LocalDate.ofEpochDay(day).dayOfWeek.value in weekdays
            "INTERVAL" -> (day-startDay)%intervalDays==0L
            "DATES" -> day in dates
            else -> false
        }
    }
}
