package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecommendationExtrasSettingsTest {
    private val extras=RecommendationExtras(180.0,true,7,setOf(31),listOf(WeightRatio(8,7,0.5)))
    private val unrelated=stringPreferencesKey("unrelated_future_setting")

    @Test fun updatingAnExistingDataStoreDefaultsExtrasOffThenPreservesThemAcrossReopen()=runBlocking {
        withFile {file->
            val previous=Preferences(energyUnit=EnergyUnit.KJ,weightInputUnit=WeightUnit.JIN,reminder=true,hour=7,minute=15,
                visibility=BodyVisibility.all(false),profile=BodyProfile(Sex.MALE,175.0,10957,1.55),hasReadIntro=true,skipMuscleInput=true)
            session(file) {store->
                Settings(store).restore(previous)
                store.edit {it.remove(stringPreferencesKey("recommendation_extras"));it[unrelated]="keep"}
            }
            session(file) {store->
                val settings=Settings(store)
                assertEquals(previous,settings.flow.first())
                settings.setRecommendationExtras(extras)
            }
            session(file) {store->
                assertEquals(previous.copy(recommendationExtras=extras),Settings(store).flow.first())
                assertEquals("keep",store.data.first()[unrelated])
            }
        }
    }

    @Test fun backupSixteenRestoresAllNewDataAndRetriesItsSettingsJournalWithoutClearingIntroOrOtherKeys()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {withFile {file->
            val repo=Repository(db);val day=LocalDate.now().toEpochDay();val profile=BodyProfile(Sex.MALE,175.0,10957,1.55)
            repo.weigh(day,Body(72.4,15.2,58.1),Plan(),profile)
            val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
            val chicken=Food(8,"鸡肉","g",100.0,Nutrition(120.0,0.0,25.0,2.0),10.0,500.0,true)
            repo.dao.saveFood(rice);repo.dao.saveFood(chicken)
            val rules=WeightRatios.encode(extras.ratios)
            val combo=FoodCombo(31,"鸡肉饭",ComboCodec.encode(listOf(ComboItem(7,"g",200.0),ComboItem(8,"g",100.0))),rules)
            db.featureDao().saveCombo(combo)
            val recipeId=RecipeStore(db).save(FoodRecipe(name="自制饭",ingredients=RecipeIngredients.encode(listOf(
                RecipeIngredients.fromFood(rice,200.0),RecipeIngredients.fromFood(chicken,100.0))),finishedWeightG=350.0),true)
            db.foodPresetDao().savePlan(FoodPresetPlan(day+3,day,2635.0,72.4,rules))
            db.foodPresetDao().insertItem(FoodPresetItem(day=day+3,foodId=7,name=rice.name,unit="g",quantity=200.0,nutrition=rice.nutrition*2.0,costYuan=2.0))
            db.foodPresetDao().insertItem(FoodPresetItem(day=day+3,foodId=8,name=chicken.name,unit="g",quantity=100.0,nutrition=chicken.nutrition,costYuan=null))
            FeatureStore(db).recordFood(day,7,100.0)
            val desired=Preferences(energyUnit=EnergyUnit.KJ,profile=profile,recommendationExtras=extras)
            val backup=BackupCodec.decode(BackupCodec.encode(repo.exportBackup(desired)))
            session(file) {store->
                val settings=Settings(store);settings.markIntroRead();store.edit {it[unrelated]="retain"}
                repo.restoreBackup(backup)
                assertNotNull(repo.dao.pendingSettings())
                assertTrue(runCatching {repo.finishPendingSettings(settings) {error("simulated interrupted reminder completion")}}.isFailure)
                assertNotNull(repo.dao.pendingSettings())
                assertEquals(desired.copy(hasReadIntro=true),settings.flow.first())
                repo.finishPendingSettings(settings) {}
                assertNull(repo.dao.pendingSettings())
                assertEquals(backup.recipes,db.recipeDao().allRecipes());assertEquals(recipeId,db.recipeDao().allRecipes().single().id)
                assertEquals(backup.combos,db.featureDao().allCombos())
                assertEquals(backup.presets,db.foodPresetDao().allPlans());assertEquals(backup.presetItems,db.foodPresetDao().allItems())
                assertEquals(backup.bodies,repo.dao.allBodies());assertEquals(backup.targets,repo.dao.allTargets());assertEquals(backup.intakes,repo.dao.allIntakes())
            }
            session(file) {store->
                assertEquals(desired.copy(hasReadIntro=true),Settings(store).flow.first())
                assertEquals("retain",store.data.first()[unrelated])
            }
        }} finally {db.close()}
    }

    private suspend fun withFile(block:suspend (File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("extras-settings-",".preferences_pb",context.cacheDir)
        file.delete()
        try {block(file)} finally {file.delete()}
    }
    private suspend fun session(file:File,block:suspend (DataStore<androidx.datastore.preferences.core.Preferences>)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(migrations=listOf(VisibilityMigration),scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
        try {block(store)} finally {job.cancelAndJoin()}
    }
}
