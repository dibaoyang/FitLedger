package com.fitledger.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.round

data class Nutrition(val kcal: Double = 0.0, val carbs: Double = 0.0, val protein: Double = 0.0, val fat: Double = 0.0) {
    operator fun plus(b: Nutrition) = Nutrition(kcal+b.kcal, carbs+b.carbs, protein+b.protein, fat+b.fat)
    operator fun minus(b: Nutrition) = Nutrition(kcal-b.kcal, carbs-b.carbs, protein-b.protein, fat-b.fat)
    operator fun times(n: Double) = Nutrition(kcal*n, carbs*n, protein*n, fat*n)
    fun values() = listOf(kcal, carbs, protein, fat)
}
data class Body(val weight: Double, val fatPercent: Double, val muscle: Double?) {
    val lean get() = weight * (1-fatPercent/100)
    fun validate() {
        require(weight.isFinite() && weight in 20.0..400.0) { "体重请输入 20～400 kg" }
        require(fatPercent.isFinite() && fatPercent > 0 && fatPercent < 100) { "体脂率须大于 0、小于 100" }
        require(muscle == null || (muscle.isFinite() && muscle > 0 && muscle <= lean)) { "肌肉量须大于 0，且不超过瘦体重" }
    }
}
enum class Basis(val label: String) { FIXED("固定值"), WEIGHT("每 kg 体重"), LEAN("每 kg 瘦体重"), PERCENT("总热量百分比"), REMAINDER("剩余热量") }
enum class PlanMode(val label: String) { FIXED("固定宏量"), CYCLE("碳循环"), TAPER("碳水渐降"), CUSTOM("身体数据计算"), RATIO("按热量比例分配宏量营养") }
data class Rule(val basis: Basis = Basis.FIXED, val value: Double = 0.0)
data class Plan(
    val goal: String = "维持", val mode: PlanMode = PlanMode.FIXED,
    val energy: Rule = Rule(Basis.FIXED, 2000.0),
    val carbs: Rule = Rule(Basis.FIXED, 250.0),
    val protein: Rule = Rule(Basis.FIXED, 120.0),
    val fat: Rule = Rule(Basis.FIXED, 57.7777777778),
    val cycle: List<Double> = listOf(0.7, 1.0, 1.3, 0.7, 1.0, 1.3, 1.0),
    val start: Long = LocalDate.now().toEpochDay(),
    val taperGrams: Double = 10.0, val taperDays: Int = 7, val minimumCarbs: Double = 100.0,
    val intakeMode:IntakeMode=IntakeMode.FIXED, val fixedIntakeKcal:Double=2000.0,
    val energyAdjustment:Double=0.0, val ratio:MacroRatio=MacroRatio(),
    val useExpenditureBudget:Boolean=false, val deficitKcal:Double=500.0,
    val advanced:AdvancedPlan?=null
)
fun Plan.validateConfiguration() {
    require(goal in listOf("减脂","维持","增肌")) { "目标类型无效" }
    advanced?.let {it.validateConfiguration(); return}
    require(energy.basis in listOf(Basis.FIXED,Basis.WEIGHT,Basis.LEAN)) { "热量计算方式无效" }
    require(listOf(energy,carbs,protein,fat).all {it.value.isFinite() && it.value>=0}) { "计划参数必须是非负有限数" }
    require(listOf(carbs,protein,fat).count {it.basis==Basis.REMAINDER}<=1) { "只能有一种营养素使用剩余热量" }
    require(cycle.size==7 && cycle.all {it.isFinite() && it>0}) { "碳循环配置须包含 7 个正数，即使当前使用其他计划模式" }
    require(taperDays>0 && taperGrams.isFinite() && taperGrams>=0 && minimumCarbs.isFinite() && minimumCarbs>=0) { "碳水渐降配置无效，请检查间隔、减量和下限" }
    LocalDate.ofEpochDay(start)
    require(deficitKcal.isFinite() && deficitKcal>=0) { "热量缺口须为非负有限数" }
    if(useExpenditureBudget && mode!=PlanMode.RATIO) {
        require(listOf(carbs,protein,fat).count {it.basis==Basis.REMAINDER}==1) { "按热量缺口计算时，请选择一种营养素使用剩余热量（例如脂肪）" }
        require(mode !in listOf(PlanMode.CYCLE,PlanMode.TAPER) || carbs.basis!=Basis.REMAINDER) { "碳循环或渐降搭配热量缺口时，请先确定碳水，将蛋白质或脂肪设为剩余热量" }
    }
    ratio.fractions()
    require(fixedIntakeKcal.isFinite() && fixedIntakeKcal>0 && energyAdjustment.isFinite()) { "摄入热量须大于 0，热量调整须为有限数" }
}
object TargetCalculator {
    fun calculate(plan: Plan, body: Body, day: Long, savedLean: Double = body.lean, estimatedExpenditure:Double?=null, context:AdvancedContext=AdvancedContext()): Nutrition {
        plan.validateConfiguration()
        body.validate()
        require(savedLean.isFinite() && savedLean > 0 && savedLean <= body.weight) { "瘦体重快照无效" }
        plan.advanced?.let {return AdvancedTargetCalculator.calculate(it,body,day,savedLean,estimatedExpenditure,context)}
        if(plan.mode==PlanMode.RATIO) {
            val target=when(plan.intakeMode) {
                IntakeMode.FIXED -> plan.fixedIntakeKcal
                IntakeMode.EXPENDITURE_ADJUSTMENT -> {
                    require(estimatedExpenditure!=null && estimatedExpenditure.isFinite() && estimatedExpenditure>0) { "缺少当天能量快照，请补全身体资料后称重，或确认立即生效" }
                    estimatedExpenditure+plan.energyAdjustment
                }
            }
            return MacroRatioCalculator.calculate(target,plan.ratio)
        }
        require(plan.energy.basis in listOf(Basis.FIXED, Basis.WEIGHT, Basis.LEAN)) { "热量仅支持固定/体重/瘦体重" }
        fun base(r: Rule, energy: Double, factor: Double): Double {
            require(r.value.isFinite() && r.value >= 0) { "计划数值须为非负有限数" }
            return when(r.basis) {
                Basis.FIXED -> r.value
                Basis.WEIGHT -> r.value * body.weight
                Basis.LEAN -> r.value * savedLean
                Basis.PERCENT -> r.value * energy / 100 / factor
                Basis.REMAINDER -> 0.0
            }
        }
        val energy = if(plan.useExpenditureBudget) {
            require(estimatedExpenditure!=null && estimatedExpenditure.isFinite() && estimatedExpenditure>0) { "缺少当天估算消耗，请先完成身体资料与称重" }
            estimatedExpenditure-plan.deficitKcal
        } else base(plan.energy, 0.0, 1.0)
        require(energy.isFinite() && energy > 0) { "目标热量须大于 0；请减少热量缺口或检查身体资料" }
        val rules = listOf(plan.carbs, plan.protein, plan.fat)
        require(rules.count { it.basis == Basis.REMAINDER } <= 1) { "只能有一种营养素使用剩余热量" }
        val factors = listOf(4.0, 4.0, 9.0)
        val grams = rules.mapIndexed { i, r -> base(r, energy, factors[i]) }.toMutableList()
        fun adjustCarbs() {
            when(plan.mode) {
                PlanMode.CYCLE -> grams[0] *= plan.cycle[LocalDate.ofEpochDay(day).dayOfWeek.value-1]
                PlanMode.TAPER -> grams[0] = max(plan.minimumCarbs, grams[0] - max(0L, day-plan.start)/plan.taperDays*plan.taperGrams)
                else -> Unit
            }
        }
        // New explicit budget: adjust scheduled carbs first, then allocate the remainder.
        // Legacy plans keep their original order so existing snapshots/configurations retain meaning.
        if(plan.useExpenditureBudget) adjustCarbs()
        val remainder = rules.indexOfFirst { it.basis == Basis.REMAINDER }
        if (remainder >= 0) {
            val left = energy - grams.indices.sumOf { grams[it]*factors[it] }
            require(left.isFinite() && left >= -0.0001) { "已设定的宏量营养热量超过目标，无法分配剩余；请降低固定量或减少热量缺口" }
            grams[remainder] = max(0.0, left)/factors[remainder]
        }
        if(!plan.useExpenditureBudget) adjustCarbs()
        // A target always has internally consistent macro energy. Cycling changes total energy too.
        val result = Nutrition(grams.indices.sumOf { grams[it]*factors[it] }, grams[0], grams[1], grams[2])
        require(result.values().all { it.isFinite() && it >= 0 } && result.kcal > 0) { "计划结果无效" }
        return result
    }
}
data class Candidate(
    val id: Long, val name: String, val perUnit: Nutrition, val step: Double, val maximum: Double, val allowed: Boolean,
    val zoneId: Long? = null, val zonePortionSize: Double = 1.0, val pricePerUnitYuan: Double? = null
)
/** Quantity available for this recommendation, after the caller deducts today's intake. */
data class ZoneLimit(val zoneId: Long, val name: String, val maximumPortions: Double) {
    init {
        require(zoneId > 0 && name.isNotBlank()) { "食品分区无效" }
        require(maximumPortions.isFinite() && maximumPortions >= 0) { "分区推荐剩余上限须为非负有限数" }
    }
}
data class Suggestion(val quantities: Map<Long, Double>, val nutrition: Nutrition, val score: Double, val costYuan: Double? = null)
object Recommender {
    fun recommend(
        foods: List<Candidate>, remaining: Nutrition, locks: Map<Long, Double>, zoneLimits: List<ZoneLimit> = emptyList(),
        policy: RecommendationPolicy = RecommendationPolicy(), spentYuan: Double? = 0.0, preferValue: Boolean = false
    ): Suggestion {
        policy.validate()
        if (policy == RecommendationPolicy() && !preferValue) {
            RecommendationSearch.validateInputs(foods, remaining, zoneLimits, policy, spentYuan)
            val result = legacyRecommend(foods, remaining, locks, zoneLimits)
            return result.copy(costYuan = RecommendationSearch.cost(foods, result.quantities))
        }
        return RecommendationSearch.recommend(foods, remaining, locks, zoneLimits, policy, spentYuan, preferValue)
    }

    /** Recheck the exact accepted quantities against current data; never silently adjust them. */
    fun validateProposal(
        foods: List<Candidate>, remaining: Nutrition, quantities: Map<Long, Double>, zoneLimits: List<ZoneLimit> = emptyList(),
        policy: RecommendationPolicy = RecommendationPolicy(), spentYuan: Double? = 0.0
    ) = RecommendationSearch.validateProposal(foods, remaining, quantities, zoneLimits, policy, spentYuan)

    private fun legacyRecommend(foods: List<Candidate>, remaining: Nutrition, locks: Map<Long, Double>, zoneLimits: List<ZoneLimit>): Suggestion {
        require(foods.map { it.id }.distinct().size == foods.size && foods.all { it.id > 0 }) { "推荐食品编号须为不重复的正数" }
        require(zoneLimits.map { it.zoneId }.distinct().size == zoneLimits.size) { "食品分区上限不能重复" }
        require(remaining.values().all { it.isFinite() }) { "剩余营养须为有限数" }
        foods.forEach {
            require(it.zoneId == null || it.zoneId > 0) { "食品分区编号无效" }
            require(it.zonePortionSize.isFinite() && it.zonePortionSize > 0) { "食品分区每份数量须大于 0" }
        }
        val eligible = foods.filter { it.allowed }.sortedBy { it.id }
        val limits = zoneLimits.associateBy { it.zoneId }
        require(locks.keys.all { id -> eligible.any { it.id == id } }) { "锁定食品必须允许参与推荐" }
        eligible.forEach {
            require(it.step.isFinite() && it.step > 0 && it.maximum.isFinite() && it.maximum >= it.step) { "食品推荐量或调整单位无效" }
            require(it.maximum / it.step <= 2000) { "推荐最大量/调整单位不能超过 2000" }
            require((it.maximum / it.zonePortionSize).isFinite()) { "食品分区每份数量过小" }
            require(it.perUnit.values().all { value -> value.isFinite() && value >= 0 && (value * it.maximum).isFinite() }) { "食品推荐营养无效" }
        }
        locks.forEach { (id, n) ->
            val f = eligible.first { it.id == id }
            require(n.isFinite() && n > 0 && n <= f.maximum && kotlin.math.abs(n/f.step-round(n/f.step)) < 0.00001) { "锁定量须符合调整单位，且不超过最大推荐量" }
        }
        val amounts = eligible.associate { it.id to (locks[it.id] ?: 0.0) }.toMutableMap()
        fun withinZoneLimit(zoneId: Long?): Boolean {
            val limit = limits[zoneId] ?: return true
            return QuotaMath.within(eligible.filter { it.zoneId == zoneId }.sumOf { amounts.getValue(it.id) / it.zonePortionSize }, limit.maximumPortions)
        }
        zoneLimits.forEach { require(withinZoneLimit(it.zoneId)) { "锁定必吃食品超过“${it.name}”分区的剩余上限，请调整锁定量或分区设置" } }
        fun total() = eligible.fold(Nutrition()) { a, f -> a + f.perUnit * amounts.getValue(f.id) }
        require(total().values().all { it.isFinite() }) { "锁定食品的营养总量超出可计算范围" }
        fun score(n: Nutrition): Double = n.values().zip(remaining.values()).mapIndexed { i, (a,b) ->
            val scale = max(kotlin.math.abs(b), if(i==0) 100.0 else 10.0)
            val delta = a/scale-b/scale
            delta*delta*(if(a>b) 1.5 else 1.0)
        }.sum()
        var best = score(total())
        val unlocked = eligible.filter { it.id !in locks }
        // Every candidate stays within both the individual maximum and the shared portion budget.
        repeat(30) {
            var changed = false
            unlocked.forEach { f ->
                var chosen = amounts.getValue(f.id)
                val previous = chosen
                val steps = kotlin.math.floor(f.maximum/f.step).toInt()
                for (i in 0..steps) {
                    amounts[f.id] = i*f.step
                    if (amounts.getValue(f.id) > f.maximum || !withinZoneLimit(f.zoneId)) continue
                    val s = score(total())
                    if (s < best-1e-9) { best = s; chosen = i*f.step }
                }
                amounts[f.id] = chosen
                changed = changed || chosen != previous
            }
            // A full shared budget can block a useful food swap. Exchange one discrete step
            // while keeping exact locks untouched, then let the next coordinate pass refine it.
            unlocked.filter { it.zoneId in limits }.groupBy { it.zoneId }.values.forEach { group ->
                group.forEach { recipient ->
                    group.filter { it.id != recipient.id }.forEach donorLoop@ { donor ->
                        val oldRecipient = amounts.getValue(recipient.id)
                        val oldDonor = amounts.getValue(donor.id)
                        val nextRecipient = (round(oldRecipient / recipient.step) + 1) * recipient.step
                        if (nextRecipient > recipient.maximum || oldDonor <= 0) return@donorLoop
                        val limit = limits.getValue(recipient.zoneId!!).maximumPortions
                        val others = eligible.filter { it.zoneId == recipient.zoneId && it.id != recipient.id && it.id != donor.id }
                            .sumOf { amounts.getValue(it.id) / it.zonePortionSize }
                        val donorBudget = (limit - others - nextRecipient / recipient.zonePortionSize) * donor.zonePortionSize
                        if (donorBudget < 0) return@donorLoop
                        var donorSteps = minOf(round(oldDonor / donor.step).toInt(), kotlin.math.floor(donorBudget / donor.step).toInt())
                        amounts[recipient.id] = nextRecipient
                        amounts[donor.id] = donorSteps * donor.step
                        while (donorSteps > 0 && !withinZoneLimit(recipient.zoneId)) {
                            donorSteps--
                            amounts[donor.id] = donorSteps * donor.step
                        }
                        val s = if (withinZoneLimit(recipient.zoneId)) score(total()) else Double.POSITIVE_INFINITY
                        if (s < best-1e-9) {
                            best = s
                            changed = true
                        } else {
                            amounts[recipient.id] = oldRecipient
                            amounts[donor.id] = oldDonor
                        }
                    }
                }
            }
            if(!changed) return Suggestion(amounts.filterValues { it > 0 }, total(), best)
        }
        return Suggestion(amounts.filterValues { it > 0 }, total(), best)
    }
}

