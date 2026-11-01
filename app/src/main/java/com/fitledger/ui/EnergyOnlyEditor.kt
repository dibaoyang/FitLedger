package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.Intake
import com.fitledger.domain.DirectNutrition
import com.fitledger.domain.EnergyUnit

@Composable fun EnergyOnlyEditor(vm:LedgerViewModel,day:Long,busy:Boolean,historical:Boolean=false) {
    var expanded by rememberSaveable(day) {mutableStateOf(false)}
    OutlinedButton(onClick={expanded=!expanded},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(expanded) "仅记录热量 ▴" else "仅记录热量 ▾")}
    key(day) {EnergyOnlyForm(null,busy,expanded) {kcal,name,cost->
        if(historical) vm.historyRecordEnergy(day,kcal,name,cost) else vm.recordEnergy(day,kcal,name,cost)
    }}
}

@Composable private fun EnergyOnlyForm(initial:Intake?,busy:Boolean,visible:Boolean=true,onSave:(Double,String,Double?)->Unit) {
    val defaultUnit=LocalEnergyUnit.current
    var unit by rememberSaveable(initial?.id) {mutableStateOf(defaultUnit)}
    var raw by rememberSaveable(initial?.id) {mutableStateOf(initial?.nutrition?.kcal?.let {unit.fromKcal(it).toString()} ?: "")}
    var canonical by rememberSaveable(initial?.id) {mutableStateOf(initial?.nutrition?.kcal)}
    var name by rememberSaveable(initial?.id) {mutableStateOf(initial?.name ?: "")}
    var cost by rememberSaveable(initial?.id) {mutableStateOf(initial?.costYuan?.toString() ?: "")}
    if(!visible) return
    OutlinedTextField(name,{name=it},label={Text("名称或备注（可留空）")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
    Choice("热量单位",unit,EnergyUnit.entries.toList(),{it.label},enabled=!busy) {next->canonical?.let {raw=next.fromKcal(it).toString()};unit=next}
    NumberField("热量 ${unit.label}",raw,enabled=!busy) {raw=it;canonical=it.toDoubleOrNull()?.let(unit::toKcal)}
    NumberField("本次费用 元（空为未计价）",cost,enabled=!busy) {cost=it}
    Text("只记录热量；碳水、蛋白质和脂肪保留未知。仍计入摄入热量、实际缺口和费用，三大营养统计标记不完整；补全前暂停按剩余营养推荐。")
    val price=if(cost.isBlank()) null else cost.toDoubleOrNull()
    val valid=canonical?.let {it.isFinite() && it>0}==true && (cost.isBlank() || price!=null && price.isFinite() && price>=0) &&
        name.trim().length<=100 && name.none {it.code<32}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={raw="";canonical=null;name="";cost=""},enabled=!busy) {Text("清空")}
        Button(onClick={canonical?.let {onSave(it,name.trim().ifBlank {"仅热量记录"},price)}},enabled=!busy && valid) {Text(if(initial==null) "添加热量记录" else "保存修改")}
    }
}

@Composable fun EnergyRecordActions(vm:LedgerViewModel,entry:Intake,busy:Boolean) {
    var editing by rememberSaveable(entry.id) {mutableStateOf(false)}
    var completing by rememberSaveable(entry.id) {mutableStateOf(false)}
    Row {
        TextButton(onClick={editing=true},enabled=!busy) {Text("修改热量")}
        TextButton(onClick={completing=true},enabled=!busy) {Text("补全营养")}
    }
    if(editing) AlertDialog(onDismissRequest={if(!busy) editing=false},title={Text("修改仅热量记录")},
        text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
            EnergyOnlyForm(entry,busy) {kcal,name,cost->vm.historyRecordEnergy(entry.day,kcal,name,cost,entry.id);editing=false}
        }},confirmButton={},dismissButton={TextButton(onClick={editing=false},enabled=!busy) {Text("取消")}})
    if(completing) {
        var carbs by rememberSaveable(entry.id) {mutableStateOf("")}
        var protein by rememberSaveable(entry.id) {mutableStateOf("")}
        var fat by rememberSaveable(entry.id) {mutableStateOf("")}
        val values=listOf(carbs,protein,fat).map {it.toDoubleOrNull()}
        val n=if(values.all {it!=null}) runCatching {DirectNutrition.calculate(values[0]!!,values[1]!!,values[2]!!)}.getOrNull() else null
        AlertDialog(onDismissRequest={if(!busy) completing=false},title={Text("确认补全营养")},
            text={Column {
                Text("三项都需要明确填写，已知为0时填0。确认后按4/4/9计算热量，替换这条原来的估计热量；其他记录不变。")
                NumberField("碳水 g",carbs,enabled=!busy) {carbs=it}
                NumberField("蛋白质 g",protein,enabled=!busy) {protein=it}
                NumberField("脂肪 g",fat,enabled=!busy) {fat=it}
                Text("原估计：${entry.nutrition.kcal.energy(LocalEnergyUnit.current)}")
                n?.let {Text("补全后：${it.kcal.energy(LocalEnergyUnit.current)}")}
            }},confirmButton={TextButton(onClick={n?.let {vm.completeEnergy(entry.day,entry.id,it.carbs,it.protein,it.fat);completing=false}},enabled=!busy && n!=null) {Text("确认补全并更新本条热量")}},
            dismissButton={TextButton(onClick={completing=false},enabled=!busy) {Text("取消")}})
    }
}
