package com.fitledger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitledger.data.Food
import com.fitledger.domain.*
import java.util.Locale

@Composable fun RecommendationComparisonSection(batch:ComparisonBatch?,foods:List<Food>,unit:EnergyUnit,busy:Boolean,
    onMore:()->Unit,onAdopt:(ComparisonOption)->Unit,onAdjust:(ComparisonOption)->Unit,
    ratioDetails:(ComparisonOption)->List<WeightRatioDeviation> = {emptyList()}) {
    var pending by remember {mutableStateOf<ComparisonOption?>(null)}
    LaunchedEffect(batch) {pending=null}
    Text("多方案比较",style=MaterialTheme.typography.titleLarge)
    Text("首次最多生成 10 套，可继续到 50 套。每套都保留锁定量、数量步长与上限、分区共同份数和同区互斥限制。")
    if(batch==null) {
        Button(onClick=onMore,enabled=!busy) {Text("生成最多 10 套比较")}
        return
    }
    Text("本轮已展示 ${batch.options.size} 套。左右滑动查看完整表格。")
    Text("“本轮最优”只比较下表已展示方案，允许并列，不保证全局最优。费用未完整计价时不比较该列；食品种数按本次不同食品计数，包含锁定项。")
    Text("营养按原条件自动比较：至少目标时，达到目标均为并列满足；不高于目标时，全天实际克数越低越优；其余比较与目标的差距。")
    val byId=foods.associateBy {it.id}
    if(batch.options.isNotEmpty()) Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            ComparisonCell("方案 / 条件",150)
            ComparisonCell("本次费用",115)
            ComparisonCell("全天费用",115)
            ComparisonCell("碳水",190)
            ComparisonCell("蛋白质",190)
            ComparisonCell("脂肪",190)
            ComparisonCell("食品种数",95)
            ComparisonCell("本次食品与数量",300)
            ComparisonCell("操作",165)
        }
        HorizontalDivider()
        batch.options.forEachIndexed {index,option->key(option.id) {
            Row(Modifier.padding(vertical=8.dp)) {
                Column(Modifier.width(150.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("方案 ${index+1}",style=MaterialTheme.typography.titleMedium)
                    Text(option.suggestion.nutrition.kcal.energy(unit))
                    Text(if(option.formalPolicySatisfied) "满足当前条件" else "需调整条件",
                        color=if(option.formalPolicySatisfied) Color(0xFF176944) else MaterialTheme.colorScheme.error)
                    option.violations.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                }
                ComparisonCostCell(option.metrics.currentCostYuan,ComparisonColumn.CURRENT_COST in option.bestColumns)
                ComparisonCostCell(option.metrics.dailyCostYuan,ComparisonColumn.DAILY_COST in option.bestColumns)
                ComparisonMacroCell(option.metrics.carbs,ComparisonColumn.CARBS in option.bestColumns)
                ComparisonMacroCell(option.metrics.protein,ComparisonColumn.PROTEIN in option.bestColumns)
                ComparisonMacroCell(option.metrics.fat,ComparisonColumn.FAT in option.bestColumns)
                Column(Modifier.width(95.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${option.metrics.foodCount} 种")
                    if(ComparisonColumn.FOOD_COUNT in option.bestColumns) BestInRound()
                }
                Column(Modifier.width(300.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    option.suggestion.quantities.toSortedMap().forEach {(id,q)->
                        val food=byId[id]
                        Text("${food?.name ?: "食品 #$id"} · ${PortionQuantities.format(food?.unit.orEmpty(),q)} ${food?.unit.orEmpty()}")
                    }
                    if(option.suggestion.quantities.isEmpty()) Text("无需新增食品")
                    val ratioPreview=runCatching {ratioDetails(option)}
                    RatioDeviationRows(foods,ratioPreview.getOrDefault(emptyList()))
                    ratioPreview.exceptionOrNull()?.let {Text("比例条件已变化，请重新生成。",color=MaterialTheme.colorScheme.error)}
                }
                Column(Modifier.width(165.dp).padding(8.dp)) {
                    if(option.formalPolicySatisfied) Button(onClick={onAdopt(option)},enabled=!busy) {Text("复验并加入待吃")}
                    else {
                        OutlinedButton(onClick={pending=option},enabled=!busy) {Text("调整条件并复验…")}
                        Text("确认应用新条件后才可采用。",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }}
    }
    Text(batch.note,style=MaterialTheme.typography.bodySmall)
    if(batch.canGenerateMore) OutlinedButton(onClick=onMore,enabled=!busy) {Text("继续生成最多 ${minOf(10,50-batch.options.size)} 套")}
    Text("加入待吃前按最新记录重新检查，吃后点击已吃才计入摄入。标为“需调整条件”的方案须先确认调整。")
    pending?.let {option->
        AlertDialog(onDismissRequest={if(!busy) pending=null},title={Text("应用调整条件并重新验证？")},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(comparisonChanges(batch.basePolicy,option.proposedPolicy))
                Text("确认后将应用上述正式推荐条件，再按最新食品、摄入与分区重新验证此方案；通过后加入待吃清单，吃后点击已吃。锁定量、整数份、数量步长、食品与周期上限及分区搭配限制均不能放宽。")
                Text("调整会保存到推荐设置，之后的推荐也会使用这些预算和营养容差；历史目标不会改变。")
                Text("周预算单独保留；如本方案超过本周额度，请先在设置中明确调整周预算，再重新生成。此处不会放宽周预算。")
            }},confirmButton={TextButton(onClick={pending=null;onAdjust(option)},enabled=!busy && !option.formalPolicySatisfied) {Text("确认应用并复验")}},
            dismissButton={TextButton(onClick={pending=null},enabled=!busy) {Text("取消")}})
    }
}

@Composable private fun ComparisonCell(text:String,width:Int) {Text(text,Modifier.width(width.dp).padding(8.dp),style=MaterialTheme.typography.titleSmall)}
@Composable private fun BestInRound() {Text("本轮最优",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)}
@Composable private fun ComparisonCostCell(value:Double?,best:Boolean) {
    Column(Modifier.width(115.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(value?.let {"¥${it.money()}"} ?: "未完整计价")
        if(best && value!=null) BestInRound()
    }
}
@Composable private fun ComparisonMacroCell(value:ComparisonMacroMetric,best:Boolean) {
    val green=Color(0xFF176944)
    val status=when(value.mode) {
        ComparisonMacroMode.AT_LEAST -> if(value.satisfied) "✓ 满足" else "未达目标"
        ComparisonMacroMode.AT_MOST -> if(value.satisfied) "✓ 未超上限" else "超过上限"
        ComparisonMacroMode.EXACT -> if(value.satisfied) "✓ 精确满足" else "尚未精确达到"
        ComparisonMacroMode.CLOSEST -> "按差距比较"
    }
    val color=when {value.mode==ComparisonMacroMode.CLOSEST->MaterialTheme.colorScheme.onSurface
        value.satisfied->green;else->MaterialTheme.colorScheme.error}
    Column(Modifier.width(190.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(when(value.mode) {
            ComparisonMacroMode.AT_LEAST -> "至少 ${value.targetGrams.f()} g"
            ComparisonMacroMode.AT_MOST -> "上限 ${value.targetGrams.f()} g"
            ComparisonMacroMode.EXACT -> "精确目标 ${value.targetGrams.f()} g"
            ComparisonMacroMode.CLOSEST -> "目标 ${value.targetGrams.f()} g"
        })
        Text("全天实际 ${value.actualGrams.f()} g",color=color)
        Text("差额 ${if(value.differenceGrams>0) "+" else ""}${value.differenceGrams.f()} g")
        Text(status,color=color)
        if(best) BestInRound()
    }
}
private fun comparisonChanges(old:RecommendationPolicy,next:RecommendationPolicy):String {
    fun amount(value:Double?,unit:String)=value?.let {"${it.f2()} $unit"} ?: "不限"
    val changes=mutableListOf<String>()
    if(old.dailyBudgetYuan!=next.dailyBudgetYuan) changes+="全天预算：${amount(old.dailyBudgetYuan,"元")} → ${amount(next.dailyBudgetYuan,"元") }"
    listOf("碳水","蛋白质","脂肪").zip(listOf(old.carbs to next.carbs,old.protein to next.protein,old.fat to next.fat)).forEach {(name,rules)->
        if(rules.first.under!=rules.second.under) changes+="$name 允许少于目标：${amount(rules.first.under,"g")} → ${amount(rules.second.under,"g") }"
        if(rules.first.over!=rules.second.over) changes+="$name 允许超过目标：${amount(rules.first.over,"g")} → ${amount(rules.second.over,"g") }"
    }
    return changes.joinToString("\n").ifBlank {"条件无变化；请重新生成并核对方案。"}
}
private fun Double.comparisonAmount():String = if(this>0 && this<0.0001) toString()
    else String.format(Locale.CHINA,"%.4f",this).trimEnd('0').trimEnd('.')
