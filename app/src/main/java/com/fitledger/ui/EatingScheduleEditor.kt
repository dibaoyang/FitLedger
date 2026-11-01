package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.ScheduleCodec
import com.fitledger.domain.EatingSchedule
import java.time.LocalDate

data class ScheduleDraft(val startDay:Long,val schedule:String)
private fun parseScheduleDate(raw:String):Long {
    val normalized=raw.trim().replace('年','-').replace('月','-').removeSuffix("日").replace('.','-')
    val parts=normalized.split('-');require(parts.size==3) {"日期请填写 yyyy-MM-dd"}
    return LocalDate.of(parts[0].toInt(),parts[1].toInt(),parts[2].toInt()).toEpochDay()
}
fun scheduleSummary(schedule:String,start:Long):String {
    val s=ScheduleCodec.decode(schedule)
    val days=listOf("一","二","三","四","五","六","日")
    return when(s.mode) {
        "WEEKDAYS"->"每周"+s.weekdays.sorted().joinToString("、") {days[it-1]}
        "INTERVAL"->"从 ${LocalDate.ofEpochDay(start)} 起，每 ${s.intervalDays} 天一次"
        "DATES"->"指定 ${s.dates.size} 个日期"
        else->"每天"
    }+(s.endDay?.let {" · 至 ${LocalDate.ofEpochDay(it)}"} ?: "")
}

@Composable fun EatingScheduleEditor(original:String,startDay:Long,busy:Boolean,onDraft:(ScheduleDraft?)->Unit) {
    val originalRule=remember(original) {ScheduleCodec.decode(original)}
    var mode by rememberSaveable(original,startDay) {mutableStateOf(originalRule.mode)}
    var start by rememberSaveable(original,startDay) {mutableStateOf(LocalDate.ofEpochDay(startDay).toString())}
    var end by rememberSaveable(original,startDay) {mutableStateOf(originalRule.endDay?.let {LocalDate.ofEpochDay(it).toString()} ?: "")}
    var weekdays by rememberSaveable(original,startDay) {mutableStateOf(originalRule.weekdays.sorted())}
    var interval by rememberSaveable(original,startDay) {mutableStateOf(originalRule.intervalDays.toString())}
    var dates by rememberSaveable(original,startDay) {mutableStateOf(originalRule.dates.sorted().joinToString("\n") {LocalDate.ofEpochDay(it).toString()})}
    val result=runCatching {
        val first=parseScheduleDate(start)
        val chosenDates=if(mode=="DATES") dates.split(Regex("[,，;；\\s]+" )).filter {it.isNotBlank()}.map(::parseScheduleDate).also {require(it.distinct().size==it.size) {"指定日期不能重复"}}.toSet() else emptySet()
        val rule=EatingSchedule(mode,if(mode=="WEEKDAYS") weekdays.toSet() else emptySet(),if(mode=="INTERVAL") interval.toInt() else 1,chosenDates,if(end.isBlank()) null else parseScheduleDate(end))
        rule.validate(first);ScheduleDraft(first,ScheduleCodec.encode(rule))
    }
    val draft=result.getOrNull()
    LaunchedEffect(draft) {onDraft(draft)}
    Text("在哪些日子添加",style=MaterialTheme.typography.titleMedium)
    Choice("重复方式",mode,listOf("DAILY","WEEKDAYS","INTERVAL","DATES"),{when(it) {"WEEKDAYS"->"每周指定星期";"INTERVAL"->"每隔若干天";"DATES"->"指定日期";else->"每天"}},enabled=!busy) {mode=it}
    OutlinedTextField(start,{start=it},label={Text("开始日期 yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    when(mode) {
        "WEEKDAYS"->listOf(1,2,3,4,5,6,7).chunked(4).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {row.forEach {day->
            FilterChip(day in weekdays,{weekdays=if(day in weekdays) weekdays-day else weekdays+day},label={Text("周"+listOf("一","二","三","四","五","六","日")[day-1])},enabled=!busy)
        }}}
        "INTERVAL"->{NumberField("每隔多少天一次（整数）",interval,enabled=!busy) {interval=it};Text("开始日算第一次。例如每 3 天一次，从 10 月 1 日开始，会在 1、4、7 日添加。")}
        "DATES"->OutlinedTextField(dates,{dates=it},label={Text("指定日期，用换行或逗号分开")},modifier=Modifier.fillMaxWidth(),minLines=2,enabled=!busy)
    }
    OutlinedTextField(end,{end=it},label={Text("结束日期（可留空）")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    if(draft==null) Text(result.exceptionOrNull()?.message ?: "请填写有效的日期规则",color=MaterialTheme.colorScheme.error)
    else Text(scheduleSummary(draft.schedule,draft.startDay))
    Text("规则只安排尚未自动添加的日期；改规则不会改写已经记录的摄入，也不会为过去日期自动补记。")
}
