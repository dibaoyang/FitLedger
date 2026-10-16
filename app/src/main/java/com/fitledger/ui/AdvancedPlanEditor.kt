package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/** New rules are opt-in. Existing plan fields remain untouched when this editor is enabled. */
fun advancedFromLegacy(plan:Plan):AdvancedPlan {
    val budget=when {
        plan.mode==PlanMode.RATIO && plan.intakeMode==IntakeMode.FIXED -> EnergyBudget(BudgetMode.FIXED,plan.fixedIntakeKcal)
        plan.mode==PlanMode.RATIO -> EnergyBudget(BudgetMode.EXPENDITURE_OFFSET,plan.energyAdjustment)
        plan.useExpenditureBudget -> EnergyBudget(BudgetMode.EXPENDITURE_OFFSET,-plan.deficitKcal)
        plan.energy.basis==Basis.WEIGHT -> EnergyBudget(BudgetMode.WEIGHT,plan.energy.value)
        plan.energy.basis==Basis.LEAN -> EnergyBudget(BudgetMode.LEAN,plan.energy.value)
        else -> EnergyBudget(BudgetMode.FIXED,plan.energy.value)
    }
    fun spec(rule:Rule)=MacroSpec(if(rule.basis==Basis.REMAINDER) rule.copy(value=1.0) else rule)
    val macros=if(plan.mode==PlanMode.RATIO) MacroAllocation(AllocationMode.RATIO,
        MacroSpec(Rule(Basis.PERCENT,plan.ratio.carbs)),MacroSpec(Rule(Basis.PERCENT,plan.ratio.protein)),MacroSpec(Rule(Basis.PERCENT,plan.ratio.fat)))
        else MacroAllocation(AllocationMode.RULES,spec(plan.carbs),spec(plan.protein),spec(plan.fat))
    val schedule=CarbSchedule(
        mode=when(plan.mode) {PlanMode.CYCLE->CarbScheduleMode.WEEKLY_MULTIPLIER;PlanMode.TAPER->CarbScheduleMode.TAPER;else->CarbScheduleMode.NONE},
        multipliers=plan.cycle,start=plan.start,intervalDays=plan.taperDays,decrementGrams=plan.taperGrams,minimumGrams=plan.minimumCarbs)
    val consistency=if(plan.mode==PlanMode.RATIO || plan.useExpenditureBudget ||
        (schedule.mode==CarbScheduleMode.NONE && listOf(plan.carbs,plan.protein,plan.fat).any {it.basis==Basis.REMAINDER}))
        EnergyConsistency.FIXED_BUDGET else EnergyConsistency.MACROS_DEFINE_TOTAL
    return AdvancedPlan(templates=listOf(PlanTemplate(budget=budget,macros=macros,consistency=consistency,carbSchedule=schedule)))
}

@Composable private fun AdvancedGroup(title:String,initiallyOpen:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    var open by remember {mutableStateOf(initiallyOpen)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={open=!open},modifier=Modifier.fillMaxWidth()) {Text("${if(open) "▾" else "▸"} $title")}
        if(open) content()
    }}
}

@Composable private fun AdvancedNumber(label:String,value:Double,energy:Boolean=false,field:BodyField?=null,nonNegative:Boolean=false,change:(Double)->Unit) {
    val unit=LocalEnergyUnit.current
    fun display(v:Double)=if(v.isFinite()) (if(energy) unit.fromKcal(v) else v).toString() else ""
    var raw by remember(label,unit) {mutableStateOf(display(value))}
    var submitted by remember(label,unit) {mutableStateOf(value)}
    LaunchedEffect(value) {
        if(value.toBits()!=submitted.toBits()) {raw=display(value);submitted=value}
    }
    if(field!=null && !LocalBodyVisibility.current.shows(field)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("$label：••••",Modifier.weight(1f));VisibilityEye(field)
        }
    } else OutlinedTextField(raw,{text->
        raw=text
        val number=text.toDoubleOrNull() ?: Double.NaN
        val parsed=if(nonNegative && number<0) Double.NaN else number
        submitted=if(energy) unit.toKcal(parsed) else parsed
        change(submitted)
    },label={Text(if(energy) "$label ${unit.label}" else label)},singleLine=true,
        isError=!value.isFinite(),supportingText={if(!value.isFinite()) Text(if(nonNegative && (raw.toDoubleOrNull() ?: 0.0)<0) "请填写非负数；缺口／盈余由上方选择确定" else "请输入有效数字")},
        trailingIcon={field?.let {VisibilityEye(it)}},
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
}

@Composable private fun AdvancedInteger(label:String,value:Int,change:(Int)->Unit) {
    var raw by remember(label) {mutableStateOf(value.toString())}
    var submitted by remember(label) {mutableIntStateOf(value)}
    LaunchedEffect(value) {if(value!=submitted) {raw=value.toString();submitted=value}}
    OutlinedTextField(raw,{raw=it;submitted=it.toIntOrNull() ?: Int.MIN_VALUE;change(submitted)},label={Text(label)},
        singleLine=true,isError=raw.toIntOrNull()==null,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
}

@Composable private fun AdvancedDate(label:String,day:Long,change:(Long)->Unit) {
    var raw by remember(label) {mutableStateOf(runCatching {LocalDate.ofEpochDay(day).toString()}.getOrDefault(""))}
    val parsed=runCatching {LocalDate.parse(raw)}
    OutlinedTextField(raw,{raw=it;change(runCatching {LocalDate.parse(it).toEpochDay()}.getOrDefault(Long.MIN_VALUE))},
        label={Text("$label YYYY-MM-DD")},singleLine=true,isError=parsed.isFailure,modifier=Modifier.fillMaxWidth())
}

@Composable private fun AdvancedToggle(label:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(checked,change)
    }
}

@Composable fun AdvancedPlanEditor(plan:AdvancedPlan,day:Long,change:(AdvancedPlan)->Unit) {
    Text("每套日计划都包含热量预算与宏量规则；再选择日期安排。所有数值以当前热量单位输入，内部仍使用 kcal。")
    AdvancedGroup("1 · 日计划模板",true) {
        var selected by remember {mutableStateOf(plan.templates.firstOrNull()?.id ?: "base")}
        val current=plan.templates.firstOrNull {it.id==selected} ?: plan.templates.firstOrNull()
        if(current!=null) {
            Choice("编辑模板",current.id,plan.templates.map {it.id},{id->plan.templates.firstOrNull {it.id==id}?.name ?: id}) {selected=it}
            key(current.id) {TemplateEditor(current) {updated->change(plan.copy(templates=plan.templates.map {if(it.id==current.id) updated else it}))}}
            TextButton(onClick={
                val remaining=plan.templates.filter {it.id!=current.id}
                val fallback=remaining.first().id
                selected=fallback
                change(plan.copy(templates=remaining,schedule=replaceTemplate(plan.schedule,current.id,fallback)))
            },enabled=plan.templates.size>1) {Text("删除当前模板")}
            OutlinedButton(onClick={
                val added=current.copy(id=UUID.randomUUID().toString(),name="日计划 ${plan.templates.size+1}")
                selected=added.id;change(plan.copy(templates=plan.templates+added))
            },enabled=plan.templates.size<50) {Text("复制为新模板")}
        }
        Text("例如建立高碳／中碳／低碳或训练／休息模板，再到日期安排中选择。删除模板时，其日期安排会改用剩余的第一套模板。")
    }
    AdvancedGroup("2 · 日期安排",true) {ScheduleEditor(plan.schedule,plan.templates,day) {change(plan.copy(schedule=it))}}
    AdvancedGroup("3 · 身体数据参考") {
        Choice("计算参考",plan.reference.mode,ReferenceMode.entries.toList(),{it.label}) {change(plan.copy(reference=plan.reference.copy(mode=it)))}
        when(plan.reference.mode) {
            ReferenceMode.TODAY -> Text("使用当天已保存的体重和瘦体重。")
            ReferenceMode.AVERAGE_7_DAYS -> Text("使用当天及之前六天已有记录的平均体重和平均瘦体重；缺失日期不填零。每日仍须先完成称重。")
            ReferenceMode.FIXED -> {
                AdvancedNumber("参考体重 kg",plan.reference.weight,field=BodyField.WEIGHT) {change(plan.copy(reference=plan.reference.copy(weight=it)))}
                AdvancedNumber("参考瘦体重 kg",plan.reference.lean,field=BodyField.LEAN) {change(plan.copy(reference=plan.reference.copy(lean=it)))}
                Text("仅用于每公斤体重／瘦体重规则，真实每日身体记录与 BMR 仍按当天数据计算。隐藏项可通过旁边按钮显示后编辑。")
            }
        }
    }
    AdvancedGroup("4 · 整周热量预算") {
        WeeklyEditor(plan.weekly) {change(plan.copy(weekly=it))}
        if(plan.weekly.mode!=WeeklyBudgetMode.NONE && plan.templates.any {it.consistency!=EnergyConsistency.FIXED_BUDGET}) {
            Text("整周预算要求每套模板保持目标热量。",color=MaterialTheme.colorScheme.error)
            OutlinedButton(onClick={change(plan.copy(templates=plan.templates.map {it.copy(consistency=EnergyConsistency.FIXED_BUDGET)}))}) {Text("将所有模板设为保持目标热量")}
            Text("这只更改草稿；若固定宏量与新预算冲突，还需配置剩余热量或授权调整项。")
        }
    }
    AdvancedGroup("5 · 额外运动回补") {ExerciseAllocationEditor(plan.exercise) {change(plan.copy(exercise=it))}}
    Text("预览与保存会检查整套配置；目标热量、固定宏量和上下限冲突时会提示，不会生成负数营养目标。")
}

private fun replaceTemplate(s:TemplateSchedule,old:String,new:String):TemplateSchedule {
    fun replace(id:String)=if(id==old) new else id
    return s.copy(singleTemplate=replace(s.singleTemplate),weeklyTemplates=s.weeklyTemplates.map(::replace),
        cycleTemplates=s.cycleTemplates.map(::replace),trainingTemplate=replace(s.trainingTemplate),restTemplate=replace(s.restTemplate),
        stages=s.stages.map {it.copy(templateId=replace(it.templateId))})
}

@Composable private fun TemplateEditor(template:PlanTemplate,change:(PlanTemplate)->Unit) {
    OutlinedTextField(template.name,{change(template.copy(name=it))},label={Text("模板名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Choice("总热量规则",template.consistency,EnergyConsistency.entries.toList(),{it.label}) {change(template.copy(consistency=it))}
    Text(if(template.consistency==EnergyConsistency.FIXED_BUDGET) "先确定目标摄入，再分配三项营养素。固定值冲突时，仅允许调整你明确授权的项目。" else "三项营养素计算完成后得出总热量；下面的预算仅作为百分比和剩余热量的计算基准，不能同时保证固定缺口。")
    AdvancedGroup("热量预算",true) {BudgetEditor(template.budget) {change(template.copy(budget=it))}}
    AdvancedGroup("宏量营养分配",true) {MacroEditor(template.macros) {change(template.copy(macros=it))}}
    AdvancedGroup("碳水循环／渐降") {CarbScheduleEditor(template.carbSchedule) {change(template.copy(carbSchedule=it))}}
}

@Composable private fun BudgetEditor(budget:EnergyBudget,change:(EnergyBudget)->Unit) {
    Choice("目标计算方式",budget.mode,BudgetMode.entries.toList(),{it.label}) {mode->
        if(mode!=budget.mode) change(budget.copy(mode=mode,value=when(mode) {
            BudgetMode.FIXED->2000.0;BudgetMode.EXPENDITURE_OFFSET->-500.0;BudgetMode.EXPENDITURE_PERCENT->-15.0;BudgetMode.WEIGHT->30.0;BudgetMode.LEAN->35.0
        }))
    }
    Choice("消耗来源",budget.expenditureSource,ExpenditureSource.entries.toList(),{it.label}) {change(budget.copy(expenditureSource=it))}
    if(budget.expenditureSource==ExpenditureSource.MANUAL) {
        AdvancedNumber("全天消耗",budget.manualExpenditure,true) {change(budget.copy(manualExpenditure=it))}
        Text("手动全天消耗若已含运动，请将对应运动设为不计入额外消耗，避免重复统计。")
    }
    Text("消耗来源用于按消耗定目标、整周缺口和首页热量差；固定摄入或每公斤热量仍按所选预算计算。")
    when(budget.mode) {
        BudgetMode.FIXED -> AdvancedNumber("目标摄入",budget.value,true) {change(budget.copy(value=it))}
        BudgetMode.WEIGHT -> AdvancedNumber("每 kg 体重热量",budget.value,true) {change(budget.copy(value=it))}
        BudgetMode.LEAN -> AdvancedNumber("每 kg 瘦体重热量",budget.value,true) {change(budget.copy(value=it))}
        BudgetMode.EXPENDITURE_OFFSET, BudgetMode.EXPENDITURE_PERCENT -> {
            var direction by remember(budget.mode) {mutableStateOf(if(budget.value<0) "缺口" else if(budget.value>0) "盈余" else "维持")}
            Choice("调整方向",direction,listOf("缺口","维持","盈余"),{it}) {
                direction=it;change(budget.copy(value=when(it) {"缺口"->-abs(budget.value);"盈余"->abs(budget.value);else->0.0}))
            }
            if(direction!="维持") AdvancedNumber(if(budget.mode==BudgetMode.EXPENDITURE_PERCENT) "${direction}占消耗百分比 %" else "目标$direction",abs(budget.value),budget.mode==BudgetMode.EXPENDITURE_OFFSET,nonNegative=true) {
                change(budget.copy(value=if(direction=="缺口") -it else it))
            }
            Text(if(budget.mode==BudgetMode.EXPENDITURE_PERCENT) "百分比针对消耗计算，例如缺口 15% 表示摄入为消耗的 85%。" else "目标摄入 = 选定消耗 − 缺口，或消耗 + 盈余。")
        }
    }
}

private fun MacroAllocation.spec(m:Macro)=when(m) {Macro.CARBS->carbs;Macro.PROTEIN->protein;Macro.FAT->fat}
private fun MacroAllocation.withSpec(m:Macro,s:MacroSpec)=when(m) {Macro.CARBS->copy(carbs=s);Macro.PROTEIN->copy(protein=s);Macro.FAT->copy(fat=s)}
private fun MacroAllocation.withComputedPercent():MacroAllocation {
    if(mode!=AllocationMode.AUTO_PERCENT) return this
    val remaining=100-Macro.entries.filter {it!=autoMacro}.sumOf {spec(it).rule.value}
    return withSpec(autoMacro,spec(autoMacro).copy(rule=Rule(Basis.PERCENT,if(remaining.isFinite()) remaining.coerceAtLeast(0.0) else 0.0)))
}
private fun MacroAllocation.asPercentRules():MacroAllocation {
    val source=withComputedPercent()
    val original=source.specs().map {it.rule.value}
    val values=when {
        mode==AllocationMode.RULES && specs().any {it.rule.basis!=Basis.PERCENT} -> listOf(50.0,30.0,20.0)
        mode==AllocationMode.RATIO && original.all {it.isFinite() && it>=0} && original.sum().let {it.isFinite() && it>0} -> original.map {it/original.sum()*100}
        else -> original
    }
    return source.copy(carbs=source.carbs.copy(rule=Rule(Basis.PERCENT,values[0])),
        protein=source.protein.copy(rule=Rule(Basis.PERCENT,values[1])),fat=source.fat.copy(rule=Rule(Basis.PERCENT,values[2])))
}

@Composable private fun MacroEditor(macros:MacroAllocation,change:(MacroAllocation)->Unit) {
    Choice("分配方式",macros.mode,AllocationMode.entries.toList(),{it.label}) {mode->
        if(mode!=macros.mode) change(macros.asPercentRules().copy(mode=mode).withComputedPercent())
    }
    if(macros.mode==AllocationMode.RULES && macros.specs().any {it.rule.basis!=Basis.PERCENT}) Text("切换到比例／百分比模式时，混合克数规则会初始化为 50%／30%／20%；可继续修改并查看预览，保存前不会改变今日目标。")
    if(macros.mode==AllocationMode.AUTO_PERCENT) Choice("自动补齐哪一项",macros.autoMacro,Macro.entries.toList(),{it.label}) {change(macros.copy(autoMacro=it).withComputedPercent())}
    Macro.entries.forEach {macro->key(macro) {
        val spec=macros.spec(macro)
        if(macros.mode==AllocationMode.RULES) {
            Choice(macro.label,spec.rule.basis,Basis.entries.toList(),{it.label}) {basis->
                change(macros.withSpec(macro,spec.copy(rule=Rule(basis,if(basis==Basis.REMAINDER) 1.0 else spec.rule.value))))
            }
            val label=when(spec.rule.basis) {
                Basis.FIXED->"${macro.label} g";Basis.WEIGHT->"${macro.label} g / kg 体重";Basis.LEAN->"${macro.label} g / kg 瘦体重"
                Basis.PERCENT->"${macro.label}占总热量 %";Basis.REMAINDER->"${macro.label}分配剩余热量的权重"
            }
            AdvancedNumber(label,spec.rule.value) {change(macros.withSpec(macro,spec.copy(rule=spec.rule.copy(value=it))))}
        } else if(macros.mode==AllocationMode.AUTO_PERCENT && macro==macros.autoMacro) {
            val left=100-Macro.entries.filter {it!=macro}.sumOf {macros.spec(it).rule.value}
            Text("${macro.label}自动补齐：${if(left.isFinite()) left.f() else "—"}%",color=if(left.isFinite() && left>=0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        } else AdvancedNumber(if(macros.mode==AllocationMode.RATIO) "${macro.label}比例权重" else "${macro.label}热量百分比 %",spec.rule.value) {
            change(macros.withSpec(macro,spec.copy(rule=Rule(Basis.PERCENT,it))).withComputedPercent())
        }
    }}
    when(macros.mode) {
        AllocationMode.RULES -> Text("可固定一项，让另外两项都选剩余热量，再设置 7 和 3 等权重。百分比始终针对总目标热量；剩余权重只分配扣除已定项目后的热量。")
        AllocationMode.RATIO -> {
            val sum=macros.specs().sumOf {it.rule.value}
            if(sum.isFinite() && sum>0) Text("折算热量占比："+Macro.entries.joinToString(" · ") {"${it.label} ${(macros.spec(it).rule.value/sum*100).f()}%"})
            Text("5∶3∶2 与 50∶30∶20 等价；输入权重不要求总和为 100。")
        }
        AllocationMode.STRICT_PERCENT -> {
            val sum=macros.specs().sumOf {it.rule.value}
            Text("合计 ${if(sum.isFinite()) sum.f() else "—"}% · 必须等于 100%",color=if(sum.isFinite() && abs(sum-100)<0.000001) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
        AllocationMode.AUTO_PERCENT -> Text("只填写两项热量百分比；第三项自动补足至 100%。")
    }
    AdvancedGroup("宏量上下限与调整优先级") {
        Macro.entries.forEach {macro->key(macro) {
            val spec=macros.spec(macro)
            AdvancedNumber("${macro.label}最低量 g",spec.minimum) {change(macros.withSpec(macro,spec.copy(minimum=it)))}
            AdvancedToggle("限制${macro.label}最高量",spec.maximum!=null) {change(macros.withSpec(macro,spec.copy(maximum=if(it) maxOf(spec.minimum,300.0) else null)))}
            spec.maximum?.let {maximum->AdvancedNumber("${macro.label}最高量 g",maximum) {change(macros.withSpec(macro,spec.copy(maximum=it)))}}
        }}
        Text("允许调整的项目按下方顺序补足或减少热量。未选中的固定项目保持锁定；无法满足所有条件时提示冲突。比例可能因你启用的上下限和调整授权而变化。")
        Macro.entries.forEach {macro->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(macro in macros.adjustmentOrder,{enabled->change(macros.copy(adjustmentOrder=if(enabled) macros.adjustmentOrder+macro else macros.adjustmentOrder-macro))})
                Text("允许调整${macro.label}",Modifier.padding(top=12.dp))
            }
        }
        macros.adjustmentOrder.forEachIndexed {index,macro->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${index+1}. ${macro.label}",Modifier.weight(1f).padding(top=12.dp))
                TextButton(onClick={
                    val order=macros.adjustmentOrder.toMutableList();val previous=order[index-1];order[index-1]=macro;order[index]=previous
                    change(macros.copy(adjustmentOrder=order))
                },enabled=index>0) {Text("上移")}
            }
        }
    }
}

@Composable private fun CarbScheduleEditor(schedule:CarbSchedule,change:(CarbSchedule)->Unit) {
    Choice("碳水调整",schedule.mode,CarbScheduleMode.entries.toList(),{it.label}) {change(schedule.copy(mode=it))}
    when(schedule.mode) {
        CarbScheduleMode.NONE -> Unit
        CarbScheduleMode.WEEKLY_MULTIPLIER -> {
            Text("先计算碳水，再乘当日倍率。保持目标热量时，须为另外一项配置剩余热量或允许调整。")
            weekdays.forEachIndexed {index,label->AdvancedNumber("$label 碳水倍率",schedule.multipliers.getOrElse(index) {1.0}) {value->
                change(schedule.copy(multipliers=List(7) {if(it==index) value else schedule.multipliers.getOrElse(it) {1.0}}))
            }}
        }
        CarbScheduleMode.TAPER -> {
            AdvancedDate("渐降起始日期",schedule.start) {change(schedule.copy(start=it))}
            AdvancedInteger("每隔多少天减少",schedule.intervalDays) {change(schedule.copy(intervalDays=it))}
            AdvancedNumber("每阶段减少碳水 g",schedule.decrementGrams) {change(schedule.copy(decrementGrams=it))}
            AdvancedNumber("渐降最低碳水 g",schedule.minimumGrams) {change(schedule.copy(minimumGrams=it))}
            Text("保持目标热量时，减少碳水释放的热量会交给剩余项目或已授权调整的项目。由宏量决定总热量时，总热量可以随碳水下降。")
        }
    }
}

private val weekdays=listOf("周一","周二","周三","周四","周五","周六","周日")

@Composable private fun ScheduleEditor(schedule:TemplateSchedule,templates:List<PlanTemplate>,day:Long,change:(TemplateSchedule)->Unit) {
    fun name(id:String)=templates.firstOrNull {it.id==id}?.name ?: "模板已不存在"
    val ids=templates.map {it.id}
    Choice("日期安排",schedule.mode,ScheduleMode.entries.toList(),{it.label}) {change(schedule.copy(mode=it))}
    when(schedule.mode) {
        ScheduleMode.SINGLE -> Choice("每日使用",schedule.singleTemplate,ids,::name) {change(schedule.copy(singleTemplate=it))}
        ScheduleMode.WEEKLY -> weekdays.forEachIndexed {index,label->Choice(label,schedule.weeklyTemplates[index],ids,::name) {selected->
            change(schedule.copy(weeklyTemplates=schedule.weeklyTemplates.mapIndexed {i,id->if(i==index) selected else id}))
        }}
        ScheduleMode.CYCLE -> {
            AdvancedDate("循环第 1 天",schedule.start) {change(schedule.copy(start=it))}
            Text("按下面的顺序逐日循环，与星期无关。")
            schedule.cycleTemplates.forEachIndexed {index,id->key(index) {
                Choice("第 ${index+1} 天",id,ids,::name) {selected->change(schedule.copy(cycleTemplates=schedule.cycleTemplates.mapIndexed {i,current->if(i==index) selected else current}))}
                TextButton(onClick={change(schedule.copy(cycleTemplates=schedule.cycleTemplates.filterIndexed {i,_->i!=index}))},enabled=schedule.cycleTemplates.size>1) {Text("删除第 ${index+1} 天")}
            }}
            OutlinedButton(onClick={change(schedule.copy(cycleTemplates=schedule.cycleTemplates+ids.first()))},enabled=schedule.cycleTemplates.size<366 && ids.isNotEmpty()) {Text("增加一天（当前 ${schedule.cycleTemplates.size} 天一循环）")}
        }
        ScheduleMode.TRAINING_REST -> {
            Choice("训练日模板",schedule.trainingTemplate,ids,::name) {change(schedule.copy(trainingTemplate=it))}
            Choice("休息日模板",schedule.restTemplate,ids,::name) {change(schedule.copy(restTemplate=it))}
            Choice("本次采用的日期类型",schedule.trainingDay,listOf(true,false),{if(it) "训练日" else "休息日"}) {change(schedule.copy(trainingDay=it))}
            Text("只临时改变今天，请选择页面下方“仅更新今天”。正常保存或立即生效会将此类型保存为以后默认选择。")
        }
        ScheduleMode.STAGES -> {
            AdvancedDate("阶段起始日期",schedule.start) {change(schedule.copy(start=it))}
            Text("第 0 天表示起始当天；阶段必须按开始天数递增排列，首阶段从第 0 天开始。")
            schedule.stages.forEachIndexed {index,stage->key(index) {
                AdvancedInteger("阶段 ${index+1}：开始于第几天",stage.startOffsetDays) {offset->change(schedule.copy(stages=schedule.stages.mapIndexed {i,current->if(i==index) current.copy(startOffsetDays=offset) else current}))}
                Choice("阶段 ${index+1} 模板",stage.templateId,ids,::name) {id->change(schedule.copy(stages=schedule.stages.mapIndexed {i,current->if(i==index) current.copy(templateId=id) else current}))}
                TextButton(onClick={change(schedule.copy(stages=schedule.stages.filterIndexed {i,_->i!=index}))},enabled=index>0) {Text("删除阶段 ${index+1}")}
            }}
            OutlinedButton(onClick={change(schedule.copy(stages=schedule.stages+PlanStage((schedule.stages.lastOrNull()?.startOffsetDays ?: 0)+7,ids.first())))},enabled=schedule.stages.size<100 && ids.isNotEmpty()) {Text("增加阶段")}
        }
    }
    Text("今天：${LocalDate.ofEpochDay(day)}。安排只决定新快照使用哪套规则，不重算过去日期。")
}

@Composable private fun WeeklyEditor(weekly:WeeklyBudget,change:(WeeklyBudget)->Unit) {
    Choice("整周控制",weekly.mode,WeeklyBudgetMode.entries.toList(),{it.label}) {mode->if(mode!=weekly.mode) change(weekly.copy(mode=mode,amount=if(mode==WeeklyBudgetMode.INTAKE) 14000.0 else 3500.0))}
    if(weekly.mode!=WeeklyBudgetMode.NONE) {
        AdvancedNumber(if(weekly.mode==WeeklyBudgetMode.INTAKE) "周一至周日总摄入预算" else "周一至周日总缺口预算",weekly.amount,true) {change(weekly.copy(amount=it))}
        Text("权重决定七天如何分配整周预算；不是百分比，不要求合计 100。历史目标始终保留，修改后的预算不会追溯补改过去日期。")
        weekdays.forEachIndexed {index,label->AdvancedNumber("$label 分配权重",weekly.weights.getOrElse(index) {1.0}) {value->change(weekly.copy(weights=List(7) {if(it==index) value else weekly.weights.getOrElse(it) {1.0}}))}}
        Text("整周预算优先于各模板的每日热量预算，宏量仍按当天模板分配。它是未包含运动回补的基础预算；开启回补会在此基础上额外增加摄入目标。")
        if(weekly.mode==WeeklyBudgetMode.DEFICIT) Text("本周这套计划首次生成目标时的消耗会作为整周基准，不随每日体重自动改变。修改计划后可采用新基准；首页的实际热量差仍按当天消耗计算，因此整周实际缺口可能与预算不同。")
    }
}

@Composable private fun ExerciseAllocationEditor(exercise:ExerciseAllocation,change:(ExerciseAllocation)->Unit) {
    AdvancedNumber("额外运动回补比例 %",exercise.fraction*100) {change(exercise.copy(fraction=it/100))}
    Text("0%：运动只影响消耗与缺口显示；100%：全部加回摄入目标；中间值仅回补部分。只计算勾选“计入额外消耗”的运动记录。")
    AdvancedToggle("设置每日回补上限",exercise.maximumKcal!=null) {change(exercise.copy(maximumKcal=if(it) 300.0 else null))}
    exercise.maximumKcal?.let {maximum->AdvancedNumber("每日最多回补",maximum,true) {change(exercise.copy(maximumKcal=it))}}
    Choice("回补分配给",exercise.destination,listOf<Macro?>(null)+Macro.entries,{it?.label ?: "按当天宏量规则分配"}) {change(exercise.copy(destination=it))}
    Text("若模板使用“由宏量决定总热量”，启用回补时须指定增加哪一项营养素。")
    Text("指定一项时只增加该项，其他项目保持原目标；与上限等规则冲突时会提示。运动记录变化后需确认更新今天的目标，不影响历史或已有饮食记录。")
}
