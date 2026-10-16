package com.fitledger.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import com.fitledger.domain.*
import org.json.JSONObject

/** Fill missing keys only, preserving both the old flag and all unrelated user settings. */
object VisibilityMigration:DataMigration<androidx.datastore.preferences.core.Preferences> {
    private val legacy=booleanPreferencesKey("body_privacy")
    fun read(p:androidx.datastore.preferences.core.Preferences)=BodyVisibility(BodyField.entries.associateWith {p[booleanPreferencesKey(it.key)] ?: !(p[legacy] ?: false)})
    override suspend fun shouldMigrate(currentData:androidx.datastore.preferences.core.Preferences)=BodyField.entries.any {currentData[booleanPreferencesKey(it.key)]==null}
    override suspend fun migrate(currentData:androidx.datastore.preferences.core.Preferences):androidx.datastore.preferences.core.Preferences = currentData.toMutablePreferences().apply {
        val visibility=read(currentData)
        BodyField.entries.forEach {this[booleanPreferencesKey(it.key)]=visibility.shows(it)}
    }
    override suspend fun cleanUp() = Unit
}
object VisibilityCodec {
    fun encode(v:BodyVisibility)=JSONObject().apply {BodyField.entries.forEach {put(it.key,v.shows(it))}}
    fun decode(j:JSONObject?,legacyHidden:Boolean)=BodyVisibility(BodyField.entries.associateWith {if(j?.has(it.key)==true) j.getBoolean(it.key) else !legacyHidden})
}
