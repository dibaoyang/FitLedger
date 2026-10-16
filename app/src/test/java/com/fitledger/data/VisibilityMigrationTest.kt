package com.fitledger.data

import androidx.datastore.preferences.core.*
import com.fitledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VisibilityMigrationTest {
    @Test fun hiddenLegacyMigratesAllHiddenAndPreservesOtherKeys()=runBlocking {
        val old=preferencesOf(booleanPreferencesKey("body_privacy") to true,stringPreferencesKey("body_profile") to "original",stringPreferencesKey("energy_unit") to "KJ",intPreferencesKey("hour") to 7)
        val migrated=VisibilityMigration.migrate(old)
        assertTrue(BodyField.entries.all {!VisibilityMigration.read(migrated).shows(it)})
        old.asMap().forEach {(key,value)->assertEquals(value,migrated.asMap()[key])}
        assertFalse(VisibilityMigration.shouldMigrate(migrated))
        assertEquals(migrated,VisibilityMigration.migrate(migrated))
    }
    @Test fun visibleLegacyAndFreshDefaultsShowAll()=runBlocking {
        listOf(emptyPreferences(),preferencesOf(booleanPreferencesKey("body_privacy") to false)).forEach {old->
            assertTrue(BodyField.entries.all {VisibilityMigration.read(VisibilityMigration.migrate(old)).shows(it)})
        }
    }
    @Test fun partialMigrationNeverOverwritesExistingIndividualChoice()=runBlocking {
        val p=preferencesOf(booleanPreferencesKey("body_privacy") to true,booleanPreferencesKey("showWeight") to true)
        val v=VisibilityMigration.read(VisibilityMigration.migrate(p))
        assertTrue(v.shows(BodyField.WEIGHT));assertTrue(BodyField.entries.filter {it!=BodyField.WEIGHT}.all {!v.shows(it)})
    }
    @Test fun everyFieldChangesIndependently() {
        BodyField.entries.forEach {field->
            val v=BodyVisibility.all(false).with(field,true)
            BodyField.entries.forEach {assertEquals(it==field,v.shows(it))}
        }
    }
    @Test fun oldSettingsAndPendingJournalRespectLegacyPrivacy() {
        listOf(true,false).forEach {hidden->
            val json=JSONObject(PreferencesCodec.encode(Preferences(privacy=hidden)));json.remove("visibility")
            val p=PreferencesCodec.decode(json.toString())
            assertTrue(BodyField.entries.all {p.visibility.shows(it)==!hidden})
        }
    }
    @Test fun mixedSettingsRoundTripPreservesRealProfileAndCalculations() {
        val profile=BodyProfile(Sex.MALE,175.0,java.time.LocalDate.of(2000,1,1).toEpochDay(),1.55)
        val p=Preferences(profile=profile,privacy=true,visibility=BodyVisibility.all(false).with(BodyField.AGE,true),energyUnit=EnergyUnit.KJ)
        val decoded=PreferencesCodec.decode(PreferencesCodec.encode(p))
        assertEquals(p,decoded)
        val day=java.time.LocalDate.of(2026,9,20).toEpochDay()
        assertEquals(EnergyExpenditureCalculator.snapshot(72.4,profile,day),EnergyExpenditureCalculator.snapshot(72.4,decoded.profile!!,day))
    }
    @Test fun actualOldDataStoreFileUpgradesAndKeepsMixedStateAcrossReopen()=runBlocking {
        val file=Files.createTempDirectory("visibility-upgrade").resolve("settings.preferences_pb").toFile()
        suspend fun session(migrate:Boolean, block:suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>)->Unit) {
            val job=SupervisorJob();val scope=CoroutineScope(Dispatchers.IO+job)
            val store=PreferenceDataStoreFactory.create(migrations=if(migrate) listOf(VisibilityMigration) else emptyList(),scope=scope,produceFile={file})
            try {block(store)} finally {job.cancelAndJoin()}
        }
        try {
            session(false) {it.edit {p->p[booleanPreferencesKey("body_privacy")]=true;p[stringPreferencesKey("plan")]=PlanCodec.encode(Plan());p[stringPreferencesKey("energy_unit")]="KJ"}}
            session(true) {store->
                assertTrue(BodyField.entries.all {!VisibilityMigration.read(store.data.first()).shows(it)})
                store.edit {it[booleanPreferencesKey("showAge")]=true}
            }
            session(true) {store->
                val p=store.data.first();val v=VisibilityMigration.read(p)
                assertTrue(v.shows(BodyField.AGE));assertTrue(BodyField.entries.filter {it!=BodyField.AGE}.all {!v.shows(it)})
                assertEquals("KJ",p[stringPreferencesKey("energy_unit")]);assertEquals(PlanCodec.encode(Plan()),p[stringPreferencesKey("plan")])
            }
        } finally {file.delete();file.parentFile.delete()}
    }
}
