package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import java.time.LocalDate

/** Captures the inputs used for a target without changing Room or historical body data. */
object TargetPlanning {
    private const val CONTEXT_VERSION = 1

    fun context(plan: Plan, body: BodyRecord, bodies: List<BodyRecord>, targets: List<DailyTarget>,
                exercises: List<Exercise>, expenditure: Double? = body.energy?.expenditureKcal): AdvancedContext {
        val recent = (bodies.filter { it.day in body.day-6 until body.day } + body)
        val monday = body.day - (LocalDate.ofEpochDay(body.day).dayOfWeek.value - 1)
        fun weekIdentity(advanced:AdvancedPlan?)=advanced?.copy(schedule=advanced.schedule.copy(trainingDay=false))
        val baseline = targets.asSequence().filter { it.day in monday..body.day }
            .sortedBy { it.day }.firstOrNull {
                weekIdentity(PlanCodec.decode(it.planSnapshot).advanced) == weekIdentity(plan.advanced) && readContext(it) != null
            }?.let { readContext(it)?.weekBaselineExpenditure }
        val effective=plan.advanced?.let { advanced ->
            AdvancedTargetCalculator.effectiveExpenditure(AdvancedTargetCalculator.resolveTemplate(advanced,body.day,AdvancedContext()),expenditure)
        } ?: expenditure
        return AdvancedContext(extraExerciseKcal=exercises.filter { it.day==body.day && it.included }.sumOf { it.kcal },
            averageWeight=recent.map { it.weight }.average(), averageLean=recent.map { it.lean }.average(),
            weekBaselineExpenditure=baseline ?: effective)
    }

    fun create(plan: Plan, body: BodyRecord, bodies: List<BodyRecord>, targets: List<DailyTarget>,
               exercises: List<Exercise>, expenditure: Double? = body.energy?.expenditureKcal,
               frozenContext: AdvancedContext? = null): DailyTarget {
        plan.validateConfiguration()
        val ctx = frozenContext ?: if(plan.advanced==null) AdvancedContext() else context(plan,body,bodies,targets,exercises,expenditure)
        val nutrition = TargetCalculator.calculate(plan,Body(body.weight,body.fatPercent,body.muscle),
            body.day,body.lean,expenditure,ctx)
        val snapshot = JSONObject(PlanCodec.encode(plan))
        if(plan.advanced!=null) snapshot.put("calculationContext",encodeContext(body.day,ctx))
        return DailyTarget(body.day,nutrition,snapshot.toString())
    }

    fun preview(plan: Plan, body: BodyRecord, allBodies: List<BodyRecord>, allTargets: List<DailyTarget>,
                exercises: List<Exercise>, expenditure: Double? = body.energy?.expenditureKcal): Nutrition =
        create(plan,body,allBodies,allTargets,exercises,expenditure).nutrition

    private fun encodeContext(day: Long, c: AdvancedContext) = JSONObject().apply {
        put("version",CONTEXT_VERSION);put("day",day);put("extraExerciseKcal",c.extraExerciseKcal)
        put("averageWeight",c.averageWeight ?: JSONObject.NULL);put("averageLean",c.averageLean ?: JSONObject.NULL)
        put("trainingDay",c.trainingDay ?: JSONObject.NULL);put("templateOverrideId",c.templateOverrideId ?: JSONObject.NULL)
        put("weekBaselineExpenditure",c.weekBaselineExpenditure ?: JSONObject.NULL)
    }

    fun readContext(target: DailyTarget): AdvancedContext? {
        val root=JSONObject(target.planSnapshot)
        if(!root.has("calculationContext")) return null
        val j=root.getJSONObject("calculationContext")
        require(listOf("version","day","extraExerciseKcal","averageWeight","averageLean","trainingDay","templateOverrideId","weekBaselineExpenditure").all(j::has)) { "目标计算快照不完整" }
        fun integer(key:String):Long {
            val value=j.get(key);require(value is Number) { "目标计算快照整数无效" }
            return java.math.BigDecimal(value.toString()).longValueExact()
        }
        fun number(key:String):Double {
            val value=j.get(key);require(value is Number) { "目标计算快照数值无效" }
            return value.toDouble().also {require(it.isFinite()) { "目标计算快照数值无效" }}
        }
        require(integer("version")==CONTEXT_VERSION.toLong() && integer("day")==target.day) { "目标计算快照版本或日期无效" }
        fun optional(key:String):Double? = if(j.isNull(key)) null else number(key).also {
            require(it.isFinite() && it>0) { "目标计算快照参数无效" }
        }
        val extra=number("extraExerciseKcal")
        require(extra.isFinite() && extra>=0) { "目标中的运动消耗无效" }
        val weight=optional("averageWeight");val lean=optional("averageLean")
        require((weight==null)==(lean==null) && (weight==null || (weight in 20.0..400.0 && lean!!<=weight))) { "参考身体数据无效" }
        require(j.isNull("trainingDay") || j.get("trainingDay") is Boolean) { "训练日标记无效" }
        val override=if(j.isNull("templateOverrideId")) null else j.get("templateOverrideId").let {
            require(it is String && it.length in 1..80) { "目标模板标记无效" };it
        }
        return AdvancedContext(extra,weight,lean,if(j.isNull("trainingDay")) null else j.getBoolean("trainingDay"),override,optional("weekBaselineExpenditure"))
    }

    fun validateSnapshot(target: DailyTarget, body:BodyRecord?=null) {
        val plan=PlanCodec.decode(target.planSnapshot)
        val context=readContext(target)
        if(plan.advanced!=null) {
            requireNotNull(context) { "高级目标缺少计算快照，无法安全恢复" }
            if(body!=null) {
                require(body.day==target.day) { "目标和身体快照日期不一致" }
                val computed=TargetCalculator.calculate(plan,Body(body.weight,body.fatPercent,body.muscle),body.day,body.lean,body.energy?.expenditureKcal,context)
                require(computed.values().zip(target.nutrition.values()).all { (a,b)-> kotlin.math.abs(a-b)<0.0001 }) { "目标与保存的计算依据不一致，无法安全恢复" }
            }
        }
    }

    /** Manual expenditure changes the energy budget, never the BMR/measurement snapshot. */
    fun baseExpenditure(target: DailyTarget?, body: BodyRecord?): Double? {
        val estimated=body?.energy?.expenditureKcal
        if(target==null) return estimated
        val advanced=PlanCodec.decode(target.planSnapshot).advanced ?: return estimated
        val template=AdvancedTargetCalculator.resolveTemplate(advanced,target.day,readContext(target) ?: AdvancedContext())
        return AdvancedTargetCalculator.effectiveExpenditure(template,estimated)
    }
}
