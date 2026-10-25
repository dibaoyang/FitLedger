package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class FoodPricingTest {
    @Test fun packageAndGramPurchasesKeepFractionalUnitPrecision() {
        assertEquals(2.0,FoodPricing.perUnit(12.0,3.0,2.0),0.0)
        val gramPrice=FoodPricing.perUnit(9.9,3.0,500.0)
        assertEquals(0.0066,gramPrice,1e-12)
        assertEquals(0.99,FoodPricing.cost(gramPrice,150.0)!!,1e-12)
    }
    @Test fun unknownPriceAndFreeFoodRemainDifferent() {
        assertNull(FoodPricing.cost(null,100.0))
        assertEquals(0.0,FoodPricing.cost(0.0,100.0)!!,0.0)
    }
    @Test fun invalidAndOverflowingPricesCannotCreateSnapshots() {
        listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {bad->
            assertThrows(IllegalArgumentException::class.java) {FoodPricing.perUnit(bad,1.0,100.0)}
        }
        assertThrows(IllegalArgumentException::class.java) {FoodPricing.perUnit(10.0,0.0,100.0)}
        assertThrows(IllegalArgumentException::class.java) {FoodPricing.cost(Double.MAX_VALUE,2.0)}
        assertThrows(IllegalArgumentException::class.java) {FoodPricing.perUnit(10.0,Double.MAX_VALUE,2.0)}
    }
}
