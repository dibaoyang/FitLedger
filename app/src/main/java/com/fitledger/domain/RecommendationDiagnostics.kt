package com.fitledger.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import java.util.Locale

enum class DiagnosticCode {
    INVALID_INPUT, UNKNOWN_INTAKE_PRICE, UNKNOWN_FOOD_PRICE, LOCK_UNAVAILABLE, LOCK_QUANTITY,
    LOCK_UNKNOWN_PRICE, ALREADY_OVER_BUDGET, LOCK_OVER_BUDGET, ALREADY_OVER_MACRO,
    LOCK_OVER_MACRO, LOCK_OVER_ZONE, LOCK_ZONE_EXCLUSIVE, NO_ELIGIBLE_FOODS, LOWER_BOUND_UNREACHABLE, SEARCH_NOT_FOUND
}

/** A proposed policy is a draft. Analyzing or running it must never persist it. */
data class RecommendationTrialOption(val id: String, val title: String, val policy: RecommendationPolicy)
data class Diagnostic(val code: DiagnosticCode, val title: String, val detail: String,
    val trial: RecommendationTrialOption? = null)

/** Kept apart from the normal suggestion: a trial is never eligible for adoption. */
data class RecommendationTrialResult(val option: RecommendationTrialOption,
    val suggestion: Suggestion? = null, val failure: String? = null)

/** Explain independently verifiable blockers without treating bounded search as a proof. */
object RecommendationDiagnostics {
    fun analyze(
        foods: List<Candidate>, remaining: Nutrition, locks: Map<Long, Double>, zoneLimits: List<ZoneLimit>,
        policy: RecommendationPolicy, spentYuan: Double?, failure: String?, preferValue: Boolean = false
    ): List<Diagnostic> {
        val invalid = runCatching { RecommendationSearch.validateInputs(foods, remaining, zoneLimits, policy, spentYuan) }
            .exceptionOrNull()
        if (invalid != null) return listOf(Diagnostic(DiagnosticCode.INVALID_INPUT, "推荐数据需要核对",
            invalid.message ?: "请检查食品、营养范围和分区设置后重新计算。"))

        val results = mutableListOf<Diagnostic>()
        val byId = foods.associateBy { it.id }
        val limitsByZone = zoneLimits.associateBy { it.zoneId }
        val lockedFoods = locks.filterValues { it.isFinite() && it > 0 }.keys.mapNotNull { byId[it] }
        val exclusiveConflicts = zoneLimits.filter { !it.allowMultipleFoods }.mapNotNull { limit ->
            val selected = lockedFoods.filter { it.zoneId == limit.zoneId }
            if (selected.size <= 1) null else Diagnostic(DiagnosticCode.LOCK_ZONE_EXCLUSIVE,
                "锁定食品违反分区互斥规则",
                "“${limit.name}”分区已关闭混选，但本次同时锁定了${selected.names()}。同一套推荐在该分区最多安排一种食品，数量多少不改变此规则。请取消多余锁定，仅保留一种；或到分区设置开启混选。")
        }
        // Nutrient/budget relaxations cannot resolve a conflict between two exact locks.
        // Resolve this structural blocker first instead of offering misleading policy trials.
        if (exclusiveConflicts.isNotEmpty()) return exclusiveConflicts
        val budget = policy.dailyBudgetYuan
        val pricedOnly = budget != null || preferValue
        val withoutBudget = RecommendationTrialOption("without_budget", "临时取消预算试算", policy.copy(dailyBudgetYuan = null))
        if (budget != null && spentYuan == null) results += Diagnostic(DiagnosticCode.UNKNOWN_INTAKE_PRICE,
            "今日已有未计价摄入", "已有记录缺少费用快照，无法保证全天预算。已有费用不会按食品现价重算；可明确取消预算后重算，今后记录前补全食品价格。", withoutBudget)
        if (budget != null && spentYuan != null && !QuotaMath.within(spentYuan, budget)) {
            results += Diagnostic(DiagnosticCode.ALREADY_OVER_BUDGET, "今日已摄入费用超过预算",
                "已记录 ¥${spentYuan.number()}，全天预算 ¥${budget.number()}。新增食品无法消除已发生的超额。", withoutBudget)
        }
        val unknown = foods.filter { it.allowed && it.pricePerUnitYuan == null }
        if (pricedOnly && unknown.isNotEmpty()) results += Diagnostic(DiagnosticCode.UNKNOWN_FOOD_PRICE,
            "部分食品未填写价格", "${unknown.names()}缺少单价，不参与预算或性价比推荐。未知价格不是免费；补全价格后可重新计算。",
            if (budget != null && !preferValue) withoutBudget else null)

        var validLocks = true
        locks.toSortedMap().forEach { (id, quantity) ->
            val food = byId[id]
            when {
                food == null || !food.allowed -> {
                    validLocks = false
                    results += Diagnostic(DiagnosticCode.LOCK_UNAVAILABLE, "锁定食品已不可用",
                        "${food?.name?.let { "“$it”" } ?: "编号 $id 的食品"}已删除、停用或不允许参与推荐，请取消该锁定或检查食品设置。")
                }
                !quantity.isFinite() || quantity <= 0 || quantity > food.maximum ||
                    abs(quantity / food.step - round(quantity / food.step)) >= 0.00001 -> {
                    validLocks = false
                    results += Diagnostic(DiagnosticCode.LOCK_QUANTITY, "锁定数量不符合调整单位",
                        "“${food.name}”须为 ${food.step.number()} 的正整数倍，且不能超过最大推荐量 ${food.maximum.number()}。请修改锁定数量。")
                }
                pricedOnly && food.pricePerUnitYuan == null -> {
                    validLocks = false
                    results += Diagnostic(DiagnosticCode.LOCK_UNKNOWN_PRICE, "锁定食品缺少价格",
                        "“${food.name}”已锁定但未填写价格。请补全价格、取消锁定，或明确关闭相关价格限制。",
                        if (budget != null && !preferValue) withoutBudget else null)
                }
            }
        }

        val eligible = foods.filter { it.allowed && (!pricedOnly || it.pricePerUnitYuan != null) }
        if (eligible.isEmpty()) results += Diagnostic(DiagnosticCode.NO_ELIGIBLE_FOODS, "没有可参与推荐的食品",
            "请添加或启用推荐食品；启用预算或性价比时，还需要食品的有效价格。")

        if (validLocks) {
            val locked = locks.entries.fold(Nutrition()) { total, (id, amount) -> total + byId.getValue(id).perUnit * amount }
            if (!locked.values().all { it.isFinite() }) {
                results += Diagnostic(DiagnosticCode.INVALID_INPUT, "锁定合计超出计算范围", "请减少锁定数量并核对食品营养值。")
                return results
            }
            val lockedZones = mutableMapOf<Long, Double>()
            locks.forEach { (id, amount) -> byId.getValue(id).let { food ->
                food.zoneId?.let { zone -> lockedZones[zone] = (lockedZones[zone] ?: 0.0) + amount / food.zonePortionSize }
            } }
            zoneLimits.forEach { limit ->
                val amount = lockedZones[limit.zoneId] ?: 0.0
                if (!QuotaMath.within(amount, limit.maximumPortions)) results += Diagnostic(DiagnosticCode.LOCK_OVER_ZONE,
                    "锁定合计超过分区剩余上限", "“${limit.name}”锁定合计 ${amount.number()} 分区份，可用 ${limit.maximumPortions.number()} 分区份。请减少锁定量或核对分区上限与换算。")
            }
            val lockedCost = RecommendationSearch.cost(foods, locks)
            val availableBudget = if (budget != null && spentYuan != null) max(0.0, budget - spentYuan) else null
            if (availableBudget != null && lockedCost != null && !QuotaMath.within(lockedCost, availableBudget)) {
                results += Diagnostic(DiagnosticCode.LOCK_OVER_BUDGET, "锁定合计超过今日剩余预算",
                    "锁定食品 ¥${lockedCost.number()}，今日剩余预算 ¥${availableBudget.number()}。请减少锁定量或调整预算。", withoutBudget)
            }
            val targets = listOf(remaining.carbs, remaining.protein, remaining.fat)
            val rules = listOf(policy.carbs, policy.protein, policy.fat)
            val lockedMacros = listOf(locked.carbs, locked.protein, locked.fat)
            val names = listOf("碳水", "蛋白质", "脂肪")
            names.indices.forEach { index ->
                val rule = rules[index]
                rule.over?.let { over ->
                    val upper = targets[index] + over
                    val option = RecommendationTrialOption("${index}_over", "临时放宽${names[index]}上限试算",
                        policy.withTolerance(index, rule.copy(over = null)))
                    if (upper < 0) results += Diagnostic(DiagnosticCode.ALREADY_OVER_MACRO, "已摄入${names[index]}超过允许上限",
                        "今日已摄入量已比允许上限多 ${(-upper).number()} g，新增推荐无法减少已经记录的摄入。", option)
                    else if (upper.isFinite() && !QuotaMath.within(lockedMacros[index], upper)) results += Diagnostic(DiagnosticCode.LOCK_OVER_MACRO,
                        "锁定合计超过${names[index]}上限", "锁定合计 ${lockedMacros[index].number()} g，本次最多可安排 ${upper.number()} g。请减少锁定量或调整该方向范围。", option)
                }
            }

            // Continuous maxima deliberately ignore shared portion/budget competition. For an
            // exclusive zone use the best single food, or only the exact locked food already
            // occupying it. Each nutrient still has its own optimistic upper bound; this is
            // not a claim that all independently best nutrient totals can occur together.
            val canCheckCapacity = (budget == null || (spentYuan != null && lockedCost != null && QuotaMath.within(spentYuan, budget))) &&
                zoneLimits.all { QuotaMath.within(lockedZones[it.zoneId] ?: 0.0, it.maximumPortions) }
            if (canCheckCapacity) names.indices.forEach { index ->
                val under = rules[index].under ?: return@forEach
                val lower = max(0.0, targets[index] - under)
                val extraBudget = availableBudget?.let { max(0.0, it - (lockedCost ?: 0.0)) }
                val occupiedExclusiveZones = lockedFoods.mapNotNull { food -> food.zoneId?.takeIf { limitsByZone[it]?.allowMultipleFoods == false } }.toSet()
                val exclusiveMaximum = mutableMapOf<Long, Double>()
                var maximum = lockedMacros[index]
                eligible.filter { it.id !in locks && it.zoneId !in occupiedExclusiveZones }.forEach { food ->
                    var quantity = food.maximum
                    food.zoneId?.let { zone -> limitsByZone[zone]?.let { limit ->
                        quantity = minOf(quantity, max(0.0, limit.maximumPortions - (lockedZones[zone] ?: 0.0)) * food.zonePortionSize)
                    } }
                    val price = food.pricePerUnitYuan
                    if (extraBudget != null && price != null && price > 0) quantity = minOf(quantity, extraBudget / price)
                    val value = listOf(food.perUnit.carbs, food.perUnit.protein, food.perUnit.fat)[index]
                    val contribution = value * quantity
                    val zone = food.zoneId
                    if (zone != null && limitsByZone[zone]?.allowMultipleFoods == false) {
                        exclusiveMaximum[zone] = max(exclusiveMaximum[zone] ?: 0.0, contribution)
                    } else maximum += contribution
                }
                maximum += exclusiveMaximum.values.sum()
                // Stay conservative around floating-point boundaries, including tiny zero goals.
                if (maximum.isFinite() && lower - maximum > max(1e-7, abs(lower) * 1e-10)) {
                    val option = RecommendationTrialOption("${index}_under", "临时放宽${names[index]}下限试算",
                        policy.withTolerance(index, rules[index].copy(under = null)))
                    results += Diagnostic(DiagnosticCode.LOWER_BOUND_UNREACHABLE, "当前食品的${names[index]}不足以达到下限",
                        "至少需要 ${lower.number()} g；即使按各食品可用最大量作乐观估算，也最多 ${maximum.number()} g。此估算尚未扣除食品之间的竞争，实际可能更少。可增加合适食品、调整最大量，或明确放宽该方向下限。", option)
                }
            }
        }
        val definite = results.any { it.code !in setOf(DiagnosticCode.UNKNOWN_FOOD_PRICE, DiagnosticCode.SEARCH_NOT_FOUND) }
        if (!definite) results += Diagnostic(DiagnosticCode.SEARCH_NOT_FOUND, "有限搜索暂未找到符合条件的组合",
            "本次搜索有时间与组合数量上限，未找到不代表数学上无解。食品步长、锁定量、分区与多个营养范围可能共同限制组合。可调整食品或锁定数量后重算，也可点击下方单项临时试算。${if (failure.isNullOrBlank()) "" else "原提示：$failure"}")
        // Single-direction drafts only; never relax all constraints automatically.
        if (!definite) {
            if (budget != null && results.none { it.trial?.id == withoutBudget.id }) results += Diagnostic(
                DiagnosticCode.SEARCH_NOT_FOUND, "检查预算方向", "可仅在临时计算中取消预算，观察预算是否影响找到组合。", withoutBudget)
            listOf(policy.carbs, policy.protein, policy.fat).forEachIndexed { index, rule ->
                listOf("下限" to rule.under, "上限" to rule.over).forEach { (direction, value) ->
                    if (value != null) {
                        val lower = direction == "下限"
                        val names = listOf("碳水", "蛋白质", "脂肪")
                        results += Diagnostic(DiagnosticCode.SEARCH_NOT_FOUND, "检查${names[index]}$direction",
                            "只放宽${names[index]}这一方向，其他正式约束保持不变进行临时计算。",
                            RecommendationTrialOption("${index}_${if (lower) "under" else "over"}", "临时放宽${names[index]}${direction}试算",
                                policy.withTolerance(index, if (lower) rule.copy(under = null) else rule.copy(over = null))))
                    }
                }
            }
        }
        return results
    }

    private fun RecommendationPolicy.withTolerance(index: Int, value: MacroTolerance) = when (index) {
        0 -> copy(carbs = value)
        1 -> copy(protein = value)
        else -> copy(fat = value)
    }
    private fun Double.number() = String.format(Locale.CHINA, "%.2f", this)
    private fun List<Candidate>.names() = take(3).joinToString("、") { "“${it.name}”" } + if (size > 3) "等 ${size} 项食品" else ""
}
