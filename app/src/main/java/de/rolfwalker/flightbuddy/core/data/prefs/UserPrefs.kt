package de.rolfwalker.flightbuddy.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import de.rolfwalker.flightbuddy.core.model.ThemeMode
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.normalizeAppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("flightbuddy_prefs")

data class UserPrefs(
    val theme: ThemeMode = ThemeMode.DARK,
    val language: String = "de",
    val units: Units = Units.METRIC,
    val mapStyle: MapStyleId = MapStyleId.DARK,
    val gateChanges: Boolean = true,
    val delaysStatus: Boolean = true,
    val preflight2h: Boolean = true,
    val gateClose: Boolean = true,
    val arrivalSoon: Boolean = true,
    val objectAlerts: Boolean = true,
    val squawkAlerts: Boolean = true,
    val liveNotification: Boolean = true,
    val batteryPromptShown: Boolean = false,
)

class PrefsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val units = stringPreferencesKey("units")
        val mapStyle = stringPreferencesKey("map_style")
        val gate = booleanPreferencesKey("gate")
        val delays = booleanPreferencesKey("delays")
        val preflight = booleanPreferencesKey("preflight")
        val gateClose = booleanPreferencesKey("gate_close")
        val arrival = booleanPreferencesKey("arrival")
        val objects = booleanPreferencesKey("objects")
        val squawk = booleanPreferencesKey("squawk")
        val liveNotification = booleanPreferencesKey("live_notification")
        val battery = booleanPreferencesKey("battery_prompt")
    }

    val flow: Flow<UserPrefs> = context.dataStore.data.map { it.toPrefs() }

    suspend fun snapshot(): UserPrefs = flow.first()

    suspend fun update(transform: (UserPrefs) -> UserPrefs) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toPrefs())
            prefs[Keys.theme] = next.theme.name
            prefs[Keys.language] = normalizeAppLanguage(next.language)
            prefs[Keys.units] = next.units.name
            prefs[Keys.mapStyle] = next.mapStyle.name
            prefs[Keys.gate] = next.gateChanges
            prefs[Keys.delays] = next.delaysStatus
            prefs[Keys.preflight] = next.preflight2h
            prefs[Keys.gateClose] = next.gateClose
            prefs[Keys.arrival] = next.arrivalSoon
            prefs[Keys.objects] = next.objectAlerts
            prefs[Keys.squawk] = next.squawkAlerts
            prefs[Keys.liveNotification] = next.liveNotification
            prefs[Keys.battery] = next.batteryPromptShown
        }
    }

    private fun Preferences.toPrefs() = UserPrefs(
        theme = runCatching { ThemeMode.valueOf(this[Keys.theme] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
        language = normalizeAppLanguage(this[Keys.language]),
        units = runCatching { Units.valueOf(this[Keys.units] ?: "METRIC") }.getOrDefault(Units.METRIC),
        mapStyle = runCatching { MapStyleId.valueOf(this[Keys.mapStyle] ?: "DARK") }.getOrDefault(MapStyleId.DARK),
        gateChanges = this[Keys.gate] ?: true,
        delaysStatus = this[Keys.delays] ?: true,
        preflight2h = this[Keys.preflight] ?: true,
        gateClose = this[Keys.gateClose] ?: true,
        arrivalSoon = this[Keys.arrival] ?: true,
        objectAlerts = this[Keys.objects] ?: true,
        squawkAlerts = this[Keys.squawk] ?: true,
        liveNotification = this[Keys.liveNotification] ?: true,
        batteryPromptShown = this[Keys.battery] ?: false,
    )
}
