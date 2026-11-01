package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class PortionQuantitiesTest {
    @Test fun newPortionCountsArePositiveIntegersWhileGramQuantitiesMayBeDecimal() {
        listOf(1.0,2.0,2000.0).forEach {PortionQuantities.requireWhole("份",it)}
        listOf(0.5,1.5,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {n->
            assertFalse(PortionQuantities.isWhole("份",n));assertTrue(runCatching {PortionQuantities.requireWhole("份",n)}.isFailure)
        }
        PortionQuantities.requireWhole("g",0.5);PortionQuantities.requireWhole("g",600.25)
    }
    @Test fun decimalLegacyStepUsesItsLeastWholeCompatibleMultiple() {
        listOf(0.5 to 1.0,0.25 to 1.0,0.2 to 1.0,0.3 to 3.0,1.5 to 3.0,2.5 to 5.0,2.0 to 2.0,10.0 to 10.0).forEach {(step,expected)->
            assertEquals(expected,PortionQuantities.recommendStep(step)!!,0.0)
        }
    }
    @Test fun invalidStepDoesNotBecomeAnArbitrarySinglePortion() {
        listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {assertNull(PortionQuantities.recommendStep(it))}
        assertTrue(PortionQuantities.recommendStep(0.3333333333333333)!!>1000)
    }
    @Test fun integerDisplayRemovesDecimalAndLegacyFractionIsExplicitAndPrecise() {
        assertEquals("2",PortionQuantities.format("份",2.0))
        assertEquals("1.23456789（旧的小数份）",PortionQuantities.format("份",1.23456789))
        assertEquals("100.25",PortionQuantities.format("g",100.25))
        assertEquals("1",PortionQuantities.inputText(1.0));assertEquals("0.5",PortionQuantities.inputText(0.5))
    }
}
