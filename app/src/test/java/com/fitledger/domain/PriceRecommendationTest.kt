package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class PriceRecommendationTest {
    private fun food(id: Long, carbs: Double = 10.0, protein: Double = 0.0, fat: Double = 0.0,
        price: Double? = 2.0, maximum: Double = 10.0, zone: Long? = null) = Candidate(
        id, "食品 $id", Nutrition(carbs * 4 + protein * 4 + fat * 9, carbs, protein, fat), 1.0, maximum, true,
        zoneId = zone, pricePerUnitYuan = price)
    private fun rejected(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error?.toString(), error is IllegalArgumentException)
        return error?.message.orEmpty()
    }
    private val target = Nutrition(160.0, 40.0)
    private val exactCarbs = RecommendationPolicy(carbs = MacroTolerance(0.0, 0.0))

    @Test fun dailyBudgetSubtractsAlreadyEatenCostBeforeRecommending() {
        val foods = listOf(food(1))
        val result = Recommender.recommend(foods, target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 10.0), spentYuan = 6.0)
        assertEquals(mapOf(1L to 2.0), result.quantities)
        assertEquals(4.0, result.costYuan!!, 0.0)
        Recommender.validateProposal(foods, target, result.quantities, policy = RecommendationPolicy(dailyBudgetYuan = 10.0), spentYuan = 6.0)
    }

    @Test fun unknownConsumedCostCannotPretendToSatisfyABudget() {
        val message = rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 10.0), spentYuan = null) }
        assertTrue(message.contains("未知价格"))
        rejected { Recommender.validateProposal(listOf(food(1)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 10.0), spentYuan = null) }
        rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 10.0), spentYuan = 11.0) }
    }

    @Test fun unknownFoodPricesAreExcludedWhenBudgetOrValueIsEnabled() {
        val foods = listOf(food(1, price = null), food(2, price = 1.0))
        listOf(false, true).forEach { value ->
            val result = Recommender.recommend(foods, target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 10.0), preferValue = value)
            assertFalse(result.quantities.containsKey(1L))
            assertTrue(result.quantities.getValue(2L) > 0)
        }
        val message = rejected { Recommender.recommend(foods, target, mapOf(1L to 1.0), preferValue = true) }
        assertTrue(message.contains("未填写价格"))
        rejected { Recommender.validateProposal(foods, target, mapOf(1L to 1.0), policy = RecommendationPolicy(dailyBudgetYuan = 10.0)) }
    }

    @Test fun explicitlyFreeFoodsRemainEligibleWithZeroBudget() {
        val foods = listOf(food(1, price = 0.0), food(2, price = null))
        val result = Recommender.recommend(foods, target, emptyMap(), policy = exactCarbs.copy(dailyBudgetYuan = 0.0), preferValue = true)
        assertEquals(mapOf(1L to 4.0), result.quantities)
        assertEquals(0.0, result.costYuan!!, 0.0)
    }

    @Test fun macroOnlyConstraintsDoNotRequireFoodPrices() {
        val result = Recommender.recommend(listOf(food(1, price = null)), target, emptyMap(), policy = exactCarbs, spentYuan = null)
        assertEquals(mapOf(1L to 4.0), result.quantities)
        assertNull(result.costYuan)
    }

    @Test fun allThreeExactMacroRangesMustHoldTogether() {
        val foods = listOf(food(1), food(2, carbs = 0.0, protein = 10.0), food(3, carbs = 0.0, fat = 5.0))
        val nutrition = Nutrition(410.0, 50.0, 30.0, 10.0)
        val policy = RecommendationPolicy(carbs = MacroTolerance(0.0, 0.0), protein = MacroTolerance(0.0, 0.0), fat = MacroTolerance(0.0, 0.0))
        val result = Recommender.recommend(foods, nutrition, emptyMap(), policy = policy)
        assertEquals(mapOf(1L to 5.0, 2L to 3.0, 3L to 2.0), result.quantities)
        assertEquals(nutrition, result.nutrition)
        Recommender.validateProposal(foods, nutrition, result.quantities, policy = policy)
    }

    @Test fun eachMacroSupportsIndependentZeroAndNonzeroUnderOverAllowances() {
        for (macro in 0..2) {
            val item = food(1, carbs = if (macro == 0) 3.0 else 0.0,
                protein = if (macro == 1) 3.0 else 0.0, fat = if (macro == 2) 3.0 else 0.0)
            val goal = Nutrition(if (macro == 2) 90.0 else 40.0,
                if (macro == 0) 10.0 else 0.0, if (macro == 1) 10.0 else 0.0, if (macro == 2) 10.0 else 0.0)
            fun policy(tolerance: MacroTolerance) = RecommendationPolicy(
                carbs = if (macro == 0) tolerance else MacroTolerance(),
                protein = if (macro == 1) tolerance else MacroTolerance(),
                fat = if (macro == 2) tolerance else MacroTolerance())
            val notLow = policy(MacroTolerance(under = 0.0, over = 2.0))
            Recommender.validateProposal(listOf(item), goal, mapOf(1L to 4.0), policy = notLow)
            rejected { Recommender.validateProposal(listOf(item), goal, mapOf(1L to 3.0), policy = notLow) }
            rejected { Recommender.validateProposal(listOf(item), goal, mapOf(1L to 5.0), policy = notLow) }
            val notHigh = policy(MacroTolerance(under = 2.0, over = 0.0))
            Recommender.validateProposal(listOf(item), goal, mapOf(1L to 3.0), policy = notHigh)
            rejected { Recommender.validateProposal(listOf(item), goal, mapOf(1L to 4.0), policy = notHigh) }
            rejected { Recommender.validateProposal(listOf(item), goal, mapOf(1L to 2.0), policy = notHigh) }
            val result = Recommender.recommend(listOf(item), goal, emptyMap(), policy = notLow)
            assertEquals(mapOf(1L to 4.0), result.quantities)
        }
    }

    @Test fun anAlreadyExceededUpperLimitCannotBeRepairedByAnEmptyProposal() {
        val remaining = Nutrition(-10.0, -2.0)
        val policy = RecommendationPolicy(carbs = MacroTolerance(over = 0.0))
        rejected { Recommender.recommend(listOf(food(1)), remaining, emptyMap(), policy = policy) }
        rejected { Recommender.validateProposal(listOf(food(1)), remaining, emptyMap(), policy = policy) }
    }

    @Test fun impossibleDiscreteRangesReturnAnExplicitNotFoundMessage() {
        val item = food(1, carbs = 3.0)
        val message = rejected { Recommender.recommend(listOf(item), Nutrition(40.0, 10.0), emptyMap(), policy = exactCarbs) }
        assertTrue(message.contains("未找到满足要求的组合"))
        assertTrue(message.contains("调整食品、预算或营养范围"))
    }

    @Test fun aBudgetCannotBeSatisfiedByReturningAnUnderTargetViolation() {
        val message = rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), policy = exactCarbs.copy(dailyBudgetYuan = 5.0)) }
        assertTrue(message.contains("未找到满足要求的组合"))
    }

    @Test fun exactLocksMustRespectBudgetAndHardMacrosAndSharedZoneTogether() {
        val foods = listOf(food(1, zone = 7), food(2, zone = 7))
        val policy = RecommendationPolicy(dailyBudgetYuan = 8.0, carbs = MacroTolerance(0.0, 0.0))
        val limit = listOf(ZoneLimit(7, "面包", 4.0))
        val result = Recommender.recommend(foods, target, mapOf(1L to 1.0), limit, policy)
        assertEquals(1.0, result.quantities.getValue(1L), 0.0)
        assertEquals(4.0, result.quantities.values.sum(), 0.0)
        assertEquals(8.0, result.costYuan!!, 0.0)
        rejected { Recommender.recommend(foods, target, mapOf(1L to 5.0), limit, policy) }
        rejected { Recommender.recommend(foods, target, mapOf(1L to 1.0), listOf(ZoneLimit(7, "面包", 3.0)), policy) }
        rejected { Recommender.recommend(foods, target, mapOf(1L to 0.5), limit, policy) }
    }

    @Test fun valuePreferenceActuallyChoosesCheaperEquivalentNutrition() {
        val foods = listOf(food(1, price = 100.0), food(2, price = 1.0))
        val goal = Nutrition(40.0, 10.0)
        assertEquals(mapOf(1L to 1.0), Recommender.recommend(foods, goal, emptyMap()).quantities)
        val result = Recommender.recommend(foods, goal, emptyMap(), preferValue = true)
        assertEquals(mapOf(2L to 1.0), result.quantities)
        assertEquals(goal, result.nutrition)
        assertEquals(1.0, result.costYuan!!, 0.0)
    }

    @Test fun valuePreferenceDoesNotChooseAFreePoorMatchOrAnEmptyMoneySavingPlan() {
        val foods = listOf(food(1, price = 1e9, maximum = 1.0), food(2, carbs = 1.0, price = 0.0, maximum = 1.0))
        val result = Recommender.recommend(foods, Nutrition(40.0, 10.0), emptyMap(), preferValue = true)
        assertEquals(mapOf(1L to 1.0), result.quantities)
        assertEquals(10.0, result.nutrition.carbs, 0.0)
    }

    @Test fun valuePreferenceCanTradeATinyNutritionDifferenceForMuchLowerCost() {
        val foods = listOf(food(1, price = 100.0, maximum = 1.0), food(2, carbs = 9.9, price = 0.01, maximum = 1.0))
        val result = Recommender.recommend(foods, Nutrition(40.0, 10.0), emptyMap(), preferValue = true)
        assertEquals(mapOf(2L to 1.0), result.quantities)
        assertEquals(0.01, result.costYuan!!, 1e-12)
    }

    @Test fun proposalAdoptionRechecksCurrentPricesNutritionAndEligibilityWithoutOptimization() {
        val foods = listOf(food(1))
        val policy = RecommendationPolicy(dailyBudgetYuan = 10.0, carbs = MacroTolerance(over = 0.0))
        Recommender.validateProposal(foods, target, mapOf(1L to 3.0), policy = policy, spentYuan = 4.0)
        rejected { Recommender.validateProposal(listOf(food(1, price = 3.0)), target, mapOf(1L to 3.0), policy = policy, spentYuan = 4.0) }
        rejected { Recommender.validateProposal(listOf(food(1, carbs = 20.0)), target, mapOf(1L to 3.0), policy = policy, spentYuan = 4.0) }
        rejected { Recommender.validateProposal(listOf(food(1).copy(allowed = false)), target, mapOf(1L to 3.0), policy = policy, spentYuan = 4.0) }
        rejected { Recommender.validateProposal(foods, target, mapOf(99L to 1.0), policy = policy, spentYuan = 4.0) }
        rejected { Recommender.validateProposal(foods, target, mapOf(1L to 1.5), policy = policy, spentYuan = 4.0) }
    }

    @Test fun defaultPolicyPreservesTheOriginalNutritionAndQuantityChoiceRegardlessOfPrices() {
        val withoutPrice = listOf(food(1, price = null), food(2, carbs = 0.0, protein = 5.0, price = null))
        val withPrice = listOf(withoutPrice[0].copy(pricePerUnitYuan = 90.0), withoutPrice[1].copy(pricePerUnitYuan = 0.01))
        val goal = Nutrition(220.0, 40.0, 15.0)
        val original = Recommender.recommend(withoutPrice, goal, mapOf(1L to 2.0))
        val priced = Recommender.recommend(withPrice, goal, mapOf(1L to 2.0), policy = RecommendationPolicy(), spentYuan = null)
        assertEquals(original.quantities, priced.quantities)
        assertEquals(original.nutrition, priced.nutrition)
        assertEquals(original.score, priced.score, 0.0)
        assertNull(original.costYuan)
        assertNotNull(priced.costYuan)
    }

    @Test fun malformedMoneyAndImpossibleAmountsAreRejectedBeforeSearching() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            rejected { Recommender.recommend(listOf(food(1, price = value)), target, emptyMap(), preferValue = true) }
            rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = value)) }
            rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), spentYuan = value) }
        }
        rejected { Recommender.validateProposal(listOf(food(1)), target, mapOf(1L to Double.NaN)) }
        rejected { Recommender.validateProposal(listOf(food(1)), target, mapOf(1L to -1.0)) }
    }

    @Test fun manyIrrelevantFoodsDoNotStarveAnObviousLateProteinSolution() {
        val foods = (1L..51L).map { id ->
            if (id == 26L) food(id, carbs = 0.0, protein = 1.0, maximum = 100.0, price = 1.0)
            else Candidate(id, "无关食品 $id", Nutrition(13.0, 1.0, 0.0, 1.0), 1.0, 64.0, true, pricePerUnitYuan = 1.0)
        }
        val result = Recommender.recommend(foods, Nutrition(400.0, 0.0, 100.0, 0.0), emptyMap(),
            policy = RecommendationPolicy(protein = MacroTolerance(0.0, 0.0)))
        assertEquals(mapOf(26L to 100.0), result.quantities)
        assertEquals(100.0, result.nutrition.protein, 0.0)
    }

    @Test fun positiveRemainingGoalsCannotReturnAnEmptySuccessWhenNothingQualifies() {
        val message = rejected { Recommender.recommend(listOf(food(1)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 0.0)) }
        assertTrue(message.contains("未找到"))
        rejected { Recommender.recommend(listOf(food(1, price = null)), target, emptyMap(), preferValue = true) }
        assertTrue(Recommender.recommend(listOf(food(1)), Nutrition(-100.0, -10.0), emptyMap()).quantities.isEmpty())
    }

    @Test fun aZeroBudgetOrZeroMacroUpperAllowanceRejectsEvenTinyPositiveValues() {
        rejected { Recommender.recommend(listOf(food(1, price = 0.0)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 0.0), spentYuan = 1e-300) }
        rejected { Recommender.recommend(listOf(food(1, price = 1e-10)), target, emptyMap(), policy = RecommendationPolicy(dailyBudgetYuan = 0.0)) }
        val item = Candidate(1, "微量食品", Nutrition(1e-9, 1e-10), 1.0, 1.0, true, pricePerUnitYuan = 0.0)
        rejected { Recommender.validateProposal(listOf(item), Nutrition(1e-9, 0.0), mapOf(1L to 1.0), policy = RecommendationPolicy(carbs = MacroTolerance(over = 0.0))) }
    }
}
