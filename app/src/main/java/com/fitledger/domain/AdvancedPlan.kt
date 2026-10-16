package com.fitledger.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BudgetMode(val label:String) { FIXED("固定摄入"), EXPENDITURE_OFFSET("估算消耗加减热量"), EXPENDITURE_PERCENT("估算消耗加减百分比"), WEIGHT("每公斤体重热量"), LEAN("每公斤瘦体重热量") }
enum class ExpenditureSource(val label:String) { PROFILE("身体资料估算"), MANUAL("手动全天消耗") }
data class EnergyBudget(val mode:BudgetMode=BudgetMode.EXPENDITURE_OFFSET, val value:Double=-500.0, val expenditureSource:ExpenditureSource=ExpenditureSource.PROFILE, val manualExpenditure:Double=2500.0)
enum class Macro(val label:String, val factor:Double) { CARBS("碳水",4.0), PROTEIN("蛋白质",4.0), FAT("脂肪",9.0) }
enum class AllocationMode(val label:String) { RULES("分别配置计算方式"), RATIO("比例权重"), STRICT_PERCENT("严格百分比"), AUTO_PERCENT("两项百分比补齐第三项") }
data class MacroSpec(val rule:Rule=Rule(Basis.PERCENT,0.0), val minimum:Double=0.0, val maximum:Double?=null)
data class MacroAllocation(
    val mode:AllocationMode=AllocationMode.STRICT_PERCENT,
    val carbs:MacroSpec=MacroSpec(Rule(Basis.PERCENT,50.0)),
    val protein:MacroSpec=MacroSpec(Rule(Basis.PERCENT,30.0)),
    val fat:MacroSpec=MacroSpec(Rule(Basis.PERCENT,20.0)),
    val autoMacro:Macro=Macro.FAT,
    val adjustmentOrder:List<Macro> = emptyList()
) { fun specs()=listOf(carbs,protein,fat) }
enum class EnergyConsistency(val label:String) { FIXED_BUDGET("保持目标热量"), MACROS_DEFINE_TOTAL("由宏量决定总热量") }
enum class CarbScheduleMode(val label:String) { NONE("不调整碳水"), WEEKLY_MULTIPLIER("按星期调整碳水"), TAPER("逐步减少碳水") }
data class CarbSchedule(val mode:CarbScheduleMode=CarbScheduleMode.NONE, val multipliers:List<Double> = listOf(0.7,1.0,1.3,0.7,1.0,1.3,1.0), val start:Long=LocalDate.now().toEpochDay(), val intervalDays:Int=7, val decrementGrams:Double=10.0, val minimumGrams:Double=100.0)
data class PlanTemplate(val id:String="base", val name:String="默认计划", val budget:EnergyBudget=EnergyBudget(), val macros:MacroAllocation=MacroAllocation(), val consistency:EnergyConsistency=EnergyConsistency.FIXED_BUDGET, val carbSchedule:CarbSchedule=CarbSchedule())
enum class ScheduleMode(val label:String) { SINGLE("单一计划"), WEEKLY("按星期"), CYCLE("自定义天数循环"), TRAINING_REST("训练日与休息日"), STAGES("分阶段") }
data class PlanStage(val startOffsetDays:Int=0, val templateId:String="base")
data class TemplateSchedule(val mode:ScheduleMode=ScheduleMode.SINGLE, val start:Long=LocalDate.now().toEpochDay(), val singleTemplate:String="base", val weeklyTemplates:List<String> = List(7){"base"}, val cycleTemplates:List<String> = listOf("base"), val trainingTemplate:String="base", val restTemplate:String="base", val stages:List<PlanStage> = listOf(PlanStage()), val trainingDay:Boolean=false)
enum class ReferenceMode(val label:String) { TODAY("当天身体数据"), AVERAGE_7_DAYS("最近七天平均值"), FIXED("固定参考值") }
data class BodyReference(val mode:ReferenceMode=ReferenceMode.TODAY, val weight:Double=60.0, val lean:Double=48.0)
enum class WeeklyBudgetMode(val label:String) { NONE("不设置整周预算"), INTAKE("整周总摄入"), DEFICIT("整周总缺口") }
data class WeeklyBudget(val mode:WeeklyBudgetMode=WeeklyBudgetMode.NONE, val amount:Double=3500.0, val weights:List<Double> = List(7){1.0})
data class ExerciseAllocation(val fraction:Double=0.0, val maximumKcal:Double?=null, val destination:Macro?=null)
data class AdvancedPlan(val templates:List<PlanTemplate> = listOf(PlanTemplate()), val schedule:TemplateSchedule=TemplateSchedule(), val reference:BodyReference=BodyReference(), val weekly:WeeklyBudget=WeeklyBudget(), val exercise:ExerciseAllocation=ExerciseAllocation())

/** All values are explicit calculation inputs and can be persisted with a DailyTarget snapshot. */
data class AdvancedContext(val extraExerciseKcal:Double=0.0, val averageWeight:Double?=null, val averageLean:Double?=null, val trainingDay:Boolean?=null, val templateOverrideId:String?=null, val weekBaselineExpenditure:Double?=null)

fun AdvancedPlan.validateConfiguration() {
    require(templates.size in 1..50 && templates.map { it.id }.distinct().size==templates.size) { "计划模板须为 1～50 个，且标识不能重复" }
    val ids=templates.map {it.id}.toSet()
    templates.forEach { template ->
        require(template.id.isNotBlank() && template.id.length<=80 && template.name.isNotBlank() && template.name.length<=80) { "模板名称和标识须为 1～80 个字符" }
        val budget=template.budget
        require(budget.value.isFinite() && budget.manualExpenditure.isFinite() && budget.manualExpenditure>0) { "热量参数无效" }
        when(budget.mode) {
            BudgetMode.FIXED, BudgetMode.WEIGHT, BudgetMode.LEAN -> require(budget.value>0) { "摄入热量或每公斤热量须大于 0" }
            BudgetMode.EXPENDITURE_PERCENT -> require(budget.value > -100) { "热量缺口百分比须小于 100%" }
            BudgetMode.EXPENDITURE_OFFSET -> Unit
        }
        val allocation=template.macros
        val specs=allocation.specs()
        specs.forEach { spec ->
            require(spec.rule.value.isFinite() && spec.rule.value>=0 && spec.minimum.isFinite() && spec.minimum>=0) { "宏量参数及下限须为非负有限数" }
            require(spec.maximum==null || (spec.maximum.isFinite() && spec.maximum>=spec.minimum)) { "宏量上限须不小于下限" }
            if(allocation.mode==AllocationMode.RULES && spec.rule.basis==Basis.REMAINDER) require(spec.rule.value>0) { "剩余热量的分配权重须大于 0" }
        }
        require(allocation.adjustmentOrder.distinct().size==allocation.adjustmentOrder.size) { "调整优先级不能包含重复项目" }
        when(allocation.mode) {
            AllocationMode.RATIO -> require(specs.sumOf {it.rule.value}.let {it.isFinite() && it>0}) { "比例权重总和须大于 0" }
            AllocationMode.STRICT_PERCENT -> require(abs(specs.sumOf {it.rule.value}-100.0)<1e-6) { "碳水、蛋白质和脂肪的热量百分比须合计 100%" }
            AllocationMode.AUTO_PERCENT -> require(specs.filterIndexed {i,_->i!=allocation.autoMacro.ordinal}.sumOf {it.rule.value}<=100.0+1e-6) { "已输入的两项百分比不能超过 100%" }
            AllocationMode.RULES -> Unit
        }
        val carb=template.carbSchedule
        require(carb.multipliers.size==7 && carb.multipliers.all {it.isFinite() && it>0}) { "碳循环须有 7 个正数倍率" }
        LocalDate.ofEpochDay(carb.start)
        require(carb.intervalDays in 1..36500 && carb.decrementGrams.isFinite() && carb.decrementGrams>=0 && carb.minimumGrams.isFinite() && carb.minimumGrams>=0) { "碳水渐降参数无效" }
        if(weekly.mode!=WeeklyBudgetMode.NONE) require(template.consistency==EnergyConsistency.FIXED_BUDGET) { "整周预算需要所有模板保持目标热量" }
        if(exercise.fraction>0 && template.consistency==EnergyConsistency.MACROS_DEFINE_TOTAL) require(exercise.destination!=null) { "由宏量决定总热量时，运动回补须指定增加哪项营养素" }
    }
    require(reference.weight.isFinite() && reference.weight in 20.0..400.0 && reference.lean.isFinite() && reference.lean>0 && reference.lean<=reference.weight) { "固定参考体重或瘦体重无效" }
    require(exercise.fraction.isFinite() && exercise.fraction in 0.0..1.0 && (exercise.maximumKcal==null || exercise.maximumKcal.isFinite() && exercise.maximumKcal>=0)) { "运动回补比例须在 0～100%，上限须为非负数" }
    require(weekly.amount.isFinite() && weekly.amount>=0 && (weekly.mode!=WeeklyBudgetMode.INTAKE || weekly.amount>0)) { "整周热量预算无效" }
    require(weekly.weights.size==7 && weekly.weights.all {it.isFinite() && it>0} && weekly.weights.sum().isFinite()) { "整周分配须包含 7 个正权重" }
    LocalDate.ofEpochDay(schedule.start)
    require(schedule.weeklyTemplates.size==7 && schedule.cycleTemplates.size in 1..366 && schedule.stages.size in 1..100) { "星期须为 7 天，循环须为 1～366 天，阶段须为 1～100 个" }
    val references=listOf(schedule.singleTemplate,schedule.trainingTemplate,schedule.restTemplate)+schedule.weeklyTemplates+schedule.cycleTemplates+schedule.stages.map {it.templateId}
    require(references.all {it in ids}) { "日期安排引用了不存在的计划模板" }
    require(schedule.stages.first().startOffsetDays==0 && schedule.stages.all {it.startOffsetDays in 0..36500} && schedule.stages.zipWithNext().all {(a,b)->a.startOffsetDays<b.startOffsetDays}) { "阶段须从第 1 天开始，按不同起始天数递增排列" }
}

object AdvancedTargetCalculator {
    fun resolveTemplate(plan:AdvancedPlan, day:Long, context:AdvancedContext=AdvancedContext()):PlanTemplate {
        val date=LocalDate.ofEpochDay(day)
        val schedule=plan.schedule
        val offset=max(0L,day-schedule.start)
        val id=context.templateOverrideId ?: when(schedule.mode) {
            ScheduleMode.SINGLE -> schedule.singleTemplate
            ScheduleMode.WEEKLY -> schedule.weeklyTemplates[date.dayOfWeek.value-1]
            ScheduleMode.CYCLE -> schedule.cycleTemplates[(offset%schedule.cycleTemplates.size).toInt()]
            ScheduleMode.TRAINING_REST -> if(context.trainingDay ?: schedule.trainingDay) schedule.trainingTemplate else schedule.restTemplate
            ScheduleMode.STAGES -> schedule.stages.lastOrNull {it.startOffsetDays<=offset}?.templateId ?: schedule.stages.first().templateId
        }
        return requireNotNull(plan.templates.find {it.id==id}) { "未找到当天使用的计划模板" }
    }

    fun effectiveExpenditure(template:PlanTemplate, estimatedExpenditure:Double?):Double? = when(template.budget.expenditureSource) {
        ExpenditureSource.PROFILE -> estimatedExpenditure
        ExpenditureSource.MANUAL -> template.budget.manualExpenditure
    }

    fun calculate(plan:AdvancedPlan, body:Body, day:Long, savedLean:Double=body.lean, estimatedExpenditure:Double?=null, context:AdvancedContext=AdvancedContext()):Nutrition {
        plan.validateConfiguration()
        body.validate()
        require(savedLean.isFinite() && savedLean>0 && savedLean<=body.weight) { "瘦体重快照无效" }
        require(context.extraExerciseKcal.isFinite() && context.extraExerciseKcal>=0) { "额外运动消耗无效" }
        require(context.weekBaselineExpenditure==null || context.weekBaselineExpenditure.isFinite() && context.weekBaselineExpenditure>0) { "本周消耗基准无效" }
        require((context.averageWeight==null)==(context.averageLean==null)) { "平均体重与平均瘦体重须同时提供" }
        val (weight,lean)=when(plan.reference.mode) {
            ReferenceMode.TODAY -> body.weight to savedLean
            ReferenceMode.FIXED -> plan.reference.weight to plan.reference.lean
            ReferenceMode.AVERAGE_7_DAYS -> (context.averageWeight ?: body.weight) to (context.averageLean ?: savedLean)
        }
        require(weight.isFinite() && weight in 20.0..400.0 && lean.isFinite() && lean>0 && lean<=weight) { "用于计算的参考身体数据无效" }
        val template=resolveTemplate(plan,day,context)
        val expenditure=effectiveExpenditure(template,estimatedExpenditure)
        fun requireExpenditure():Double {
            require(expenditure!=null && expenditure.isFinite() && expenditure>0) { "缺少当天估算消耗，请补全身体资料或填写手动消耗" }
            return expenditure
        }
        val budget=template.budget
        val weekday=LocalDate.ofEpochDay(day).dayOfWeek.value-1
        val fraction=plan.weekly.weights[weekday]/plan.weekly.weights.sum()
        val target=when(plan.weekly.mode) {
            WeeklyBudgetMode.NONE -> when(budget.mode) {
                BudgetMode.FIXED -> budget.value
                BudgetMode.WEIGHT -> budget.value*weight
                BudgetMode.LEAN -> budget.value*lean
                BudgetMode.EXPENDITURE_OFFSET -> requireExpenditure()+budget.value
                BudgetMode.EXPENDITURE_PERCENT -> requireExpenditure()*(1+budget.value/100)
            }
            WeeklyBudgetMode.INTAKE -> plan.weekly.amount*fraction
            WeeklyBudgetMode.DEFICIT -> (context.weekBaselineExpenditure ?: requireExpenditure())-plan.weekly.amount*fraction
        }
        require(target.isFinite() && target>0) { "目标热量须大于 0，请检查缺口或整周预算" }
        val exercise=min(context.extraExerciseKcal*plan.exercise.fraction,plan.exercise.maximumKcal ?: Double.MAX_VALUE)
        require(exercise.isFinite()) { "运动回补结果无效" }
        val allocationTarget=target+if(plan.exercise.destination==null) exercise else 0.0
        // A directed exercise addition starts from the complete original target, including
        // its original bounds. It must never make room by changing other nutrients.
        val specs=template.macros.specs()
        val rules=specs.map {it.rule}
        val flexible=mutableMapOf<Int,Double>()
        val grams=DoubleArray(3)
        if(template.macros.mode==AllocationMode.RULES) {
            rules.forEachIndexed {i,rule ->
                grams[i]=when(rule.basis) {
                    Basis.FIXED -> rule.value
                    Basis.WEIGHT -> rule.value*weight
                    Basis.LEAN -> rule.value*lean
                    Basis.PERCENT -> allocationTarget*rule.value/100/Macro.entries[i].factor
                    Basis.REMAINDER -> { flexible[i]=rule.value; 0.0 }
                }
            }
        } else {
            val weights=rules.map {it.value}.toMutableList()
            if(template.macros.mode==AllocationMode.AUTO_PERCENT) weights[template.macros.autoMacro.ordinal]=max(0.0,100-weights.filterIndexed {i,_->i!=template.macros.autoMacro.ordinal}.sum())
            weights.forEachIndexed {i,w->flexible[i]=w}
        }
        val fixedBudget=template.consistency==EnergyConsistency.FIXED_BUDGET
        val carb=template.carbSchedule
        fun scheduledCarbs(base:Double)=when(carb.mode) {
            CarbScheduleMode.NONE -> base
            CarbScheduleMode.WEEKLY_MULTIPLIER -> base*carb.multipliers[weekday]
            CarbScheduleMode.TAPER -> max(carb.minimumGrams,base-max(0L,day-carb.start)/carb.intervalDays*carb.decrementGrams)
        }
        if(carb.mode!=CarbScheduleMode.NONE && fixedBudget) {
            // A low-carb day can make an otherwise excessive baseline feasible. Apply the
            // schedule before testing the budget or bounds, then keep those scheduled carbs fixed.
            if(0 in flexible) {
                val left=allocationTarget-grams.indices.filter {it !in flexible}.sumOf {grams[it]*Macro.entries[it].factor}
                val largest=flexible.values.maxOrNull() ?: 0.0
                val totalWeight=flexible.values.sumOf {if(largest>0) it/largest else 0.0}
                // An initial overspend can still be feasible when the user allowed protein
                // or fat to decrease. The final allocator applies those explicit priorities.
                grams[0]=if(totalWeight>0) max(0.0,left)*(flexible.getValue(0)/largest)/totalWeight/4 else 0.0
            }
            grams[0]=scheduledCarbs(grams[0])
            if(0 in flexible) {
                if(carb.mode==CarbScheduleMode.TAPER) require(specs[0].maximum==null || specs[0].maximum!!>=carb.minimumGrams) { "碳水渐降下限超过碳水最大量" }
                grams[0]=grams[0].coerceIn(specs[0].minimum,specs[0].maximum ?: Double.MAX_VALUE)
            }
            require(inBounds(grams[0],specs[0])) { "调整后的碳水不符合上下限，请修改碳水计划或限制" }
            allocate(allocationTarget,grams,flexible.filterKeys {it!=0},specs,template.macros.adjustmentOrder.filter {it!=Macro.CARBS},true)
        } else {
            val allocationSpecs=if(carb.mode!=CarbScheduleMode.NONE) specs.mapIndexed {i,s->if(i==0) s.copy(minimum=0.0,maximum=null) else s} else specs
            allocate(allocationTarget,grams,flexible,allocationSpecs,template.macros.adjustmentOrder,fixedBudget)
            if(carb.mode!=CarbScheduleMode.NONE) {
                grams[0]=scheduledCarbs(grams[0])
                require(inBounds(grams[0],specs[0])) { "调整后的碳水不符合上下限，请修改碳水计划或限制" }
            }
        }
        if(plan.exercise.destination!=null && exercise>0) {
            val index=plan.exercise.destination.ordinal
            grams[index]+=exercise/plan.exercise.destination.factor
            require(inBounds(grams[index],specs[index])) { "指定营养素无法容纳运动回补，请调整上限或回补量" }
        }
        val result=Nutrition(grams.indices.sumOf {grams[it]*Macro.entries[it].factor},grams[0],grams[1],grams[2])
        require(result.values().all {it.isFinite() && it>=0} && result.kcal>0) { "计划结果无效" }
        if(fixedBudget) require(abs(result.kcal-(target+exercise))<0.001) { "宏量与目标热量存在冲突" }
        return result
    }

    private fun inBounds(value:Double,spec:MacroSpec)=value.isFinite() && value>=spec.minimum-1e-7 && (spec.maximum==null || value<=spec.maximum+1e-7)

    /** Fixed rules remain locked unless the user explicitly listed them in adjustmentOrder. */
    private fun allocate(target:Double,grams:DoubleArray,flexible:Map<Int,Double>,specs:List<MacroSpec>,order:List<Macro>,enforceBudget:Boolean) {
        val fixed=grams.indices.filter {it !in flexible}
        fixed.forEach {i ->
            if(!inBounds(grams[i],specs[i])) {
                require(Macro.entries[i] in order) { "${Macro.entries[i].label}固定量超出上下限，且未允许自动调整" }
                grams[i]=grams[i].coerceIn(specs[i].minimum,specs[i].maximum ?: Double.MAX_VALUE)
            }
        }
        if(!enforceBudget && flexible.isEmpty()) return
        val minimum=flexible.keys.sumOf {specs[it].minimum*Macro.entries[it].factor}
        val maximum=if(flexible.keys.any {specs[it].maximum==null && flexible.getValue(it)>0}) Double.POSITIVE_INFINITY else flexible.keys.sumOf { i -> (if(flexible.getValue(i)==0.0) specs[i].minimum else specs[i].maximum ?: specs[i].minimum)*Macro.entries[i].factor }
        fun available()=target-fixed.sumOf {grams[it]*Macro.entries[it].factor}
        var remaining=available()
        // First use explicit priorities to make the remaining budget feasible; never silently alter a locked rule.
        var needed=when { remaining<minimum -> remaining-minimum; remaining>maximum -> remaining-maximum; else -> 0.0 }
        order.filter {it.ordinal in fixed}.forEach {macro ->
            if(abs(needed)>1e-7) {
                val i=macro.ordinal
                val desired=grams[i]+needed/macro.factor
                val next=desired.coerceIn(specs[i].minimum,specs[i].maximum ?: Double.MAX_VALUE)
                needed-=(next-grams[i])*macro.factor
                grams[i]=next
            }
        }
        remaining=available()
        require(remaining>=minimum-1e-6 && remaining<=maximum+1e-6 && remaining.isFinite()) { "目标热量与已锁定宏量、上下限冲突，请增加可调整项或修改预算" }
        if(flexible.isEmpty()) return
        // Find a common calorie multiplier. Clamping both lower and upper bounds in the same
        // monotone equation avoids incorrectly freezing a lower-bound item while another hits its cap.
        val largest=flexible.values.maxOrNull() ?: 0.0
        val weights=flexible.mapValues {(_,v)->if(largest>0) v/largest else 0.0}
        fun energy(index:Int,multiplier:Double):Double {
            val factor=Macro.entries[index].factor
            val lower=specs[index].minimum*factor
            val upper=if(weights.getValue(index)==0.0) lower else specs[index].maximum?.times(factor) ?: Double.MAX_VALUE
            return (multiplier*weights.getValue(index)).coerceIn(lower,upper)
        }
        fun sum(multiplier:Double)=flexible.keys.sumOf {energy(it,multiplier)}
        var low=0.0
        var high=max(1.0,remaining)
        var attempts=0
        while(sum(high)<remaining-1e-7 && attempts++<2048) {
            high=min(Double.MAX_VALUE,high*2)
            if(high==Double.MAX_VALUE) break
        }
        require(sum(high)>=remaining-1e-6) { "剩余热量无法在当前比例和上下限内分配" }
        repeat(150) {
            val middle=low+(high-low)/2
            if(sum(middle)<remaining) low=middle else high=middle
        }
        flexible.keys.forEach {i->grams[i]=energy(i,high)/Macro.entries[i].factor}
        val rest=remaining-flexible.keys.sumOf {grams[it]*Macro.entries[it].factor}
        require(abs(rest)<0.001 && flexible.keys.all {inBounds(grams[it],specs[it])}) { "剩余热量无法在当前比例和上下限内分配" }
    }
}

