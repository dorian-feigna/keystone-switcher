package com.keystoneswitcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.positionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "positions_store")

/** Persistance locale (DataStore) : liste des positions + configuration apprise, au format JSON. */
class PositionRepository(context: Context) {

    private val store = context.applicationContext.positionsDataStore
    private val keyPositions = stringPreferencesKey("positions_json")
    private val keyConfig = stringPreferencesKey("engine_config_json")

    private val safeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val positions: Flow<List<KeystonePosition>> = safeData.map { KeystonePosition.listFromJson(it[keyPositions]) }

    suspend fun currentConfig(): EngineConfig = safeData.map { EngineConfig.fromJson(it[keyConfig]) }.first()

    /** Enregistre la position ; remplace celle qui porte le même nom. Retourne true si remplacement. */
    suspend fun savePosition(position: KeystonePosition): Boolean {
        var replaced = false
        store.edit { prefs ->
            val list = KeystonePosition.listFromJson(prefs[keyPositions]).toMutableList()
            val index = list.indexOfFirst { it.name.equals(position.name, ignoreCase = true) }
            if (index >= 0) {
                list[index] = position
                replaced = true
            } else {
                list.add(position)
            }
            prefs[keyPositions] = KeystonePosition.listToJson(list)
        }
        return replaced
    }

    suspend fun deletePosition(name: String) {
        store.edit { prefs ->
            val list = KeystonePosition.listFromJson(prefs[keyPositions]).filterNot { it.name == name }
            prefs[keyPositions] = KeystonePosition.listToJson(list)
        }
    }

    suspend fun updateConfig(transform: (EngineConfig) -> EngineConfig) {
        store.edit { prefs ->
            prefs[keyConfig] = transform(EngineConfig.fromJson(prefs[keyConfig])).toJson()
        }
    }
}
