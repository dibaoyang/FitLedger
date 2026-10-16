package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DomainTest {
    @Test(expected=IllegalArgumentException::class) fun invalidInactivePlanConfigurationCannotBeSaved() {
        TargetCalculator.calculate(Plan(mode=PlanMode.FIXED,cycle=listOf(Double.NaN)),Body(70.0,20.0,45.0),0)
    }
    @Test fun energyConversionIsReversibleAndPreservesNegativeRemainders() {
        assertEquals(100.0,EnergyUnit.KJ.toKcal(418.4),1e-10)
        listOf(0.0,116.0,-800.0,2200.123456).forEach {n->assertEquals(n,EnergyUnit.KJ.toKcal(EnergyUnit.KJ.fromKcal(n)),1e-9)}
    }
    @Test fun immediateRecalculationCanUsePersistedLeanSnapshot() {
        val plan=Plan(protein=Rule(Basis.LEAN,2.0))
        assertEquals(122.8,TargetCalculator.calculate(plan,Body(72.4,15.2,58.1),0,61.4).protein,1e-9)
    }
    private val body=Body(72.4,15.2,58.1)
    private val monday=LocalDate.of(2026,9,14).toEpochDay()
    @Test fun leanUsesPercent() {assertEquals(61.3952,body.lean,0.000001)}
    @Test fun leanProteinAndRemainingCarbs() {
        val p=Plan(energy=Rule(Basis.FIXED,2200.0),protein=Rule(Basis.LEAN,2.0),fat=Rule(Basis.LEAN,0.8),carbs=Rule(Basis.REMAINDER))
        val n=TargetCalculator.calculate(p,body,monday)
        assertEquals(122.7904,n.protein,0.00001);assertEquals(49.11616,n.fat,0.00001)
        assertEquals(2200.0,n.kcal,0.00001)
    }
    @Test fun cycleUsesCalendarWeekday() {
        val p=Plan(mode=PlanMode.CYCLE)
        assertEquals(175.0,TargetCalculator.calculate(p,body,monday).carbs,0.001)
        assertEquals(325.0,TargetCalculator.calculate(p,body,monday+2).carbs,0.001)
    }
    @Test fun taperRespectsBoundariesAndFloor() {
        val p=Plan(mode=PlanMode.TAPER,start=monday,taperDays=7,taperGrams=20.0,minimumCarbs=100.0)
        assertEquals(250.0,TargetCalculator.calculate(p,body,monday+6).carbs,0.001)
        assertEquals(230.0,TargetCalculator.calculate(p,body,monday+7).carbs,0.001)
        assertEquals(100.0,TargetCalculator.calculate(p,body,monday+700).carbs,0.001)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMultipleRemainders() {
        TargetCalculator.calculate(Plan(carbs=Rule(Basis.REMAINDER),fat=Rule(Basis.REMAINDER)),body,monday)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNegativeRemainingEnergy() {
        TargetCalculator.calculate(Plan(energy=Rule(Basis.FIXED,100.0),carbs=Rule(Basis.REMAINDER)),body,monday)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNaNBody() {Body(Double.NaN,20.0,40.0).validate()}
    private val foods=listOf(
        Candidate(1,"米饭",Nutrition(1.16,0.26,0.026,0.003),10.0,400.0,true),
        Candidate(2,"鸡蛋",Nutrition(72.0,0.4,6.3,4.8),1.0,3.0,true),
        Candidate(3,"不允许",Nutrition(100.0,20.0,10.0,3.0),1.0,9.0,false))
    @Test fun recommendationHonorsExactLocksAndPortions() {
        val s=Recommender.recommend(foods,Nutrition(550.0,90.0,25.0,15.0),mapOf(1L to 200.0))
        assertEquals(200.0,s.quantities.getValue(1L),0.001)
        assertFalse(3L in s.quantities)
        val eggs=s.quantities[2L] ?: 0.0
        assertEquals(eggs.toInt().toDouble(),eggs,0.001);assertTrue(eggs<=3)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsForbiddenLock() {Recommender.recommend(foods,Nutrition(),mapOf(3L to 1.0))}
    @Test(expected=IllegalArgumentException::class) fun rejectsFractionalEggLock() {Recommender.recommend(foods,Nutrition(),mapOf(2L to 1.5))}
    @Test fun overshootDoesNotAddUnnecessaryFoods() {
        val s=Recommender.recommend(foods,Nutrition(-100.0,-10.0,-10.0,-5.0),emptyMap())
        assertTrue(s.quantities.isEmpty())
    }
    @Test fun remainingKeepsNegativeValues() {assertEquals(-50.0,(Nutrition(100.0)-Nutrition(150.0)).kcal,0.001)}
}
