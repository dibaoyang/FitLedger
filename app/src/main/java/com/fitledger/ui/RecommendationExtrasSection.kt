package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.WeightRatios
import java.time.LocalDate

@Composable fun WeeklyBudgetCard(day:Long,intakes:List<Intake>,extras:RecommendationExtras) {
    if(extras.weeklyBudgetYuan==null) return
    val result=runCatching {ExtendedRecommendations.weeklyBudget(day,intakes,extras)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("本周饮食预算",style=MaterialTheme.typography.titleMedium)
        result.getOrNull()?.let {week->
            Text("${LocalDate.ofEpochDay(week.startDay)}～${LocalDate.ofEpochDay(week.endDay)}")
            Text("预算 ¥${week.budgetYuan.money()} · 已计价 ¥${week.knownCostYuan.money()}")
            if(week.unknownPriceCount>0) Text("${week.unknownPriceCount} 条摄入未计价，剩余额度暂不能确认。",color=MaterialTheme.colorScheme.error)
            else if(week.knownCostYuan>week.budgetYuan) Text("已超过周预算 ¥${(week.knownCostYuan-week.budgetYuan).money()}；如需继续推荐，请先调整周预算。",color=MaterialTheme.colorScheme.error)
            else {
                Text("本周剩余 ¥${week.remainingYuan!!.money()}")
                Text("本次可用周额度 ¥${week.currentRecommendationAllowance!!.money()}"+(if(extras.shareWeekRemainder) "（按本周剩余天数均分）" else ""))
            }
        }
        result.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Text("仅累计本周已记录摄入的费用；未吃的预设不扣款。日预算和周预算同时设置时都要满足。",style=MaterialTheme.typography.bodySmall)
    }}
}

@Composable fun RecommendationExtrasSection(extras:RecommendationExtras,foods:List<Food>,combos:List<FoodCombo>,
    intakes:List<Intake>,day:Long,busy:Boolean,onSave:(RecommendationExtras)->Unit) {
    var open by rememberSaveable {mutableStateOf(false)}
    var budget by rememberSaveable(extras.weeklyBudgetYuan) {mutableStateOf(extras.weeklyBudgetYuan?.toString()?:"")}
    var share by rememberSaveable(extras.shareWeekRemainder) {mutableStateOf(extras.shareWeekRemainder)}
    var days by rememberSaveable(extras.varietyDays) {mutableStateOf(extras.varietyDays.toString())}
    var selected by remember(extras.comboIds) {mutableStateOf(extras.comboIds)}
    var ratioText by rememberSaveable(extras.ratios) {mutableStateOf(WeightRatios.encode(extras.ratios))}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("周预算、轮换、整套组合与比例",style=MaterialTheme.typography.titleMedium)
        WeeklyBudgetCard(day,intakes,extras)
        OutlinedButton(onClick={open=!open},enabled=!busy) {Text(if(open) "收起扩展设置" else "编辑推荐扩展设置")}
        if(open) {
            NumberField("周一至周日预算 元（留空关闭）",budget,enabled=!busy) {budget=it}
            Row {Checkbox(share,{share=it},enabled=!busy);Text("剩余周预算按含今天的剩余天数均分")}
            Text("按本周实际摄入费用扣额，不是购物支出；缺价格不当免费。与日预算同时设置时采用更紧的上限，不改历史费用。")
            NumberField("近几天吃过的食品少推荐（0关闭，最多90天）",days,enabled=!busy) {days=it}
            Text("轮换是偏好，必吃、营养、预算和分区限制仍优先；候选不足时不会保证每天不同。")
            Text("作为整套候选参与推荐的组合")
            combos.forEach {combo->Row {
                Checkbox(combo.id in selected,{checked->selected=if(checked)selected+combo.id else selected-combo.id},enabled=!busy && (combo.id in selected || selected.size<8))
                Text(combo.name,Modifier.padding(top=12.dp))
            }}
            (selected-combos.map {it.id}.toSet()).forEach {id->TextButton(onClick={selected=selected-id},enabled=!busy) {Text("移除已删除组合$id")}}
            Text("一套组合被选用时食品一起出现、使用同一倍率；也可以整套不用。最多8套，同一种食品不能同时属于两套参与推荐的组合。手动添加组合仍可按实际吃的内容选择。")
            WeightRatioEditor(foods,ratioText,busy) {ratioText=it}
            val draft=runCatching {
                val p=if(budget.isBlank())null else requireNotNull(budget.toDoubleOrNull()) {"周预算格式无效"}
                val count=requireNotNull(days.toIntOrNull()) {"轮换天数须为整数"}
                RecommendationExtras(p,share,count,selected,WeightRatios.decode(ratioText),extras.rules).also {
                    ExtendedRecommendations.validateConfiguration(foods,combos,it)
                }
            }
            draft.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={draft.getOrNull()?.let(onSave)},enabled=!busy && draft.isSuccess) {Text("保存推荐扩展设置")}
        }
    }}
}
