package com.loosewire.kelp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class KelpPlaybackPreferences(
    val continuousPlayback: Boolean = true,
)

interface KelpPreferences {
    val playback: Flow<KelpPlaybackPreferences>

    suspend fun setContinuousPlayback(enabled: Boolean)
}

class DataStoreTidePreferences(
    private val dataStore: DataStore<Preferences>,
) : KelpPreferences {
    override val playback: Flow<KelpPlaybackPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            KelpPlaybackPreferences(
                continuousPlayback = preferences[ContinuousPlaybackKey] ?: true,
            )
        }

    override suspend fun setContinuousPlayback(enabled: Boolean) {
        dataStore.edit { it[ContinuousPlaybackKey] = enabled }
    }

    private companion object {
        val ContinuousPlaybackKey = booleanPreferencesKey("playback_continuous")
    }
}
