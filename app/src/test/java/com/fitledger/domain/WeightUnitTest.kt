package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeightUnitTest {
    @Test fun jinInputIsCanonicalKgForBodyLeanEnergyAndTargets() {
        val fromJin=Body(WeightUnit.JIN.toKg(144.8),15.2,58.1)
        val fromKg=Body(WeightUnit.KG.toKg(72.4),15.2,58.1)
        fromJin.validate()
        assertEquals(fromKg,fromJin)
        assertEquals(61.3952,fromJin.lean,1e-10)
        val day=LocalDate.of(2026,10,13).toEpochDay()
        val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
        assertEquals(EnergyExpenditureCalculator.snapshot(fromKg.weight,profile,day),EnergyExpenditureCalculator.snapshot(fromJin.weight,profile,day))
        val plan=Plan(protein=Rule(Basis.WEIGHT,2.0),fat=Rule(Basis.LEAN,0.8))
        assertEquals(TargetCalculator.calculate(plan,fromKg,day),TargetCalculator.calculate(plan,fromJin,day))
    }

    @Test fun switchingInputUnitsConvertsExactDecimalsWithoutRoundingDrift() {
        var raw="72.4123456789"
        repeat(100) {
            raw=WeightUnit.KG.convertInput(raw,WeightUnit.JIN)!!
            assertEquals("144.8246913578",raw)
            raw=WeightUnit.JIN.convertInput(raw,WeightUnit.KG)!!
        }
        assertEquals("72.4123456789",raw)
        assertEquals("60",WeightUnit.JIN.convertInput("120",WeightUnit.KG))
        assertEquals("120",WeightUnit.KG.convertInput("60",WeightUnit.JIN))
        assertEquals(144.8,WeightUnit.JIN.fromKg(72.4),0.0)
    }

    @Test fun unfinishedOrInvalidInputIsNeverReinterpretedInAnotherUnit() {
        assertEquals("",WeightUnit.KG.convertInput("",WeightUnit.JIN))
        listOf(".","1..2","NaN","Infinity","abc","1e400").forEach {
            assertNull(WeightUnit.KG.convertInput(it,WeightUnit.JIN))
        }
        assertEquals("120",WeightUnit.KG.convertInput("60.",WeightUnit.JIN))
    }

    @Test fun weightLimitsAreValidatedInKgRegardlessOfInputUnit() {
        Body(WeightUnit.JIN.toKg(40.0),20.0,10.0).validate()
        Body(WeightUnit.JIN.toKg(800.0),20.0,100.0).validate()
        listOf(39.0,801.0,0.0,-120.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {
            assertTrue(runCatching {Body(WeightUnit.JIN.toKg(it),20.0,10.0).validate()}.isFailure)
        }
    }

    @Test fun shortcutUsesKilogramsAndDoesNotRoundToRecommendationSteps() {
        assertEquals(60.0,WeightFoodShortcut.grams(WeightUnit.JIN.toKg(120.0),"g",true)!!,0.0)
        assertEquals(72.4123,WeightFoodShortcut.grams(72.4123,"g",true)!!,0.0)
        val foodPer100Grams=Nutrition(120.0,20.0,4.0,2.0)
        val eaten=foodPer100Grams*(WeightFoodShortcut.grams(60.0,"g",true)!!/100.0)
        assertEquals(72.0,eaten.kcal,1e-10)
        assertEquals(12.0,eaten.carbs,1e-10)
    }

    @Test fun shortcutRequiresGramsEnabledPreferenceAndValidSavedWeight() {
        assertNull(WeightFoodShortcut.grams(60.0,"份",true))
        assertNull(WeightFoodShortcut.grams(60.0,"g",false))
        listOf(0.0,-60.0,Double.NaN,Double.POSITIVE_INFINITY,500.0).forEach {
            assertNull(WeightFoodShortcut.grams(it,"g",true))
        }
    }
}
