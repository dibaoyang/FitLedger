package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class IntroPreferencesTest {
    @Test fun oldPreferencesAndJournalsShowIntroOnceAndKeepMuscleRequired() {
        val original=Preferences(energyUnit=EnergyUnit.KJ,privacy=true,weightInputUnit=WeightUnit.JIN)
        val legacy=JSONObject(PreferencesCodec.encode(original)).apply {remove("hasReadIntro");remove("skipMuscleInput")}
        val restored=PreferencesCodec.decode(legacy.toString())
        assertEquals(original,restored)
        assertFalse(restored.hasReadIntro)
        assertFalse(restored.skipMuscleInput)
    }

    @Test fun malformedNewPreferencesAreRejected() {
        for(key in listOf("hasReadIntro","skipMuscleInput")) for(value in listOf("true",1,JSONObject.NULL)) {
            val j=JSONObject(PreferencesCodec.encode(Preferences())).put(key,value)
            assertTrue(runCatching {PreferencesCodec.decode(j.toString())}.isFailure)
        }
    }

    @Test fun readingAndOmissionSurviveClosingReopeningAndPlanChanges()=runBlocking {
        withFile {file ->
            val old=Preferences(energyUnit=EnergyUnit.KJ,reminder=true,hour=7,weightInputUnit=WeightUnit.JIN)
            session(file) {store ->
                val settings=Settings(store)
                settings.restore(old)
                store.edit {it[stringPreferencesKey("future_key")]="keep"}
                assertFalse(settings.flow.first().hasReadIntro)
                settings.markIntroRead();settings.setSkipMuscleInput(true)
            }
            session(file) {store ->
                val settings=Settings(store)
                assertEquals(old.copy(hasReadIntro=true,skipMuscleInput=true),settings.flow.first())
                settings.savePlan(Plan(goal="减脂"),20000)
                settings.setEnergyUnit(EnergyUnit.KCAL)
            }
            session(file) {store ->
                val p=Settings(store).flow.first()
                assertTrue(p.hasReadIntro);assertTrue(p.skipMuscleInput)
                assertEquals("减脂",p.nextPlan?.goal)
                assertEquals("keep",store.data.first()[stringPreferencesKey("future_key")])
            }
        }
    }

    @Test fun restoringOlderBackupOrPendingSettingsNeverClearsReadingAcknowledgement()=runBlocking {
        withFile {file ->
            session(file) {store ->
                val settings=Settings(store)
                settings.markIntroRead();settings.setSkipMuscleInput(true)
                settings.restore(Preferences())
                assertTrue(settings.flow.first().hasReadIntro)
                assertFalse(settings.flow.first().skipMuscleInput)
                settings.restore(Preferences(hasReadIntro=true,skipMuscleInput=true))
                settings.setSkipMuscleInput(false)
                assertTrue(settings.flow.first().hasReadIntro)
            }
            session(file) {store ->
                assertTrue(Settings(store).flow.first().hasReadIntro)
                assertFalse(Settings(store).flow.first().skipMuscleInput)
            }
        }
    }

    private suspend fun withFile(block:suspend (File)->Unit) {
        val root=Files.createDirectories(Paths.get("build","tmp","intro-preferences"))
        val file=Files.createTempDirectory(root,"settings-").resolve("settings.preferences_pb").toFile()
        try {block(file)} finally {file.delete();file.parentFile?.delete()}
    }
    private suspend fun session(file:File,block:suspend (DataStore<androidx.datastore.preferences.core.Preferences>)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(migrations=listOf(VisibilityMigration),scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
        try {block(store)} finally {job.cancelAndJoin()}
    }
}
