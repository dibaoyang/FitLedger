package com.fitledger.domain
import org.junit.Test
import org.junit.Assert.*
class ExerciseCalculatorTest {
    @Test fun presetsUseNetEnergyAndTime() {
        assertEquals(210.0,ExerciseCalculator.extra(70.0,30.0,Cardio.HILL.met),1e-8)
        assertEquals(203.0,ExerciseCalculator.extra(70.0,30.0,Cardio.STAIRS.met),1e-8)
        assertEquals(245.0,ExerciseCalculator.extra(70.0,30.0,Cardio.RUN.met),1e-8)
    }
    @Test fun gapCanBeDeficitOrSurplusAndMissingBaseStaysUnknown() {
        assertEquals(800.0,ExerciseCalculator.gap(2200.0,300.0,1700.0)!!,0.0)
        assertEquals(-200.0,ExerciseCalculator.gap(2200.0,300.0,2700.0)!!,0.0)
        assertNull(ExerciseCalculator.gap(null,300.0,1700.0))
    }
    @Test fun rejectsInvalidInput() {listOf(0.0,-1.0,1441.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {assertTrue(runCatching {ExerciseCalculator.extra(70.0,it,7.0)}.isFailure)}}
    @Test fun manualKjIsCanonicalKcal() {assertEquals(100.0,EnergyUnit.KJ.toKcal(418.4),1e-8)}
}
