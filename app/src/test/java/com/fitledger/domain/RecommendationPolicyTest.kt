package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class RecommendationPolicyTest {
    @Test fun legacyDefaultHasNoBudgetOrMacroRestrictionsAndZeroIsExplicit() {
        val unlimited = RecommendationPolicy()
        unlimited.validate()
        assertNull(unlimited.dailyBudgetYuan)
        listOf(unlimited.carbs, unlimited.protein, unlimited.fat).forEach { assertNull(it.under); assertNull(it.over) }
        val strict = RecommendationPolicy(0.0, MacroTolerance(over = 0.0), MacroTolerance(under = 0.0), MacroTolerance(0.0, 0.0))
        strict.validate()
        assertNotEquals(unlimited, strict)
    }

    @Test fun budgetAndBothToleranceDirectionsRejectNonFiniteOrNegativeNumbers() {
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(runCatching { RecommendationPolicy(dailyBudgetYuan = invalid).validate() }.isFailure)
            assertTrue(runCatching { MacroTolerance(under = invalid).validate() }.isFailure)
            assertTrue(runCatching { MacroTolerance(over = invalid).validate() }.isFailure)
            assertTrue(runCatching { RecommendationPolicy(fat = MacroTolerance(over = invalid)).validate() }.isFailure)
        }
    }

    @Test fun everyNutrientAndDirectionCanBeConfiguredIndependently() {
        val policy = RecommendationPolicy(30.5, MacroTolerance(10.0, 0.0), MacroTolerance(0.0, null), MacroTolerance(null, 5.0))
        policy.validate()
        assertEquals(10.0, policy.carbs.under!!, 0.0)
        assertEquals(0.0, policy.carbs.over!!, 0.0)
        assertNull(policy.protein.over); assertNull(policy.fat.under)
        assertEquals(5.0, policy.fat.over!!, 0.0)
    }
}
