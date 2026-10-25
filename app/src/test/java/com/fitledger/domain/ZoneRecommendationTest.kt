package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class ZoneRecommendationTest {
    private val largeTarget = Nutrition(10000.0, 1000.0, 500.0, 100.0)
    private fun bread(id: Long, maximum: Double = 6.0, zone: Long? = 7L, allowed: Boolean = true) =
        Candidate(id, "面包 $id", Nutrition(100.0, 20.0, 3.0, 1.0), 1.0, maximum, allowed, zone, 1.0)
    private fun portions(foods: List<Candidate>, result: Suggestion, zone: Long = 7L) = foods
        .filter { it.zoneId == zone }.sumOf { (result.quantities[it.id] ?: 0.0) / it.zonePortionSize }
    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)

    @Test fun twoBreadTypesShareOneThreePortionBudgetIncludingLocks() {
        val foods = listOf(bread(1), bread(2))
        val result = Recommender.recommend(foods, largeTarget, mapOf(1L to 1.0), listOf(ZoneLimit(7, "面包", 3.0)))
        assertEquals(1.0, result.quantities.getValue(1), 0.0)
        assertEquals(2.0, result.quantities.getValue(2), 0.0)
        assertEquals(3.0, portions(foods, result), 0.0)
    }

    @Test fun remainingDailyBudgetAlreadyDeductedByCallerCapsWholeRecommendation() {
        val foods = listOf(bread(1), bread(2))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 2.0)))
        assertEquals(2.0, portions(foods, result), 0.0)
    }

    @Test fun individualMaximumIsRetainedWhenSharedLimitIsLarger() {
        val foods = listOf(bread(1, 2.0), bread(2, 2.0))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 5.0)))
        assertEquals(mapOf(1L to 2.0, 2L to 2.0), result.quantities)
    }

    @Test fun gramsAndServingsUseTheirOwnExplicitPortionConversion() {
        val foods = listOf(
            Candidate(1, "称重面包", Nutrition(2.0, 0.4, 0.06, 0.02), 25.0, 300.0, true, 7, 100.0),
            bread(2)
        )
        val result = Recommender.recommend(foods, largeTarget, mapOf(1L to 150.0), listOf(ZoneLimit(7, "面包", 3.0)))
        assertEquals(150.0, result.quantities.getValue(1), 0.0)
        assertEquals(1.0, result.quantities.getValue(2), 0.0)
        assertEquals(2.5, portions(foods, result), 0.0)
    }

    @Test fun overBudgetLocksAreRejectedBeforeSearching() {
        val foods = listOf(bread(1), bread(2))
        rejected { Recommender.recommend(foods, largeTarget, mapOf(1L to 2.0, 2L to 2.0), listOf(ZoneLimit(7, "面包", 3.0))) }
    }

    @Test fun zeroSharedBudgetBlocksItsFoodsButAllowsOtherZones() {
        val foods = listOf(bread(1), bread(2, zone = 8), bread(3, zone = null))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 0.0)))
        assertFalse(result.quantities.containsKey(1))
        assertTrue(result.quantities.getValue(2) > 0)
        assertTrue(result.quantities.getValue(3) > 0)
        rejected { Recommender.recommend(foods, largeTarget, mapOf(1L to 1.0), listOf(ZoneLimit(7, "面包", 0.0))) }
    }

    @Test fun disabledFoodsCannotBeRecommendedOrLockedEvenWithinACappedZone() {
        val foods = listOf(bread(1, allowed = false), bread(2))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 3.0)))
        assertEquals(mapOf(2L to 3.0), result.quantities)
        rejected { Recommender.recommend(foods, largeTarget, mapOf(1L to 1.0), listOf(ZoneLimit(7, "面包", 3.0))) }
        rejected { Recommender.recommend(foods, largeTarget, mapOf(999L to 1.0)) }
    }

    @Test fun aZoneWithoutAnyLimitRetainsIndividualMaximumBehavior() {
        val food = bread(1, 4.0)
        assertEquals(mapOf(1L to 4.0), Recommender.recommend(listOf(food), largeTarget, emptyMap()).quantities)
        val noZone = food.copy(zoneId = null)
        assertEquals(
            Recommender.recommend(listOf(noZone), largeTarget, emptyMap()),
            Recommender.recommend(listOf(noZone), largeTarget, emptyMap(), emptyList())
        )
    }

    @Test fun decimalStepsCannotRoundPastTheSharedBudget() {
        val foods = listOf(bread(1).copy(step = 0.1), bread(2).copy(step = 0.1))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 0.3)))
        assertTrue(portions(foods, result) > 0)
        assertTrue(QuotaMath.within(portions(foods, result), 0.3))
        result.quantities.forEach { (_, amount) -> assertEquals(kotlin.math.round(amount / 0.1), amount / 0.1, 1e-9) }
    }

    @Test fun distinctZonesHaveIndependentBudgets() {
        val foods = listOf(bread(1), bread(2), bread(3, zone = 8))
        val result = Recommender.recommend(foods, largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 3.0), ZoneLimit(8, "其他", 1.0)))
        assertEquals(3.0, portions(foods, result), 0.0)
        assertEquals(1.0, portions(foods, result, 8), 0.0)
    }

    @Test fun aFullBudgetCanSwapFoodsToImproveProtein() {
        val foods = listOf(
            Candidate(1, "普通面包", Nutrition(100.0, 20.0, 1.0, 1.0), 1.0, 3.0, true, 7, 1.0),
            Candidate(2, "高蛋白面包", Nutrition(100.0, 18.0, 10.0, 1.0), 1.0, 3.0, true, 7, 1.0)
        )
        val result = Recommender.recommend(foods, Nutrition(300.0, 56.0, 21.0, 3.0), emptyMap(), listOf(ZoneLimit(7, "面包", 3.0)))
        assertEquals(mapOf(1L to 1.0, 2L to 2.0), result.quantities)
        assertEquals(Nutrition(300.0, 56.0, 21.0, 3.0), result.nutrition)
        val locked = Recommender.recommend(foods, Nutrition(300.0, 56.0, 21.0, 3.0), mapOf(1L to 2.0), listOf(ZoneLimit(7, "面包", 3.0)))
        assertEquals(mapOf(1L to 2.0, 2L to 1.0), locked.quantities)
    }

    @Test fun duplicateIdsAndInvalidConversionsAreRejected() {
        rejected { Recommender.recommend(listOf(bread(1), bread(1)), largeTarget, emptyMap()) }
        rejected { Recommender.recommend(listOf(bread(0)), largeTarget, emptyMap()) }
        rejected { Recommender.recommend(listOf(bread(1)), largeTarget, emptyMap(), listOf(ZoneLimit(7, "面包", 1.0), ZoneLimit(7, "重复", 2.0))) }
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { size ->
            rejected { Recommender.recommend(listOf(bread(1).copy(zonePortionSize = size)), largeTarget, emptyMap()) }
        }
        rejected { Recommender.recommend(listOf(bread(1, zone = 0)), largeTarget, emptyMap()) }
    }

    @Test fun malformedLimitsNutritionAndUnboundedSearchAreRejected() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { rejected { ZoneLimit(7, "面包", it) } }
        rejected { ZoneLimit(0, "面包", 3.0) }
        rejected { ZoneLimit(7, "", 3.0) }
        rejected { Recommender.recommend(listOf(bread(1)), Nutrition(Double.NaN), emptyMap()) }
        rejected { Recommender.recommend(listOf(bread(1).copy(perUnit = Nutrition(Double.POSITIVE_INFINITY))), largeTarget, emptyMap()) }
        rejected { Recommender.recommend(listOf(bread(1).copy(step = 0.001)), largeTarget, emptyMap()) }
        rejected { Recommender.recommend(listOf(bread(1).copy(zonePortionSize = Double.MIN_VALUE)), largeTarget, emptyMap()) }
    }

    @Test fun negativeRemainingNutritionStillChoosesNothing() {
        val result = Recommender.recommend(listOf(bread(1)), Nutrition(-100.0, -10.0, -10.0, -5.0), emptyMap(), listOf(ZoneLimit(7, "面包", 3.0)))
        assertTrue(result.quantities.isEmpty())
    }
}
