package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class DirectNutritionTest {
    @Test fun convertsGramsToCanonicalEnergyAndKeepsEachMacro() {
        assertEquals(Nutrition(590.0,100.0,25.0,10.0),DirectNutrition.calculate(100.0,25.0,10.0))
        assertEquals(Nutrition(45.0,0.0,0.0,5.0),DirectNutrition.parse(""," ","5"))
        assertEquals(Nutrition(10.0,1.25,1.25,0.0),DirectNutrition.parse(" 1.25 ","1.25","0"))
        assertEquals(2468.56,EnergyUnit.KJ.fromKcal(590.0),1e-8)
    }
    @Test fun invalidAndEmptyEntriesCannotBecomeRecordedNutrition() {
        for(input in listOf("-1","NaN","Infinity","abc","1,2","1e309")) {
            assertTrue(input,runCatching {DirectNutrition.parse(input,"2","3")}.isFailure)
        }
        assertTrue(runCatching {DirectNutrition.parse("","","")}.isFailure)
        assertTrue(runCatching {DirectNutrition.calculate(Double.MAX_VALUE,0.0,0.0)}.isFailure)
    }
}
