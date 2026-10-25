package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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

class RecommendationPolicySettingsTest {
    private val policy = RecommendationPolicy(30.5, MacroTolerance(10.0, 0.0), MacroTolerance(0.0, null), MacroTolerance(null, 5.0))

    @Test fun nullableLimitsAndStrictZeroRoundTripThroughCodecAndPreferences() {
        val encoded = RecommendationPolicyCodec.encode(policy)
        assertTrue(encoded.getJSONObject("protein").isNull("over"))
        assertEquals(0.0, encoded.getJSONObject("carbs").getDouble("over"), 0.0)
        assertEquals(policy, RecommendationPolicyCodec.decode(encoded.toString()))
        val preferences = Preferences(energyUnit = EnergyUnit.KJ, privacy = true, skipMuscleInput = true,
            recommendationPolicy = policy)
        assertEquals(preferences, PreferencesCodec.decode(PreferencesCodec.encode(preferences)))
        val allStrict = RecommendationPolicy(0.0, MacroTolerance(0.0, 0.0), MacroTolerance(0.0, 0.0), MacroTolerance(0.0, 0.0))
        assertEquals(allStrict, RecommendationPolicyCodec.decode(RecommendationPolicyCodec.encode(allStrict)))
    }

    @Test fun missingLegacyPolicyDoesNotChangePlansPrivacyUnitsOrOtherPreferences() {
        val old = Preferences(energyUnit = EnergyUnit.KJ, reminder = true, hour = 7, minute = 45,
            nextPlan = Plan(goal = "减脂"), effectiveFrom = 21000, privacy = true,
            weightInputUnit = WeightUnit.JIN, hasReadIntro = true, skipMuscleInput = true)
        val json = JSONObject(PreferencesCodec.encode(old)).apply { remove("recommendationPolicy") }
        val decoded = PreferencesCodec.decode(json.toString())
        assertEquals(old, decoded)
        assertEquals(RecommendationPolicy(), decoded.recommendationPolicy)
        assertEquals(RecommendationPolicy(), RecommendationPolicyCodec.decode(JSONObject()))
        assertEquals(RecommendationPolicy(carbs = MacroTolerance(over = 0.0)),
            RecommendationPolicyCodec.decode(JSONObject().put("carbs", JSONObject().put("over", 0))))
    }

    @Test fun malformedPresentNumbersAndContainersAreRejectedBeforeRestoringAnything() {
        for (bad in listOf<Any>("30", true, -1)) {
            val json = RecommendationPolicyCodec.encode(policy).put("dailyBudgetYuan", bad)
            assertTrue(runCatching { RecommendationPolicyCodec.decode(json) }.isFailure)
        }
        for (key in listOf("carbs", "protein", "fat")) {
            for (bad in listOf<Any>("0", true, JSONObject.NULL)) {
                val json = RecommendationPolicyCodec.encode(policy).put(key, bad)
                assertTrue(runCatching { RecommendationPolicyCodec.decode(json) }.isFailure)
            }
            for (side in listOf("under", "over")) for (bad in listOf<Any>("0", false, -1)) {
                val json = RecommendationPolicyCodec.encode(policy)
                json.getJSONObject(key).put(side, bad)
                assertTrue(runCatching { RecommendationPolicyCodec.decode(json) }.isFailure)
            }
        }
        for (bad in listOf<Any>("{}", false, JSONObject.NULL)) {
            val json = JSONObject(PreferencesCodec.encode(Preferences())).put("recommendationPolicy", bad)
            assertTrue(runCatching { PreferencesCodec.decode(json.toString()) }.isFailure)
        }
    }

    @Test fun settingSurvivesReopenRestoreAndUnrelatedUpdatesWithoutClearingUnknownKeys() = runBlocking {
        withFile { file ->
            val original = Preferences(energyUnit = EnergyUnit.KJ, reminder = true, hour = 6, minute = 15,
                weightInputUnit = WeightUnit.JIN, hasReadIntro = true)
            session(file) { store ->
                val settings = Settings(store)
                settings.restore(original)
                store.edit { it[stringPreferencesKey("future_key")] = "keep" }
                assertEquals(RecommendationPolicy(), settings.flow.first().recommendationPolicy)
                settings.setRecommendationPolicy(policy)
                assertEquals(original.copy(recommendationPolicy = policy), settings.flow.first())
            }
            session(file) { store ->
                val settings = Settings(store)
                assertEquals(policy, settings.flow.first().recommendationPolicy)
                settings.setSkipMuscleInput(true); settings.setEnergyUnit(EnergyUnit.KCAL)
                settings.savePlan(Plan(goal = "减脂"), 21000)
                assertEquals(policy, settings.flow.first().recommendationPolicy)
                assertEquals("keep", store.data.first()[stringPreferencesKey("future_key")])
                val saved = settings.flow.first()
                val different = policy.copy(dailyBudgetYuan = 0.0)
                settings.setRecommendationPolicy(different)
                settings.restore(PreferencesCodec.decode(PreferencesCodec.encode(saved)))
                assertEquals(saved, settings.flow.first())
                assertEquals("keep", store.data.first()[stringPreferencesKey("future_key")])
            }
            session(file) { store -> assertEquals(policy, Settings(store).flow.first().recommendationPolicy) }
        }
    }

    @Test fun invalidSaveCannotReplaceExistingPolicy() = runBlocking {
        withFile { file ->
            session(file) { store ->
                val settings = Settings(store)
                settings.setRecommendationPolicy(policy)
                assertTrue(runCatching { settings.setRecommendationPolicy(policy.copy(dailyBudgetYuan = -1.0)) }.isFailure)
                assertEquals(policy, settings.flow.first().recommendationPolicy)
            }
        }
    }

    private suspend fun withFile(block: suspend (File) -> Unit) {
        val root = Files.createDirectories(Paths.get("build", "tmp", "recommendation-policy"))
        val file = Files.createTempDirectory(root, "settings-").resolve("settings.preferences_pb").toFile()
        try { block(file) } finally { file.delete(); file.parentFile?.delete() }
    }
    private suspend fun session(file: File, block: suspend (DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(migrations = listOf(VisibilityMigration),
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try { block(store) } finally { job.cancelAndJoin() }
    }
}
