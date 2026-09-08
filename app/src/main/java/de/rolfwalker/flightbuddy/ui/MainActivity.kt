package de.rolfwalker.flightbuddy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import de.rolfwalker.flightbuddy.core.applyAppLanguage
import de.rolfwalker.flightbuddy.core.currentAppLanguage
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.ui.FlightBuddyTheme
import de.rolfwalker.flightbuddy.core.ui.LocalizedContent
import de.rolfwalker.flightbuddy.feature.settings.SettingsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {
    private val prefs: PrefsStore by inject()
    private val settingsVm: SettingsViewModel by viewModel()
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) settingsVm.exportTo(this, uri)
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) settingsVm.importFrom(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val openFlight = intent.getStringExtra(EXTRA_FLIGHT_ID)
        val openAlerts = intent.getBooleanExtra(EXTRA_OPEN_ALERTS, false)
        setContent {
            val userPrefs by prefs.flow.collectAsState(initial = UserPrefs(language = currentAppLanguage()))
            LaunchedEffect(userPrefs.language) { applyAppLanguage(userPrefs.language) }
            LocalizedContent(userPrefs.language) {
                FlightBuddyTheme(userPrefs.theme) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val tablet = maxWidth >= 600.dp
                        FlightBuddyRoot(
                            tablet = tablet,
                            openFlightId = openFlight,
                            openAlerts = openAlerts,
                            settingsVm = settingsVm,
                            onExportBackup = { exportBackup.launch(it) },
                            onImportBackup = {
                                importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FLIGHT_ID = "flight_id"
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}
