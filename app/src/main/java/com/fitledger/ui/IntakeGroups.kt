package com.fitledger.ui

import com.fitledger.data.Intake
import com.fitledger.domain.Nutrition

/** Identity and unit must match: a gram quantity cannot be added to portions. */
data class IntakeGroupKey(val foodId:Long?,val unit:String,val source:String="FOOD")
data class IntakeGroup(val key:IntakeGroupKey,val entries:List<Intake>) {
    val name get()=entries.last().name
    val quantity get()=entries.sumOf {it.quantity}
    // Sum recorded snapshots; never recalculate earlier entries from the current food definition.
    val nutrition get()=entries.fold(Nutrition()) {sum,entry->sum+entry.nutrition}
}
fun groupIntakesForDay(entries:List<Intake>,day:Long):List<IntakeGroup> = entries
    .filter {it.day==day}
    .groupBy {IntakeGroupKey(it.foodId,it.unit,it.source)}
    .map {(key,records)->IntakeGroup(key,records.sortedBy {it.id})}
    .sortedByDescending {it.entries.last().id}
