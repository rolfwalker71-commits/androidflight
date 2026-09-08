package de.rolfwalker.flightbuddy

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.rolfwalker.flightbuddy.core.data.AirportSeeder
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.di.appModule
import de.rolfwalker.flightbuddy.tracking.DailyAdvanceWorker
import de.rolfwalker.flightbuddy.tracking.TrackerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre

class FlightBuddyApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
        }
        // One-arg init uses WellKnownTileServer.MapLibre (no key). The two-arg
        // overload defaults to MapTiler and shows an API-key prompt.
        MapLibre.getInstance(this)
        startKoin {
            androidContext(this@FlightBuddyApp)
            modules(appModule)
        }
        scope.launch {
            get<KeysStore>().seedFromBuildConfigIfEmpty()
            AirportSeeder.seed(this@FlightBuddyApp, get())
            TrackerController.sync(this@FlightBuddyApp)
            DailyAdvanceWorker.enqueue(this@FlightBuddyApp)
        }
    }
}
