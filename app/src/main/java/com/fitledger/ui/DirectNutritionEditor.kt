package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.DirectNutrition

@Composable fun DirectNutritionEditor(vm:LedgerViewModel,day:Long,busy:Boolean) {
    // Outside the expanded branch: collapsing never discards the draft. The parent TodayScreen
    // leaves composition on a tab change, so returning from another tab starts with empty inputs.
    var expanded by rememberSaveable(day) {mutableStateOf(false)}
    var carbs by rememberSaveable(day) {mutableStateOf("")}
    var protein by rememberSaveable(day) {mutableStateOf("")}
    var fat by rememberSaveable(day) {mutableStateOf("")}
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Text("直接添加营养",Modifier.weight(1f))
        Text(if(expanded) "▴" else "▾")
    }
    if(expanded) {
        NumberField("碳水 g",carbs) {carbs=it}
        NumberField("蛋白质 g",protein) {protein=it}
        NumberField("脂肪 g",fat) {fat=it}
        val preview=runCatching {DirectNutrition.parse(carbs,protein,fat)}
        preview.getOrNull()?.let {Text("本次约 ${it.kcal.energy(LocalEnergyUnit.current)}（碳水和蛋白质每克 4 kcal，脂肪每克 9 kcal）")}
        Text("未填项按 0 计算，至少一项大于 0。点击添加才计入今日摄入；收起保留输入，切换底部其他分区会清空输入。")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={carbs="";protein="";fat=""},enabled=!busy) {Text("清空")}
            Button(onClick={vm.recordNutrition(day,carbs,protein,fat)},enabled=!busy) {Text("添加")}
        }
    }
}
