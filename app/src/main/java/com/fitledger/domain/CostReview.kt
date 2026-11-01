package com.fitledger.domain

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class CostReviewPeriod(val label:String) { LAST_7("最近 7 天"),LAST_30("最近 30 天"),WEEK("自然周"),MONTH("自然月") }
data class CostReviewEntry(val day:Long,val foodId:Long?,val source:String,val name:String,val costYuan:Double?)
data class CostReviewFood(val foodId:Long?,val source:String,val name:String,val knownYuan:Double,val unknownEntries:Int,val entries:Int)
data class CostReviewResult(
    val start:Long,val end:Long,val knownYuan:Double,val unknownEntries:Int,
    val completeDays:List<Long>,val unknownDays:List<Long>,val emptyDays:List<Long>,
    val averageYuan:Double?,val averageDays:Int,val includesToday:Boolean,val foods:List<CostReviewFood>
)
object CostReview {
    fun range(today:Long,period:CostReviewPeriod,offset:Int=0):LongRange {
        require(offset<=0) {"不能查看未来周期"}
        val date=LocalDate.ofEpochDay(today)
        val start=when(period) {
            CostReviewPeriod.LAST_7 -> date.minusDays(6)
            CostReviewPeriod.LAST_30 -> date.minusDays(29)
            CostReviewPeriod.WEEK -> date.minusDays((date.dayOfWeek.value-1).toLong()).plusWeeks(offset.toLong())
            CostReviewPeriod.MONTH -> date.withDayOfMonth(1).plusMonths(offset.toLong())
        }
        val end=when(period) {
            CostReviewPeriod.WEEK -> start.plusDays(6)
            CostReviewPeriod.MONTH -> start.with(TemporalAdjusters.lastDayOfMonth())
            else -> date
        }
        return start.toEpochDay()..minOf(today,end.toEpochDay())
    }

    /** Missing prices stay unknown; only fully priced, nonempty, completed days enter the average. */
    fun calculate(entries:List<CostReviewEntry>,range:LongRange,today:Long):CostReviewResult {
        require(!range.isEmpty() && range.last<=today && range.last-range.first<=3660) {"费用回顾日期范围无效"}
        val selected=entries.filter {it.day in range}
        require(selected.all {it.costYuan==null || (it.costYuan.isFinite() && it.costYuan>=0)}) {"摄入费用快照无效"}
        val byDay=selected.groupBy {it.day}
        val complete=mutableListOf<Long>();val unknown=mutableListOf<Long>();val empty=mutableListOf<Long>()
        range.forEach {day->when {
            byDay[day].isNullOrEmpty() -> empty+=day
            byDay.getValue(day).any {it.costYuan==null} -> unknown+=day
            else -> complete+=day
        }}
        val averaged=complete.filter {it<today}
        val known=selected.sumOf {it.costYuan ?: 0.0}
        val average=if(averaged.isEmpty()) null else averaged.sumOf {day->byDay.getValue(day).sumOf {it.costYuan!!}}/averaged.size
        require(known.isFinite() && (average==null || average.isFinite())) {"费用总额超出计算范围"}
        val foods=selected.groupBy {it.foodId to it.source}.map {(key,items)->
            CostReviewFood(key.first,key.second,items.maxBy {it.day}.name,items.sumOf {it.costYuan ?: 0.0},items.count {it.costYuan==null},items.size)
        }.sortedWith(compareByDescending<CostReviewFood> {it.knownYuan}.thenBy {it.name})
        return CostReviewResult(range.first,range.last,known,selected.count {it.costYuan==null},complete,unknown,empty,
            average,averaged.size,today in range,foods)
    }
}
