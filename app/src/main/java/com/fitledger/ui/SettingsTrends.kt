package com.fitledger.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.*
import java.time.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable private fun RuleEditor(label: String,rule: Rule,energy: Boolean=false,change:(Rule)->Unit) {
    val unit=LocalEnergyUnit.current
    Text(label,style=MaterialTheme.typography.titleMedium)
    Choice("计算方式",rule.basis,if(energy) listOf(Basis.FIXED,Basis.WEIGHT,Basis.LEAN) else Basis.entries.toList(),{it.label}) {change(rule.copy(basis=it))}
    var raw by remember(rule.value,unit) {mutableStateOf(if(rule.value.isFinite()) (if(energy) unit.fromKcal(rule.value) else rule.value).toString() else "")}
    if(rule.basis!=Basis.REMAINDER) NumberField(if(rule.basis==Basis.PERCENT) "百分数 %" else if(energy) "${unit.label} 或 ${unit.label}/kg" else "g 或 g/kg",raw) {raw=it;val number=it.toDoubleOrNull() ?: Double.NaN;change(rule.copy(value=if(energy) unit.toKcal(number) else number))}
}
@Composable fun SettingsScreen(vm: LedgerViewModel,p: Preferences,busy: Boolean) {
    var plan by remember(p.editablePlan) {mutableStateOf(p.editablePlan)}
    var advancedDraft by remember(p.editablePlan) {mutableStateOf(p.editablePlan.advanced)}
    var validDate by remember {mutableStateOf(true)}
    var initialConfirmation by remember {mutableStateOf<Triple<Plan,Long,BodyProfile>?>(null)}
    var confirmation by remember {mutableStateOf<Triple<Plan,Long,BodyProfile>?>(null)}
    var todayOnlyConfirmation by remember {mutableStateOf<Triple<Plan,Long,BodyProfile>?>(null)}
    val state by vm.state.collectAsStateWithLifecycle()
    val day by vm.today.collectAsStateWithLifecycle()
    Text("显示偏好",style=MaterialTheme.typography.titleLarge)
    Choice("默认热量单位",p.energyUnit,EnergyUnit.entries.toList(),{it.label}) {vm.energyUnit(it)}
    Text("首页、食品、推荐和趋势使用此单位显示；改变单位不会改变实际热量。")
    Text("录入偏好",style=MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("省略肌肉量输入",Modifier.weight(1f).padding(top=12.dp))
        Switch(p.skipMuscleInput,{vm.skipMuscleInput(it)},enabled=!busy)
    }
    Text("开启后，每日只需录入体重和体脂率，肌肉量保存为未记录，趋势留空。关闭后恢复每日填写；已保存的今日和历史数值不变。")
    Choice("体重输入单位",p.weightInputUnit,WeightUnit.entries.toList(),{it.label},enabled=!busy) {vm.weightInputUnit(it)}
    Text("称重时也可切换 kg／斤；1 kg = 2 斤。历史体重和各项计算继续统一使用 kg。")
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("按今日体重快捷填入食品克数",Modifier.weight(1f).padding(top=12.dp))
        Switch(p.weightFoodShortcut,{vm.weightFoodShortcut(it)},enabled=!busy)
    }
    Text("每 1 kg 体重对应 1 g 食品，例如 60 kg 可填入 60 g。仅适用于按 g 计量的食品；填入后可修改，再确认记录。")
    val draftProfile=ProfileEditor(vm,p.profile,busy,day)
    Text("饮食计划",style=MaterialTheme.typography.titleLarge)
    p.effectiveFrom?.takeIf {it>day}?.let {Text("已保存待生效计划：${LocalDate.ofEpochDay(it)} 起使用。")}
    Text("目标标签不自动改变摄入量；请配置自己的数值。固定宏量的总热量按 4/4/9 计算。热量基准用于百分比与剩余热量计算。")
    Choice("目标",plan.goal,listOf("减脂","维持","增肌"),{it}) {plan=plan.copy(goal=it)}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("高级组合计划",Modifier.weight(1f).padding(top=12.dp))
        Switch(plan.advanced!=null,{enabled->
            if(enabled) {
                val restored=advancedDraft ?: advancedFromLegacy(plan)
                advancedDraft=restored;plan=plan.copy(advanced=restored)
            } else {advancedDraft=plan.advanced;plan=plan.copy(advanced=null)}
        },enabled=!busy)
    }
    Text("高级组合可配置缺口、宏量分配、日期安排和运动回补。关闭时继续使用原计划规则；此处编辑在保存前不会改变目标。")
    if(plan.advanced!=null) AdvancedPlanEditor(requireNotNull(plan.advanced),day) {advancedDraft=it;plan=plan.copy(advanced=it)} else {
    Choice("计划",plan.mode,PlanMode.entries.toList(),{it.label}) {plan=plan.copy(mode=it)}
    if(plan.mode==PlanMode.RATIO) RatioEditor(plan) {plan=it} else {
        Choice("热量目标方式",plan.useExpenditureBudget,listOf(false,true),{if(it) "当天估算消耗 − 热量缺口" else "原热量基准规则"}) {plan=plan.copy(useExpenditureBudget=it)}
        if(plan.useExpenditureBudget) {
            DeficitEditor(plan.deficitKcal) {plan=plan.copy(deficitKcal=it)}
            Text("目标摄入 = 当天身体资料与活动系数估算消耗 − 缺口；不累加额外运动。")
            Text("先确定碳水、蛋白质的量，再将脂肪设为剩余热量。也可以选择其他一种宏量承接剩余。")
            TextButton(onClick={plan=plan.copy(carbs=if(plan.carbs.basis==Basis.REMAINDER) Rule(Basis.FIXED,250.0) else plan.carbs,protein=if(plan.protein.basis==Basis.REMAINDER) Rule(Basis.FIXED,120.0) else plan.protein,fat=Rule(Basis.REMAINDER))}) {Text("脂肪使用剩余热量")}
        } else RuleEditor("热量基准",plan.energy,true) {plan=plan.copy(energy=it)}
        RuleEditor("碳水",plan.carbs) {plan=plan.copy(carbs=it)}
        RuleEditor("蛋白质",plan.protein) {plan=plan.copy(protein=it)}
        RuleEditor("脂肪",plan.fat) {plan=plan.copy(fat=it)}
    }
    if(plan.mode==PlanMode.CYCLE) {
        Text(if(plan.useExpenditureBudget) "先按周一至周日倍率调整碳水，再分配剩余宏量，使总热量符合缺口目标。" else "周一至周日的碳水倍率；蛋白质、脂肪不变，总热量随碳水变化。")
        var raw by remember {mutableStateOf(plan.cycle.joinToString(","))}
        OutlinedTextField(raw,{raw=it;plan=plan.copy(cycle=it.replace('，',',').split(',').map {v->v.trim().toDoubleOrNull() ?: Double.NaN})},label={Text("7 个倍率，用逗号分隔")},modifier=Modifier.fillMaxWidth())
    }
    if(plan.mode==PlanMode.TAPER) {
        var date by remember {mutableStateOf(LocalDate.ofEpochDay(plan.start).toString())}
        var interval by remember {mutableStateOf(plan.taperDays.toString())}
        var decrement by remember {mutableStateOf(plan.taperGrams.toString())}
        var floor by remember {mutableStateOf(plan.minimumCarbs.toString())}
        OutlinedTextField(date,{date=it;val parsed=runCatching {LocalDate.parse(it)};validDate=parsed.isSuccess;parsed.onSuccess {d->plan=plan.copy(start=d.toEpochDay())}},label={Text("起始日期 YYYY-MM-DD")},isError=!validDate)
        NumberField("每隔多少天",interval) {interval=it;plan=plan.copy(taperDays=it.toIntOrNull() ?: 0)}
        NumberField("每阶段减少碳水 g",decrement) {decrement=it;plan=plan.copy(taperGrams=it.toDoubleOrNull() ?: Double.NaN)}
        NumberField("碳水最低量 g",floor) {floor=it;plan=plan.copy(minimumCarbs=it.toDoubleOrNull() ?: Double.NaN)}
    }
    }
    val preview=runCatching {
        val b=requireNotNull(state.bodies.find {it.day==day}) {"完成今日称重后显示预览"}
        val e=EnergyExpenditureCalculator.snapshot(b.weight,requireNotNull(draftProfile) {"请补全身体资料"},day)
        TargetPlanning.preview(plan,b,state.bodies,state.targets,state.exercises,e.expenditureKcal)
    }
    preview.getOrNull()?.let {nutrition->
        NutritionCard("按今日身体数据预览 · 尚未更新目标",nutrition)
        if(plan.advanced!=null) {
            Text("实际热量占比：碳水 ${(nutrition.carbs*4/nutrition.kcal*100).f()}% · 蛋白质 ${(nutrition.protein*4/nutrition.kcal*100).f()}% · 脂肪 ${(nutrition.fat*9/nutrition.kcal*100).f()}%")
            Text("严格百分比检查输入合计为 100%；上下限、碳水安排或定向运动回补可能改变最终占比，请以此预览为准。分别配置规则时，固定克数仅在你允许调整该项后才可改变。")
        }
    }
    if(plan.advanced==null && plan.useExpenditureBudget && plan.mode!=PlanMode.RATIO) {
        runCatching {val b=state.bodies.first {it.day==day};EnergyExpenditureCalculator.snapshot(b.weight,requireNotNull(draftProfile),day).expenditureKcal}.getOrNull()?.let {
            Text("当前资料估算消耗 ${it.energy(LocalEnergyUnit.current)} − 缺口 ${plan.deficitKcal.energy(LocalEnergyUnit.current)} = 目标 ${(it-plan.deficitKcal).energy(LocalEnergyUnit.current)}")
        }
    }
    if(preview.isFailure) Text(preview.exceptionOrNull()?.message ?: "请检查计划输入")
    val validConfiguration=runCatching {plan.validateConfiguration()}.isSuccess && (plan.advanced!=null || plan.mode!=PlanMode.TAPER || validDate)
    Button(onClick={draftProfile?.let {vm.savePlan(plan,it)}},enabled=!busy && draftProfile!=null && validConfiguration) {Text("正常保存（计划明天生效）")}
    val hasBody=state.bodies.any {it.day==day} && state.targets.any {it.day==day}
    OutlinedButton(onClick={draftProfile?.let {confirmation=Triple(plan,day,it)}},enabled=!busy && hasBody && draftProfile!=null && preview.isSuccess && validConfiguration) {Text("立即生效（今天及以后）")}
    OutlinedButton(onClick={draftProfile?.let {todayOnlyConfirmation=Triple(plan,day,it)}},enabled=!busy && hasBody && draftProfile!=null && preview.isSuccess && validConfiguration) {Text("仅更新今天")}
    Text("仅更新今天会保存今天的目标快照，保留明天及以后使用的计划与资料。训练／休息日的临时选择也可使用此按钮。")
    if(!hasBody) {
        Text("正常保存仍从明天生效。若当前计划无法完成今日称重，可先修正今日待生成的计划。")
        OutlinedButton(onClick={draftProfile?.let {initialConfirmation=Triple(plan,day,it)}},enabled=!busy && draftProfile!=null && validConfiguration) {Text("用于今日首次称重")}
    }
    initialConfirmation?.let {c->AlertDialog(onDismissRequest={initialConfirmation=null},title={Text("更新今日待生成计划？")},
        text={Text("仅在今天尚未保存身体和目标快照时可用。将保存当前资料与规则用于今日首次称重，保留历史数据及已安排的明日起计划。是否继续？")},
        confirmButton={TextButton(onClick={initialConfirmation=null;vm.setInitialPlan(c.first,c.second,c.third)},enabled=!busy){Text("确认用于今日")}},
        dismissButton={TextButton(onClick={initialConfirmation=null}){Text("取消")}})}
    confirmation?.let {confirmed -> AlertDialog(onDismissRequest={confirmation=null},title={Text("确认立即生效？")},
        text={Text("将保存当前编辑的资料与计划，使用新的性别、生日、身高、活动系数和今天已记录的身体数据，更新今日 BMR、估算消耗及营养目标。今日称重数值、已经记录的饮食和过去日期都不改变。是否继续？")},
        confirmButton={TextButton(onClick={confirmation=null;vm.applyToday(confirmed.first,confirmed.second,confirmed.third)},enabled=!busy) {Text("确认立即生效")}},
        dismissButton={TextButton(onClick={confirmation=null}) {Text("取消")}}) }
    todayOnlyConfirmation?.let {confirmed -> AlertDialog(onDismissRequest={todayOnlyConfirmation=null},title={Text("只更新今天的目标？")},
        text={Text("使用当前编辑的资料、计划及今天已记录的身体数据，重新计算今日能量与营养目标。今天的饮食、称重数值和过去日期保持原样。不会保存为以后使用的计划，也不会更改已安排的明日起计划。是否继续？")},
        confirmButton={TextButton(onClick={todayOnlyConfirmation=null;vm.applyTodayOnly(confirmed.first,confirmed.second,confirmed.third)},enabled=!busy) {Text("确认仅更新今天")}},
        dismissButton={TextButton(onClick={todayOnlyConfirmation=null}) {Text("取消")}}) }
    HorizontalDivider()
    Text("每日称重提醒",style=MaterialTheme.typography.titleLarge)
    var hour by remember(p.hour) {mutableStateOf(p.hour.toString())}; var minute by remember(p.minute) {mutableStateOf(p.minute.toString())}
    NumberField("小时 0–23",hour) {hour=it};NumberField("分钟 0–59",minute) {minute=it}
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(granted) vm.reminder(true,hour.toIntOrNull() ?: -1,minute.toIntOrNull() ?: -1)
        else vm.message.value="未获得通知权限；可在系统设置中允许通知后重新开启"
    }
    Text(if(p.reminder) "已启用 · %02d:%02d".format(p.hour,p.minute) else "尚未启用")
    Button(onClick={if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.reminder(true,hour.toIntOrNull() ?: -1,minute.toIntOrNull() ?: -1)},enabled=!busy) {Text("开启 / 更新提醒")}
    TextButton(onClick={vm.reminder(false,p.hour,p.minute)},enabled=!busy) {Text("关闭提醒")}
    Text("提醒使用系统后台任务，省电策略可能造成延迟；当天已经称重时不通知。")
    HorizontalDivider()
    DataManagement(vm,busy)
}

@Composable private fun DataManagement(vm:LedgerViewModel,busy:Boolean) {
    Text("数据管理",style=MaterialTheme.typography.titleLarge)
    Text("备份包含身体、食品、饮食、目标快照、计划和设置。文件不加密，请保存在可信位置；卸载会删除 App 内的数据。")
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {it?.let {uri->vm.exportBackup(uri)}}
    val previous=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {it?.let {uri->vm.exportBackup(uri,true)}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(vm::previewRestore)}
    Button(onClick={export.launch("FitLedger-${LocalDate.now()}.json")},enabled=!busy) {Text("导出备份")}
    OutlinedButton(onClick={restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy) {Text("从备份恢复")}
    TextButton(onClick={previous.launch("FitLedger-before-restore-${LocalDate.now()}.json")},enabled=!busy) {Text("导出上次恢复前副本")}
    val preview by vm.restorePreview.collectAsStateWithLifecycle()
    preview?.let {b -> AlertDialog(onDismissRequest={if(!busy) vm.cancelRestore()},title={Text("恢复将覆盖当前数据")},
        text={Text("备份时间：${b.exportTime}\n来源版本：${b.appVersion}\n身体 ${b.bodies.size} 条 · 目标 ${b.targets.size} 条\n在库食品 ${b.foods.count {!it.isDeleted}} 条 · 已删除食品 ${b.foods.count {it.isDeleted}} 条\n饮食 ${b.intakes.size} 条 · 运动 ${b.exercises.size} 条\n\n将用这份备份整体替换本机数据与重要设置，不会合并。食品及其删除状态将恢复到备份时的状态。恢复前会自动保存本机副本；建议先导出自己的备份。确定继续？")},
        confirmButton={TextButton(onClick={vm.confirmRestore()},enabled=!busy) {Text("确认覆盖并恢复")}},
        dismissButton={TextButton(onClick={vm.cancelRestore()},enabled=!busy) {Text("取消")}}) }
}

private data class Metric(val label: String,val unit: String,val color: Color,val field:BodyField?=null,val value:(LedgerState,Long)->Double?)
private fun total(s: LedgerState,day: Long) = s.intakes.filter {it.day==day}.takeIf {it.isNotEmpty()}?.fold(Nutrition()) {a,i->a+i.nutrition}
private val metrics = listOf(
    Metric("体重","kg",Color(0xFF167D65),BodyField.WEIGHT) {s,d->s.bodies.find {it.day==d}?.weight},
    Metric("体脂率","%",Color(0xFF9B671A),BodyField.BODY_FAT) {s,d->s.bodies.find {it.day==d}?.fatPercent},
    Metric("瘦体重","kg",Color(0xFF336CB0),BodyField.LEAN) {s,d->s.bodies.find {it.day==d}?.lean},
    Metric("肌肉量","kg",Color(0xFF8A50A0),BodyField.MUSCLE) {s,d->s.bodies.find {it.day==d}?.muscle},
    Metric("热量摄入","kcal",Color(0xFFC25345)) {s,d->total(s,d)?.kcal},
    Metric("剩余摄入额度（目标−已记录摄入）","kcal",Color(0xFF63742B)) {s,d->total(s,d)?.let {n->s.targets.find {it.day==d}?.nutrition?.kcal?.minus(n.kcal)}},
    Metric("碳水","g",Color(0xFF257E95)) {s,d->total(s,d)?.carbs},
    Metric("蛋白质","g",Color(0xFFAB4F79)) {s,d->total(s,d)?.protein},
    Metric("脂肪","g",Color(0xFF865935)) {s,d->total(s,d)?.fat},
    Metric("基础代谢 BMR（估算）","kcal",Color(0xFF575499),BodyField.BMR) {s,d->s.bodies.find {it.day==d}?.energy?.bmrKcal},
    Metric("估算每日总消耗（含额外运动）","kcal",Color(0xFF237B50)) {s,d->totalEnergy(s,d)},
    Metric("额外运动消耗","kcal",Color(0xFF167D65)) {s,d->if(s.bodies.any {it.day==d}) extraEnergy(s,d) else null},
    Metric("目标摄入热量","kcal",Color(0xFFA17216)) {s,d->s.targets.find {it.day==d}?.nutrition?.kcal},
    Metric("计划热量差（消耗−目标）","kcal",Color(0xFF497594)) {s,d->totalEnergy(s,d)?.let {energy->s.targets.find {it.day==d}?.nutrition?.let {energy-it.kcal}}},
    Metric("估算实际热量差（总消耗−已记录摄入）","kcal",Color(0xFFA5496D)) {s,d->totalEnergy(s,d)?.let {energy->total(s,d)?.let {energy-it.kcal}}}
)
@Composable fun TrendsScreen(s: LedgerState,day: Long) {
    val energyUnit=LocalEnergyUnit.current
    var days by rememberSaveable {mutableIntStateOf(30)}
    var selected by rememberSaveable {mutableStateOf(listOf(0,1,2))}
    Text("身体与营养趋势",style=MaterialTheme.typography.titleLarge)
    Choice("时间范围",days,listOf(7,30,90),{"${it}天"}) {days=it}
    metrics.forEachIndexed {i,m-> Row {Checkbox(i in selected,{selected=if(it) selected+i else selected-i});Text("${m.label} (${if(m.unit=="kcal") energyUnit.label else m.unit})",Modifier.padding(top=12.dp))} }
    Text("总消耗包含当日计入的额外运动，能量消耗是公式估算。计划热量差 = 估算消耗 − 目标；实际热量差 = 估算消耗 − 已记录摄入。正数表示缺口，负数表示盈余。缺失记录留空，不反推历史值。")
    selected.sorted().forEach { index ->
        val metric=metrics[index]
        if(metric.field!=null && !LocalBodyVisibility.current.shows(metric.field)) {
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {Text(metric.label);Text("•••• · 此项数值与曲线已隐藏");VisibilityEye(metric.field)}}
            return@forEach
        }
        val displayUnit=if(metric.unit=="kcal") energyUnit.label else metric.unit
        val points=(day-days+1..day).map {it to metric.value(s,it)?.let {value->if(metric.unit=="kcal") energyUnit.fromKcal(value) else value}}
        val valid=points.mapNotNull {it.second}
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
            Text(metric.label,style=MaterialTheme.typography.titleMedium);metric.field?.let {VisibilityEye(it)}
            if(valid.isEmpty()) Text("暂无记录") else {
                val low=valid.min();val high=valid.max();val span=(high-low).coerceAtLeast(1.0)
                Text("${low.f()} – ${high.f()} $displayUnit · 最近 ${valid.last().f()}")
                Canvas(Modifier.fillMaxWidth().height(120.dp).padding(8.dp)) {
                    fun point(i:Int,v:Double)=Offset(i.toFloat()/(days-1)*size.width,size.height-((v-low)/span*size.height).toFloat())
                    points.forEachIndexed {i,p->p.second?.let {v->
                        drawCircle(metric.color,4.dp.toPx(),point(i,v))
                        if(i>0) points[i-1].second?.let {previous->drawLine(metric.color,point(i-1,previous),point(i,v),2.dp.toPx())}
                    }}
                }
                Text("${LocalDate.ofEpochDay(day-days+1)} → ${LocalDate.ofEpochDay(day)}",style=MaterialTheme.typography.labelSmall)
            }
        }}
    }
}





