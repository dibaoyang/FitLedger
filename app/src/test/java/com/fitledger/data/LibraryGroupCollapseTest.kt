package com.fitledger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
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

class LibraryGroupCollapseTest {
    private val type=LibraryGroupCollapse.type(42)
    private val otherType=LibraryGroupCollapse.type(77)
    private val carbs=LibraryGroupCollapse.nutrition("CARBS")
    private val protein=LibraryGroupCollapse.nutrition("PROTEIN")
    private val keys=setOf(type,carbs)
    private fun fixture(p:Preferences=Preferences(collapsedLibraryGroups=keys)):Backup {
        val day=20000L
        val food=Food(7,"保留原食品","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true)
        return Backup(bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,58.1)),
            targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
            foods=listOf(food),intakes=listOf(Intake(9,day,7,"原快照","g",100.0,food.nutrition)),preferences=p)
    }

    @Test fun identifiersAreStableAndRejectInvalidOrAmbiguousZoneNames() {
        LibraryGroupCollapse.validate(setOf(type,otherType,carbs,protein,"NUTRITION:NONE","TYPE:${Long.MAX_VALUE}"))
        for(key in listOf("","TYPE:0","TYPE:-1","TYPE:01","TYPE:1.0","TYPE:9223372036854775808","TYPE:面包","NUTRITION:OTHER","CARBS","type:42")) {
            assertTrue(key,runCatching {LibraryGroupCollapse.validate(setOf(key))}.isFailure)
        }
        assertTrue(runCatching {LibraryGroupCollapse.type(0)}.isFailure)
        assertTrue(runCatching {LibraryGroupCollapse.nutrition("OTHER")}.isFailure)
    }

    @Test fun preferencesRoundTripAndOldMissingSettingsDefaultToExpanded() {
        val original=Preferences(energyUnit=EnergyUnit.KJ,weightInputUnit=WeightUnit.JIN,collapsedLibraryGroups=keys)
        assertEquals(original,PreferencesCodec.decode(PreferencesCodec.encode(original)))
        val legacy=JSONObject(PreferencesCodec.encode(original)).apply {remove("collapsedLibraryGroups")}
        assertEquals(original.copy(collapsedLibraryGroups=emptySet()),PreferencesCodec.decode(legacy.toString()))
    }

    @Test fun malformedCollapsePreferencesAreNeverSilentlyDropped() {
        val bad=listOf<Any>(true,1,"TYPE:42",JSONObject.NULL,JSONArray(listOf(type,type)),
            JSONArray(listOf(type,1)),JSONArray(listOf("TYPE:0")),JSONArray(listOf("NUTRITION:OTHER")))
        bad.forEach {value->
            val json=JSONObject(PreferencesCodec.encode(Preferences())).put("collapsedLibraryGroups",value)
            assertTrue(value.toString(),runCatching {PreferencesCodec.decode(json.toString())}.isFailure)
        }
        assertTrue(runCatching {PreferencesCodec.encode(Preferences(collapsedLibraryGroups=setOf("bad")))}.isFailure)
    }

    @Test fun individualAndBatchUpdatesSurviveReopenAndPreserveOtherGroupsAndSettings()=runBlocking {
        withFile {file->
            val original=Preferences(energyUnit=EnergyUnit.KJ,weightInputUnit=WeightUnit.JIN,reminder=true,hour=7,minute=25)
            session(file) {store->
                val settings=Settings(store)
                settings.restore(original)
                store.edit {it[stringPreferencesKey("future_key")]="preserve"}
                settings.setLibraryGroupsCollapsed(setOf(type),true)
                settings.setLibraryGroupsCollapsed(setOf(carbs,protein),true)
                settings.setLibraryGroupsCollapsed(setOf(otherType),true)
                settings.setLibraryGroupsCollapsed(setOf(type),false)
                assertEquals(original.copy(collapsedLibraryGroups=setOf(carbs,protein,otherType)),settings.flow.first())
            }
            session(file) {store->
                val settings=Settings(store)
                assertEquals(setOf(carbs,protein,otherType),settings.flow.first().collapsedLibraryGroups)
                settings.setEnergyUnit(EnergyUnit.KCAL);settings.setSkipMuscleInput(true)
                settings.savePlan(Plan(goal="减脂"),21000)
                settings.setLibraryGroupsCollapsed(setOf(type,otherType),false)
                assertEquals(setOf(carbs,protein),settings.flow.first().collapsedLibraryGroups)
                val saved=settings.flow.first()
                settings.setLibraryGroupsCollapsed(setOf(carbs,protein),false)
                settings.restore(PreferencesCodec.decode(PreferencesCodec.encode(saved)))
                assertEquals(saved,settings.flow.first())
                assertEquals("preserve",store.data.first()[stringPreferencesKey("future_key")])
            }
            session(file) {store->assertEquals(setOf(carbs,protein),Settings(store).flow.first().collapsedLibraryGroups)}
        }
    }

    @Test fun rejectedUpdateCannotOverwriteStoredGroupStateOrClearUnrelatedPreferences()=runBlocking {
        withFile {file->session(file) {store->
            val settings=Settings(store)
            settings.restore(Preferences(collapsedLibraryGroups=keys,energyUnit=EnergyUnit.KJ))
            store.edit {it[stringPreferencesKey("future_key")]="keep"}
            val before=settings.flow.first()
            assertTrue(runCatching {settings.setLibraryGroupsCollapsed(setOf("TYPE:0"),true)}.isFailure)
            assertTrue(runCatching {settings.restore(before.copy(collapsedLibraryGroups=setOf("bad")))}.isFailure)
            assertEquals(before,settings.flow.first())
            assertEquals("keep",store.data.first()[stringPreferencesKey("future_key")])
        }}
    }

    @Test fun backupFourteenKeepsCollapseAndOldThirteenRestoresAllFoodSnapshotsWithDefaultViewState() {
        val original=fixture()
        assertEquals(17,original.backupVersion)
        assertEquals(13,original.databaseVersion)
        assertEquals(original,BackupCodec.decode(BackupCodec.encode(original)))
        val legacy=original.copy(backupVersion=13,preferences=original.preferences.copy(collapsedLibraryGroups=emptySet()))
        val root=JSONObject(BackupCodec.encode(legacy))
        edit(root) {it.getJSONObject("preferences").remove("collapsedLibraryGroups")}
        assertEquals(legacy,BackupCodec.decode(root.toString()))
        val decoded=BackupCodec.decode(root.toString())
        assertEquals(original.bodies,decoded.bodies);assertEquals(original.targets,decoded.targets)
        assertEquals(original.foods,decoded.foods);assertEquals(original.intakes,decoded.intakes)
    }

    @Test fun downgradeAndMalformedNewBackupAreRejectedBeforeRestore() {
        val original=fixture()
        assertTrue(runCatching {original.copy(backupVersion=13).validate()}.isFailure)
        val missing=JSONObject(BackupCodec.encode(original))
        edit(missing) {it.getJSONObject("preferences").remove("collapsedLibraryGroups")}
        assertTrue(runCatching {BackupCodec.decode(missing.toString())}.isFailure)
        val downgrade=JSONObject(BackupCodec.encode(original)).put("backupVersion",13)
        assertTrue(runCatching {BackupCodec.decode(downgrade.toString())}.isFailure)
        val invalid=JSONObject(BackupCodec.encode(original))
        edit(invalid) {it.getJSONObject("preferences").put("collapsedLibraryGroups",JSONArray(listOf("TYPE:42","TYPE:42")))}
        assertTrue(runCatching {BackupCodec.decode(invalid.toString())}.isFailure)
    }

    private fun edit(root:JSONObject,change:(JSONObject)->Unit) {
        val data=JSONObject(root.getString("payload"));change(data)
        val payload=data.toString()
        root.put("payload",payload).put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
    private suspend fun withFile(block:suspend (File)->Unit) {
        val root=Files.createDirectories(Paths.get("build","tmp","library-collapse"))
        val file=Files.createTempDirectory(root,"settings-").resolve("settings.preferences_pb").toFile()
        try {block(file)} finally {file.delete();file.parentFile?.delete()}
    }
    private suspend fun session(file:File,block:suspend (DataStore<androidx.datastore.preferences.core.Preferences>)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(migrations=listOf(VisibilityMigration),scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
        try {block(store)} finally {job.cancelAndJoin()}
    }
}
