package io.github.hrmasss.iris.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hrmasss.iris.camera.Look
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    /** Empty means pick the best IR match automatically. */
    val preferredCameraId: String = "",
    val defaultLook: Look = Look.Natural,
    val shutterSound: Boolean = true,
    val grid: Boolean = false,
    val timerSeconds: Int = 0,
    val bakeLook: Boolean = true,
    /** Front-camera photos and videos are saved mirrored, exactly as the preview shows them. */
    val mirrorSelfies: Boolean = true,
    val volumeKeysShoot: Boolean = true,
)

private val Context.store by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val preferredCamera = stringPreferencesKey("preferred_camera")
        val defaultLook = stringPreferencesKey("default_look")
        val shutterSound = booleanPreferencesKey("shutter_sound")
        val grid = booleanPreferencesKey("grid")
        val timer = intPreferencesKey("timer_seconds")
        val bakeLook = booleanPreferencesKey("bake_look")
        val mirrorSelfies = booleanPreferencesKey("mirror_selfies")
        val volumeKeys = booleanPreferencesKey("volume_keys_shoot")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            preferredCameraId = p[Keys.preferredCamera].orEmpty(),
            defaultLook = Look.fromName(p[Keys.defaultLook]),
            shutterSound = p[Keys.shutterSound] ?: true,
            grid = p[Keys.grid] ?: false,
            timerSeconds = p[Keys.timer] ?: 0,
            bakeLook = p[Keys.bakeLook] ?: true,
            mirrorSelfies = p[Keys.mirrorSelfies] ?: true,
            volumeKeysShoot = p[Keys.volumeKeys] ?: true,
        )
    }

    suspend fun setPreferredCamera(id: String) = set(Keys.preferredCamera, id)
    suspend fun setDefaultLook(look: Look) = set(Keys.defaultLook, look.name)
    suspend fun setShutterSound(on: Boolean) = set(Keys.shutterSound, on)
    suspend fun setGrid(on: Boolean) = set(Keys.grid, on)
    suspend fun setTimer(seconds: Int) = set(Keys.timer, seconds)
    suspend fun setBakeLook(on: Boolean) = set(Keys.bakeLook, on)
    suspend fun setMirrorSelfies(on: Boolean) = set(Keys.mirrorSelfies, on)
    suspend fun setVolumeKeysShoot(on: Boolean) = set(Keys.volumeKeys, on)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }
}
