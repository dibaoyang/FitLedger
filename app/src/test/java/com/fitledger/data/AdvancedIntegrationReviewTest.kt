package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdvancedIntegrationReviewTest {
    private val day = LocalDate.of(2026, 10, 14).toEpochDay()
    private val profile = BodyProfile(Sex.MALE, 175.0, LocalDate.of(2000, 1, 1).toEpochDay(), 1.55)
    private fun body(date: Long, weight: Double, energy: Boolean = false) = BodyRecord(date, weight, 20.0, weight * 0.8, 40.0,
        if (energy) EnergyExpenditureCalculator.snapshot(weight, profile, date) else null)

    @Test fun averageUsesAvailableLastSevenDatesAndReplacesTodaysRow() {
        val today = body(day, 60.0)
        val rows = listOf(body(day - 8, 100.0), body(day - 6, 56.0), body(day, 90.0), body(day + 1, 80.0))
        val advanced = AdvancedPlan(templates = listOf(PlanTemplate(budget = EnergyBudget(BudgetMode.WEIGHT, 30.0))),
            reference = BodyReference(ReferenceMode.AVERAGE_7_DAYS))
        val plan = Plan(advanced = advanced)
        val target = TargetPlanning.create(plan, today, rows, emptyList(), emptyList())
        val context = requireNotNull(TargetPlanning.readContext(target))
        assertEquals(58.0, requireNotNull(context.averageWeight), 1e-9)
        assertEquals(46.4, requireNotNull(context.averageLean), 1e-9)
        assertEquals(1740.0, target.nutrition.kcal, 1e-9)
        TargetPlanning.validateSnapshot(target, today)
    }

    @Test fun exerciseRefreshKeepsFrozenBodyReferenceAndExcludesOtherDatesOrUncountedRecords() {
        val today = body(day, 60.0)
        val plan = Plan(advanced = AdvancedPlan(
            templates = listOf(PlanTemplate(budget = EnergyBudget(BudgetMode.WEIGHT, 30.0))),
            reference = BodyReference(ReferenceMode.AVERAGE_7_DAYS), exercise = ExerciseAllocation(0.5, 150.0, Macro.CARBS)))
        val exercises = listOf(
            Exercise(1, day, "MANUAL", null, null, null, 100.0, true),
            Exercise(2, day, "MANUAL", null, null, null, 900.0, false),
            Exercise(3, day - 1, "MANUAL", null, null, null, 800.0, true))
        val target = TargetPlanning.create(plan, today, listOf(body(day - 1, 56.0)), emptyList(), exercises)
        val original = requireNotNull(TargetPlanning.readContext(target))
        assertEquals(100.0, original.extraExerciseKcal, 0.0)
        assertEquals(1790.0, target.nutrition.kcal, 1e-9)
        val refreshed = TargetPlanning.create(plan, today, listOf(body(day - 1, 100.0)), listOf(target), emptyList(),
            frozenContext = original.copy(extraExerciseKcal = 400.0))
        val resultContext = requireNotNull(TargetPlanning.readContext(refreshed))
        assertEquals(original.averageWeight, resultContext.averageWeight)
        assertEquals(original.averageLean, resultContext.averageLean)
        assertEquals(1890.0, refreshed.nutrition.kcal, 1e-9)
        assertEquals(target.nutrition.protein, refreshed.nutrition.protein, 1e-9)
        assertEquals(target.nutrition.fat, refreshed.nutrition.fat, 1e-9)
        TargetPlanning.validateSnapshot(refreshed, today)
    }

    @Test fun weeklyBaselinePersistsWithinWeekAndResetsNextMonday() {
        val monday = day - 2
        val first = body(monday, 60.0, true)
        val today = body(day, 65.0, true)
        val plan = Plan(advanced = AdvancedPlan(weekly = WeeklyBudget(WeeklyBudgetMode.DEFICIT, 3500.0)))
        val start = TargetPlanning.create(plan, first, emptyList(), emptyList(), emptyList())
        val target = TargetPlanning.create(plan, today, listOf(first), listOf(start), emptyList())
        val baseline = first.energy!!.expenditureKcal
        assertEquals(baseline, requireNotNull(TargetPlanning.readContext(target)?.weekBaselineExpenditure), 0.0)
        assertEquals(baseline - 500.0, target.nutrition.kcal, 1e-9)
        assertEquals(today.energy!!.expenditureKcal, requireNotNull(TargetPlanning.baseExpenditure(target, today)), 0.0)
        TargetPlanning.validateSnapshot(target, today)
        val nextBody = body(monday + 7, 62.0, true)
        val next = TargetPlanning.create(plan, nextBody, listOf(first, today), listOf(start, target), emptyList())
        assertEquals(nextBody.energy!!.expenditureKcal, requireNotNull(TargetPlanning.readContext(next)?.weekBaselineExpenditure), 0.0)
    }

    @Test fun manualExpenditureSupportsLegacyBodyWithoutInventingBmrSnapshot() {
        val today = body(day, 60.0)
        val plan = Plan(advanced = AdvancedPlan(templates = listOf(PlanTemplate(
            budget = EnergyBudget(BudgetMode.EXPENDITURE_OFFSET, -500.0, ExpenditureSource.MANUAL, 2500.0)))))
        val target = TargetPlanning.create(plan, today, emptyList(), emptyList(), emptyList())
        assertEquals(2000.0, target.nutrition.kcal, 1e-9)
        assertEquals(2500.0, requireNotNull(TargetPlanning.baseExpenditure(target, today)), 0.0)
        val backup = Backup(bodies = listOf(today), targets = listOf(target), foods = emptyList(), intakes = emptyList(), preferences = Preferences(plan = plan))
        val restored = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, restored)
        assertNull(restored.bodies.single().energy)
    }

    @Test fun backupRejectsAdvancedTargetThatDisagreesWithItsSavedCalculationInputs() {
        val today = body(day, 60.0, true)
        val plan = Plan(advanced = AdvancedPlan())
        val original = TargetPlanning.create(plan, today, emptyList(), emptyList(), emptyList())
        val altered = original.copy(nutrition = original.nutrition.copy(kcal = original.nutrition.kcal + 100.0))
        val backup = Backup(bodies = listOf(today), targets = listOf(altered), foods = emptyList(), intakes = emptyList(), preferences = Preferences(plan = plan))
        assertTrue(runCatching { backup.validate() }.isFailure)
    }

    @Test fun contextRejectsTruncatedNumbersCoercedTypesAndMissingNullableValues() {
        val today = body(day, 60.0, true)
        val target = TargetPlanning.create(Plan(advanced = AdvancedPlan()), today, emptyList(), emptyList(), emptyList())
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 1.5) }, { it.put("day", day + 0.5) }, { it.put("extraExerciseKcal", "0") },
            { it.put("trainingDay", "false") }, { it.put("templateOverrideId", 42) }, { it.remove("averageLean") })
        mutations.forEach { mutate ->
            val json = JSONObject(target.planSnapshot); mutate(json.getJSONObject("calculationContext"))
            assertTrue(runCatching { TargetPlanning.validateSnapshot(target.copy(planSnapshot = json.toString()), today) }.isFailure)
        }
    }
}
