package com.fitledger.data

import com.fitledger.domain.*
import org.junit.Assert.*
import org.junit.Test

class IntakeNutritionTotalsTest {
    private val day=20000L
    private val meal=Intake(1,day,7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),costYuan=2.0)
    private fun energy(kcal:Double=500.0)=energyIntake(day,kcal,"外食估算",12.0).copy(id=2)

    @Test fun mixedRecordsKeepCaloriesAndKnownSubtotalButAllMacroTotalsAreUnknown() {
        val summary=IntakeNutritionTotals.calculate(listOf(meal,energy()))
        assertEquals(616.0,summary.kcal,0.0)
        assertEquals(2,summary.count);assertEquals(1,summary.unknownMacroCount)
        assertEquals(meal.nutrition.carbs,summary.knownNutrition.carbs,0.0)
        assertNull(summary.carbs);assertNull(summary.protein);assertNull(summary.fat);assertNull(summary.completeNutrition)
        val failure=runCatching {IntakeNutritionTotals.requireKnown(listOf(meal,energy()))}.exceptionOrNull()
        assertEquals(IntakeNutritionTotals.INCOMPLETE_MESSAGE,failure?.message)
        assertFalse(energy().macrosKnown);assertTrue(meal.macrosKnown)
    }

    @Test fun explicitZeroMacrosAreKnownAndAnEmptyDayHasNoInventedIntake() {
        val zero=meal.copy(nutrition=Nutrition(0.0,0.0,0.0,0.0))
        val known=IntakeNutritionTotals.calculate(listOf(meal,zero))
        assertEquals(meal.nutrition,known.completeNutrition);assertEquals(0,known.unknownMacroCount)
        assertEquals(meal.nutrition,IntakeNutritionTotals.requireKnown(listOf(meal)))
        val empty=IntakeNutritionTotals.calculate(emptyList())
        assertEquals(0,empty.count);assertEquals(Nutrition(),empty.completeNutrition)
    }

    @Test fun energyFactoryPreservesKcalAndCostWithoutCreatingAFoodOrObservedMacros() {
        val entry=energyIntake(day,EnergyUnit.KJ.toKcal(2092.0),"  ",null)
        assertEquals(500.0,entry.nutrition.kcal,1e-9)
        assertEquals("仅热量记录",entry.name);assertEquals("ENERGY",entry.source)
        assertNull(entry.foodId);assertNull(entry.costYuan);assertEquals("次",entry.unit)
        assertEquals(1.0,entry.quantity,0.0)
        assertNull(IntakeNutritionTotals.calculate(listOf(entry)).protein)
    }

    @Test fun malformedUnknownMarkersAndInvalidNumbersFailRatherThanBecomeZeros() {
        listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {value->
            assertTrue(runCatching {energy(value)}.isFailure)
        }
        val valid=energy()
        listOf(valid.copy(foodId=7),valid.copy(unit="g"),valid.copy(quantity=2.0),valid.copy(source="DIRECT"),
            valid.copy(nutrition=valid.nutrition.copy(carbs=1.0)),valid.copy(name=""),valid.copy(name="a\nb"),
            valid.copy(name="x".repeat(101)),valid.copy(costYuan=-1.0),valid.copy(costYuan=Double.NaN)).forEach {
            assertTrue(runCatching {IntakeNutritionTotals.validateEnergy(it)}.isFailure)
        }
        assertTrue(runCatching {IntakeNutritionTotals.calculate(listOf(energy(Double.MAX_VALUE),energy(Double.MAX_VALUE)))}.isFailure)
        assertTrue(runCatching {IntakeNutritionTotals.calculate(listOf(meal.copy(nutrition=Nutrition(1.0,-1.0))))}.isFailure)
    }
}
