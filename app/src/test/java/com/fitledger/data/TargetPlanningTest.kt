package com.fitledger.data

import com.fitledger.domain.*
import com.fitledger.domain.Rule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TargetPlanningTest {
    private val day=LocalDate.of(2026,10,14).toEpochDay()
    private val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2000,1,1).toEpochDay(),1.55)
    private fun body(date:Long=day,weight:Double=70.0)=BodyRecord(date,weight,20.0,weight*.8,45.0,
        EnergyExpenditureCalculator.snapshot(weight,profile,date))
    private fun plan(advanced:AdvancedPlan=AdvancedPlan())=Plan(advanced=advanced)
    private fun create(p:Plan=plan(), b:BodyRecord=body(), bodies:List<BodyRecord> = emptyList(),
                       targets:List<DailyTarget> = emptyList(),exercises:List<Exercise> = emptyList())=
        TargetPlanning.create(p,b,bodies,targets,exercises)

    @Test fun legacyPlansKeepIdenticalTargetsAndHaveNoContextAdded() {
        val p=Plan(mode=PlanMode.CYCLE);val b=body()
        val target=create(p,b)
        assertEquals(TargetCalculator.calculate(p,Body(b.weight,b.fatPercent,b.muscle),day,b.lean,b.energy!!.expenditureKcal),target.nutrition)
        assertEquals(PlanCodec.encode(p),target.planSnapshot)
        assertNull(TargetPlanning.readContext(target))
    }
    @Test fun sevenDayAverageUsesOnlyPastSixDaysAndTodayOnce() {
        val p=plan(AdvancedPlan(reference=BodyReference(ReferenceMode.AVERAGE_7_DAYS)))
        val records=listOf(body(day-7,90.0),body(day-1,60.0),body(day,70.0),body(day+1,100.0))
        val target=create(p,bodies=records)
        val c=TargetPlanning.readContext(target)!!
        assertEquals(65.0,c.averageWeight!!,0.0);assertEquals(52.0,c.averageLean!!,0.0)
        TargetPlanning.validateSnapshot(target,body())
    }
    @Test fun sameWeekPlanReusesFirstExpenditureBaselineButNewWeekDoesNot() {
        val p=plan(AdvancedPlan(weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,3500.0)))
        val monday=body(day-2,68.0);val first=create(p,monday)
        val next=create(p,targets=listOf(first))
        assertEquals(monday.energy!!.expenditureKcal,TargetPlanning.readContext(next)!!.weekBaselineExpenditure!!,0.0)
        val newWeek=body(day+5,72.0)
        assertEquals(newWeek.energy!!.expenditureKcal,TargetPlanning.readContext(create(p,newWeek,targets=listOf(first,next)))!!.weekBaselineExpenditure!!,0.0)
    }
    @Test fun changedWeeklyPlanStartsItsOwnBaselineAndLeavesOldSnapshotUnchanged() {
        val p=plan(AdvancedPlan(weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,3500.0)))
        val first=create(p,body(day-2,68.0));val before=first.copy()
        val changed=p.copy(advanced=p.advanced!!.copy(weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,2800.0)))
        val next=create(changed,targets=listOf(first))
        assertEquals(body().energy!!.expenditureKcal,TargetPlanning.readContext(next)!!.weekBaselineExpenditure!!,0.0)
        assertEquals(before,first)
    }
    @Test fun choosingTrainingDayDoesNotResetTheSameWeeklyBudgetBaseline() {
        val advanced=AdvancedPlan(schedule=TemplateSchedule(mode=ScheduleMode.TRAINING_REST),weekly=WeeklyBudget(WeeklyBudgetMode.DEFICIT,3500.0))
        val first=create(plan(advanced),body(day-2,68.0))
        val today=create(plan(advanced.copy(schedule=advanced.schedule.copy(trainingDay=true))),targets=listOf(first))
        assertEquals(TargetPlanning.readContext(first)!!.weekBaselineExpenditure,TargetPlanning.readContext(today)!!.weekBaselineExpenditure)
    }
    @Test fun manualExpenditureIsDisplayedFromTargetWithoutChangingBmrSnapshot() {
        val b=body()
        val t=PlanTemplate(budget=EnergyBudget(expenditureSource=ExpenditureSource.MANUAL,manualExpenditure=2500.0))
        val target=create(plan(AdvancedPlan(templates=listOf(t))),b)
        assertEquals(2500.0,TargetPlanning.baseExpenditure(target,b)!!,0.0)
        assertEquals(2000.0,target.nutrition.kcal,1e-6)
        assertNotEquals(2500.0,b.energy!!.expenditureKcal,1e-6)
        TargetPlanning.validateSnapshot(target,b)
    }
    @Test fun onlyIncludedSameDayExerciseIsSnapshottedAndHalfReturned() {
        val p=plan(AdvancedPlan(exercise=ExerciseAllocation(.5)))
        val exercises=listOf(Exercise(1,day,"MANUAL",null,null,null,200.0,true),
            Exercise(2,day,"MANUAL",null,null,null,400.0,false),Exercise(3,day-1,"MANUAL",null,null,null,800.0,true))
        val target=create(p,exercises=exercises)
        assertEquals(200.0,TargetPlanning.readContext(target)!!.extraExerciseKcal,0.0)
        assertEquals(body().energy!!.expenditureKcal-500+100,target.nutrition.kcal,1e-6)
        TargetPlanning.validateSnapshot(target,body())
    }
    @Test fun frozenContextSurvivesDifferentHistoryDuringExerciseRecalculation() {
        val p=plan(AdvancedPlan(reference=BodyReference(ReferenceMode.AVERAGE_7_DAYS),exercise=ExerciseAllocation(1.0)))
        val initial=create(p,bodies=listOf(body(day-1,60.0)))
        val frozen=TargetPlanning.readContext(initial)!!.copy(extraExerciseKcal=300.0)
        val changed=TargetPlanning.create(p,body(),listOf(body(day-1,90.0)),listOf(initial),emptyList(),frozenContext=frozen)
        assertEquals(65.0,TargetPlanning.readContext(changed)!!.averageWeight!!,0.0)
        assertEquals(initial.nutrition.kcal+300,changed.nutrition.kcal,1e-6)
    }
    @Test fun snapshotRejectsIncorrectDayUnsupportedVersionAndMissingContext() {
        val target=create()
        for(key in listOf("version","day")) {
            val j=JSONObject(target.planSnapshot);j.getJSONObject("calculationContext").put(key,999)
            assertTrue(runCatching {TargetPlanning.validateSnapshot(target.copy(planSnapshot=j.toString()))}.isFailure)
        }
        val j=JSONObject(target.planSnapshot);j.remove("calculationContext")
        assertTrue(runCatching {TargetPlanning.validateSnapshot(target.copy(planSnapshot=j.toString()))}.isFailure)
    }
    @Test fun snapshotRejectsStringNumbersFractionalDatesAndMissingNullableFields() {
        val target=create()
        val mutations:List<(JSONObject)->Unit> = listOf(
            {it.put("extraExerciseKcal","300")},{it.put("day",day+.5)},
            {it.remove("averageLean")},{it.put("trainingDay","false")})
        mutations.forEach {mutate->
            val j=JSONObject(target.planSnapshot);mutate(j.getJSONObject("calculationContext"))
            assertTrue(runCatching {TargetPlanning.validateSnapshot(target.copy(planSnapshot=j.toString()))}.isFailure)
        }
    }
    @Test fun backupRejectsTamperedTargetButRoundTripsRealAdvancedSnapshot() {
        val target=create();val backup=Backup(bodies=listOf(body()),targets=listOf(target),foods=emptyList(),intakes=emptyList(),preferences=Preferences(plan=plan(),profile=profile))
        assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
        assertTrue(runCatching {BackupCodec.encode(backup.copy(targets=listOf(target.copy(nutrition=target.nutrition.copy(kcal=target.nutrition.kcal+100)))))}.isFailure)
    }
}
