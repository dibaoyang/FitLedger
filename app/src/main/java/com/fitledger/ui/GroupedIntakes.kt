package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.Intake
import com.fitledger.data.macrosKnown
import com.fitledger.domain.PortionQuantities

@Composable fun GroupedIntakes(entries:List<Intake>,day:Long,busy:Boolean,onDelete:(Long)->Unit,onEdit:(Long,Double)->Unit={_,_->},vm:LedgerViewModel?=null) {
    var editingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var quantity by rememberSaveable(day) {mutableStateOf("")}
    val groups=remember(entries,day) {groupIntakesForDay(entries,day)}
    groups.forEach {group->key(day,group.key.foodId,group.key.unit,group.key.source) {
        var expanded by rememberSaveable(day,group.key.foodId,group.key.unit,group.key.source) {mutableStateOf(false)}
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("${group.name} ${PortionQuantities.format(group.key.unit,group.quantity)} ${group.key.unit}",style=MaterialTheme.typography.titleMedium)
                    Text("合计 ${group.nutrition.kcal.energy(LocalEnergyUnit.current)} · ${group.entries.size} 次")
                    val missingPrices=group.entries.count {it.costYuan==null}
                    Text("费用 ¥${group.entries.sumOf {it.costYuan ?: 0.0}.money()}"+if(missingPrices>0) " · $missingPrices 次未计价" else "")
                    if(group.key.source=="DIRECT") Text("碳水 ${group.nutrition.carbs.f()} g · 蛋白质 ${group.nutrition.protein.f()} g · 脂肪 ${group.nutrition.fat.f()} g")
                    if(group.key.source=="ENERGY") Text("碳水、蛋白质、脂肪未知")
                }
                if(group.entries.size>1) TextButton(onClick={expanded=!expanded}) {Text(if(expanded) "收起 ▴" else "展开 ▾")}
                else Column {
                    val entry=group.entries.single()
                    if(entry.source in setOf("FOOD","ROUTINE")) TextButton(onClick={editingId=entry.id;quantity=entry.quantity.toString()},enabled=!busy) {Text("修改")}
                    if(!entry.macrosKnown && vm!=null) EnergyRecordActions(vm,entry,busy)
                    TextButton(onClick={onDelete(entry.id)},enabled=!busy) {Text("删除")}
                }
            }
            if(expanded && group.entries.size>1) {
                HorizontalDivider()
                group.entries.forEachIndexed {index,entry->key(entry.id) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("第 ${index+1} 次 · ${entry.name}\n${PortionQuantities.format(entry.unit,entry.quantity)} ${entry.unit} · ${entry.nutrition.kcal.energy(LocalEnergyUnit.current)}"+
                            if(entry.source=="DIRECT") "\n碳水 ${entry.nutrition.carbs.f()} g · 蛋白质 ${entry.nutrition.protein.f()} g · 脂肪 ${entry.nutrition.fat.f()} g" else "",Modifier.weight(1f))
                        Column {
                            if(entry.source=="ROUTINE") Text("每日固定")
                            if(entry.source in setOf("FOOD","ROUTINE")) TextButton(onClick={editingId=entry.id;quantity=entry.quantity.toString()},enabled=!busy) {Text("修改此次")}
                            if(!entry.macrosKnown && vm!=null) EnergyRecordActions(vm,entry,busy)
                            TextButton(onClick={onDelete(entry.id)},enabled=!busy) {Text("删除此次")}
                        }
                    }
                }}
            }
        }}
    }}
    entries.find {it.day==day && it.id==editingId}?.let {entry->
        AlertDialog(onDismissRequest={if(!busy) editingId=null},title={Text("修改 ${entry.name}")},text={Column {
            NumberField("本次摄入 ${entry.unit}",quantity) {quantity=it}
            Text("按这条记录原有的营养和价格同比调整，不使用食品库后来修改的值。")
            if(entry.unit=="份") Text("新份数请填整数；旧的小数份记录不会被自动取整。")
        }},confirmButton={TextButton(onClick={val q=quantity.toDoubleOrNull();if(q!=null && PortionQuantities.isWhole(entry.unit,q)) {onEdit(entry.id,q);editingId=null}},enabled=!busy && quantity.toDoubleOrNull()?.let {PortionQuantities.isWhole(entry.unit,it)}==true) {Text("保存")}},
            dismissButton={TextButton(onClick={editingId=null},enabled=!busy) {Text("取消")}})
    }
}
