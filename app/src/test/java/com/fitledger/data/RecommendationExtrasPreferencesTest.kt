package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class RecommendationExtrasPreferencesTest {
    private val extras=RecommendationExtras(180.0,true,7,setOf(31),listOf(WeightRatio(8,7,0.5)))
    private val futureKey=stringPreferencesKey("unrelated_future_setting")

    @Test fun profilesAndCustomRulesSurviveReopenWithoutChangingExistingSettings()=runBlocking {
        withFile {file->
            val rules=RecommendationRules(listOf(FoodCycleRule(7,5,2,300.0)),emptyList(),setOf("我自定义的条件"))
            val profile=RecommendationProfile("dorm","宿舍日",RecommendationPolicy(dailyBudgetYuan=30.0),extras.copy(rules=rules))
            val previous=Preferences(energyUnit=EnergyUnit.KJ,hasReadIntro=true,visibility=BodyVisibility.all(false))
            session(file) {store->
                val settings=Settings(store);settings.restore(previous);store.edit {it[futureKey]="retain"}
                settings.setRecommendationProfiles(listOf(profile));settings.setRecommendationExtras(profile.extras)
                assertTrue(runCatching {settings.setRecommendationProfiles(listOf(profile,profile))}.isFailure)
                assertEquals(listOf(profile),settings.flow.first().recommendationProfiles)
            }
            session(file) {store->
                assertEquals(previous.copy(recommendationProfiles=listOf(profile),recommendationExtras=profile.extras),Settings(store).flow.first())
                assertEquals("retain",store.data.first()[futureKey])
                Settings(store).restore(Preferences())
                assertEquals(Preferences(hasReadIntro=true),Settings(store).flow.first())
                assertEquals("retain",store.data.first()[futureKey])
            }
        }
    }

    @Test fun legacyDataStoreAndNewExtrasSurviveReopenWithoutResettingExistingOrUnknownSettings()=runBlocking {
        withFile {file->
            val previous=Preferences(energyUnit=EnergyUnit.KJ,weightInputUnit=WeightUnit.JIN,reminder=true,hour=7,minute=15,
                visibility=BodyVisibility.all(false),profile=BodyProfile(Sex.MALE,175.0,10957,1.55),hasReadIntro=true,skipMuscleInput=true)
            session(file) {store->
                Settings(store).restore(previous)
                store.edit {it.remove(stringPreferencesKey("recommendation_extras"));it[futureKey]="keep"}
            }
            session(file) {store->
                val settings=Settings(store)
                assertEquals(previous,settings.flow.first());assertFalse(settings.flow.first().recommendationExtras.enabled)
                settings.setRecommendationExtras(extras)
                assertEquals(previous.copy(recommendationExtras=extras),settings.flow.first())
            }
            session(file) {store->
                assertEquals(previous.copy(recommendationExtras=extras),Settings(store).flow.first())
                assertEquals("keep",store.data.first()[futureKey])
            }
        }
    }

    @Test fun restoringAnOldJournalResetsOnlyNewConfigAndKeepsStickyIntroAndUnknownKeys()=runBlocking {
        withFile {file->
            session(file) {store->
                val settings=Settings(store)
                settings.markIntroRead();settings.setRecommendationExtras(extras)
                store.edit {it[futureKey]="retain"}
                settings.restore(Preferences(energyUnit=EnergyUnit.KJ))
                assertEquals(Preferences(energyUnit=EnergyUnit.KJ,hasReadIntro=true),settings.flow.first())
            }
            session(file) {store->
                assertEquals(Preferences(energyUnit=EnergyUnit.KJ,hasReadIntro=true),Settings(store).flow.first())
                assertEquals("retain",store.data.first()[futureKey])
            }
        }
    }

    @Test fun invalidExtrasFailBeforeChangingAnyStoredPreference()=runBlocking {
        withFile {file->
            session(file) {store->
                val settings=Settings(store);settings.setRecommendationExtras(extras)
                val before=store.data.first()
                for(invalid in listOf(extras.copy(weeklyBudgetYuan=-1.0),extras.copy(weeklyBudgetYuan=Double.NaN),
                    extras.copy(varietyDays=91),extras.copy(comboIds=setOf(0)),extras.copy(ratios=listOf(WeightRatio(7,7,0.5))))) {
                    assertTrue(runCatching {settings.setRecommendationExtras(invalid)}.isFailure)
                    assertEquals(before,store.data.first())
                }
            }
        }
    }

    private suspend fun withFile(block:suspend (File)->Unit) {
        val root=Files.createDirectories(Paths.get("build","tmp","recommendation-extras-preferences"))
        val file=Files.createTempDirectory(root,"settings-").resolve("settings.preferences_pb").toFile()
        try {block(file)} finally {file.delete();file.parentFile?.delete()}
    }
    private suspend fun session(file:File,block:suspend (DataStore<androidx.datastore.preferences.core.Preferences>)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(migrations=listOf(VisibilityMigration),scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
        try {block(store)} finally {job.cancelAndJoin()}
    }
}
