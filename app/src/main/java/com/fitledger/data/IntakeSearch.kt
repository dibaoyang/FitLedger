package com.fitledger.data

import java.time.LocalDate
import java.util.Locale

enum class IntakeSearchSource(val label:String) {
    ALL("全部来源"),FOOD("手动食品"),ROUTINE("每日固定食品"),DIRECT("直接添加营养"),ENERGY("仅热量")
}
data class IntakeSearchFilter(val query:String="",val firstDay:Long?=null,val lastDay:Long?=null,
    val source:IntakeSearchSource=IntakeSearchSource.ALL) {
    fun validate() {
        require(query.length<=200) {"搜索词最多200字"}
        firstDay?.let(LocalDate::ofEpochDay);lastDay?.let(LocalDate::ofEpochDay)
        require(firstDay==null || lastDay==null || firstDay<=lastDay) {"开始日期不能晚于结束日期"}
    }
}
data class IntakeSearchDay(val day:Long,val entries:List<Intake>) {
    val nutrition get()=IntakeNutritionTotals.calculate(entries)
    val knownCostYuan get()=entries.sumOf {it.costYuan ?: 0.0}.also {require(it.isFinite() && it>=0) {"费用合计超出范围"}}
    val unknownPriceCount get()=entries.count {it.costYuan==null}
    val totalCostYuan get()=if(unknownPriceCount==0)knownCostYuan else null
}
object IntakeSearch {
    /** Only persisted intake names are searched. Current food edits never rename historical rows. */
    fun find(entries:List<Intake>,filter:IntakeSearchFilter):List<IntakeSearchDay> {
        filter.validate()
        val needle=filter.query.trim().lowercase(Locale.ROOT)
        return entries.asSequence().filter {entry->
            (needle.isEmpty() || entry.name.lowercase(Locale.ROOT).contains(needle)) &&
                (filter.firstDay==null || entry.day>=filter.firstDay) && (filter.lastDay==null || entry.day<=filter.lastDay) &&
                (filter.source==IntakeSearchSource.ALL || entry.source==filter.source.name)
        }.groupBy {it.day}.toSortedMap(reverseOrder()).map {(day,rows)->IntakeSearchDay(day,rows.sortedByDescending {it.id})}
    }
}
