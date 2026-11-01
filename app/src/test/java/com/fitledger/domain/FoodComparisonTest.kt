package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class FoodComparisonTest {
    private fun food(unit:String="g",price:Double?=0.03,n:Nutrition=Nutrition(4.0,0.5,0.2,0.1))=
        ComparisonFood(1,"食品",unit,n,price)

    @Test fun gramsAndPortionsAreNeverInterchangeable() {
        assertEquals(3.0,FoodComparison.quote(food(),ComparisonBasis.GRAMS_100).costYuan!!,1e-12)
        assertTrue(ComparisonIssue.UNIT in FoodComparison.quote(food("份"),ComparisonBasis.GRAMS_100).issues)
        assertTrue(ComparisonIssue.UNIT in FoodComparison.quote(food(),ComparisonBasis.PORTION).issues)
        assertEquals(1.0,FoodComparison.quote(food("份"),ComparisonBasis.PORTION).quantity!!,0.0)
    }
    @Test fun energyAndMacrosUseNativeNutritionForEitherUnit() {
        val grams=FoodComparison.quote(food(),ComparisonBasis.KCAL_100)
        val portion=FoodComparison.quote(food("份",3.0,Nutrition(400.0,50.0,20.0,10.0)),ComparisonBasis.KCAL_100)
        assertEquals(25.0,grams.quantity!!,0.0)
        assertEquals(0.25,portion.quantity!!,0.0)
        assertEquals(grams.costYuan!!,portion.costYuan!!,1e-12)
        assertEquals(100.0,FoodComparison.quote(food(),ComparisonBasis.PROTEIN_20).quantity!!,0.0)
        assertEquals(40.0,FoodComparison.quote(food(),ComparisonBasis.CARBS_20).quantity!!,0.0)
        assertEquals(200.0,FoodComparison.quote(food(),ComparisonBasis.FAT_20).quantity!!,0.0)
    }
    @Test fun zeroNutrientAndUnknownPriceRemainSeparateReasons() {
        val quote=FoodComparison.quote(food(price=null,n=Nutrition(4.0,1.0,0.0,0.0)),ComparisonBasis.PROTEIN_20)
        assertEquals(setOf(ComparisonIssue.ZERO_NUTRIENT,ComparisonIssue.UNKNOWN_PRICE),quote.issues)
        assertNull(quote.quantity)
        assertNull(quote.costYuan)
        val unpriced=FoodComparison.quote(food(price=null),ComparisonBasis.KCAL_100)
        assertEquals(25.0,unpriced.quantity!!,0.0)
        assertEquals(setOf(ComparisonIssue.UNKNOWN_PRICE),unpriced.issues)
    }
    @Test fun zeroPriceIsFreeAndNeverUsedAsTheDenominator() {
        val quote=FoodComparison.quote(food(price=0.0),ComparisonBasis.PROTEIN_20)
        assertTrue(quote.issues.isEmpty())
        assertEquals(0.0,quote.costYuan!!,0.0)
        assertEquals(100.0,quote.quantity!!,0.0)
    }
    @Test fun overflowCannotCreateAnApparentlyComparablePrice() {
        val quote=FoodComparison.quote(food(price=Double.MAX_VALUE),ComparisonBasis.GRAMS_100)
        assertNull(quote.costYuan)
        assertTrue(ComparisonIssue.INVALID in quote.issues)
    }
}
