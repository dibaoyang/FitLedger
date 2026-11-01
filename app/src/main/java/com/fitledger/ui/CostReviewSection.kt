package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*
import java.time.LocalDate

@Composable fun CostReviewSection(s:LedgerState,day:Long) {
    var periodName by rememberSaveable {mutableStateOf(CostReviewPeriod.LAST_7.name)}
    var offset by rememberSaveable {mutableIntStateOf(0)}
    var showDates by rememberSaveable {mutableStateOf(false)}
    val period=CostReviewPeriod.valueOf(periodName)
    Text("饮食费用回顾",style=MaterialTheme.typography.titleLarge)
    Text("统计已吃掉食品的摄入费用快照，不是购物支出。食品库以后改价不会改写这里。")
    Choice("时间范围",period,CostReviewPeriod.entries.toList(),{it.label}) {periodName=it.name;offset=0}
    if(period==CostReviewPeriod.WEEK || period==CostReviewPeriod.MONTH) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={offset--}) {Text("上一${if(period==CostReviewPeriod.WEEK) "周" else "月"}")}
        OutlinedButton(onClick={offset++},enabled=offset<0) {Text("下一${if(period==CostReviewPeriod.WEEK) "周" else "月"}")}
        TextButton(onClick={offset=0},enabled=offset<0) {Text("回到本期")}
    }
    val range=CostReview.range(day,period,offset)
    val review=remember(s.intakes,range,day) {CostReview.calculate(s.intakes.map {CostReviewEntry(it.day,it.foodId,it.source,it.name,it.costYuan)},range,day)}
    Text("${LocalDate.ofEpochDay(review.start)} ～ ${LocalDate.ofEpochDay(review.end)}${if(review.includesToday) " · 含未结束的今天" else ""}")
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("已知费用合计 ¥${review.knownYuan.money()}",style=MaterialTheme.typography.titleMedium)
        Text(if(review.unknownEntries>0) "另有 ${review.unknownEntries} 条摄入未计价，合计不是完整费用。" else "所记摄入均已计价；无摄入日期不代表费用为 0。")
        Text("完整计价 ${review.completeDays.size} 天 · 有未计价 ${review.unknownDays.size} 天 · 无摄入 ${review.emptyDays.size} 天")
        Text(review.averageYuan?.let {"完整日期日均 ¥${it.money()}（${review.averageDays} 天）"} ?: "完整日期日均：暂无可用日期")
        Text("日均只纳入已结束、存在摄入且全部计价的日期；今天不进入日均。")
    }}
    TextButton(onClick={showDates=!showDates}) {Text(if(showDates) "收起日期明细" else "查看各类日期")}
    if(showDates) {
        fun dates(days:List<Long>)=if(days.isEmpty()) "无" else days.joinToString("、") {LocalDate.ofEpochDay(it).toString()}
        Text("完整计价：${dates(review.completeDays)}")
        Text("有未计价：${dates(review.unknownDays)}")
        Text("无摄入：${dates(review.emptyDays)}")
    }
    if(review.foods.isNotEmpty()) {
        Text("主要花费（按食品与来源汇总）",style=MaterialTheme.typography.titleMedium)
        review.foods.take(12).forEach {food->
            val source=when(food.source) {"DIRECT"->"直接营养";"ROUTINE"->"固定食品";else->"普通食品"}
            val cost=if(food.unknownEntries==food.entries) "全部未计价" else "已知 ¥${food.knownYuan.money()}"
            Text("${food.name} · $source\n$cost · ${food.entries} 条${if(food.unknownEntries>0) "，${food.unknownEntries} 条未计价" else ""}")
        }
        if(review.foods.size>12) Text("显示已知费用最高的 12 组；合计包含全部摄入。")
    }
}
