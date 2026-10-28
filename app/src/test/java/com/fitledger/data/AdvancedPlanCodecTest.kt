package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDate

class AdvancedPlanCodecTest {
    private val day = LocalDate.of(2026, 10, 14).toEpochDay()
    private val body = BodyRecord(day, 60.0, 20.0, 48.0, 40.0)
    private val advanced = AdvancedPlan(templates = listOf(PlanTemplate(budget = EnergyBudget(BudgetMode.FIXED, 2000.0))))
    private val plan = Plan(advanced = advanced)

    private fun backup(preferences: Preferences = Preferences(plan = plan), advancedTarget: Boolean = false): Backup = Backup(
        bodies = listOf(body),
        targets = listOf(if (advancedTarget) TargetPlanning.create(plan, body, listOf(body), emptyList(), emptyList())
            else DailyTarget(day, Nutrition(2000.0, 250.0, 120.0, 57.777777), PlanCodec.encode(Plan()))),
        foods = listOf(Food(7, "土豆泥", "g", 100.0, Nutrition(100.0, 20.0, 4.0, 0.4), 10.0, 500.0, true)),
        intakes = listOf(Intake(9, day, 7, "历史食品名称", "g", 60.0, Nutrition(60.0, 12.0, 2.4, 0.24))),
        preferences = preferences
    )

    @Test fun legacyPlanStaysLegacyWithoutInventingAdvancedDefaults() {
        val original = Plan(mode = PlanMode.CYCLE, carbs = Rule(Basis.WEIGHT, 3.0), protein = Rule(Basis.LEAN, 2.0), fat = Rule(Basis.REMAINDER))
        val encoded = PlanCodec.encode(original)
        assertFalse(JSONObject(encoded).has("advanced"))
        assertEquals(original, PlanCodec.decode(encoded))
        assertNull(PlanCodec.decode(encoded).advanced)
    }

    @Test fun fullAdvancedConfigurationRoundTripsEveryField() {
        val high = PlanTemplate(
            id = "high", name = "训练日高碳", budget = EnergyBudget(BudgetMode.EXPENDITURE_PERCENT, -15.0, ExpenditureSource.MANUAL, 2800.0),
            macros = MacroAllocation(
                mode = AllocationMode.RULES,
                carbs = MacroSpec(Rule(Basis.WEIGHT, 3.5), 50.0, 500.0),
                protein = MacroSpec(Rule(Basis.LEAN, 2.0), 40.0, 300.0),
                fat = MacroSpec(Rule(Basis.REMAINDER, 1.0), 20.0, 200.0),
                autoMacro = Macro.CARBS, adjustmentOrder = listOf(Macro.FAT, Macro.CARBS)
            ),
            carbSchedule = CarbSchedule(CarbScheduleMode.TAPER, listOf(0.8, 1.1, 1.2, 0.9, 1.0, 1.3, 1.4), day - 10, 5, 12.5, 80.0)
        )
        val value = plan.copy(advanced = advanced.copy(
            templates = advanced.templates + high,
            schedule = TemplateSchedule(ScheduleMode.STAGES, day - 10, "base", listOf("base", "high", "base", "high", "base", "high", "base"),
                listOf("high", "base", "high"), "high", "base", listOf(PlanStage(0, "base"), PlanStage(14, "high")), true),
            reference = BodyReference(ReferenceMode.FIXED, 65.5, 51.2),
            weekly = WeeklyBudget(WeeklyBudgetMode.DEFICIT, 3500.0, listOf(1.0, 1.2, 0.8, 1.3, 0.9, 1.0, 0.8)),
            exercise = ExerciseAllocation(0.5, 400.0, Macro.CARBS)
        ))
        val encoded = PlanCodec.encode(value)
        assertEquals(1, JSONObject(encoded).getJSONObject("advanced").getInt("version"))
        assertEquals(value, PlanCodec.decode(encoded))
    }

    @Test fun allSupportedAllocationModesPreserveTheirConfiguration() {
        for (mode in AllocationMode.entries) {
            val macros = if (mode == AllocationMode.RULES) MacroAllocation(mode,
                MacroSpec(Rule(Basis.FIXED, 250.0)), MacroSpec(Rule(Basis.FIXED, 150.0)), MacroSpec(Rule(Basis.REMAINDER, 1.0)))
                else MacroAllocation(mode = mode)
            val value = plan.copy(advanced = advanced.copy(templates = listOf(advanced.templates.single().copy(macros = macros))))
            assertEquals(value, PlanCodec.decode(PlanCodec.encode(value)))
        }
    }

    @Test fun rejectsMissingNullAndUnsupportedAdvancedVersions() {
        for (version in listOf(null, JSONObject.NULL, 0, 2, 1.5, "1", true)) {
            val json = JSONObject(PlanCodec.encode(plan))
            val config = json.getJSONObject("advanced")
            if (version == null) config.remove("version") else config.put("version", version)
            assertTrue("version=$version", runCatching { PlanCodec.decode(json.toString()) }.isFailure)
        }
        val json = JSONObject(PlanCodec.encode(plan)).put("advanced", JSONObject.NULL)
        assertTrue(runCatching { PlanCodec.decode(json.toString()) }.isFailure)
    }

    @Test fun rejectsCoercedNumbersFractionalIntegersBooleansAndMissingFields() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("exercise").put("fraction", "0.5") },
            { it.getJSONObject("exercise").put("fraction", "NaN") },
            { it.getJSONObject("schedule").put("start", day + 0.5) },
            { it.getJSONObject("schedule").put("trainingDay", "false") },
            { it.getJSONObject("reference").put("weight", JSONObject.NULL) },
            { it.getJSONObject("weekly").put("mode", "FUTURE_MODE") },
            { it.getJSONObject("exercise").remove("maximumKcal") },
            { it.getJSONArray("templates").getJSONObject(0).getJSONObject("carbSchedule").put("intervalDays", 1.5) }
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JSONObject(PlanCodec.encode(plan)); mutate(json.getJSONObject("advanced"))
            assertTrue("mutation=$index", runCatching { PlanCodec.decode(json.toString()) }.isFailure)
        }
    }

    @Test fun rejectsOversizedCollectionsAndInvalidPlansBeforeWriting() {
        val json = JSONObject(PlanCodec.encode(plan))
        json.getJSONObject("advanced").getJSONObject("schedule").put("cycleTemplates", JSONArray(List(367) { "base" }))
        assertTrue(runCatching { PlanCodec.decode(json.toString()) }.isFailure)
        val invalid = plan.copy(advanced = advanced.copy(exercise = ExerciseAllocation(Double.NaN)))
        assertTrue(runCatching { PlanCodec.encode(invalid) }.isFailure)
        assertTrue(runCatching { PlanCodec.encode(plan.copy(advanced = advanced.copy(templates = emptyList()))) }.isFailure)
    }

    @Test fun backupEightPreservesCurrentPendingPlansAndHistoricalContext() {
        val preferences = Preferences(plan = plan, nextPlan = plan.copy(goal = "增肌"), effectiveFrom = day + 1,
            energyUnit = EnergyUnit.KJ, weightInputUnit = WeightUnit.JIN, visibility = BodyVisibility.all(false), reminder = true, hour = 6)
        val original = backup(preferences, advancedTarget = true).copy(backupVersion=8)
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(8, restored.backupVersion)
        assertEquals(original, restored)
        assertNotNull(TargetPlanning.readContext(restored.targets.single()))
        assertEquals(original.intakes, restored.intakes)
        assertEquals(original.bodies, restored.bodies)
    }

    @Test fun everyOldBackupVersionRetainsLegacyPlansAndData() {
        val original = backup(Preferences(plan = Plan(), nextPlan = Plan(goal = "减脂"), effectiveFrom = day + 1))
        for (version in 1..7) {
            val old = original.copy(backupVersion = version)
            val restored = BackupCodec.decode(BackupCodec.encode(old))
            assertEquals(old, restored)
            assertNull(restored.preferences.plan.advanced)
            assertNull(restored.preferences.nextPlan!!.advanced)
            assertNull(PlanCodec.decode(restored.targets.single().planSnapshot).advanced)
        }
    }

    @Test fun advancedCurrentPendingAndHistoricalPlansCannotBeDowngraded() {
        val variants = listOf(backup(), backup(Preferences(nextPlan = plan, effectiveFrom = day + 1)), backup(Preferences(), advancedTarget = true))
        for (original in variants) {
            assertTrue(runCatching { BackupCodec.encode(original.copy(backupVersion = 7)) }.isFailure)
            val encoded = JSONObject(BackupCodec.encode(original)).put("backupVersion", 7)
            assertTrue(runCatching { BackupCodec.decode(encoded.toString()) }.isFailure)
        }
    }

    @Test fun rejectsFractionalStringAndFutureBackupVersionMetadata() {
        for (value in listOf(8.5, "8", Int.MAX_VALUE, 0, -1, JSONObject.NULL)) {
            val root = JSONObject(BackupCodec.encode(backup())).put("backupVersion", value)
            assertTrue(runCatching { BackupCodec.decode(root.toString()) }.isFailure)
        }
    }

    @Test fun invalidAdvancedContextIsRejectedBeforeRestore() {
        val original = backup(advancedTarget = true)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("calculationContext") },
            { it.getJSONObject("calculationContext").put("version", 99) },
            { it.getJSONObject("calculationContext").put("day", day - 1) },
            { it.getJSONObject("calculationContext").put("extraExerciseKcal", -1) }
        )
        mutations.forEach { mutate ->
            val root = JSONObject(BackupCodec.encode(original))
            editPayload(root) { payload ->
                val row = payload.getJSONArray("targets").getJSONObject(0)
                val snapshot = JSONObject(row.getString("planSnapshot"))
                mutate(snapshot); row.put("planSnapshot", snapshot.toString())
            }
            assertTrue(runCatching { BackupCodec.decode(root.toString()) }.isFailure)
        }
    }

    @Test fun advancedSettingsPersistAcrossReopenWithoutClearingOtherPreferences() = runBlocking {
        val root = Files.createDirectories(Paths.get("build", "tmp", "advanced-preferences"))
        val file = Files.createTempDirectory(root, "settings-").resolve("settings.preferences_pb").toFile()
        val futureKey = stringPreferencesKey("unrelated_future_setting")
        val initial = Preferences(energyUnit = EnergyUnit.KJ, weightInputUnit = WeightUnit.JIN,
            visibility = BodyVisibility.all(false), reminder = true, hour = 7, minute = 15)
        try {
            session(file) { store ->
                store.edit { it[futureKey] = "retain" }
                Settings(store).restore(initial)
                Settings(store).savePlan(plan, day)
            }
            session(file) { store ->
                val settings = Settings(store)
                assertEquals(initial.copy(nextPlan = plan, effectiveFrom = day + 1), settings.flow.first())
                assertEquals("retain", store.data.first()[futureKey])
                assertEquals(initial.plan, settings.flow.first().planFor(day))
                assertEquals(plan, settings.flow.first().planFor(day + 1))
                val restored = BackupCodec.decode(BackupCodec.encode(backup(settings.flow.first()))).preferences
                settings.restore(restored)
            }
            session(file) { store ->
                assertEquals(initial.copy(nextPlan = plan, effectiveFrom = day + 1), Settings(store).flow.first())
                assertEquals("retain", store.data.first()[futureKey])
            }
        } finally { file.delete(); file.parentFile.delete() }
    }

    private fun editPayload(root: JSONObject, edit: (JSONObject) -> Unit) {
        val data = JSONObject(root.getString("payload")); edit(data)
        val payload = data.toString()
        root.put("payload", payload).put("sha256", MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private suspend fun session(file: File, block: suspend (DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(migrations = listOf(VisibilityMigration), scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try { block(store) } finally { job.cancelAndJoin() }
    }
}
