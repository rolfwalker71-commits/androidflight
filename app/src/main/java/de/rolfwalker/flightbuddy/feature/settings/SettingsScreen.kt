package de.rolfwalker.flightbuddy.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.prefs.INVALID_API_CREDENTIAL_CHARS
import de.rolfwalker.flightbuddy.core.data.prefs.isUnsafeHeaderError
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import de.rolfwalker.flightbuddy.core.model.ThemeMode
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.feature.map.FlightMapView

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val ui by vm.state.collectAsState()
    val objects by vm.objects.collectAsState()
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Section(stringResource(R.string.settings_notifications)) {
            Pref(stringResource(R.string.settings_gate_changes), ui.prefs.gateChanges) { vm.updatePrefs { p -> p.copy(gateChanges = it) } }
            Pref(stringResource(R.string.settings_delays_status), ui.prefs.delaysStatus) { vm.updatePrefs { p -> p.copy(delaysStatus = it) } }
            Pref(stringResource(R.string.settings_preflight), ui.prefs.preflight2h) { vm.updatePrefs { p -> p.copy(preflight2h = it) } }
            Pref(stringResource(R.string.settings_gate_close), ui.prefs.gateClose) { vm.updatePrefs { p -> p.copy(gateClose = it) } }
            Pref(stringResource(R.string.settings_arrival_soon), ui.prefs.arrivalSoon) { vm.updatePrefs { p -> p.copy(arrivalSoon = it) } }
            Pref(stringResource(R.string.settings_object_alerts), ui.prefs.objectAlerts) { vm.updatePrefs { p -> p.copy(objectAlerts = it) } }
            Pref(stringResource(R.string.settings_squawk_alerts), ui.prefs.squawkAlerts) { vm.updatePrefs { p -> p.copy(squawkAlerts = it) } }
        }

        Section(stringResource(R.string.settings_battery)) {
            Text(stringResource(if (ignoring) R.string.settings_battery_ok else R.string.settings_battery_hint), style = MaterialTheme.typography.bodyMedium)
            if (!ignoring) {
                TextButton(onClick = {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))
                    context.startActivity(i)
                }) { Text(stringResource(R.string.settings_battery_exempt)) }
            }
        }

        Section(stringResource(R.string.map_objects)) {
            if (objects.isEmpty()) Text(stringResource(R.string.map_objects_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            objects.forEach { o ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.label ?: o.callsign} · ${o.starts}/${o.landings}")
                    TextButton(onClick = { vm.untrack(o.id) }) { Text(stringResource(R.string.map_untrack)) }
                }
            }
            Text(stringResource(R.string.map_object_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = ui.prefs.theme == mode,
                        onClick = { vm.updatePrefs { it.copy(theme = mode) } },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                },
                            )
                        },
                        shape = CircleShape,
                    )
                }
            }
            Text(stringResource(R.string.settings_language))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.prefs.language == "de", onClick = { vm.updatePrefs { it.copy(language = "de") } }, label = { Text(stringResource(R.string.settings_lang_de)) }, shape = CircleShape)
                FilterChip(selected = ui.prefs.language == "en", onClick = { vm.updatePrefs { it.copy(language = "en") } }, label = { Text(stringResource(R.string.settings_lang_en)) }, shape = CircleShape)
            }
            Text(stringResource(R.string.settings_units))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.prefs.units == Units.METRIC, onClick = { vm.updatePrefs { it.copy(units = Units.METRIC) } }, label = { Text(stringResource(R.string.settings_units_metric)) }, shape = CircleShape)
                FilterChip(selected = ui.prefs.units == Units.IMPERIAL, onClick = { vm.updatePrefs { it.copy(units = Units.IMPERIAL) } }, label = { Text(stringResource(R.string.settings_units_imperial)) }, shape = CircleShape)
            }
            Text(stringResource(R.string.settings_map_style))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapStyleId.entries.take(3).forEach { id ->
                    FilterChip(selected = ui.prefs.mapStyle == id, onClick = { vm.updatePrefs { it.copy(mapStyle = id) } }, label = { Text(id.name.lowercase()) }, shape = CircleShape)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapStyleId.entries.drop(3).forEach { id ->
                    FilterChip(selected = ui.prefs.mapStyle == id, onClick = { vm.updatePrefs { it.copy(mapStyle = id) } }, label = { Text(id.name.lowercase()) }, shape = CircleShape)
                }
            }
            TonalCard(Modifier.fillMaxWidth().height(140.dp)) {
                FlightMapView(emptyList(), null, emptyList(), ui.prefs.mapStyle, Modifier.fillMaxSize())
            }
        }

        Section(stringResource(R.string.settings_api_keys)) {
            OutlinedTextField(ui.keys.openSkyUsername, { v -> vm.updateKeys { it.copy(openSkyUsername = v) } }, label = { Text(stringResource(R.string.settings_opensky_user)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(ui.keys.openSkyPassword, { v -> vm.updateKeys { it.copy(openSkyPassword = v) } }, label = { Text(stringResource(R.string.settings_opensky_pass)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                ui.keys.aeroKey,
                { v -> vm.updateKeys { it.copy(aeroKey = v) } },
                label = { Text("AERODATABOX_KEY") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                ui.keys.aeroHost,
                { v -> vm.updateKeys { it.copy(aeroHost = v) } },
                label = { Text("AERODATABOX_HOST") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(ui.keys.fr24Token, { v -> vm.updateKeys { it.copy(fr24Token = v) } }, label = { Text("FR24_API_TOKEN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Pref(stringResource(R.string.settings_fr24_enable), ui.keys.fr24Enabled) { v -> vm.updateKeys { it.copy(fr24Enabled = v) } }
            Text(stringResource(R.string.settings_fr24_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(ui.keys.fr24MinIntervalMs.toString(), { v -> v.toIntOrNull()?.let { n -> vm.updateKeys { it.copy(fr24MinIntervalMs = n) } } }, label = { Text("FR24_MIN_INTERVAL_MS") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ui.keys.openSkyMinIntervalMs.toString(), { v -> v.toIntOrNull()?.let { n -> vm.updateKeys { it.copy(openSkyMinIntervalMs = n) } } }, label = { Text("OPENSKY_MIN_INTERVAL_MS") }, modifier = Modifier.fillMaxWidth())
            ProviderLine(stringResource(R.string.settings_opensky_name), ui.opensky)
            ProviderLine(stringResource(R.string.settings_aero_name), ui.aero)
            ProviderLine(stringResource(R.string.settings_fr24_name), ui.fr24)
        }

        Text(stringResource(R.string.settings_logo_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TonalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
        }
    }
}

@Composable
private fun Pref(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun ProviderLine(name: String, status: de.rolfwalker.flightbuddy.core.model.ProviderStatus) {
    Column {
        val detail = when {
            !status.configured -> stringResource(R.string.settings_provider_off)
            status.lastError == INVALID_API_CREDENTIAL_CHARS || isUnsafeHeaderError(status.lastError) ->
                stringResource(R.string.flight_invalid_api_key)
            status.lastError != null -> status.lastError!!
            else -> stringResource(R.string.settings_healthy)
        }
        Text("$name · $detail")
        status.remaining?.let { Text(stringResource(R.string.settings_remaining, it), style = MaterialTheme.typography.bodySmall) }
        status.lastCallAt?.let {
            Text(
                "HTTP ${status.lastStatusCode ?: "—"} · ${DateTimeFmt.dateTime(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, name = "API keys KEY HOST FR24")
@Composable
private fun SettingsApiKeysPreview() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField("", {}, label = { Text("OpenSky username / client id") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField("", {}, label = { Text("OpenSky password / secret") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField("", {}, label = { Text("AERODATABOX_KEY") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField("prod.api.market/api/v1/aedbx/aerodatabox", {}, label = { Text("AERODATABOX_HOST") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField("", {}, label = { Text("FR24_API_TOKEN") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
