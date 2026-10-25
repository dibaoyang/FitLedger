package com.fitledger.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** Bounded search: failure means no matching combination was found, not proof of infeasibility. */
internal object RecommendationSearch {
    private const val EPS = 1e-8
    private const val MAX_EVALUATIONS = 120000
    private const val MAX_MILLIS = 1800L
    private const val BEAM_WIDTH = 48
    private const val NOT_FOUND = "当前食品未找到满足要求的组合，请调整食品、预算或营养范围"

    private data class Node(val quantities: Map<Long, Double>, val nutrition: Nutrition, val cost: Double, val zones: Map<Long, Double>)
    private class Context(
        val foods: List<Candidate>, val remaining: Nutrition, val zoneLimits: List<ZoneLimit>,
        val policy: RecommendationPolicy, spentYuan: Double?, val preferValue: Boolean
    ) {
        val pricedOnly = policy.dailyBudgetYuan != null || preferValue
        val byId = foods.associateBy { it.id }
        val eligible = foods.filter { it.allowed && (!pricedOnly || it.pricePerUnitYuan != null) }
        val limits = zoneLimits.associate { it.zoneId to it.maximumPortions }
        val rules = listOf(policy.carbs, policy.protein, policy.fat)
        val target = listOf(remaining.carbs, remaining.protein, remaining.fat)
        val lower = rules.indices.map { i -> rules[i].under?.let { max(0.0, target[i] - it) } ?: 0.0 }
        val upper = rules.indices.map { i -> rules[i].over?.let { target[i] + it } ?: Double.POSITIVE_INFINITY }
        val availableBudget = policy.dailyBudgetYuan?.let { budget ->
            require(spentYuan != null) { "今日已摄入食品存在未知价格，无法保证全天预算；请暂时关闭预算，已有记录不会按现价重算" }
            require(QuotaMath.within(spentYuan, budget)) { "今日已摄入费用已经超过全天预算，请调整预算" }
            (budget - spentYuan).coerceAtLeast(0.0)
        }
        val priceScale = eligible.mapNotNull { f -> f.pricePerUnitYuan?.times(f.step)?.takeIf { it > 0 && it.isFinite() } }
            .minOrNull()?.coerceAtLeast(1.0) ?: 1.0

        init { require(upper.all { it >= 0 }) { NOT_FOUND } }

        fun node(quantities: Map<Long, Double>): Node {
            var nutrition = Nutrition()
            var totalCost = 0.0
            val zones = mutableMapOf<Long, Double>()
            quantities.toSortedMap().forEach { (id, quantity) ->
                val food = byId[id]
                require(food != null && food.allowed) { "食品已不可用或不允许参与推荐，请重新计算" }
                require(quantity.isFinite() && quantity >= 0 && quantity <= food.maximum &&
                    abs(quantity / food.step - round(quantity / food.step)) < 0.00001) { "食品数量须符合调整单位，且不超过最大推荐量" }
                if (quantity == 0.0) return@forEach
                require(!pricedOnly || food.pricePerUnitYuan != null) { "“${food.name}”未填写价格，预算或性价比推荐不能使用；请补全价格或取消锁定" }
                nutrition += food.perUnit * quantity
                totalCost += (food.pricePerUnitYuan ?: 0.0) * quantity
                food.zoneId?.let { zone -> zones[zone] = (zones[zone] ?: 0.0) + quantity / food.zonePortionSize }
            }
            require(nutrition.values().all { it.isFinite() } && totalCost.isFinite()) { "推荐总量超出可计算范围" }
            return Node(quantities.filterValues { it > 0 }, nutrition, totalCost, zones)
        }

        fun upperAllowed(node: Node): Boolean {
            val macros = listOf(node.nutrition.carbs, node.nutrition.protein, node.nutrition.fat)
            return node.nutrition.values().all { it.isFinite() } && node.cost.isFinite() &&
                macros.indices.all { upper[it] == Double.POSITIVE_INFINITY || QuotaMath.within(macros[it], upper[it]) } &&
                (availableBudget == null || QuotaMath.within(node.cost, availableBudget)) &&
                node.zones.all { (zone, amount) -> limits[zone]?.let { QuotaMath.within(amount, it) } ?: true }
        }

        fun feasible(node: Node): Boolean {
            val macros = listOf(node.nutrition.carbs, node.nutrition.protein, node.nutrition.fat)
            return upperAllowed(node) && macros.indices.all { QuotaMath.within(lower[it], macros[it]) }
        }

        fun nutritionScore(node: Node): Double = node.nutrition.values().zip(remaining.values()).mapIndexed { i, (actual, goal) ->
            val scale = max(abs(goal), if (i == 0) 100.0 else 10.0)
            val difference = actual / scale - goal / scale
            difference * difference * if (actual > goal) 1.5 else 1.0
        }.sum()

        fun objective(node: Node): Double {
            val costPart = if (preferValue) {
                // The price preference is bounded so a cheap, nutritionally distant choice cannot win.
                0.02 * (if (node.cost <= priceScale) node.cost / (node.cost + priceScale) else 1.0 / (1.0 + priceScale / node.cost))
            } else 0.0
            return nutritionScore(node) + costPart
        }

        fun heuristic(node: Node): Double {
            val macros = listOf(node.nutrition.carbs, node.nutrition.protein, node.nutrition.fat)
            val missing = macros.indices.sumOf { i ->
                val distance = max(0.0, lower[i] - macros[i]) / max(10.0, lower[i])
                distance * distance
            }
            return objective(node) + missing * 100.0
        }

        fun add(node: Node, food: Candidate, quantity: Double): Node {
            if (quantity == 0.0) return node
            val zones = if (food.zoneId == null) node.zones else node.zones +
                (food.zoneId to ((node.zones[food.zoneId] ?: 0.0) + quantity / food.zonePortionSize))
            return Node(node.quantities + (food.id to quantity), node.nutrition + food.perUnit * quantity,
                node.cost + (food.pricePerUnitYuan ?: 0.0) * quantity, zones)
        }
    }

    fun validateInputs(foods: List<Candidate>, remaining: Nutrition, zoneLimits: List<ZoneLimit>, policy: RecommendationPolicy, spentYuan: Double?) {
        policy.validate()
        require(remaining.values().all { it.isFinite() }) { "剩余营养须为有限数" }
        require(spentYuan == null || (spentYuan.isFinite() && spentYuan >= 0)) { "今日已摄入费用无效" }
        require(foods.all { it.id > 0 } && foods.map { it.id }.distinct().size == foods.size) { "推荐食品编号须为不重复的正数" }
        require(zoneLimits.map { it.zoneId }.distinct().size == zoneLimits.size) { "食品分区上限不能重复" }
        foods.forEach { food ->
            require(food.zoneId == null || food.zoneId > 0) { "食品分区编号无效" }
            require(food.zonePortionSize.isFinite() && food.zonePortionSize > 0) { "食品分区每份数量须大于 0" }
            require(food.pricePerUnitYuan == null || (food.pricePerUnitYuan.isFinite() && food.pricePerUnitYuan >= 0)) { "食品单价须为非负有限数" }
            if (food.allowed) {
                require(food.step.isFinite() && food.step > 0 && food.maximum.isFinite() && food.maximum >= food.step && food.maximum / food.step <= 2000) { "食品最大推荐量或调整单位无效，最多支持 2000 个单位" }
                require((food.maximum / food.zonePortionSize).isFinite()) { "食品分区每份数量过小" }
                require(food.perUnit.values().all { it.isFinite() && it >= 0 && (it * food.maximum).isFinite() }) { "食品推荐营养无效" }
                require(food.pricePerUnitYuan?.let { (it * food.maximum).isFinite() } != false) { "食品最大推荐费用超出可计算范围" }
            }
        }
    }

    fun cost(foods: List<Candidate>, quantities: Map<Long, Double>): Double? {
        val byId = foods.associateBy { it.id }
        var total = 0.0
        quantities.filterValues { it > 0 }.forEach { (id, amount) ->
            val price = byId[id]?.pricePerUnitYuan ?: return null
            total += price * amount
        }
        return total.takeIf { it.isFinite() }
    }

    fun validateProposal(
        foods: List<Candidate>, remaining: Nutrition, quantities: Map<Long, Double>, zoneLimits: List<ZoneLimit>,
        policy: RecommendationPolicy, spentYuan: Double?
    ) {
        validateInputs(foods, remaining, zoneLimits, policy, spentYuan)
        val context = Context(foods, remaining, zoneLimits, policy, spentYuan, false)
        require(context.feasible(context.node(quantities))) { NOT_FOUND }
    }

    fun recommend(
        foods: List<Candidate>, remaining: Nutrition, locks: Map<Long, Double>, zoneLimits: List<ZoneLimit>,
        policy: RecommendationPolicy, spentYuan: Double?, preferValue: Boolean
    ): Suggestion {
        validateInputs(foods, remaining, zoneLimits, policy, spentYuan)
        val context = Context(foods, remaining, zoneLimits, policy, spentYuan, preferValue)
        require(locks.values.all { it.isFinite() && it > 0 }) { "锁定量须为大于 0 的有限数" }
        val initial = context.node(locks)
        require(context.upperAllowed(initial)) { "锁定必吃食品超过预算、分区或营养上限，请调整锁定量或限制" }
        val unlocked = context.eligible.filter { it.id !in locks }.sortedBy { it.id }
        var best: Node? = null
        var bestNonempty: Node? = null
        fun consider(node: Node) {
            if (!context.feasible(node)) return
            fun better(other: Node?): Boolean = other == null || context.objective(node) < context.objective(other) - 1e-12 ||
                (abs(context.objective(node) - context.objective(other)) <= 1e-12 && node.cost < other.cost)
            if (better(best)) best = node
            if (node.quantities.isNotEmpty() && better(bestNonempty)) bestNonempty = node
        }
        consider(initial)
        val deadline = System.nanoTime() + MAX_MILLIS * 1_000_000
        var evaluations = 0
        // Give every food a chance to solve the target on its own before beam prefixes can
        // consume the search budget. This also avoids starving a useful late-listed food.
        for (food in unlocked) {
            for (quantity in quantities(food, initial, context)) {
                if (evaluations >= MAX_EVALUATIONS / 5 || System.nanoTime() >= deadline) break
                evaluations++
                val node = context.add(initial, food, quantity)
                if (context.upperAllowed(node)) consider(node)
            }
            if (evaluations >= MAX_EVALUATIONS / 5 || System.nanoTime() >= deadline) break
        }
        val relevantMacros = context.lower.indices.filter { context.lower[it] > 0 }
            .ifEmpty { context.target.indices.filter { context.target[it] > 0 } }
        val usefulOrder = unlocked.sortedByDescending { food ->
            val nutrients = listOf(food.perUnit.carbs, food.perUnit.protein, food.perUnit.fat)
            relevantMacros.sumOf { i -> minOf(1.0, nutrients[i] * food.maximum / max(10.0, if (context.lower[i] > 0) context.lower[i] else context.target[i])) }
        }
        val orders = listOf(usefulOrder, unlocked.reversed(), unlocked.sortedBy { food ->
            (food.pricePerUnitYuan ?: 0.0) / max(0.000001, food.perUnit.kcal)
        }).distinctBy { order -> order.map { it.id } }
        val perOrderEvaluations = (MAX_EVALUATIONS - evaluations) / max(1, orders.size)
        orders.forEach { order ->
            val orderLimit = evaluations + perOrderEvaluations
            val future = Array(order.size + 1) { DoubleArray(3) }
            for (index in order.indices.reversed()) {
                val food = order[index]
                val nutrients = listOf(food.perUnit.carbs, food.perUnit.protein, food.perUnit.fat)
                for (macro in 0..2) future[index][macro] = future[index + 1][macro] + nutrients[macro] * food.maximum
            }
            var beam = listOf(initial)
            for ((index, food) in order.withIndex()) {
                if (evaluations >= orderLimit || evaluations >= MAX_EVALUATIONS || System.nanoTime() >= deadline) break
                val expanded = ArrayList<Node>()
                var exhausted = false
                for (node in beam) {
                    for (quantity in quantities(food, node, context)) {
                        if (evaluations >= orderLimit || evaluations >= MAX_EVALUATIONS ||
                            (evaluations % 128 == 0 && System.nanoTime() >= deadline)) { exhausted = true; break }
                        evaluations++
                        val next = context.add(node, food, quantity)
                        if (!context.upperAllowed(next)) continue
                        consider(next)
                        val values = listOf(next.nutrition.carbs, next.nutrition.protein, next.nutrition.fat)
                        if (values.indices.any { values[it] + future[index + 1][it] + EPS < context.lower[it] }) continue
                        expanded.add(next)
                    }
                    if (exhausted) break
                }
                if (expanded.isEmpty()) break
                beam = expanded.sortedWith(compareBy<Node> { context.heuristic(it) }.thenBy { it.cost })
                    .distinctBy { signature(it) }.take(BEAM_WIDTH)
                if (exhausted) break
            }
        }
        var chosen = best ?: throw IllegalArgumentException(NOT_FOUND)
        // Price alone must not turn a nutritionally better nonempty recommendation into no food.
        if (chosen.quantities.isEmpty() && bestNonempty?.let { context.nutritionScore(it) < context.nutritionScore(chosen) - 1e-12 } == true) {
            chosen = bestNonempty!!
        }
        require(chosen.quantities.isNotEmpty() || remaining.values().none { it > 0 }) { NOT_FOUND }
        validateProposal(foods, remaining, chosen.quantities, zoneLimits, policy, spentYuan)
        return Suggestion(chosen.quantities, chosen.nutrition, context.nutritionScore(chosen), cost(foods, chosen.quantities))
    }

    private fun quantities(food: Candidate, node: Node, context: Context): List<Double> {
        val maximum = floor(food.maximum / food.step).toInt()
        val indices = sortedSetOf(0, maximum)
        if (maximum <= 64) indices.addAll(0..maximum)
        else {
            indices.add(1)
            fun near(quantity: Double) {
                if (!quantity.isFinite()) return
                val step = quantity / food.step
                if (!step.isFinite()) return
                val center = floor(step).toInt().coerceIn(0, maximum)
                listOf(-8, -3, -1, 0, 1, 2, 4, 8).forEach { offset -> indices.add((center + offset).coerceIn(0, maximum)) }
            }
            val actual = node.nutrition.values()
            val perUnit = food.perUnit.values()
            val goals = context.remaining.values()
            perUnit.indices.filter { perUnit[it] > 0 }.forEach { macro ->
                near((goals[macro] - actual[macro]) / perUnit[macro])
                if (macro > 0) {
                    near((context.lower[macro - 1] - actual[macro]) / perUnit[macro])
                    near((context.upper[macro - 1] - actual[macro]) / perUnit[macro])
                }
            }
            context.availableBudget?.let { budget -> food.pricePerUnitYuan?.takeIf { it > 0 }?.let { near((budget - node.cost) / it) } }
            food.zoneId?.let { zone -> context.limits[zone]?.let { near((it - (node.zones[zone] ?: 0.0)) * food.zonePortionSize) } }
            for (index in 1..16) indices.add((maximum.toLong() * index / 16).toInt())
        }
        return indices.map { it * food.step }.filter { it <= food.maximum }
    }

    private fun signature(node: Node): String {
        fun rounded(value: Double) = if (abs(value) < 1e9) round(value * 1e8).toLong().toString() else value.toString()
        return node.nutrition.values().joinToString(",", transform = ::rounded) + "|" + rounded(node.cost) + "|" +
            node.zones.toSortedMap().entries.joinToString(",") { "${it.key}:${rounded(it.value)}" }
    }
}
