package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDate

class WeightPreferencesTest {
    private val day = LocalDate.of(2026, 10, 13).toEpochDay()
    private val profile = BodyProfile(Sex.MALE, 175.0, LocalDate.of(2000, 1, 1).toEpochDay(), 1.55)

    private fun fixture(preferences: Preferences) = Backup(
        bodies = listOf(BodyRecord(day, 60.0, 20.0, 48.0, 40.0)),
        targets = listOf(DailyTarget(day, Nutrition(2000.0, 250.0, 120.0, 57.777777), PlanCodec.encode(Plan()))),
        foods = listOf(Food(7, "土豆泥", "g", 100.0, Nutrition(100.0, 20.0, 4.0, 0.4), 10.0, 500.0, true)),
        intakes = listOf(Intake(9, day, 7, "土豆泥", "g", 60.0, Nutrition(60.0, 12.0, 2.4, 0.24))),
        preferences = preferences
    )

    @Test fun newSettingsAndBackupsRoundTripWithoutConvertingStoredBodyWeight() {
        val preferences = Preferences(
            profile = profile, energyUnit = EnergyUnit.KJ, reminder = true, hour = 7,
            weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false
        )
        assertEquals(preferences, PreferencesCodec.decode(PreferencesCodec.encode(preferences)))
        val backup = fixture(preferences)
        val restored = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, restored)
        assertEquals(60.0, restored.bodies.single().weight, 0.0)
        assertEquals(48.0, restored.bodies.single().lean, 0.0)
        assertEquals(60.0, restored.intakes.single().quantity, 0.0)
        assertEquals(11, restored.backupVersion)
    }

    @Test fun legacySettingsAndPendingJournalDefaultToKgAndEnabled() {
        val original = Preferences(
            profile = profile, privacy = true, energyUnit = EnergyUnit.KJ, reminder = true,
            nextPlan = Plan(goal = "增肌"), effectiveFrom = day + 1
        )
        val json = JSONObject(PreferencesCodec.encode(original))
        json.remove("weightInputUnit"); json.remove("weightFoodShortcut")
        assertEquals(original, PreferencesCodec.decode(json.toString()))
    }

    @Test fun legacyVersionSixBackupRestoresNewDefaultsAndKeepsEverySnapshot() {
        val original = fixture(Preferences(profile = profile, energyUnit = EnergyUnit.KJ))
        val root = JSONObject(BackupCodec.encode(original))
        val payload = JSONObject(root.getString("payload"))
        payload.getJSONObject("preferences").apply {
            remove("weightInputUnit"); remove("weightFoodShortcut")
        }
        payload.getJSONArray("foods").getJSONObject(0).remove("isDeleted")
        val text = payload.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        root.put("payload", text); root.put("sha256", hash)
        root.put("backupVersion", 6); root.put("databaseVersion", 4)
        val restored = BackupCodec.decode(root.toString())
        assertEquals(original.copy(backupVersion = 6, databaseVersion = 4), restored)
        assertEquals(WeightUnit.KG, restored.preferences.weightInputUnit)
        assertTrue(restored.preferences.weightFoodShortcut)
    }

    @Test fun invalidWeightUnitIsRejectedBeforeAnyRestore() {
        val json = JSONObject(PreferencesCodec.encode(Preferences()))
        json.put("weightInputUnit", "UNKNOWN")
        assertTrue(runCatching { PreferencesCodec.decode(json.toString()) }.isFailure)
    }

    @Test fun existingDataStoreKeepsOldValuesAndNewChoicesSurviveReopen() = runBlocking {
        withStoreFile { file ->
            val unknownKey = stringPreferencesKey("future_preference")
            val original = Preferences(profile = profile, privacy = true, reminder = true, hour = 6, minute = 15, energyUnit = EnergyUnit.KJ)
            session(file, migrate = false) { store ->
                store.edit {
                    it[stringPreferencesKey("plan")] = PlanCodec.encode(original.plan)
                    it[booleanPreferencesKey("body_privacy")] = true
                    it[stringPreferencesKey("body_profile")] = ProfileCodec.encode(profile).toString()
                    it[stringPreferencesKey("energy_unit")] = "KJ"
                    it[booleanPreferencesKey("reminder")] = true
                    it[intPreferencesKey("hour")] = 6
                    it[intPreferencesKey("minute")] = 15
                    it[unknownKey] = "preserve me"
                }
            }
            session(file) { store ->
                val settings = Settings(store)
                assertEquals(original, settings.flow.first())
                settings.setWeightInputUnit(WeightUnit.JIN)
                assertEquals(original.copy(weightInputUnit = WeightUnit.JIN), settings.flow.first())
                settings.setWeightFoodShortcut(false)
                assertEquals(original.copy(weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false), settings.flow.first())
                settings.savePlan(Plan(goal = "增肌"), day)
            }
            session(file) { store ->
                val actual = Settings(store).flow.first()
                assertEquals(original.copy(
                    nextPlan = Plan(goal = "增肌"), effectiveFrom = day + 1,
                    weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false
                ), actual)
                assertEquals("JIN", store.data.first()[stringPreferencesKey("weight_input_unit")])
                assertEquals(false, store.data.first()[booleanPreferencesKey("weight_food_shortcut")])
                assertEquals("preserve me", store.data.first()[unknownKey])
            }
        }
    }

    @Test fun backupSettingsRestorePersistsBothNewValuesWithoutClearingUnrelatedKeys() = runBlocking {
        withStoreFile { file ->
            val desired = Preferences(
                profile = profile, energyUnit = EnergyUnit.KJ, reminder = true,
                visibility = BodyVisibility.all(false).with(BodyField.AGE, true),
                weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false
            )
            val restored = BackupCodec.decode(BackupCodec.encode(fixture(desired))).preferences
            val unknownKey = stringPreferencesKey("future_preference")
            session(file) { store ->
                store.edit { it[unknownKey] = "preserve me" }
                val settings = Settings(store)
                settings.restore(restored)
                settings.setWeightInputUnit(WeightUnit.KG)
                settings.setWeightFoodShortcut(true)
                settings.restore(restored)
                assertEquals(desired, settings.flow.first())
            }
            session(file) { store ->
                assertEquals(desired, Settings(store).flow.first())
                assertEquals("preserve me", store.data.first()[unknownKey])
            }
        }
    }

    private suspend fun withStoreFile(block: suspend (File) -> Unit) {
        val root = Files.createDirectories(Paths.get("build", "tmp", "weight-preferences"))
        val file = Files.createTempDirectory(root, "settings-").resolve("settings.preferences_pb").toFile()
        try { block(file) } finally { file.delete(); file.parentFile.delete() }
    }

    private suspend fun session(
        file: File,
        migrate: Boolean = true,
        block: suspend (DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            migrations = if (migrate) listOf(VisibilityMigration) else emptyList(),
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }
        )
        try { block(store) } finally { job.cancelAndJoin() }
    }
}
