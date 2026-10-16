package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*
import com.fitledger.data.PlanCodec
import com.fitledger.data.TargetPlanning

fun extraEnergy(s:LedgerState,day:Long)=s.exercises.filter {it.day==day && it.included}.sumOf {it.kcal}
fun baseEnergy(s:LedgerState,day:Long)=TargetPlanning.baseExpenditure(s.targets.find {it.day==day},s.bodies.find {it.day==day})
fun totalEnergy(s:LedgerState,day:Long)=ExerciseCalculator.total(baseEnergy(s,day),extraEnergy(s,day))

@Composable fun ExerciseSection(vm:LedgerViewModel,s:LedgerState,day:Long,busy:Boolean) {
    Text("今日运动与额外消耗",style=MaterialTheme.typography.titleLarge)
    Text("今日额外运动消耗：${extraEnergy(s,day).energy(LocalEnergyUnit.current)}")
    val advanced=s.targets.find {it.day==day}?.let {PlanCodec.decode(it.planSnapshot).advanced}
    val returnEnabled=(advanced?.exercise?.fraction ?: 0.0)>0
    var confirmedAction by remember(day) {mutableStateOf<(() -> Unit)?>(null)}
    if(returnEnabled) {
        val policy=advanced!!.exercise
        Text("今日计划补回 ${(policy.fraction*100).f()}% 的额外运动热量"+
            (policy.maximumKcal?.let {"，上限 ${it.energy(LocalEnergyUnit.current)}"} ?: "")+
            (policy.destination?.let {"，分配给${it.label}"} ?: "，按计划分配"))
        Text("添加或删除运动时，确认后同时更新今日目标。身体记录、已有饮食与未来计划保持不变。")
        OutlinedButton(onClick={confirmedAction={vm.recalculateExerciseTarget(day)}},enabled=!busy) {Text("按当前运动重算今日目标")}
    }
    confirmedAction?.let {action->AlertDialog(onDismissRequest={confirmedAction=null},title={Text("更新运动与今日目标？")},
        text={Text("将按今日已启用的运动补回规则重新计算目标和剩余营养。已有饮食、身体测量、历史日期和明日计划不会改变。若设置的营养限制无法满足，操作将提示错误并保持原记录。")},
        confirmButton={TextButton(onClick={confirmedAction=null;action()},enabled=!busy){Text("确认更新")}},
        dismissButton={TextButton(onClick={confirmedAction=null}){Text("取消")}})}
    var manual by rememberSaveable {mutableStateOf(false)}
    var type by remember {mutableStateOf(Cardio.HILL)}
    var minutes by rememberSaveable(day) {mutableStateOf("")}
    var raw by rememberSaveable(day) {mutableStateOf("")}
    val unit=LocalEnergyUnit.current
    var previousUnit by remember {mutableStateOf(unit)}
    LaunchedEffect(unit) {if(unit!=previousUnit) {raw=raw.toDoubleOrNull()?.let {unit.fromKcal(previousUnit.toKcal(it)).toString()} ?: raw;previousUnit=unit}}
    var included by rememberSaveable {mutableStateOf(true)}
    Choice("记录方式",manual,listOf(false,true),{if(it) "直接填写消耗" else "按运动时长估算"}) {manual=it}
    if(manual) NumberField("额外消耗 ${unit.label}",raw) {raw=it}
    else {
        Choice("有氧类型",type,Cardio.entries.toList(),{it.label}) {type=it}
        NumberField("有效运动时长（分钟）",minutes) {minutes=it}
        val estimate=runCatching {ExerciseCalculator.extra(s.bodies.first {it.day==day}.weight,minutes.toDouble(),type.met)}.getOrNull()
        estimate?.let {Text("预计额外消耗：${it.energy(unit)}")}
        Text("按当天体重和所选强度估算，已扣除同时段静息消耗。速度、坡度不同会影响结果，也可直接填写运动设备的活动消耗。")
    }
    Row {Checkbox(included,{included=it});Text("计入今日额外消耗",Modifier.padding(top=12.dp))}
    Text("如果活动系数已包含这次运动，请取消勾选，避免重复计入。手填的是额外/活动消耗，不是全天总消耗。")
    Button(onClick={
        val action={vm.exercise(day,if(manual) null else type,minutes.toDoubleOrNull(),raw.toDoubleOrNull()?.let(unit::toKcal),included,returnEnabled)}
        if(returnEnabled) confirmedAction=action else action()
    },enabled=!busy) {Text("添加运动记录")}
    s.exercises.filter {it.day==day}.forEach {e->
        Row(Modifier.fillMaxWidth()) {
            val name=if(e.type=="MANUAL") "手填消耗" else Cardio.valueOf(e.type).label
            Text("$name${e.minutes?.let {" · ${it.f()} 分钟"} ?: ""}\n${e.kcal.energy(unit)} · ${if(e.included) "计入额外消耗" else "仅记录、不累加"}",Modifier.weight(1f))
            TextButton(onClick={if(returnEnabled) confirmedAction={vm.removeExercise(day,e.id,true)} else vm.removeExercise(day,e.id)},enabled=!busy) {Text("删除")}
        }
    }
}
