package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppPreferencesState
import com.example.data.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.beachDataStore: DataStore<Preferences> by preferencesDataStore(name = "beaver_beach_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_pref")
        val SURF_ENABLED = booleanPreferencesKey("surf_enabled")
        val SURF_VOLUME = floatPreferencesKey("surf_volume")
        val MUSIC_ENABLED = booleanPreferencesKey("music_enabled")
        val MUSIC_VOLUME = floatPreferencesKey("music_volume")
        val SFX_ENABLED = booleanPreferencesKey("sfx_enabled")
        val AUTO_BACKUP = booleanPreferencesKey("auto_cloud_backup")
        val SAF_CLOUD_URI = stringPreferencesKey("saf_cloud_uri")
        val SAF_CLOUD_NAME = stringPreferencesKey("saf_cloud_name")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        val LAST_SYNC = longPreferencesKey("last_cloud_sync")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden_files")
    }

    val preferencesFlow: Flow<AppPreferencesState> = context.beachDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME] ?: ThemePreference.SYSTEM.name
        val theme = runCatching { ThemePreference.valueOf(themeStr) }.getOrDefault(ThemePreference.SYSTEM)
        AppPreferencesState(
            themePreference = theme,
            surfEnabled = prefs[Keys.SURF_ENABLED] ?: false,
            surfVolume = prefs[Keys.SURF_VOLUME] ?: 0.55f,
            musicEnabled = prefs[Keys.MUSIC_ENABLED] ?: false,
            musicVolume = prefs[Keys.MUSIC_VOLUME] ?: 0.45f,
            sfxEnabled = prefs[Keys.SFX_ENABLED] ?: true,
            autoCloudBackup = prefs[Keys.AUTO_BACKUP] ?: false,
            safCloudTreeUri = prefs[Keys.SAF_CLOUD_URI] ?: "",
            safCloudFolderName = prefs[Keys.SAF_CLOUD_NAME] ?: "",
            webDavUrl = prefs[Keys.WEBDAV_URL] ?: "",
            webDavUsername = prefs[Keys.WEBDAV_USER] ?: "",
            webDavPassword = prefs[Keys.WEBDAV_PASS] ?: "",
            lastCloudSyncMillis = prefs[Keys.LAST_SYNC] ?: 0L,
            showHiddenFiles = prefs[Keys.SHOW_HIDDEN] ?: false
        )
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.beachDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setSurfEnabled(enabled: Boolean) {
        context.beachDataStore.edit { it[Keys.SURF_ENABLED] = enabled }
    }

    suspend fun setSurfVolume(volume: Float) {
        context.beachDataStore.edit { it[Keys.SURF_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setMusicEnabled(enabled: Boolean) {
        context.beachDataStore.edit { it[Keys.MUSIC_ENABLED] = enabled }
    }

    suspend fun setMusicVolume(volume: Float) {
        context.beachDataStore.edit { it[Keys.MUSIC_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setSfxEnabled(enabled: Boolean) {
        context.beachDataStore.edit { it[Keys.SFX_ENABLED] = enabled }
    }

    suspend fun setAutoCloudBackup(enabled: Boolean) {
        context.beachDataStore.edit { it[Keys.AUTO_BACKUP] = enabled }
    }

    suspend fun setSafCloudTarget(uri: String, folderName: String) {
        context.beachDataStore.edit {
            it[Keys.SAF_CLOUD_URI] = uri
            it[Keys.SAF_CLOUD_NAME] = folderName
        }
    }

    suspend fun setWebDavConfig(url: String, username: String, password: String) {
        context.beachDataStore.edit {
            it[Keys.WEBDAV_URL] = url.trim()
            it[Keys.WEBDAV_USER] = username.trim()
            it[Keys.WEBDAV_PASS] = password
        }
    }

    suspend fun disconnectCloud() {
        context.beachDataStore.edit {
            it[Keys.SAF_CLOUD_URI] = ""
            it[Keys.SAF_CLOUD_NAME] = ""
            it[Keys.WEBDAV_URL] = ""
            it[Keys.WEBDAV_USER] = ""
            it[Keys.WEBDAV_PASS] = ""
            it[Keys.AUTO_BACKUP] = false
        }
    }

    suspend fun updateLastSyncTime(millis: Long) {
        context.beachDataStore.edit { it[Keys.LAST_SYNC] = millis }
    }

    suspend fun setShowHiddenFiles(enabled: Boolean) {
        context.beachDataStore.edit { it[Keys.SHOW_HIDDEN] = enabled }
    }
}
