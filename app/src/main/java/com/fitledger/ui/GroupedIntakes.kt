package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.Intake

@Composable fun GroupedIntakes(entries:List<Intake>,day:Long,busy:Boolean,onDelete:(Long)->Unit) {
    val groups=remember(entries,day) {groupIntakesForDay(entries,day)}
    groups.forEach {group->key(day,group.key.foodId,group.key.unit,group.key.source) {
        var expanded by rememberSaveable(day,group.key.foodId,group.key.unit,group.key.source) {mutableStateOf(false)}
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("${group.name} ${group.quantity.f()} ${group.key.unit}",style=MaterialTheme.typography.titleMedium)
                    Text("合计 ${group.nutrition.kcal.energy(LocalEnergyUnit.current)} · ${group.entries.size} 次")
                    if(group.key.source=="DIRECT") Text("碳水 ${group.nutrition.carbs.f()} g · 蛋白质 ${group.nutrition.protein.f()} g · 脂肪 ${group.nutrition.fat.f()} g")
                }
                if(group.entries.size>1) TextButton(onClick={expanded=!expanded}) {Text(if(expanded) "收起 ▴" else "展开 ▾")}
                else TextButton(onClick={onDelete(group.entries.single().id)},enabled=!busy) {Text("删除")}
            }
            if(expanded && group.entries.size>1) {
                HorizontalDivider()
                group.entries.forEachIndexed {index,entry->key(entry.id) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("第 ${index+1} 次 · ${entry.name}\n${entry.quantity.f()} ${entry.unit} · ${entry.nutrition.kcal.energy(LocalEnergyUnit.current)}"+
                            if(entry.source=="DIRECT") "\n碳水 ${entry.nutrition.carbs.f()} g · 蛋白质 ${entry.nutrition.protein.f()} g · 脂肪 ${entry.nutrition.fat.f()} g" else "",Modifier.weight(1f))
                        TextButton(onClick={onDelete(entry.id)},enabled=!busy) {Text("删除此次")}
                    }
                }}
            }
        }}
    }}
}
