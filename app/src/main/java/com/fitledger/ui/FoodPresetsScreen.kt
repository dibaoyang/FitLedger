package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.FoodPricing
import com.fitledger.domain.FoodPresetEstimates
import com.fitledger.domain.PortionQuantities
import java.time.LocalDate
import kotlin.math.abs

/** Presets have their own snapshots. They are never treated as intake by this screen. */
@Composable fun TomorrowFoodPresetScreen(vm:LedgerViewModel,s:LedgerState,today:Long,busy:Boolean) {
    var selectedDay by rememberSaveable(today) {mutableStateOf(today+1)}
    val choices=(1L..7L).map {today+it}
    Choice("预设日期",selectedDay,choices,{date->"${LocalDate.ofEpochDay(date)} · ${if(date==today+1) "明天" else "${date-today}天后"}"},enabled=!busy) {selectedDay=it}
    val day=selectedDay
    val plan=s.presets.find {it.day==day}
    val items=s.presetItems.filter {it.day==day}.sortedBy {it.id}
    var query by rememberSaveable(day) {mutableStateOf("")}
    var favoritesOnly by rememberSaveable(day) {mutableStateOf(false)}
    var grouping by rememberSaveable(day) {mutableStateOf("TYPE")}
    var filter by rememberSaveable(day) {mutableStateOf("ALL")}
    var selectedFood by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var editingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var deletingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var clearing by rememberSaveable(day) {mutableStateOf(false)}
    val hasBody=s.bodies.any {it.day==today} && s.targets.any {it.day==today}

    Text("未来饮食预设 · ${LocalDate.ofEpochDay(day)}",style=MaterialTheme.typography.titleLarge)
    Text("提前安排未来1至7天吃什么。这里只保存预设，不计入实际摄入，也不提前生成未来身体数据或目标。")
    Text("新预设用今天保存的总消耗作为参考，已有预设保留原参考日期；只减去本预设热量，不使用今天已吃后的缺口。")
    PresetSummary(plan,items)
    if(plan!=null) OutlinedButton(onClick={vm.refreshPresetReference(day)},enabled=!busy && hasBody) {Text("按今天的消耗更新预估基准")}
    if(!hasBody) Text("请先完成今天的身体记录，再保存未来预设。",color=MaterialTheme.colorScheme.error)

    Text("添加所选日期食品",style=MaterialTheme.typography.titleMedium)
    FoodSearchControls(query,favoritesOnly,busy,{query=it},{favoritesOnly=it})
    FoodGroupingFilter(grouping,filter,s.zones,!busy,{grouping=it;filter="ALL"},{filter=it})
    val matching=filterFoods(searchFoods(s.foods.filterNot {it.isDeleted},query,favoritesOnly),grouping,filter)
    if(matching.isEmpty()) Text("没有符合条件的食品，可调整筛选，或先在食品库添加。")
    else {
        val food=matching.find {it.id==selectedFood} ?: matching.first()
        var amount by rememberSaveable(day,food.id,food.unit) {mutableStateOf("")}
        val quantity=amount.toDoubleOrNull()
        Choice("食品",food,matching,{"${it.name}（${it.unit}）"},enabled=!busy) {selectedFood=it.id}
        NumberField("预设数量 ${food.unit}",amount,enabled=!busy) {amount=it}
        FoodQuickAmounts(food,s.intakes,today,busy) {amount=PortionQuantities.inputText(it)}
        if(food.unit=="份") Text("新预设份数请填写整数。")
        val scheduled=s.dailyFoods.any {template->template.enabled && template.foodId==food.id &&
            s.dailySkips.none {it.day==day && it.templateId==template.id} &&
            runCatching {ScheduleCodec.decode(template.schedule).matches(day,template.startDay)}.getOrDefault(false)}
        if(scheduled) Text("这项食品也安排了所选日期固定自动添加。当天请核对实际已记数量，避免重复添加。",color=MaterialTheme.colorScheme.primary)
        Button(onClick={quantity?.let {q->vm.savePresetItem(day,food.id,q,expectedUnit=food.unit) {amount=""}}},
            enabled=!busy && hasBody && quantity!=null && PortionQuantities.isWhole(food.unit,quantity)) {Text("保存到所选日期预设")}
    }
    Text("本预设不自动合并每日固定项，也不包含其他尚未安排的饮食。到所选日期完成称重后，在今日页勾选确认，才会写入实际摄入。")
    HorizontalDivider()
    Text("所选日期已保存的食品",style=MaterialTheme.typography.titleMedium)
    if(items.isEmpty()) Text("这个日期还没有预设食品。保存后关闭、重启应用仍会保留。")
    items.forEach {item->key(item.id) {
        PresetItemCard(item,s.allFoods.find {it.id==item.foodId},busy,editingId==item.id,
            edit={editingId=item.id},cancel={editingId=null},
            save={q->vm.savePresetItem(day,item.foodId,q,item.id,expectedUnit=item.unit) {editingId=null}},
            remove={deletingId=item.id})
    }}
    PresetRatioSection(vm,plan,items,s.allFoods,busy)
    if(items.any {it.appliedIntakeId==null}) OutlinedButton(onClick={clearing=true},enabled=!busy) {Text("清空未添加预设")}
    PresetRemovalDialogs(vm,items,day,busy,deletingId,clearing,{deletingId=null},{clearing=false})
}

@Composable fun ArrivedFoodPresetSection(vm:LedgerViewModel,s:LedgerState,day:Long,busy:Boolean) {
    val plan=s.presets.find {it.day==day} ?: return
    val items=s.presetItems.filter {it.day==day}.sortedBy {it.id}
    if(items.isEmpty()) return
    var expanded by rememberSaveable(day) {mutableStateOf(true)}
    var selectedIds by rememberSaveable(day) {mutableStateOf(listOf<Long>())}
    var confirming by rememberSaveable(day) {mutableStateOf(false)}
    var editingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var deletingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var clearing by rememberSaveable(day) {mutableStateOf(false)}
    val available=items.filter {it.appliedIntakeId==null && presetFoodUnchanged(it,s.allFoods.find {f->f.id==it.foodId})}
    val availableIds=available.map {it.id}.toSet()
    val selected=items.filter {it.id in selectedIds && it.id in availableIds}
    val obsoleteSelections=selectedIds.any {it !in availableIds}
    HorizontalDivider()
    OutlinedButton(onClick={expanded=!expanded},enabled=!busy) {Text(if(expanded) "收起提前预设" else "查看提前预设")}
    if(!expanded) return
    Text("提前预设 · 等待确认实际吃下的食品",style=MaterialTheme.typography.titleMedium)
    Text("完成今日身体记录后仍不会自动加入。请勾选已经吃下、准备记录的项目；确认后按今日数据更新实际缺口，提前保存的预估基准保持不变。")
    PresetSummary(plan,items,arrived=true)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={selectedIds=available.map {it.id}},enabled=!busy && available.isNotEmpty()) {Text("选择可添加项")}
        TextButton(onClick={selectedIds=emptyList()},enabled=!busy && selectedIds.isNotEmpty()) {Text("取消选择")}
    }
    items.forEach {item->key(item.id) {
        val applied=item.appliedIntakeId!=null
        val canSelect=item.id in availableIds
        Row {
            Checkbox(item.id in selectedIds,{checked->selectedIds=if(checked) (selectedIds+item.id).distinct() else selectedIds-item.id},enabled=!busy && canSelect)
            Text(if(applied) {
                if(s.intakes.any {it.id==item.appliedIntakeId}) "已添加到今日摄入" else "已添加过，原摄入现已删除；不会自动重复添加"
            } else if(canSelect) "尚未计入摄入" else "资料已变化或食品不可用，请先处理",Modifier.weight(1f).padding(top=12.dp))
        }
        PresetItemCard(item,s.allFoods.find {it.id==item.foodId},busy,editingId==item.id,
            edit={editingId=item.id},cancel={editingId=null},
            save={q->vm.savePresetItem(day,item.foodId,q,item.id,expectedUnit=item.unit) {editingId=null;selectedIds=selectedIds-item.id}},
            remove={deletingId=item.id})
    }}
    if(obsoleteSelections) Text("有已选择项目的状态发生变化，请取消选择后重新勾选；不会自动忽略这些项目。",color=MaterialTheme.colorScheme.error)
    PresetRatioSection(vm,plan,items,s.allFoods,busy)
    if(selected.any {item->s.intakes.any {it.day==day && it.foodId==item.foodId}}) Text("所选食品今天已有摄入记录，可能来自每日固定项或手动添加。确认前请核对，避免重复记录。",color=MaterialTheme.colorScheme.primary)
    Button(onClick={confirming=true},enabled=!busy && selected.isNotEmpty() && !obsoleteSelections) {Text("添加所选到今日摄入（${selected.size} 项）")}
    if(items.any {it.appliedIntakeId==null}) OutlinedButton(onClick={clearing=true},enabled=!busy) {Text("清空未添加预设")}
    if(confirming) AlertDialog(onDismissRequest={if(!busy) confirming=false},title={Text("确认添加到今日摄入？")},
        text={Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            selected.forEach {Text("${it.name} · ${PortionQuantities.format(it.unit,it.quantity)} ${it.unit} · ${it.nutrition.kcal.energy(LocalEnergyUnit.current)}")}
            Text("只添加上面勾选的项目，不会替换今日已有饮食，也不会修改身体数据或目标。")
            if(selected.any {item->s.intakes.any {it.day==day && it.foodId==item.foodId}}) Text("这些食品中有今天已经记录过的项目，请再次核对数量。",color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={vm.applyPreset(day,selected.map {it.id});confirming=false;selectedIds=emptyList()},enabled=!busy && selected.isNotEmpty() && !obsoleteSelections) {Text("确认添加")}},
        dismissButton={TextButton(onClick={confirming=false},enabled=!busy) {Text("取消")}})
    PresetRemovalDialogs(vm,items,day,busy,deletingId,clearing,{deletingId=null},{clearing=false})
    HorizontalDivider()
}

@Composable private fun PresetRatioSection(vm:LedgerViewModel,plan:FoodPresetPlan?,items:List<FoodPresetItem>,foods:List<Food>,busy:Boolean) {
    if(plan==null || items.isEmpty()) return
    var expanded by rememberSaveable(plan.day) {mutableStateOf(plan.ratioRules.isNotEmpty())}
    var encoded by rememberSaveable(plan.day,plan.ratioRules) {mutableStateOf(plan.ratioRules)}
    var confirming by remember {mutableStateOf<PresetRatioPreview?>(null)}
    val itemFoodIds=items.map {it.foodId}.toSet()
    val choices=foods.filter {it.id in itemFoodIds && !it.isDeleted}
    OutlinedButton(onClick={expanded=!expanded},enabled=!busy) {Text(if(expanded) "收起预设重量比例" else "设置预设重量比例")}
    if(!expanded) return
    Text("例如A重量＝B重量×0.5。先在预设中加入两种食品；参与比例的食品各保留一条未添加预设。按份食品需先填写每份克重。")
    WeightRatioEditor(choices,encoded,busy) {encoded=it}
    Text("先保存规则，再确认按调整单位取整后的数量。只保存规则不会改变数量；手动预设不受推荐上限限制。")
    Button(onClick={vm.savePresetRatios(plan.day,encoded)},enabled=!busy && encoded!=plan.ratioRules) {Text("保存本日比例规则")}
    if(encoded!=plan.ratioRules) {Text("有未保存的比例修改，保存后再预览数量。");return}
    if(plan.ratioRules.isEmpty()) return
    val result=runCatching {FoodPresetRatios.preview(plan,items,foods)}
    val preview=result.getOrNull()
    if(preview==null) Text(result.exceptionOrNull()?.message ?: "当前预设无法按比例调整，请核对食品和数量。",color=MaterialTheme.colorScheme.error)
    else {
        RatioDeviationRows(preview.foods,preview.rounding.deviations)
        val total=FoodPresetEstimates.calculate(plan.referenceExpenditureKcal,preview.updated.map {it.nutrition})
        NutritionCard("按比例调整后预览 · 未保存数量",total.nutrition)
        val known=preview.updated.sumOf {it.costYuan ?: 0.0}
        val missing=preview.updated.count {it.costYuan==null}
        Text(if(known.isFinite()) "调整后已计价费用 ¥${known.money()}"+(if(missing>0) " · $missing 项未计价" else "") else "调整后费用合计超出范围")
        val changes=preview.updated.filter {next->next!=preview.before.first {it.id==next.id}}
        Text("仅调整本日参与比例的未添加条目，并刷新这些食品的名称、营养和费用快照；参考消耗和实际摄入不变。")
        Button(onClick={confirming=preview},enabled=!busy && changes.isNotEmpty()) {Text("确认比例数量并保存预设")}
        if(changes.isEmpty()) Text("当前数量已经符合取整后的比例。")
    }
    confirming?.let {expected->AlertDialog(onDismissRequest={if(!busy) confirming=null},title={Text("保存这次比例调整？")},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            expected.updated.filter {next->next!=expected.before.first {it.id==next.id}}.forEach {next->
                val previous=expected.before.first {it.id==next.id}
                Text("${next.name}：${PortionQuantities.format(previous.unit,previous.quantity)} → ${PortionQuantities.format(next.unit,next.quantity)} ${next.unit}")
            }
            RatioDeviationRows(expected.foods,expected.rounding.deviations)
            Text("只保存上述预设调整，不会记录摄入或改写身体、每日目标。保存前会重新核对食品、比例和数量；资料变化时拒绝这次旧预览。")
        }},confirmButton={TextButton(onClick={vm.applyPresetRatios(expected);confirming=null},enabled=!busy) {Text("确认保存")}},
        dismissButton={TextButton(onClick={confirming=null},enabled=!busy) {Text("取消")}})}
}

@Composable private fun PresetSummary(plan:FoodPresetPlan?,items:List<FoodPresetItem>,arrived:Boolean=false) {
    val estimate=runCatching {FoodPresetEstimates.calculate(plan?.referenceExpenditureKcal,items.map {it.nutrition})}.getOrNull()
    if(estimate==null) {Text("预设合计超出可计算范围，请调整数量。",color=MaterialTheme.colorScheme.error);return}
    val total=estimate.nutrition
    NutritionCard(if(items.any {it.appliedIntakeId!=null}) "预设合计 · 保留安排快照" else "预设总摄入 · 尚未计入摄入",total)
    val known=items.sumOf {it.costYuan ?: 0.0}
    val unknown=items.count {it.costYuan==null}
    Text(if(known.isFinite()) "已计价预设费用：¥${known.money()}"+(if(unknown>0) " · $unknown 项未计价" else "") else "预设费用合计超出可计算范围")
    if(plan==null) Text("保存食品后建立所选日期预设；参考基准来自今天已经保存的数据。")
    else {
        Text("预估基准：${LocalDate.ofEpochDay(plan.referenceDay)} 已保存的总消耗")
        val expenditure=plan.referenceExpenditureKcal
        if(expenditure==null) Text("参考日期没有有效消耗快照，暂时无法估算热量缺口。食品预设仍会保留，不使用0或当前资料补算。")
        else {
            Text("参考消耗：${expenditure.energy(LocalEnergyUnit.current)}")
            val gap=requireNotNull(estimate.deficitKcal)
            Text("${if(gap>=0) "预计热量缺口" else "预计热量盈余"}：${abs(gap).energy(LocalEnergyUnit.current)}",style=MaterialTheme.typography.titleLarge)
        }
    }
    val label=if(arrived) "今日" else "所选日期"
    Text("仅按本预设估算，未包含${label}固定自动添加项及其他饮食；实际缺口仍按当日身体、消耗和实际摄入计算。")
}

private fun presetFoodUnchanged(item:FoodPresetItem,food:Food?):Boolean {
    if(food==null || food.isDeleted || food.unit!=item.unit || food.name!=item.name || !PortionQuantities.isWhole(item.unit,item.quantity)) return false
    return runCatching {
        val nutrition=food.nutrition*(item.quantity/food.basisAmount)
        val cost=FoodPricing.cost(food.pricePerUnitYuan,item.quantity)
        nutrition==item.nutrition && cost==item.costYuan
    }.getOrDefault(false)
}

@Composable private fun PresetItemCard(item:FoodPresetItem,food:Food?,busy:Boolean,editing:Boolean,
    edit:()->Unit,cancel:()->Unit,save:(Double)->Unit,remove:()->Unit) {
    val applied=item.appliedIntakeId!=null
    val available=food!=null && !food.isDeleted && food.unit==item.unit
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("${item.name} · ${PortionQuantities.format(item.unit,item.quantity)} ${item.unit}",style=MaterialTheme.typography.titleMedium)
        Text("${item.nutrition.kcal.energy(LocalEnergyUnit.current)} · 碳水 ${item.nutrition.carbs.f()} / 蛋白质 ${item.nutrition.protein.f()} / 脂肪 ${item.nutrition.fat.f()} g")
        Text(item.costYuan?.let {"预设费用 ¥${it.money()}"} ?: "预设费用未计价")
        if(applied) Text("本项已添加，保留原预设用于核对；实际摄入可在今日记录中修改或删除。")
        else {
            if(!available) Text("原食品已删除或单位已改变。可删除这条未添加预设，再选择有效食品；原记录不会自动换算。",color=MaterialTheme.colorScheme.error)
            else if(!presetFoodUnchanged(item,food)) Text("食品名称、营养或价格已改变。请点修改数量并保存以刷新资料，再确认添加。",color=MaterialTheme.colorScheme.error)
            if(editing && available) {
                var amount by rememberSaveable(item.id,item.quantity) {mutableStateOf(PortionQuantities.inputText(item.quantity))}
                val quantity=amount.toDoubleOrNull()
                NumberField("预设数量 ${item.unit}",amount,enabled=!busy) {amount=it}
                Text("保存将使用食品库当前的名称、营养和价格刷新这条预设；其他记录不变。")
                Row {
                    Button(onClick={quantity?.let(save)},enabled=!busy && quantity!=null && PortionQuantities.isWhole(item.unit,quantity)) {Text("保存数量并刷新资料")}
                    TextButton(onClick=cancel,enabled=!busy) {Text("取消")}
                }
            } else Row {
                TextButton(onClick=edit,enabled=!busy && available) {Text("修改数量／更新资料")}
                TextButton(onClick=remove,enabled=!busy) {Text("删除预设")}
            }
        }
    }}
}

@Composable private fun PresetRemovalDialogs(vm:LedgerViewModel,items:List<FoodPresetItem>,day:Long,busy:Boolean,
    deletingId:Long?,clearing:Boolean,closeDelete:()->Unit,closeClear:()->Unit) {
    items.find {it.id==deletingId && it.appliedIntakeId==null}?.let {item->
        AlertDialog(onDismissRequest={if(!busy) closeDelete()},title={Text("删除这条预设？")},
            text={Text("删除「${item.name}」的未添加安排，并移除涉及它的比例规则；不删除食品或任何已有摄入，其他数量不变。")},
            confirmButton={TextButton(onClick={vm.removePresetItem(day,item.id);closeDelete()},enabled=!busy) {Text("删除预设")}},
            dismissButton={TextButton(onClick=closeDelete,enabled=!busy) {Text("取消")}})
    }
    if(clearing) AlertDialog(onDismissRequest={if(!busy) closeClear()},title={Text("清空未添加预设？")},
        text={Text("只清空尚未添加的安排，并移除涉及这些食品的比例规则。食品库、已经添加的摄入和防重复标记都会保留。")},
        confirmButton={TextButton(onClick={vm.clearPreset(day);closeClear()},enabled=!busy) {Text("清空未添加预设")}},
        dismissButton={TextButton(onClick=closeClear,enabled=!busy) {Text("取消")}})
}
