package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class EnergyCalculatorsTest {
    private val date=LocalDate.of(2026,9,20).toEpochDay()
    private val birth=LocalDate.of(2005,5,20).toEpochDay()
    private val body=Body(72.4,15.2,58.1)
    @Test fun maleRevisedHarrisBenedict() {assertEquals(1778.9128,BmrCalculator.calculate(72.4,175.0,Sex.MALE,21),1e-8)}
    @Test fun femaleRevisedHarrisBenedict() {assertEquals(1568.2958,BmrCalculator.calculate(72.4,175.0,Sex.FEMALE,21),1e-8)}
    @Test fun ageUsesHistoricalDateAndChangesOnBirthday() {
        assertEquals(20,AgeCalculator.age(birth,LocalDate.of(2026,5,19).toEpochDay()))
        assertEquals(21,AgeCalculator.age(birth,LocalDate.of(2026,5,20).toEpochDay()))
        assertEquals(19,AgeCalculator.age(birth,LocalDate.of(2024,5,20).toEpochDay()))
    }
    @Test fun leapDayBirthdayUsesCompletedCalendarYears() {
        val b=LocalDate.of(2004,2,29).toEpochDay()
        assertEquals(20,AgeCalculator.age(b,LocalDate.of(2025,2,28).toEpochDay()))
        assertEquals(21,AgeCalculator.age(b,LocalDate.of(2025,3,1).toEpochDay()))
    }
    @Test fun allFiveActivityLevels() {
        val expected=listOf(2040.0,2337.5,2635.0,2932.5,3230.0)
        ActivityLevel.entries.forEachIndexed {i,a->assertEquals(expected[i],EnergyExpenditureCalculator.calculate(1700.0,a.factor),1e-8)}
    }
    @Test fun snapshotsRetainOriginalProfileAndDailyAge() {
        val original=BodyProfile(Sex.MALE,175.0,birth,1.55)
        val snapshot=EnergyExpenditureCalculator.snapshot(body.weight,original,date)
        val later=EnergyExpenditureCalculator.snapshot(body.weight,original.copy(heightCm=180.0,activityFactor=1.9),date+1)
        assertEquals(21,snapshot.age);assertEquals(1778.9128,snapshot.bmrKcal,1e-8)
        assertEquals(2757.31484,snapshot.expenditureKcal,1e-8);assertNotEquals(snapshot.expenditureKcal,later.expenditureKcal)
        snapshot.validate(body.weight,date)
    }
    @Test fun ratio532AllocatesEnergyNotGrams() {
        val n=MacroRatioCalculator.calculate(2000.0,MacroRatio())
        assertEquals(250.0,n.carbs,1e-9);assertEquals(150.0,n.protein,1e-9);assertEquals(400.0/9,n.fat,1e-9)
        assertEquals(2000.0,n.carbs*4+n.protein*4+n.fat*9,1e-9)
    }
    @Test fun decimalRatiosAndZeroComponentWork() {
        val n=MacroRatioCalculator.calculate(2000.0,MacroRatio(4.5,3.5,2.0))
        assertEquals(225.0,n.carbs,1e-9);assertEquals(175.0,n.protein,1e-9)
        assertEquals(0.0,MacroRatioCalculator.calculate(2000.0,MacroRatio(0.0,1.0,1.0)).carbs,0.0)
    }
    @Test fun largeRatiosDoNotOverflowNormalization() {
        val n=MacroRatioCalculator.calculate(2000.0,MacroRatio(1e308,1e308,1e308))
        assertTrue(n.values().all {it.isFinite()});assertEquals(2000.0,n.carbs*4+n.protein*4+n.fat*9,1e-8)
    }
    @Test fun fixedRatioTargetDoesNotRequireExpenditure() {
        val n=TargetCalculator.calculate(Plan(mode=PlanMode.RATIO,fixedIntakeKcal=2100.0),body,date)
        assertEquals(2100.0,n.kcal,0.0);assertEquals(262.5,n.carbs,1e-8)
    }
    @Test fun deficitMaintenanceAndSurplus() {
        val base=Plan(mode=PlanMode.RATIO,intakeMode=IntakeMode.EXPENDITURE_ADJUSTMENT)
        listOf(-500.0,0.0,300.0).forEach {a->assertEquals(2635+a,TargetCalculator.calculate(base.copy(energyAdjustment=a),body,date,estimatedExpenditure=2635.0).kcal,1e-8)}
    }
    @Test fun rejectsInvalidRatios() {
        listOf(MacroRatio(0.0,0.0,0.0),MacroRatio(-1.0,2.0,3.0),MacroRatio(Double.NaN,1.0,1.0),MacroRatio(1.0,Double.POSITIVE_INFINITY,1.0)).forEach {
            assertTrue(runCatching {MacroRatioCalculator.calculate(2000.0,it)}.isFailure)
        }
    }
    @Test fun excessiveDeficitAndMissingSnapshotAreRejected() {
        val plan=Plan(mode=PlanMode.RATIO,intakeMode=IntakeMode.EXPENDITURE_ADJUSTMENT,energyAdjustment=-2700.0)
        assertTrue(runCatching {TargetCalculator.calculate(plan,body,date,estimatedExpenditure=2635.0)}.isFailure)
        assertTrue(runCatching {TargetCalculator.calculate(plan,body,date)}.isFailure)
    }
    @Test fun invalidProfileAndFutureBirthdayAreRejected() {
        assertTrue(runCatching {BodyProfile(Sex.MALE,Double.NaN,birth,1.55).validate(date)}.isFailure)
        assertTrue(runCatching {BodyProfile(Sex.MALE,175.0,date+1,1.55).validate(date)}.isFailure)
        assertTrue(runCatching {EnergyExpenditureCalculator.calculate(1700.0,Double.POSITIVE_INFINITY)}.isFailure)
    }
    @Test fun kjDisplayAndInputDoNotChangeRatios() {
        val n=TargetCalculator.calculate(Plan(mode=PlanMode.RATIO,fixedIntakeKcal=EnergyUnit.KJ.toKcal(8368.0)),body,date)
        assertEquals(2000.0,n.kcal,1e-8);assertEquals(8368.0,EnergyUnit.KJ.fromKcal(n.kcal),1e-8)
    }
}
