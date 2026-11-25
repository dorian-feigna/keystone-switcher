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

private val Context.keystoneDataStore: DataStore<Preferences> by preferencesDataStore(name = "keystone_store")

/**
 * Persistance locale : DataStore Preferences contenant deux documents JSON
 * (liste des profils + configuration projecteur).
 */
class ProfileRepository(context: Context) {

    private val store = context.applicationContext.keystoneDataStore

    private val keyProfiles = stringPreferencesKey("profiles_json")
    private val keyDevice = stringPreferencesKey("device_config_json")

    private val safeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val profiles: Flow<List<KeystoneProfile>> = safeData.map { KeystoneProfile.listFromJson(it[keyProfiles]) }

    val deviceConfig: Flow<DeviceConfig> = safeData.map { DeviceConfig.fromJsonString(it[keyDevice]) }

    suspend fun currentProfiles(): List<KeystoneProfile> = profiles.first()

    suspend fun currentDeviceConfig(): DeviceConfig = deviceConfig.first()

    /**
     * Crée ou met à jour un profil. [originalName] = nom avant édition (null pour une création).
     * @throws IllegalStateException si le nom est déjà utilisé par un autre profil.
     */
    suspend fun saveProfile(profile: KeystoneProfile, originalName: String?) {
        store.edit { prefs ->
            val list = KeystoneProfile.listFromJson(prefs[keyProfiles]).toMutableList()
            val duplicate = list.any {
                it.profileName.equals(profile.profileName, ignoreCase = true) && it.profileName != originalName
            }
            check(!duplicate) { "Un profil nommé « ${profile.profileName} » existe déjà." }
            val index = list.indexOfFirst { it.profileName == originalName }
            if (index >= 0) list[index] = profile else list.add(profile)
            prefs[keyProfiles] = KeystoneProfile.listToJson(list)
        }
    }

    suspend fun deleteProfile(name: String) {
        store.edit { prefs ->
            val list = KeystoneProfile.listFromJson(prefs[keyProfiles]).filterNot { it.profileName == name }
            prefs[keyProfiles] = KeystoneProfile.listToJson(list)
        }
    }

    suspend fun saveDeviceConfig(config: DeviceConfig) {
        store.edit { prefs -> prefs[keyDevice] = config.toJson().toString() }
    }

    suspend fun updateDeviceConfig(transform: (DeviceConfig) -> DeviceConfig) {
        store.edit { prefs ->
            val current = DeviceConfig.fromJsonString(prefs[keyDevice])
            prefs[keyDevice] = transform(current).toJson().toString()
        }
    }
}
