package com.fitledger.ui

import androidx.compose.foundation.layout.*
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
        Text("${q.zone.name}：今日已吃 ${q.consumedPortions.f()} 份 · 每日上限 ${q.zone.dailyMaximum?.f() ?: "不限"} · 本次最多 ${q.effectiveRecommendationMaximum?.f() ?: "不限"} 份")
        if(q.incompatibleUnits && q.zone.dailyMaximum!=null) Text("${q.zone.name}有今日记录的单位已改变，为避免低估已吃量，暂不推荐该区食品。",color=MaterialTheme.colorScheme.error)
    }
    Text("锁定食品也占用额度；手动记录不受分区上限限制。")
}

@Composable fun ZoneManager(vm:LedgerViewModel,zones:List<FoodZone>,busy:Boolean) {
    var open by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable {mutableStateOf<Long?>(null)}
    var showEditor by rememberSaveable {mutableStateOf(false)}
    var deleting by rememberSaveable {mutableStateOf<Long?>(null)}
    OutlinedButton(onClick={open=!open},enabled=!busy) {Text(if(open) "收起种类分区管理" else "管理种类分区")}
    if(!open) return
    Text("每日上限先扣除今天已吃的量；单次推荐上限留空时跟随每日上限。均留空则不限，0 表示不推荐该区食品。")
    Button(onClick={editing=null;showEditor=true},enabled=!busy) {Text("新增分区")}
    zones.forEach {zone->
        Row(Modifier.fillMaxWidth()) {
            Text("${zone.name}\n每日 ${zone.dailyMaximum?.f() ?: "不限"} 份 · 单次 ${zone.recommendationMaximum?.f() ?: "跟随每日"}",Modifier.weight(1f))
            TextButton(onClick={editing=zone.id;showEditor=true},enabled=!busy) {Text("编辑")}
            TextButton(onClick={deleting=zone.id},enabled=!busy) {Text("删除")}
        }
    }
    if(showEditor) key(editing) {
        val zone=zones.find {it.id==editing}
        var name by rememberSaveable {mutableStateOf(zone?.name ?: "")}
        var daily by rememberSaveable {mutableStateOf(zone?.dailyMaximum?.toString() ?: "")}
        var batch by rememberSaveable {mutableStateOf(zone?.recommendationMaximum?.toString() ?: "")}
        OutlinedTextField(name,{name=it},label={Text("分区名称")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
        NumberField("每日最多几分区份（空不限）",daily) {daily=it}
        NumberField("单次推荐最多几份（空跟随每日）",batch) {batch=it}
        Row {
            Button(onClick={vm.saveZone(FoodZone(zone?.id ?: 0,name.trim(),daily.takeIf {it.isNotBlank()}?.let {it.toDoubleOrNull() ?: Double.NaN},batch.takeIf {it.isNotBlank()}?.let {it.toDoubleOrNull() ?: Double.NaN},zone?.sortOrder ?: 0)) {showEditor=false}},enabled=!busy) {Text("保存分区")}
            TextButton(onClick={showEditor=false},enabled=!busy) {Text("取消")}
        }
    }
    zones.find {it.id==deleting}?.let {zone->AlertDialog(onDismissRequest={deleting=null},title={Text("删除 ${zone.name}？")},text={Text("食品会保留并移至未分区，所有已记录的摄入和营养不变。")},
        confirmButton={TextButton(onClick={vm.deleteZone(zone.id);deleting=null},enabled=!busy) {Text("删除分区")}},dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}})}
}
