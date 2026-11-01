package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.PortionQuantities
import java.time.LocalDate
import java.util.UUID
import kotlin.math.floor

@Composable fun RecommendationRulesSection(rules:RecommendationRules,foods:List<Food>,zones:List<FoodZone>,intakes:List<Intake>,day:Long,
    busy:Boolean,onSave:(RecommendationRules)->Unit) {
    var open by rememberSaveable {mutableStateOf(false)}
    val cycles=remember(rules) {mutableStateListOf<FoodCycleRule>().apply {addAll(rules.cycleRules)}}
    val conditions=remember(rules) {mutableStateListOf<ConditionalFoodRule>().apply {addAll(rules.conditions)}}
    var preparations by rememberSaveable(rules) {mutableStateOf(rules.availablePreparations.sorted().joinToString(","))}
    var foodId by rememberSaveable {mutableStateOf<Long?>(null)}
    var period by rememberSaveable {mutableStateOf("7")}
    var maximumDays by rememberSaveable {mutableStateOf("")}
    var maximumQuantity by rememberSaveable {mutableStateOf("")}
    var editingCondition by rememberSaveable {mutableStateOf<String?>(null)}
    var triggerKind by rememberSaveable {mutableStateOf("FOOD")}
    var triggerId by rememberSaveable {mutableStateOf<Long?>(null)}
    var conditionKind by rememberSaveable {mutableStateOf("REQUIRE_ZONE")}
    var targetZone by rememberSaveable {mutableStateOf<Long?>(null)}
    val active=foods.filterNot {it.isDeleted}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("食品周期、条件搭配与准备条件",style=MaterialTheme.typography.titleMedium)
        Text("${rules.cycleRules.size}项食品周期 · ${rules.conditions.size}条搭配条件 · ${if(rules.availablePreparations.isEmpty()) "准备条件不限" else rules.availablePreparations.joinToString("、")}")
        OutlinedButton(onClick={open=!open},enabled=!busy) {Text(if(open) "收起规则编辑" else "编辑这些规则")}
        if(open) {
            Text("食品周期上限",style=MaterialTheme.typography.titleMedium)
            Text("每种食品可选周期天数，默认7天。从它第一次实际记录摄入的日期算第1天，之后连续分段，不按周一重置；待吃和未来预设不算已吃。删除或补改历史记录后，以修正后的首次记录重新计算。同一天吃多次只占1天。")
            cycles.toList().forEach {cycle->
                val food=foods.find {it.id==cycle.foodId}
                Text("${food?.name?:"食品${cycle.foodId}"}：每${cycle.periodDays}天，最多${cycle.maximumDays?.toString()?:"不限"}天；累计${cycle.maximumQuantity?.let {"${PortionQuantities.format(food?.unit?:"g",it)} ${food?.unit.orEmpty()}"}?:"不限"}")
                food?.let {runCatching {FoodCycles.summary(day,it,intakes,cycle)}.getOrNull()}?.let {summary->
                    Text("${LocalDate.ofEpochDay(summary.startDay)}～${LocalDate.ofEpochDay(summary.endDay)} · 已出现${summary.usedDays}天"+
                        (if(cycle.maximumQuantity!=null) " · 已吃${PortionQuantities.format(food?.unit?:"g",summary.usedQuantity)} ${food?.unit.orEmpty()}" else "")+
                        (if(summary.anchorDay==null) " · 首次吃下后确定起点" else ""),style=MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick={foodId=cycle.foodId;period=cycle.periodDays.toString();maximumDays=cycle.maximumDays?.toString().orEmpty();maximumQuantity=cycle.maximumQuantity?.let {if(food?.unit=="份") java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() else it.toString()}.orEmpty()},enabled=!busy) {Text("修改")}
                    TextButton(onClick={cycles.remove(cycle)},enabled=!busy) {Text("移除此周期")}
                }
            }
            if(active.isNotEmpty()) {
                val food=active.find {it.id==foodId}?:active.first()
                Choice("周期食品",food.id,active.map {it.id},{id->active.first {it.id==id}.name},enabled=!busy) {foodId=it}
                NumberField("一个周期多少天（1～366）",period,enabled=!busy) {period=it}
                NumberField("每周期最多出现几天（留空不限）",maximumDays,enabled=!busy) {maximumDays=it}
                NumberField("每周期累计最多多少${food.unit}（留空不限）",maximumQuantity,enabled=!busy) {maximumQuantity=it}
                val draftCycle=runCatching {
                    val count=if(maximumDays.isBlank())null else requireNotNull(maximumDays.toIntOrNull()) {"出现天数须为整数"}
                    val q=if(maximumQuantity.isBlank())null else requireNotNull(maximumQuantity.toDoubleOrNull()) {"累计数量格式无效"}
                    require(q==null || food.unit!="份" || q==floor(q)) {"份数须为整数"}
                    FoodCycleRule(food.id,requireNotNull(period.toIntOrNull()) {"周期须为整数天"},count,q).also {it.validate()}
                }
                draftCycle.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                Button(onClick={draftCycle.getOrNull()?.let {draft->cycles.removeAll {it.foodId==draft.foodId};cycles.add(draft)}},enabled=!busy && draftCycle.isSuccess) {Text("加入／更新这个周期")}
            }
            HorizontalDivider()
            Text("条件搭配",style=MaterialTheme.typography.titleMedium)
            Text("按今日已吃和本次推荐一起检查。例如选某食品就须搭配指定种类分区，或两个分区不能同时出现。这些是推荐硬限制，实际吃下的仍能如实手动记录。")
            conditions.toList().forEach {condition->
                val trigger=if(condition.triggerKind=="FOOD") foods.find {it.id==condition.triggerId}?.name else zones.find {it.id==condition.triggerId}?.name
                Text("选${trigger?:"已移除项目${condition.triggerId}"}时，${if(condition.kind=="REQUIRE_ZONE") "须包含" else "不能包含"}${zones.find {it.id==condition.zoneId}?.name?:"分区${condition.zoneId}"}")
                Row {
                    TextButton(onClick={editingCondition=condition.id;triggerKind=condition.triggerKind;triggerId=condition.triggerId;conditionKind=condition.kind;targetZone=condition.zoneId},enabled=!busy) {Text("修改规则")}
                    TextButton(onClick={conditions.remove(condition);if(editingCondition==condition.id) editingCondition=null},enabled=!busy) {Text("移除规则")}
                }
            }
            if(zones.isNotEmpty() && active.isNotEmpty()) {
                Choice("触发项目",triggerKind,listOf("FOOD","ZONE"),{if(it=="FOOD") "一种食品" else "整个种类分区"},enabled=!busy) {triggerKind=it;triggerId=null}
                val triggers=if(triggerKind=="FOOD") active.map {it.id to it.name} else zones.map {it.id to it.name}
                val trigger=triggers.find {it.first==triggerId}?:triggers.first()
                Choice("当推荐包含",trigger.first,triggers.map {it.first},{id->triggers.first {it.first==id}.second},enabled=!busy) {triggerId=it}
                Choice("搭配要求",conditionKind,listOf("REQUIRE_ZONE","EXCLUDE_ZONE"),{if(it=="REQUIRE_ZONE") "至少再选该分区的一种" else "不能同时包含该分区"},enabled=!busy) {conditionKind=it}
                val zone=zones.find {it.id==targetZone}?:zones.first()
                Choice("指定分区",zone.id,zones.map {it.id},{id->zones.first {it.id==id}.name},enabled=!busy) {targetZone=it}
                val draftCondition=runCatching {ConditionalFoodRule(editingCondition?:"draft",triggerKind,trigger.first,conditionKind,zone.id).also {it.validate()}}
                draftCondition.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                Button(onClick={draftCondition.getOrNull()?.let {draft->
                    val saved=draft.copy(id=editingCondition?:UUID.randomUUID().toString());conditions.removeAll {it.id==saved.id};conditions.add(saved);editingCondition=null
                }},enabled=!busy && draftCondition.isSuccess && (editingCondition!=null || conditions.size<50)) {Text(if(editingCondition==null) "加入搭配规则" else "更新这条规则")}
                if(editingCondition!=null) TextButton(onClick={editingCondition=null},enabled=!busy) {Text("取消修改这条规则")}
            } else Text("先有食品和种类分区，才能设置搭配规则。")
            HorizontalDivider()
            Text("当前可用准备条件",style=MaterialTheme.typography.titleMedium)
            Text("名称可自行填写和修改，例如即食、宿舍微波、能下锅；食品编辑中填写对应标签。选多项时符合任一项即可，未分类食品不参与此筛选；留空不筛选。")
            OutlinedTextField(preparations,{preparations=it},label={Text("允许的准备条件（逗号分隔）")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
            val labels=foods.flatMap {runCatching {PreparationTags.decode(it.preparationTags)}.getOrDefault(emptyList())}.distinct().sorted()
            if(labels.isNotEmpty()) Text("食品库已有：${labels.joinToString("、")}")
            val draft=runCatching {RecommendationRules(cycles.toList(),conditions.toList(),PreparationTags.decode(PreparationTags.parseInput(preparations)).toSet()).also {
                RecommendationRuleContext.validateReferences(foods,zones,it)
            }}
            draft.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={draft.getOrNull()?.let(onSave)},enabled=!busy && draft.isSuccess) {Text("保存周期、搭配与准备条件")}
            Text("先点加入／更新，再保存整组规则；不会修改饮食目标、实际摄入或历史身体记录。",style=MaterialTheme.typography.bodySmall)
        }
    }}
}
