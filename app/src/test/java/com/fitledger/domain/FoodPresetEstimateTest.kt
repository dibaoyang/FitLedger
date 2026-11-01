package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class FoodPresetEstimateTest {
    @Test fun previousDayExpenditureMinusPlannedIntakeIsOnlyAnEstimate() {
        val preview=FoodPresetEstimates.calculate(2600.0,listOf(Nutrition(1200.0,150.0,80.0,30.0),Nutrition(800.0,90.0,50.0,20.0)))
        assertEquals(2000.0,preview.nutrition.kcal,0.0)
        assertEquals(600.0,preview.deficitKcal!!,0.0)
        assertEquals(240.0,preview.nutrition.carbs,0.0)
        assertEquals(130.0,preview.nutrition.protein,0.0)
        assertEquals(50.0,preview.nutrition.fat,0.0)
    }
    @Test fun missingReferenceStaysUnknownWhileNutrientsRemainAvailable() {
        val preview=FoodPresetEstimates.calculate(null,listOf(Nutrition(800.0,100.0,35.0,20.0)))
        assertNull(preview.referenceExpenditureKcal)
        assertNull(preview.deficitKcal)
        assertEquals(800.0,preview.nutrition.kcal,0.0)
    }
    @Test fun surplusRetainsItsSignAndFoodLabelEnergyIsNotRecalculatedFromMacros() {
        val preview=FoodPresetEstimates.calculate(2000.0,listOf(Nutrition(2300.0,100.0,50.0,20.0)))
        assertEquals(-300.0,preview.deficitKcal!!,0.0)
        assertEquals(2300.0,preview.nutrition.kcal,0.0)
    }
    @Test fun forecastOnlyCountsExplicitPresetItems() {
        val preview=FoodPresetEstimates.calculate(2500.0,listOf(Nutrition(500.0,80.0,20.0,10.0)))
        assertEquals(2000.0,preview.deficitKcal!!,0.0)
        assertEquals(Nutrition(),FoodPresetEstimates.calculate(2500.0,emptyList()).nutrition)
    }
    @Test fun invalidOrOverflowingTotalsNeverBecomeAUsableForecast() {
        listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {
            assertTrue(runCatching {FoodPresetEstimates.calculate(it,emptyList())}.isFailure)
        }
        assertTrue(runCatching {FoodPresetEstimates.calculate(null,listOf(Nutrition(-1.0)))}.isFailure)
        assertTrue(runCatching {FoodPresetEstimates.calculate(null,listOf(Nutrition(Double.MAX_VALUE),Nutrition(Double.MAX_VALUE)))}.isFailure)
    }
}
