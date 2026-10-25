package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class QuotaMathTest {
    @Test fun decimalAdditionOrderOnlyReceivesAFewUlpsOfTolerance() {
        assertTrue(QuotaMath.within(0.1 + 0.2 + 0.3, 0.6))
        assertTrue(QuotaMath.within(0.3 + 0.2 + 0.1, 0.6))
        var value = 1.0
        repeat(4) { value = Math.nextUp(value) }
        assertTrue(QuotaMath.within(value, 1.0))
        assertFalse(QuotaMath.within(Math.nextUp(value), 1.0))
        assertFalse(QuotaMath.within(0.6000000001, 0.6))
    }

    @Test fun zeroAndSubnormalQuotasCannotBecomeSmallPositiveAllowances() {
        assertTrue(QuotaMath.within(0.0, 0.0))
        assertFalse(QuotaMath.within(Double.MIN_VALUE, 0.0))
        assertFalse(QuotaMath.within(Double.MIN_VALUE * 2, Double.MIN_VALUE))
    }

    @Test fun malformedValuesNeverPassAHardLimit() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertFalse(QuotaMath.within(invalid, 1.0))
            assertFalse(QuotaMath.within(0.0, invalid))
        }
    }
}
