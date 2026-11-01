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

@Composable fun IntakeSearchScreen(s:LedgerState,busy:Boolean,onOpenDay:(Long)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var first by rememberSaveable {mutableStateOf("")}
    var last by rememberSaveable {mutableStateOf("")}
    var source by rememberSaveable {mutableStateOf(IntakeSearchSource.ALL)}
    var shown by rememberSaveable {mutableIntStateOf(100)}
    LaunchedEffect(query,first,last,source) {shown=100}
    Text("搜索饮食记录",style=MaterialTheme.typography.titleLarge)
    Text("跨日期查找已经记录的食品，按保存时的名称搜索。待吃和未来预设不会混入结果，搜索不会修改记录。")
    OutlinedTextField(query,{query=it},label={Text("食品／记录名称，例如土豆泥")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(first,{first=it},label={Text("开始日期 yyyy-MM-dd（可留空）")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(last,{last=it},label={Text("结束日期 yyyy-MM-dd（可留空）")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    Choice("记录来源",source,IntakeSearchSource.entries.toList(),{it.label},enabled=!busy) {source=it}
    TextButton(onClick={query="";first="";last="";source=IntakeSearchSource.ALL},enabled=!busy) {Text("清空搜索条件")}
    val filter=runCatching {
        fun date(raw:String):Long?=if(raw.isBlank())null else LocalDate.parse(raw.trim()).toEpochDay()
        IntakeSearchFilter(query,date(first),date(last),source).also {it.validate()}
    }
    if(filter.isFailure) {Text(filter.exceptionOrNull()?.message?.takeUnless {it.contains("could not be parsed")} ?: "请填写有效日期，例如2026-10-31。",color=MaterialTheme.colorScheme.error);return}
    val matches=remember(s.intakes,filter.getOrNull()) {IntakeSearch.find(s.intakes,filter.getOrThrow())}
    val count=matches.sumOf {it.entries.size}
    Text("找到 $count 条实际记录，分布于 ${matches.size} 天。")
    if(count==0) {Text("没有符合条件的记录，可调整名称、日期或来源。");return}
    var remaining=shown
    matches.forEach {group->
        if(remaining<=0) return@forEach
        val visible=group.entries.take(remaining);remaining-=visible.size
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(LocalDate.ofEpochDay(group.day).toString(),Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={onOpenDay(group.day)},enabled=!busy) {Text("查看当天")}
            }
            val nutrition=runCatching {group.nutrition}.getOrNull()
            nutrition?.let {total->
                Text("当日匹配项合计 ${total.kcal.energy(LocalEnergyUnit.current)}")
                if(total.unknownMacroCount==0) Text("碳水 ${total.knownNutrition.carbs.f()}g · 蛋白质 ${total.knownNutrition.protein.f()}g · 脂肪 ${total.knownNutrition.fat.f()}g")
                else Text("三大营养合计不完整：${total.unknownMacroCount}条仅热量记录，未知不当作0。")
            }
            val known=runCatching {group.knownCostYuan}.getOrNull()
            Text(known?.let {"已计价 ¥${it.money()}"+(if(group.unknownPriceCount>0) " · ${group.unknownPriceCount}条未计价" else "")} ?: "费用合计超出范围")
            Text("以上仅为搜索匹配项，不代表当天全部饮食。",style=MaterialTheme.typography.bodySmall)
            visible.forEach {entry->key(entry.id) {
                HorizontalDivider()
                Text("${entry.name} · ${PortionQuantities.format(entry.unit,entry.quantity)} ${entry.unit}",style=MaterialTheme.typography.titleSmall)
                Text("${IntakeSearchSource.entries.find {it.name==entry.source}?.label ?: "其他记录"} · ${entry.nutrition.kcal.energy(LocalEnergyUnit.current)}")
                if(entry.macrosKnown) Text("碳水 ${entry.nutrition.carbs.f()}g · 蛋白质 ${entry.nutrition.protein.f()}g · 脂肪 ${entry.nutrition.fat.f()}g")
                else Text("碳水／蛋白质／脂肪未知")
                Text(entry.costYuan?.let {"记录费用 ¥${it.money()}"} ?: "记录费用未计价")
            }}
            if(visible.size<group.entries.size) Text("本日还有 ${group.entries.size-visible.size} 条匹配项，继续显示可查看。",style=MaterialTheme.typography.bodySmall)
        }}
    }
    if(shown<count) OutlinedButton(onClick={shown=minOf(count,shown+100)},enabled=!busy) {Text("继续显示100条（已显示${minOf(shown,count)}条）")}
}
