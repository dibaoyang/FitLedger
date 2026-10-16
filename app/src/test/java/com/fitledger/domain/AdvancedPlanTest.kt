package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdvancedPlanTest {
    private val day=LocalDate.of(2026,10,5).toEpochDay()
    private val body=Body(70.0,20.0,45.0)
    private fun calculate(advanced:AdvancedPlan=AdvancedPlan(), date:Long=day, expenditure:Double?=2500.0, context:AdvancedContext=AdvancedContext(), savedLean:Double=body.lean)=TargetCalculator.calculate(Plan(advanced=advanced),body,date,savedLean,expenditure,context)
    private fun withTemplate(template:PlanTemplate)=AdvancedPlan(templates=listOf(template))
    private fun spec(basis:Basis,value:Double,min:Double=0.0,max:Double?=null)=MacroSpec(Rule(basis,value),min,max)
    private fun rules(c:MacroSpec,p:MacroSpec,f:MacroSpec,order:List<Macro> = emptyList())=MacroAllocation(AllocationMode.RULES,c,p,f,adjustmentOrder=order)
    private fun fixedBudget(macros:MacroAllocation)=PlanTemplate(budget=EnergyBudget(BudgetMode.FIXED,2000.0),macros=macros)
    private fun rejects(block:()->Unit)=assertTrue(runCatching(block).isFailure)

    @Test fun deficitUsesIntakeCaloriePercentages() {
        val n=calculate()
        assertEquals(2000.0,n.kcal,1e-7);assertEquals(250.0,n.carbs,1e-7);assertEquals(150.0,n.protein,1e-7);assertEquals(400.0/9,n.fat,1e-7)
    }

    @Test fun allBudgetModesHaveConsistentEnergy() {
        val cases=listOf(EnergyBudget(BudgetMode.FIXED,1800.0) to 1800.0,EnergyBudget(BudgetMode.EXPENDITURE_OFFSET,300.0) to 2800.0,EnergyBudget(BudgetMode.EXPENDITURE_OFFSET,0.0) to 2500.0,EnergyBudget(BudgetMode.EXPENDITURE_PERCENT,-20.0) to 2000.0,EnergyBudget(BudgetMode.EXPENDITURE_PERCENT,10.0) to 2750.0,EnergyBudget(BudgetMode.WEIGHT,30.0) to 2100.0,EnergyBudget(BudgetMode.LEAN,30.0) to 1680.0)
        cases.forEach {(budget,expected)->assertEquals(expected,calculate(withTemplate(PlanTemplate(budget=budget))).kcal,1e-7)}
        assertEquals(2200.0,calculate(withTemplate(PlanTemplate(budget=EnergyBudget(value=-500.0,expenditureSource=ExpenditureSource.MANUAL,manualExpenditure=2700.0))),expenditure=null).kcal,1e-7)
    }

    @Test fun savedLeanAndReferenceSelectionAreUsedWithoutChangingBody() {
        val a=withTemplate(fixedBudget(rules(spec(Basis.WEIGHT,3.0),spec(Basis.LEAN,2.0),spec(Basis.REMAINDER,1.0))))
        val today=calculate(a,savedLean=55.5)
        assertEquals(210.0,today.carbs,0.0);assertEquals(111.0,today.protein,0.0)
        val fixed=calculate(a.copy(reference=BodyReference(ReferenceMode.FIXED,60.0,48.0)))
        assertEquals(180.0,fixed.carbs,0.0);assertEquals(96.0,fixed.protein,0.0)
        val avg=calculate(a.copy(reference=BodyReference(ReferenceMode.AVERAGE_7_DAYS)),context=AdvancedContext(averageWeight=68.0,averageLean=54.0))
        assertEquals(204.0,avg.carbs,0.0);assertEquals(108.0,avg.protein,0.0)
        assertEquals(70.0,body.weight,0.0)
    }

    @Test fun allThreeChoicesOfRemainingNutrientWork() {
        val configs=listOf(rules(spec(Basis.REMAINDER,1.0),spec(Basis.FIXED,150.0),spec(Basis.FIXED,40.0)),rules(spec(Basis.FIXED,250.0),spec(Basis.REMAINDER,1.0),spec(Basis.FIXED,40.0)),rules(spec(Basis.FIXED,250.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,1.0)))
        configs.forEach {assertEquals(2000.0,calculate(withTemplate(fixedBudget(it))).kcal,1e-7)}
    }

    @Test fun fixedProteinAndTwoRemaindersSplitOnlyTheRemainingCalories() {
        val n=calculate(withTemplate(fixedBudget(rules(spec(Basis.REMAINDER,7.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,3.0)))))
        assertEquals(150.0,n.protein,0.0);assertEquals(1400*0.7/4,n.carbs,1e-7);assertEquals(1400*0.3/9,n.fat,1e-7)
    }

    @Test fun mixedPercentAndWeightRulesUseTheTotalBudget() {
        val n=calculate(withTemplate(fixedBudget(rules(spec(Basis.REMAINDER,1.0),spec(Basis.WEIGHT,2.0),spec(Basis.PERCENT,25.0)))))
        assertEquals(140.0,n.protein,0.0);assertEquals(500.0/9,n.fat,1e-7);assertEquals((2000-560-500)/4.0,n.carbs,1e-7)
    }

    @Test fun weightsStrictPercentAndAutomaticThirdAreDifferentModes() {
        val allocation=MacroAllocation(mode=AllocationMode.RATIO,carbs=spec(Basis.PERCENT,50.0),protein=spec(Basis.PERCENT,30.0),fat=spec(Basis.PERCENT,30.0))
        assertEquals(2000*50.0/110/4,calculate(withTemplate(fixedBudget(allocation))).carbs,1e-7)
        rejects {calculate(withTemplate(fixedBudget(allocation.copy(mode=AllocationMode.STRICT_PERCENT))))}
        val auto=calculate(withTemplate(fixedBudget(allocation.copy(mode=AllocationMode.AUTO_PERCENT,autoMacro=Macro.FAT))))
        assertEquals(400.0/9,auto.fat,1e-7)
        val zero=allocation.copy(mode=AllocationMode.AUTO_PERCENT,protein=spec(Basis.PERCENT,50.0))
        assertEquals(0.0,calculate(withTemplate(fixedBudget(zero))).fat,1e-7)
    }

    @Test fun simultaneousLowerAndUpperBoundsFindAFeasibleAllocation() {
        // Initial 90/10 kcal shares violate both bounds; fixing both at once would wrongly leave 10 kcal unallocated.
        val allocation=rules(spec(Basis.REMAINDER,9.0,max=12.5),spec(Basis.REMAINDER,1.0,min=10.0),spec(Basis.FIXED,0.0))
        val n=calculate(withTemplate(fixedBudget(allocation).copy(budget=EnergyBudget(BudgetMode.FIXED,100.0))))
        assertEquals(100.0,n.kcal,1e-7);assertEquals(12.5,n.carbs,1e-7);assertEquals(12.5,n.protein,1e-7)
    }

    @Test fun remainderBoundsRedistributeEnergyWithoutChangingLockedProtein() {
        val n=calculate(withTemplate(fixedBudget(rules(spec(Basis.REMAINDER,7.0,max=200.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,3.0)))))
        assertEquals(200.0,n.carbs,1e-7);assertEquals(150.0,n.protein,0.0);assertEquals(600.0/9,n.fat,1e-7)
    }

    @Test fun explicitPriorityChangesOnlyAllowedMacrosAndStopsAtBounds() {
        val locked=rules(spec(Basis.FIXED,300.0,min=250.0),spec(Basis.FIXED,200.0,min=100.0),spec(Basis.REMAINDER,1.0,min=50.0))
        rejects {calculate(withTemplate(fixedBudget(locked)))}
        val n=calculate(withTemplate(fixedBudget(locked.copy(adjustmentOrder=listOf(Macro.CARBS,Macro.PROTEIN)))))
        assertEquals(250.0,n.carbs,1e-7);assertEquals(137.5,n.protein,1e-7);assertEquals(50.0,n.fat,1e-7)
    }

    @Test fun threeFixedMacrosDeriveTotalOrRequireExplicitAdjustment() {
        val allocation=rules(spec(Basis.FIXED,250.0),spec(Basis.FIXED,150.0),spec(Basis.FIXED,50.0))
        rejects {calculate(withTemplate(fixedBudget(allocation)))}
        val n=calculate(withTemplate(fixedBudget(allocation).copy(consistency=EnergyConsistency.MACROS_DEFINE_TOTAL)))
        assertEquals(2050.0,n.kcal,1e-7)
        val adjusted=calculate(withTemplate(fixedBudget(allocation.copy(adjustmentOrder=listOf(Macro.FAT)))))
        assertEquals(400.0/9,adjusted.fat,1e-7)
    }

    @Test fun lowCarbScheduleRunsBeforeBudgetAndBoundsChecks() {
        val allocation=rules(spec(Basis.FIXED,400.0,max=250.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,1.0))
        val t=fixedBudget(allocation).copy(carbSchedule=CarbSchedule(CarbScheduleMode.WEEKLY_MULTIPLIER,List(7){0.5}))
        val n=calculate(withTemplate(t))
        assertEquals(200.0,n.carbs,1e-7);assertEquals(2000.0,n.kcal,1e-7);assertEquals(600.0/9,n.fat,1e-7)
    }

    @Test fun ratioScheduleLocksScheduledCarbsAndRescalesOthers() {
        val t=fixedBudget(MacroAllocation()).copy(carbSchedule=CarbSchedule(CarbScheduleMode.WEEKLY_MULTIPLIER,List(7){0.7}))
        val n=calculate(withTemplate(t))
        assertEquals(175.0,n.carbs,1e-7);assertEquals(195.0,n.protein,1e-7);assertEquals(520.0/9,n.fat,1e-7);assertEquals(2000.0,n.kcal,1e-7)
    }

    @Test fun scheduledRemainingCarbsCanReduceExplicitlyAdjustableProtein() {
        val allocation=rules(spec(Basis.REMAINDER,1.0,min=100.0),spec(Basis.FIXED,600.0,min=300.0),spec(Basis.FIXED,0.0),listOf(Macro.PROTEIN))
        val schedule=CarbSchedule(CarbScheduleMode.WEEKLY_MULTIPLIER,List(7){0.7})
        val template=fixedBudget(allocation).copy(carbSchedule=schedule)
        val n=calculate(withTemplate(template))
        assertEquals(100.0,n.carbs,1e-7);assertEquals(400.0,n.protein,1e-7);assertEquals(2000.0,n.kcal,1e-7)
        rejects {calculate(withTemplate(template.copy(macros=allocation.copy(adjustmentOrder=emptyList()))))}
        val taper=template.copy(carbSchedule=CarbSchedule(mode=CarbScheduleMode.TAPER,start=day,minimumGrams=150.0))
        val tapered=calculate(withTemplate(taper))
        assertEquals(150.0,tapered.carbs,1e-7);assertEquals(350.0,tapered.protein,1e-7)
    }

    @Test fun flexibleScheduledCarbBoundsRedistributeButContradictoryTaperBoundsFail() {
        val allocation=MacroAllocation(carbs=spec(Basis.PERCENT,50.0,min=200.0))
        val template=fixedBudget(allocation).copy(carbSchedule=CarbSchedule(CarbScheduleMode.WEEKLY_MULTIPLIER,List(7){0.7}))
        val n=calculate(withTemplate(template))
        assertEquals(200.0,n.carbs,1e-7);assertEquals(180.0,n.protein,1e-7);assertEquals(2000.0,n.kcal,1e-7)
        val impossible=template.copy(macros=allocation.copy(carbs=spec(Basis.PERCENT,50.0,max=80.0)),carbSchedule=CarbSchedule(mode=CarbScheduleMode.TAPER,start=day,minimumGrams=100.0))
        rejects {calculate(withTemplate(impossible))}
    }

    @Test fun taperCanKeepBudgetOrLowerMacroDerivedTotal() {
        val a=rules(spec(Basis.FIXED,250.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,1.0))
        val taper=CarbSchedule(mode=CarbScheduleMode.TAPER,start=day,intervalDays=7,decrementGrams=20.0,minimumGrams=100.0)
        val t=fixedBudget(a).copy(carbSchedule=taper)
        assertEquals(2000.0,calculate(withTemplate(t),date=day+7).kcal,1e-7)
        val derived=t.copy(consistency=EnergyConsistency.MACROS_DEFINE_TOTAL)
        val n=calculate(withTemplate(derived),date=day+7)
        assertEquals(230.0,n.carbs,1e-7);assertEquals(1920.0,n.kcal,1e-7)
        assertEquals(100.0,calculate(withTemplate(t),date=day+700).carbs,1e-7)
    }

    @Test fun allSchedulesResolveCompleteTemplatesAtTheirBoundaries() {
        val base=fixedBudget(MacroAllocation())
        val high=base.copy(id="high",name="高碳",budget=EnergyBudget(BudgetMode.FIXED,2400.0))
        val a=AdvancedPlan(templates=listOf(base,high))
        fun selected(schedule:TemplateSchedule,date:Long,context:AdvancedContext=AdvancedContext())=calculate(a.copy(schedule=schedule),date,context=context).kcal
        assertEquals(2400.0,selected(TemplateSchedule(mode=ScheduleMode.WEEKLY,weeklyTemplates=listOf("high")+List(6){"base"}),day),1e-7)
        val cycle=TemplateSchedule(mode=ScheduleMode.CYCLE,start=day,cycleTemplates=listOf("base","high","base"))
        assertEquals(2400.0,selected(cycle,day+4),1e-7);assertEquals(2000.0,selected(cycle,day+3),1e-7)
        val training=TemplateSchedule(mode=ScheduleMode.TRAINING_REST,trainingTemplate="high",trainingDay=true)
        assertEquals(2400.0,selected(training,day),1e-7);assertEquals(2000.0,selected(training,day,AdvancedContext(trainingDay=false)),1e-7)
        val stages=TemplateSchedule(mode=ScheduleMode.STAGES,start=day,stages=listOf(PlanStage(0),PlanStage(14,"high")))
        assertEquals(2000.0,selected(stages,day+13),1e-7);assertEquals(2400.0,selected(stages,day+14),1e-7)
        assertEquals(2400.0,selected(TemplateSchedule(),day,AdvancedContext(templateOverrideId="high")),1e-7)
    }

    @Test fun weeklyIntakeWeightsSumToTheRequestedBudgetWithoutRequiringProfile() {
        val a=AdvancedPlan(weekly=WeeklyBudget(WeeklyBudgetMode.INTAKE,14000.0,listOf(1.0,1.0,1.0,1.0,1.0,2.0,3.0)))
        val days=(0..6).map {calculate(a,date=day+it,expenditure=null).kcal}
        assertEquals(14000.0,days.sum(),1e-7);assertEquals(4200.0,days[6],1e-7)
    }

    @Test fun weeklyDeficitUsesTheLockedWeekBaselineDespiteDailyExpenditureChanges() {
        val a=AdvancedPlan(weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,3500.0,List(7){1.0}))
        val context=AdvancedContext(weekBaselineExpenditure=2500.0)
        val days=(0..6).map {calculate(a,date=day+it,expenditure=2500.0-it*10,context=context).kcal}
        assertEquals(14000.0,days.sum(),1e-7);assertTrue(days.all {kotlin.math.abs(it-2000.0)<1e-7})
    }

    @Test fun exerciseCanBeIgnoredAddedFullyPartlyCappedOrGivenToOneMacro() {
        val context=AdvancedContext(extraExerciseKcal=600.0)
        assertEquals(2000.0,calculate(context=context).kcal,1e-7)
        assertEquals(2600.0,calculate(AdvancedPlan(exercise=ExerciseAllocation(1.0)),context=context).kcal,1e-7)
        assertEquals(2300.0,calculate(AdvancedPlan(exercise=ExerciseAllocation(0.5)),context=context).kcal,1e-7)
        assertEquals(2200.0,calculate(AdvancedPlan(exercise=ExerciseAllocation(0.5,200.0)),context=context).kcal,1e-7)
        val n=calculate(AdvancedPlan(exercise=ExerciseAllocation(0.5,destination=Macro.CARBS)),context=context)
        assertEquals(325.0,n.carbs,1e-7);assertEquals(150.0,n.protein,1e-7);assertEquals(400.0/9,n.fat,1e-7)
    }

    @Test fun boundsApplyToTheFinalAmountIncludingSpecifiedExercise() {
        val allocation=rules(spec(Basis.FIXED,250.0,min=200.0,max=350.0),spec(Basis.FIXED,150.0),spec(Basis.REMAINDER,1.0))
        val a=withTemplate(fixedBudget(allocation)).copy(exercise=ExerciseAllocation(1.0,destination=Macro.CARBS))
        val n=calculate(a,context=AdvancedContext(extraExerciseKcal=300.0))
        assertEquals(325.0,n.carbs,1e-7);assertEquals(150.0,n.protein,1e-7);assertEquals(2300.0,n.kcal,1e-7)
        rejects {calculate(a,context=AdvancedContext(extraExerciseKcal=500.0))}
        val invalidBase=allocation.copy(carbs=spec(Basis.FIXED,250.0,min=300.0,max=350.0))
        rejects {calculate(withTemplate(fixedBudget(invalidBase)).copy(exercise=a.exercise),context=AdvancedContext(extraExerciseKcal=300.0))}
    }

    @Test fun directedExerciseCannotMakeRoomByChangingOtherRatioNutrients() {
        val allocation=MacroAllocation(carbs=spec(Basis.PERCENT,50.0,max=260.0))
        val a=withTemplate(fixedBudget(allocation)).copy(exercise=ExerciseAllocation(1.0,destination=Macro.CARBS))
        val baseline=calculate(a)
        assertEquals(250.0,baseline.carbs,1e-7);assertEquals(150.0,baseline.protein,1e-7);assertEquals(400.0/9,baseline.fat,1e-7)
        // C would become 275 g: reject instead of lowering base C and increasing P/F to compensate.
        rejects {calculate(a,context=AdvancedContext(extraExerciseKcal=100.0))}
        val added=calculate(a,context=AdvancedContext(extraExerciseKcal=40.0))
        assertEquals(260.0,added.carbs,1e-7);assertEquals(baseline.protein,added.protein,0.0);assertEquals(baseline.fat,added.fat,0.0)
    }

    @Test fun feasibleBoundedRatiosPreserveEnergyAcrossManyConstraintCombinations() {
        // Each configuration has a known feasible allocation, but the requested ratio may
        // push multiple nutrients into different bounds at once.
        for(c in listOf(120.0,200.0,280.0)) for(p in listOf(80.0,120.0,180.0)) {
            val f=(2000-c*4-p*4)/9
            if(f<=0) continue
            val allocation=MacroAllocation(mode=AllocationMode.RATIO,
                carbs=spec(Basis.PERCENT,8.0,min=c*0.8,max=c*1.2),
                protein=spec(Basis.PERCENT,1.0,min=p*0.8,max=p*1.2),
                fat=spec(Basis.PERCENT,1.0,min=f*0.8,max=f*1.2))
            val n=calculate(withTemplate(fixedBudget(allocation)))
            assertEquals(2000.0,n.kcal,1e-7)
            assertTrue(n.carbs in c*0.8-1e-7..c*1.2+1e-7)
            assertTrue(n.protein in p*0.8-1e-7..p*1.2+1e-7)
            assertTrue(n.fat in f*0.8-1e-7..f*1.2+1e-7)
        }
    }

    @Test fun impossibleBoundsSchedulesAndBudgetsAreRejected() {
        rejects {calculate(AdvancedPlan(exercise=ExerciseAllocation(1.1)))}
        rejects {calculate(withTemplate(PlanTemplate(budget=EnergyBudget(BudgetMode.EXPENDITURE_PERCENT,-100.0))))}
        rejects {calculate(withTemplate(fixedBudget(MacroAllocation(carbs=spec(Basis.PERCENT,50.0,max=50.0),protein=spec(Basis.PERCENT,30.0,max=50.0),fat=spec(Basis.PERCENT,20.0,max=10.0)))))}
        rejects {calculate(AdvancedPlan(schedule=TemplateSchedule(cycleTemplates=listOf("missing"))))}
        rejects {calculate(AdvancedPlan(schedule=TemplateSchedule(stages=listOf(PlanStage(1)))))}
        rejects {calculate(AdvancedPlan(weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,20000.0)))}
        rejects {calculate(withTemplate(fixedBudget(MacroAllocation()).copy(consistency=EnergyConsistency.MACROS_DEFINE_TOTAL)).copy(exercise=ExerciseAllocation(1.0)))}
    }

    @Test fun optInDoesNotChangeLegacyCalculationsOrRequireLegacyUnusedFields() {
        val old=Plan(mode=PlanMode.CYCLE,energy=Rule(Basis.FIXED,2000.0),fat=Rule(Basis.REMAINDER),cycle=List(7){0.7})
        assertEquals(1700.0,TargetCalculator.calculate(old,body,day).kcal,1e-7)
        val n=TargetCalculator.calculate(old.copy(advanced=AdvancedPlan()),body,day,estimatedExpenditure=2500.0)
        assertEquals(2000.0,n.kcal,1e-7)
        assertNull(old.advanced)
    }
}
