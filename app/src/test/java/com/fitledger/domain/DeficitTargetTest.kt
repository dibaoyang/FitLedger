package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeficitTargetTest {
    private val body=Body(70.0,20.0,45.0)
    private val monday=LocalDate.of(2026,10,5).toEpochDay()
    private val plan=Plan(useExpenditureBudget=true,deficitKcal=500.0,carbs=Rule(Basis.FIXED,250.0),protein=Rule(Basis.FIXED,150.0),fat=Rule(Basis.REMAINDER))
    @Test fun fixedCarbsAndProteinAllocateFatAtRequestedDeficit() {
        val n=TargetCalculator.calculate(plan,body,monday,estimatedExpenditure=2500.0)
        assertEquals(2000.0,n.kcal,1e-8);assertEquals(250.0,n.carbs,0.0);assertEquals(150.0,n.protein,0.0);assertEquals(400.0/9,n.fat,1e-8)
    }
    @Test fun weightLeanAndPercentRulesUseActualDailyBudget() {
        val p=plan.copy(carbs=Rule(Basis.WEIGHT,3.0),protein=Rule(Basis.LEAN,2.0))
        val n=TargetCalculator.calculate(p,body,monday,savedLean=55.5,estimatedExpenditure=2500.0)
        assertEquals(210.0,n.carbs,0.0);assertEquals(111.0,n.protein,0.0);assertEquals((2000-840-444)/9.0,n.fat,1e-8)
        val percent=TargetCalculator.calculate(plan.copy(carbs=Rule(Basis.PERCENT,50.0)),body,monday,estimatedExpenditure=2500.0)
        assertEquals(250.0,percent.carbs,1e-8)
    }
    @Test fun dailyBodyChangesAlterNewTargetsButNotOldObjects() {
        val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
        val first=EnergyExpenditureCalculator.snapshot(70.0,profile,monday).expenditureKcal
        val second=EnergyExpenditureCalculator.snapshot(69.0,profile,monday+1).expenditureKcal
        val old=TargetCalculator.calculate(plan,body,monday,estimatedExpenditure=first)
        val newer=TargetCalculator.calculate(plan,body.copy(weight=69.0),monday+1,estimatedExpenditure=second)
        assertEquals(first-500,old.kcal,1e-8);assertEquals(second-500,newer.kcal,1e-8);assertNotEquals(old,newer)
    }
    @Test fun cyclesAdjustCarbsBeforeFatWithoutChangingBudget() {
        val n=TargetCalculator.calculate(plan.copy(mode=PlanMode.CYCLE,cycle=List(7){0.7}),body,monday,estimatedExpenditure=2500.0)
        assertEquals(175.0,n.carbs,1e-8);assertEquals((2000-700-600)/9.0,n.fat,1e-8);assertEquals(2000.0,n.kcal,1e-8)
    }
    @Test fun taperAndZeroRemainderAreValid() {
        val n=TargetCalculator.calculate(plan.copy(mode=PlanMode.TAPER,start=monday,taperDays=7,taperGrams=20.0),body,monday+7,estimatedExpenditure=2500.0)
        assertEquals(230.0,n.carbs,0.0);assertEquals(2000.0,n.kcal,1e-8)
        assertEquals(0.0,TargetCalculator.calculate(plan,body,monday,estimatedExpenditure=2100.0).fat,1e-8)
    }
    @Test fun rejectsImpossibleBudgetAndInvalidChoices() {
        listOf<()->Unit>(
            {TargetCalculator.calculate(plan,body,monday)},
            {TargetCalculator.calculate(plan,body,monday,estimatedExpenditure=500.0)},
            {TargetCalculator.calculate(plan,body,monday,estimatedExpenditure=1500.0)},
            {plan.copy(deficitKcal=-1.0).validateConfiguration()},
            {plan.copy(deficitKcal=Double.NaN).validateConfiguration()},
            {plan.copy(fat=Rule(Basis.FIXED,50.0)).validateConfiguration()},
            {plan.copy(mode=PlanMode.CYCLE,carbs=Rule(Basis.REMAINDER),fat=Rule(Basis.FIXED,50.0)).validateConfiguration()}
        ).forEach {assertTrue(runCatching(it).isFailure)}
    }
    @Test fun remainingCarbsAndKjDeficitWork() {
        val n=TargetCalculator.calculate(plan.copy(deficitKcal=EnergyUnit.KJ.toKcal(2092.0),carbs=Rule(Basis.REMAINDER),fat=Rule(Basis.FIXED,60.0)),body,monday,estimatedExpenditure=2500.0)
        assertEquals(215.0,n.carbs,1e-8)
    }
    @Test fun inactiveBudgetDoesNotChangeLegacyPlans() {
        val p=Plan(mode=PlanMode.CYCLE,energy=Rule(Basis.FIXED,2000.0),fat=Rule(Basis.REMAINDER),cycle=List(7){0.7})
        val old=TargetCalculator.calculate(p,body,monday)
        val n=TargetCalculator.calculate(p.copy(intakeMode=IntakeMode.EXPENDITURE_ADJUSTMENT,energyAdjustment=-500.0),body,monday,estimatedExpenditure=3000.0)
        assertEquals(old,n);assertEquals(1700.0,n.kcal,1e-8)
    }
}
