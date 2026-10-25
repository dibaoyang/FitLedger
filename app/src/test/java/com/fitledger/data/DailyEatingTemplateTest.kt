package com.fitledger.data

import org.junit.Assert.*
import org.junit.Test

class DailyEatingTemplateTest {
    private val fixed = DailyFoodTemplate(foodId = 7, unit = "g", mode = "FIXED", quantity = 120.0, startDay = 20000)

    @Test fun fixedGramsAndPortionsKeepTheirNativeAmounts() {
        assertEquals(120.0, fixed.quantityForWeight(60.0), 0.0)
        assertEquals(120.0, fixed.quantityForWeight(90.0), 0.0)
        assertEquals(2.0, fixed.copy(unit = "份", quantity = 2.0).quantityForWeight(60.0), 0.0)
    }

    @Test fun perWeightUsesRealKgAndKeepsPrecisionUntilDisplay() {
        val template = fixed.copy(mode = "PER_WEIGHT", quantity = 1.25)
        assertEquals(75.0, template.quantityForWeight(60.0), 0.0)
        assertEquals(90.5, template.quantityForWeight(72.4), 1e-10)
        assertEquals(100.0, template.quantityForWeight(80.0), 0.0)
    }

    @Test fun rejectsInvalidIdentifiersUnitsModesQuantitiesAndDates() {
        val invalid = listOf(
            fixed.copy(id = -1), fixed.copy(foodId = 0), fixed.copy(unit = "kg"),
            fixed.copy(mode = "UNKNOWN"), fixed.copy(unit = "份", mode = "PER_WEIGHT"),
            fixed.copy(quantity = 0.0), fixed.copy(quantity = -1.0),
            fixed.copy(quantity = Double.NaN), fixed.copy(quantity = Double.POSITIVE_INFINITY),
            fixed.copy(startDay = -1), fixed.copy(startDay = Long.MAX_VALUE)
        )
        invalid.forEach { assertTrue("Expected invalid template: $it", runCatching { it.validate() }.isFailure) }
    }

    @Test fun invalidWeightAndQuantityOverflowNeverProduceAnIntakeAmount() {
        val template = fixed.copy(mode = "PER_WEIGHT")
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { weight ->
            assertTrue(runCatching { template.quantityForWeight(weight) }.isFailure)
        }
        assertTrue(runCatching { template.copy(quantity = Double.MAX_VALUE).quantityForWeight(72.4) }.isFailure)
    }
}
