package com.example.answeringmachine.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.answeringDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calldeck_settings"
)

data class AnsweringSettings(
    val isEnabled: Boolean = true,
    val ringDelaySeconds: Int = 3,
    val maxRecordingSeconds: Int = 60,
    val useCustomGreeting: Boolean = false,
    val greetingNote: String = "Hi, I can't get to the phone right now. Please leave your message after the tone."
)

class SettingsRepository private constructor(private val appContext: Context) {

    private object Keys {
        val IS_ENABLED = booleanPreferencesKey("answering_enabled")
        val RING_DELAY_SECONDS = intPreferencesKey("ring_delay_seconds")
        val MAX_RECORDING_SECONDS = intPreferencesKey("max_recording_seconds")
        val USE_CUSTOM_GREETING = booleanPreferencesKey("use_custom_greeting")
        val GREETING_NOTE = stringPreferencesKey("greeting_note")
    }

    val settingsFlow: Flow<AnsweringSettings> = appContext.answeringDataStore.data.map { prefs ->
        AnsweringSettings(
            isEnabled = prefs[Keys.IS_ENABLED] ?: true,
            ringDelaySeconds = prefs[Keys.RING_DELAY_SECONDS] ?: 3,
            maxRecordingSeconds = prefs[Keys.MAX_RECORDING_SECONDS] ?: 60,
            useCustomGreeting = prefs[Keys.USE_CUSTOM_GREETING] ?: false,
            greetingNote = prefs[Keys.GREETING_NOTE]
                ?: "Hi, I can't get to the phone right now. Please leave your message after the tone."
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        appContext.answeringDataStore.edit { it[Keys.IS_ENABLED] = enabled }
    }

    suspend fun setRingDelaySeconds(seconds: Int) {
        appContext.answeringDataStore.edit { it[Keys.RING_DELAY_SECONDS] = seconds.coerceIn(0, 20) }
    }

    suspend fun setMaxRecordingSeconds(seconds: Int) {
        appContext.answeringDataStore.edit { it[Keys.MAX_RECORDING_SECONDS] = seconds.coerceIn(15, 180) }
    }

    suspend fun setUseCustomGreeting(useCustom: Boolean) {
        appContext.answeringDataStore.edit { it[Keys.USE_CUSTOM_GREETING] = useCustom }
    }

    suspend fun setGreetingNote(note: String) {
        appContext.answeringDataStore.edit { it[Keys.GREETING_NOTE] = note }
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
