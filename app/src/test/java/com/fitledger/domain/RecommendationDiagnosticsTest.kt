package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class RecommendationDiagnosticsTest {
    private fun food(id: Long = 1, carbs: Double = 10.0, protein: Double = 0.0, fat: Double = 0.0,
        step: Double = 1.0, maximum: Double = 10.0, allowed: Boolean = true, price: Double? = 1.0,
        zone: Long? = null, portion: Double = 1.0) = Candidate(id, "食品$id",
        Nutrition(carbs * 4 + protein * 4 + fat * 9, carbs, protein, fat), step, maximum, allowed, zone, portion, price)

    private fun analyze(foods: List<Candidate> = listOf(food()), remaining: Nutrition = Nutrition(400.0, 100.0),
        locks: Map<Long, Double> = emptyMap(), zones: List<ZoneLimit> = emptyList(),
        policy: RecommendationPolicy = RecommendationPolicy(), spent: Double? = 0.0, preferValue: Boolean = false) =
        RecommendationDiagnostics.analyze(foods, remaining, locks, zones, policy, spent, "当前食品未找到满足要求的组合", preferValue)

    @Test fun unknownHistoricalCostsAreNotRepricedAndTrialOnlyRemovesBudget() {
        val policy = RecommendationPolicy(20.0, carbs = MacroTolerance(1.0, 2.0))
        val diagnostic = analyze(policy = policy, spent = null).first { it.code == DiagnosticCode.UNKNOWN_INTAKE_PRICE }
        assertTrue(diagnostic.detail.contains("不会按食品现价重算"))
        assertEquals(policy.copy(dailyBudgetYuan = null), diagnostic.trial!!.policy)
        assertEquals(20.0, policy.dailyBudgetYuan!!, 0.0)
    }

    @Test fun missingFoodPricesAreExcludedOnlyByAnActivePriceRequirement() {
        val foods = listOf(food(price = null), food(id = 2))
        assertFalse(analyze(foods).any { it.code == DiagnosticCode.UNKNOWN_FOOD_PRICE })
        assertTrue(analyze(foods, policy = RecommendationPolicy(20.0)).any { it.code == DiagnosticCode.UNKNOWN_FOOD_PRICE })
        assertTrue(analyze(foods, preferValue = true).any { it.code == DiagnosticCode.UNKNOWN_FOOD_PRICE })
        assertNull(analyze(foods, preferValue = true).first { it.code == DiagnosticCode.UNKNOWN_FOOD_PRICE }.trial)
    }

    @Test fun unavailableLockAndOffStepLockHaveDifferentDiagnostics() {
        assertTrue(analyze(locks = mapOf(99L to 1.0)).any { it.code == DiagnosticCode.LOCK_UNAVAILABLE })
        assertTrue(analyze(foods = listOf(food(allowed = false)), locks = mapOf(1L to 1.0))
            .any { it.code == DiagnosticCode.LOCK_UNAVAILABLE })
        for (amount in listOf(0.0, -1.0, 0.5, 11.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = analyze(locks = mapOf(1L to amount))
            assertTrue("amount=$amount", result.any { it.code == DiagnosticCode.LOCK_QUANTITY })
            assertFalse(result.any { it.code == DiagnosticCode.LOCK_UNAVAILABLE })
        }
    }

    @Test fun unknownLockedPriceIsNotZero() {
        val result = analyze(foods = listOf(food(price = null)), locks = mapOf(1L to 1.0), policy = RecommendationPolicy(0.0))
        assertTrue(result.any { it.code == DiagnosticCode.LOCK_UNKNOWN_PRICE })
        assertFalse(result.any { it.code == DiagnosticCode.LOCK_OVER_BUDGET })
    }

    @Test fun alreadyOverspentAndLockedCostExcessAreDistinguished() {
        val policy = RecommendationPolicy(5.0)
        val spent = analyze(policy = policy, spent = 6.0)
        assertTrue(spent.any { it.code == DiagnosticCode.ALREADY_OVER_BUDGET })
        val lock = analyze(locks = mapOf(1L to 2.0), policy = policy, spent = 4.0)
        assertTrue(lock.any { it.code == DiagnosticCode.LOCK_OVER_BUDGET })
        assertFalse(lock.any { it.code == DiagnosticCode.ALREADY_OVER_BUDGET })
    }

    @Test fun lockMacroAndSharedZoneTotalsUseAllLocks() {
        val policy = RecommendationPolicy(protein = MacroTolerance(over = 0.0))
        val foods = listOf(food(carbs = 0.0, protein = 10.0, zone = 1), food(id = 2, carbs = 0.0, protein = 10.0, zone = 1))
        val result = analyze(foods, remaining = Nutrition(protein = 15.0), locks = mapOf(1L to 1.0, 2L to 1.0),
            zones = listOf(ZoneLimit(1, "蛋白区", 1.0)), policy = policy)
        val macro = result.first { it.code == DiagnosticCode.LOCK_OVER_MACRO }
        assertEquals(policy.copy(protein = MacroTolerance()), macro.trial!!.policy)
        assertTrue(result.any { it.code == DiagnosticCode.LOCK_OVER_ZONE })
    }

    @Test fun exclusiveZoneLockConflictIsExplicitAndNeverOffersPolicyRelaxation() {
        val foods = listOf(food(zone = 1), food(id = 2, zone = 1))
        val result = analyze(foods, remaining = Nutrition(carbs = 1.0), locks = mapOf(1L to 1.0, 2L to 1.0),
            zones = listOf(ZoneLimit(1, "主食区", Double.MAX_VALUE, allowMultipleFoods = false)),
            policy = RecommendationPolicy(1.0, carbs = MacroTolerance(0.0, 0.0)), spent = null)
        assertEquals(listOf(DiagnosticCode.LOCK_ZONE_EXCLUSIVE), result.map { it.code })
        assertTrue(result.single().detail.contains("食品1"))
        assertTrue(result.single().detail.contains("食品2"))
        assertTrue(result.single().detail.contains("取消多余锁定"))
        assertTrue(result.single().detail.contains("分区设置开启混选"))
        assertTrue(result.all { it.trial == null })
    }

    @Test fun multiplePortionsOfOneFoodAndLocksInDifferentZonesAreNotExclusiveConflicts() {
        val foods = listOf(food(zone = 1), food(id = 2, zone = 2))
        val rules = listOf(ZoneLimit(1, "主食区", Double.MAX_VALUE, false), ZoneLimit(2, "蛋白区", Double.MAX_VALUE, false))
        assertFalse(analyze(foods, locks = mapOf(1L to 3.0), zones = rules)
            .any { it.code == DiagnosticCode.LOCK_ZONE_EXCLUSIVE })
        assertFalse(analyze(foods, locks = mapOf(1L to 1.0, 2L to 1.0), zones = rules)
            .any { it.code == DiagnosticCode.LOCK_ZONE_EXCLUSIVE })
        val mixed = listOf(food(zone = 1), food(id = 2, zone = 1))
        assertFalse(analyze(mixed, locks = mapOf(1L to 1.0, 2L to 1.0), zones = listOf(ZoneLimit(1, "主食区", 10.0)))
            .any { it.code == DiagnosticCode.LOCK_ZONE_EXCLUSIVE })
    }

    @Test fun exclusiveZoneCapacityUsesTheBestSingleFoodInsteadOfAddingAlternatives() {
        val foods = listOf(food(maximum = 1.0, zone = 1), food(id = 2, maximum = 1.0, zone = 1))
        val rule = ZoneLimit(1, "互斥区", Double.MAX_VALUE, false)
        val policy = RecommendationPolicy(carbs = MacroTolerance(under = 0.0))
        val exclusive = analyze(foods, remaining = Nutrition(carbs = 15.0), zones = listOf(rule), policy = policy)
        assertTrue(exclusive.any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE && it.detail.contains("10.00") })
        assertFalse(analyze(foods, remaining = Nutrition(carbs = 15.0), zones = listOf(rule.copy(allowMultipleFoods = true)), policy = policy)
            .any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
    }

    @Test fun oneLockedFoodOccupiesTheExclusiveZoneAndBlocksOtherFoodsFromCapacity() {
        val foods = listOf(food(carbs = 0.0, protein = 1.0, zone = 1), food(id = 2, zone = 1))
        val result = analyze(foods, remaining = Nutrition(carbs = 10.0), locks = mapOf(1L to 1.0),
            zones = listOf(ZoneLimit(1, "互斥区", Double.MAX_VALUE, false)),
            policy = RecommendationPolicy(carbs = MacroTolerance(under = 0.0)))
        assertTrue(result.any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertFalse(result.any { it.code == DiagnosticCode.LOCK_ZONE_EXCLUSIVE })
    }

    @Test fun independentMacroBoundsForExclusiveAlternativesStillDoNotProveACombinationExists() {
        val foods = listOf(food(carbs = 10.0, maximum = 1.0, zone = 1), food(id = 2, carbs = 0.0, protein = 10.0, maximum = 1.0, zone = 1))
        val result = analyze(foods, remaining = Nutrition(carbs = 10.0, protein = 10.0),
            zones = listOf(ZoneLimit(1, "互斥区", Double.MAX_VALUE, false)),
            policy = RecommendationPolicy(carbs = MacroTolerance(under = 0.0), protein = MacroTolerance(under = 0.0)))
        assertFalse(result.any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertTrue(result.any { it.code == DiagnosticCode.SEARCH_NOT_FOUND && it.detail.contains("不代表数学上无解") })
    }

    @Test fun alreadyOverMacroIsNotBlamedOnAnEmptyLock() {
        val result = analyze(remaining = Nutrition(carbs = -3.0), policy = RecommendationPolicy(carbs = MacroTolerance(over = 1.0)))
        assertTrue(result.any { it.code == DiagnosticCode.ALREADY_OVER_MACRO })
        assertFalse(result.any { it.code == DiagnosticCode.LOCK_OVER_MACRO })
    }

    @Test fun aComputableNutrientShortfallExplainsOnlyThatDirection() {
        val policy = RecommendationPolicy(carbs = MacroTolerance(under = 0.0, over = 2.0), fat = MacroTolerance(over = 1.0))
        val diagnostic = analyze(foods = listOf(food(maximum = 2.0)), remaining = Nutrition(carbs = 30.0), policy = policy)
            .first { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE }
        assertEquals(policy.copy(carbs = MacroTolerance(over = 2.0)), diagnostic.trial!!.policy)
        assertTrue(diagnostic.detail.contains("20.00"))
    }

    @Test fun capacityRespectsExactLocksAndIndividualBudgetAndZoneCaps() {
        val policy = RecommendationPolicy(1.0, carbs = MacroTolerance(under = 0.0))
        assertTrue(analyze(remaining = Nutrition(carbs = 20.0), policy = policy)
            .any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertTrue(analyze(remaining = Nutrition(carbs = 20.0), locks = mapOf(1L to 1.0),
            policy = policy.copy(dailyBudgetYuan = null)).any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertTrue(analyze(foods = listOf(food(zone = 1)), remaining = Nutrition(carbs = 20.0),
            zones = listOf(ZoneLimit(1, "区", 1.0)), policy = policy.copy(dailyBudgetYuan = null))
            .any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
    }

    @Test fun competingFoodsUseAnOptimisticBoundAndDoNotFalselyProveInfeasibility() {
        val result = analyze(foods = listOf(food(maximum = 1.0, zone = 1), food(id = 2, maximum = 1.0, zone = 1)),
            remaining = Nutrition(carbs = 15.0), zones = listOf(ZoneLimit(1, "共享区", 1.0)),
            policy = RecommendationPolicy(carbs = MacroTolerance(under = 0.0)))
        assertFalse(result.any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertTrue(result.any { it.code == DiagnosticCode.SEARCH_NOT_FOUND && it.detail.contains("不代表数学上无解") })
    }

    @Test fun discreteSearchFailureIsNeverReportedAsAProofAndTrialsKeepOtherDirections() {
        val policy = RecommendationPolicy(15.0, carbs = MacroTolerance(0.0, 0.0), protein = MacroTolerance(over = 2.0))
        val result = analyze(foods = listOf(food(maximum = 1.0)), remaining = Nutrition(carbs = 5.0), policy = policy)
        assertFalse(result.any { it.code == DiagnosticCode.LOWER_BOUND_UNREACHABLE })
        assertTrue(result.any { it.detail.contains("时间与组合数量上限") })
        val over = result.mapNotNull { it.trial }.first { it.id == "0_over" }.policy
        assertEquals(policy.copy(carbs = MacroTolerance(under = 0.0)), over)
        assertTrue(result.mapNotNull { it.trial }.none { it.policy == RecommendationPolicy() })
    }

    @Test fun invalidInputReturnsADiagnosticWithoutThrowing() {
        val result = analyze(policy = RecommendationPolicy(dailyBudgetYuan = Double.NaN))
        assertEquals(listOf(DiagnosticCode.INVALID_INPUT), result.map { it.code })
    }

    @Test fun analysisDoesNotChangeTheNormalRecommendationOrItsPolicy() {
        val foods = listOf(food())
        val remaining = Nutrition(80.0, 20.0)
        val policy = RecommendationPolicy(10.0, carbs = MacroTolerance(0.0, 0.0))
        val before = Recommender.recommend(foods, remaining, emptyMap(), policy = policy)
        analyze(foods, remaining, policy = policy)
        val after = Recommender.recommend(foods, remaining, emptyMap(), policy = policy)
        assertEquals(before, after)
        assertEquals(10.0, policy.dailyBudgetYuan!!, 0.0)
    }
}
