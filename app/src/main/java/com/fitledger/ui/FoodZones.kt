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
import java.util.Locale

private val nutritionNames=linkedMapOf("CARBS" to "碳水区","PROTEIN" to "蛋白质区","FAT" to "脂肪区")
fun String.nutritionLabel()=split(',').mapNotNull {nutritionNames[it]}.joinToString("、").ifEmpty {"未分类"}
fun Double.money()=String.format(Locale.CHINA,"%.2f",this)
fun FoodZone.quotaUnitLabel()=if(limitUnit=="G") "g" else "份"
private fun Double.unitPrice()=java.math.BigDecimal.valueOf(this).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun filterFoods(foods:List<Food>,grouping:String,filter:String):List<Food> = when {
    filter=="ALL" -> foods
    grouping=="NUTRITION" && filter=="NONE" -> foods.filter {it.nutritionTags.isBlank()}
    grouping=="NUTRITION" -> foods.filter {filter in it.nutritionTags.split(',')}
    filter=="NONE" -> foods.filter {it.zoneId==null}
    else -> foods.filter {it.zoneId==filter.toLongOrNull()}
}

@Composable fun FoodGroupingFilter(grouping:String,filter:String,zones:List<FoodZone>,enabled:Boolean,onGrouping:(String)->Unit,onFilter:(String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(grouping=="TYPE",{onGrouping("TYPE")},label={Text("按种类")},enabled=enabled)
        FilterChip(grouping=="NUTRITION",{onGrouping("NUTRITION")},label={Text("按营养")},enabled=enabled)
    }
    val options=if(grouping=="TYPE") listOf("ALL","NONE")+zones.map {it.id.toString()} else listOf("ALL","NONE")+nutritionNames.keys
    LaunchedEffect(options,filter) {if(filter !in options) onFilter("ALL")}
    Choice("分区",filter.takeIf {it in options} ?: "ALL",options,{id->when(id) {
        "ALL"->"全部食品";"NONE"->if(grouping=="TYPE") "未分区" else "未分类"
        else->if(grouping=="TYPE") zones.find {it.id.toString()==id}?.name ?: "已删除的分区" else nutritionNames[id] ?: id
    }},enabled,onFilter)
}

@Composable fun NutritionTagEditor(tags:String,enabled:Boolean,change:(String)->Unit) {
    Text("营养分区 · 可多选",style=MaterialTheme.typography.titleMedium)
    nutritionNames.forEach {(id,name)->Row {
        Checkbox(id in tags.split(','),{checked->val next=tags.split(',').filter {it.isNotBlank()}.toMutableSet();if(checked) next.add(id) else next.remove(id);change(nutritionNames.keys.filter {it in next}.joinToString(","))},enabled=enabled)
        Text(name,Modifier.padding(top=12.dp))
    }}
    Text("由你选择，便于查找；分类不会改变营养值，也不另加推荐上限。")
}

@Composable fun FoodPriceEditor(unit:String,initial:Double?,enabled:Boolean,change:(Double?)->Unit) {
    var mode by rememberSaveable {mutableStateOf("DIRECT")}
    var direct by rememberSaveable {mutableStateOf(initial?.toString() ?: "")}
    var total by rememberSaveable {mutableStateOf("")}
    var packages by rememberSaveable {mutableStateOf("1")}
    var quantityPerPackage by rememberSaveable {mutableStateOf(if(unit=="g") "100" else "1")}
    fun update() {
        val value=if(mode=="DIRECT") {
            if(direct.isBlank()) null else direct.toDoubleOrNull() ?: Double.NaN
        } else if(total.isBlank()) null else {
            val cost=total.toDoubleOrNull() ?: Double.NaN
            val count=packages.toDoubleOrNull() ?: Double.NaN
            val perPackage=quantityPerPackage.toDoubleOrNull() ?: Double.NaN
            runCatching {FoodPricing.perUnit(cost,count,perPackage)}.getOrDefault(Double.NaN)
        }
        change(value)
    }
    Text("食品价格 · 可选",style=MaterialTheme.typography.titleMedium)
    Choice("价格录入",mode,listOf("DIRECT","PACKAGE"),{if(it=="DIRECT") "直接填每 $unit 价格" else "按购买包装换算"},enabled) {mode=it;update()}
    if(mode=="DIRECT") NumberField("每 $unit 多少元（空为未计价，0 为免费）",direct) {direct=it;update()}
    else {
        NumberField("一共支付多少元",total) {total=it;update()}
        NumberField("购买包数",packages) {packages=it;update()}
        NumberField("每包多少 $unit",quantityPerPackage) {quantityPerPackage=it;update()}
        val cost=total.toDoubleOrNull();val count=packages.toDoubleOrNull();val per=quantityPerPackage.toDoubleOrNull()
        if(cost!=null && cost.isFinite() && cost>=0 && count!=null && count.isFinite() && count>0 && per!=null && per.isFinite() && per>0 && (count*per).isFinite()) {
            val price=cost/(count*per)
            Text("约 ¥${price.unitPrice()} / $unit"+(if(unit=="g") " · ¥${(price*100).money()} / 100 g" else ""))
        }
    }
    Text("包数 × 每包数量 = 购买总量。例如 3 包共 12 元、每包 2 份，即每份 2 元。改价格只影响后续记录。")
}

@Composable fun FoodPriceLabel(food:Food) {
    food.pricePerUnitYuan?.let {p->Text("¥${p.unitPrice()} / ${food.unit}"+(if(food.unit=="g") " · ¥${(p*100).money()} / 100 g" else ""))} ?: Text("价格未填写")
}

@Composable fun DailyCostCard(entries:List<Intake>) {
    val known=entries.sumOf {it.costYuan ?: 0.0}
    val unknown=entries.count {it.costYuan==null}
    Text("今日已记录费用：¥${known.money()}"+if(unknown>0) " · $unknown 次摄入未计价" else "",style=MaterialTheme.typography.titleMedium)
}

@Composable fun ZoneQuotaOverview(s:LedgerState,day:Long) {
    if(s.zones.isEmpty()) return
    Text("种类分区推荐额度",style=MaterialTheme.typography.titleMedium)
    FoodZoneQuotas.summaries(s.zones,s.allFoods,s.intakes,day).forEach {q->
        Text("${q.zone.name}：今日已吃${if(q.incompatibleUnits) "额度待核对" else " ${q.consumedPortions.f()} ${q.zone.quotaUnitLabel()}"} · 每日上限 ${q.zone.dailyMaximum?.let {"${it.f()} ${q.zone.quotaUnitLabel()}"} ?: "不限"} · 本次最多 ${q.effectiveRecommendationMaximum?.let {"${it.f()} ${q.zone.quotaUnitLabel()}"} ?: "不限"}")

        if(q.incompatibleUnits) Text("${q.zone.name}存在旧单位、旧换算或今日记录单位不一致，暂不参与推荐。请到分区管理整理并确认上限；已有记录保留。",color=MaterialTheme.colorScheme.error)
    }
    Text("本区仅累计同单位食品；锁定食品也占用额度，手动摄入不受推荐上限限制。")
}

@Composable fun ZoneManager(vm:LedgerViewModel,zones:List<FoodZone>,foods:List<Food>,busy:Boolean) {
    var open by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable {mutableStateOf<Long?>(null)}
    var showEditor by rememberSaveable {mutableStateOf(false)}
    var deleting by rememberSaveable {mutableStateOf<Long?>(null)}
    var adding by rememberSaveable {mutableStateOf<Long?>(null)}
    var removing by rememberSaveable {mutableStateOf<Long?>(null)}
    var removalUnit by rememberSaveable {mutableStateOf("份")}
    OutlinedButton(onClick={open=!open},enabled=!busy) {Text(if(open) "收起种类分区管理" else "管理种类分区")}
    if(!open) return
    Text("g 分区只放按 g 记录的食品，份分区只放按份记录的食品。每日和单次推荐上限分别填写，均使用本区单位。每日先扣除已吃量；单次留空跟随每日，均留空不限，0 表示不推荐。")
    Button(onClick={editing=null;showEditor=true},enabled=!busy) {Text("新增分区")}
    zones.forEach {zone->
        val issues=FoodZoneQuotas.zoneIssues(zone,foods)
        val wrongUnits=foods.filter {!it.isDeleted && it.zoneId==zone.id && !FoodZoneQuotas.matchesUnit(it,zone)}
        Row(Modifier.fillMaxWidth()) {
            Text("${zone.name}\n每日 ${zone.dailyMaximum?.let {"${it.f()} ${zone.quotaUnitLabel()}"} ?: "不限"} · 单次 ${zone.recommendationMaximum?.let {"${it.f()} ${zone.quotaUnitLabel()}"} ?: "跟随每日"}",Modifier.weight(1f))
            TextButton(onClick={editing=zone.id;showEditor=true},enabled=!busy) {Text("编辑")}
            TextButton(onClick={deleting=zone.id},enabled=!busy) {Text("删除")}
        }
        if(issues.isNotEmpty()) {
            Text("旧分区需要整理：${issues.joinToString("、") {it.name}}。可编辑分区选择匹配单位并重新确认上限，或将不同单位食品移至未分区；整理前暂停本区推荐。",color=MaterialTheme.colorScheme.error)
            if(wrongUnits.isNotEmpty()) TextButton(onClick={removing=zone.id;removalUnit=FoodZoneQuotas.unitFor(zone)},enabled=!busy) {Text("移出单位不匹配的食品")}
        }
        OutlinedButton(onClick={adding=zone.id},enabled=!busy && issues.isEmpty()) {Text("添加食品到本区")}
    }
    if(showEditor) key(editing) {
        val zone=zones.find {it.id==editing}
        val needsReview=zone?.let {FoodZoneQuotas.zoneIssues(it,foods).isNotEmpty()} ?: false
        var name by rememberSaveable {mutableStateOf(zone?.name ?: "")}
        var daily by rememberSaveable {mutableStateOf(if(needsReview) "" else zone?.dailyMaximum?.toString() ?: "")}
        var batch by rememberSaveable {mutableStateOf(if(needsReview) "" else zone?.recommendationMaximum?.toString() ?: "")}
        var limitUnit by rememberSaveable {mutableStateOf(zone?.limitUnit ?: "PORTION")}
        var unitChanged by rememberSaveable {mutableStateOf(false)}
        var reviewed by rememberSaveable {mutableStateOf(!needsReview)}
        LaunchedEffect(needsReview) {if(needsReview) {daily="";batch="";reviewed=false}}

        val nativeUnit=if(limitUnit=="G") "g" else "份"
        val wrongUnits=if(zone==null) emptyList() else foods.filter {!it.isDeleted && it.zoneId==zone.id && it.unit!=nativeUnit}
        OutlinedTextField(name,{name=it},label={Text("分区名称")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Choice("食品与上限单位",limitUnit,listOf("PORTION","G"),{if(it=="G") "g（克）" else "份"},enabled=!busy) {
            if(it!=limitUnit) {limitUnit=it;daily="";batch="";unitChanged=true;reviewed=false}
        }
        if(unitChanged || needsReview) Text("旧单位或旧换算不再混合使用，请按当前食品的原生数量重新确认上限。旧上限不会自动换算；留空表示不限。",color=MaterialTheme.colorScheme.primary)
        if(wrongUnits.isNotEmpty()) {
            Text("以下食品不按 $nativeUnit 记录，需先移出才能保存：${wrongUnits.joinToString("、") {it.name}}",color=MaterialTheme.colorScheme.error)
            TextButton(onClick={removing=zone?.id;removalUnit=nativeUnit},enabled=!busy && zone!=null) {Text("将这些食品移至未分区")}
        }
        NumberField("每日最多 $nativeUnit（空不限）",daily) {daily=it}
        NumberField("单次推荐最多 $nativeUnit（空跟随每日）",batch) {batch=it}
        Text(if(limitUnit=="PORTION") "直接累计每项食品记录的份数，例如 1 份面包加 2 份另一种面包共 3 份。" else "直接累计本区所有食品的克数，例如 100 g 加 50 g 共 150 g。")
        if(unitChanged || needsReview || !reviewed) Row {
            Checkbox(reviewed,{reviewed=it},enabled=!busy)
            Text("我已按当前单位重新确认上限，留空表示不限",Modifier.weight(1f).padding(top=12.dp))
        }

        Row {
            Button(onClick={vm.saveZone(FoodZone(zone?.id ?: 0,name.trim(),daily.takeIf {it.isNotBlank()}?.let {it.toDoubleOrNull() ?: Double.NaN},batch.takeIf {it.isNotBlank()}?.let {it.toDoubleOrNull() ?: Double.NaN},zone?.sortOrder ?: 0,limitUnit=limitUnit)) {showEditor=false}},enabled=!busy && wrongUnits.isEmpty() && reviewed) {Text(if(unitChanged || needsReview) "确认新上限并保存" else "保存分区")}
            TextButton(onClick={showEditor=false},enabled=!busy) {Text("取消")}
        }
    }
    zones.find {it.id==deleting}?.let {zone->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除 ${zone.name}？")},text={Text("食品会保留并移至未分区，所有已记录的摄入和营养不变。")},
        confirmButton={TextButton(onClick={vm.deleteZone(zone.id);deleting=null},enabled=!busy) {Text("删除分区")}},dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
    zones.find {it.id==removing}?.let {zone->
        val moved=foods.filter {!it.isDeleted && it.zoneId==zone.id && it.unit!=removalUnit}
        AlertDialog(onDismissRequest={if(!busy) removing=null},title={Text("将 ${moved.size} 项食品移至未分区？")},
            text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())) {
                Text("为使本区只包含按 $removalUnit 记录的食品，将移出以下项目：")
                moved.forEach {Text("${it.name}（${it.unit}）")}
                Text("只改变分区归属，不删除食品，不更改营养、价格和历史摄入。之后可建立同单位分区重新加入。")
            }},
            confirmButton={TextButton(onClick={vm.removeFoodsFromZone(zone.id,moved.map {it.id}) {removing=null}},enabled=!busy && moved.isNotEmpty()) {Text("确认移至未分区")}},
            dismissButton={TextButton(onClick={removing=null},enabled=!busy) {Text("取消")}})
    }
    zones.find {it.id==adding}?.let {zone->key(zone.id) {ZoneFoodPicker(zone,foods,zones,busy,onDismiss={adding=null},onConfirm={items->vm.assignFoodsToZone(zone.id,items) {adding=null}})}}
}
