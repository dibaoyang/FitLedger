package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OptionalMuscleTest {
    private val measured=Body(72.4,15.2,58.1)
    private val omitted=measured.copy(muscle=null)
    private val day=LocalDate.of(2026,10,15).toEpochDay()

    @Test fun absenceIsDifferentFromZeroAndInvalidMeasurementsStillFail() {
        omitted.validate()
        assertNull(omitted.muscle)
        assertEquals(61.3952,omitted.lean,1e-10)
        listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,70.0).forEach {
            assertTrue(runCatching {measured.copy(muscle=it).validate()}.isFailure)
        }
        assertTrue(runCatching {omitted.copy(weight=Double.NaN).validate()}.isFailure)
        assertTrue(runCatching {omitted.copy(fatPercent=0.0).validate()}.isFailure)
    }

    @Test fun everyLegacyPlanUsesSameWeightLeanAndEnergyWhenMuscleIsOmitted() {
        val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
        val energy=EnergyExpenditureCalculator.snapshot(measured.weight,profile,day)
        assertEquals(energy,EnergyExpenditureCalculator.snapshot(omitted.weight,profile,day))
        PlanMode.entries.forEach {mode ->
            val plan=Plan(mode=mode,carbs=Rule(Basis.REMAINDER),protein=Rule(Basis.LEAN,2.0),fat=Rule(Basis.WEIGHT,0.8))
            assertEquals(mode.name,TargetCalculator.calculate(plan,measured,day,measured.lean,energy.expenditureKcal),
                TargetCalculator.calculate(plan,omitted,day,omitted.lean,energy.expenditureKcal))
        }
    }

    @Test fun advancedDeficitAndPercentageAllocationDoesNotRequireMuscle() {
        val plan=Plan(advanced=AdvancedPlan())
        assertEquals(TargetCalculator.calculate(plan,measured,day,measured.lean,2500.0),
            TargetCalculator.calculate(plan,omitted,day,omitted.lean,2500.0))
    }
}
