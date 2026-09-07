package de.rolfwalker.flightbuddy

import android.app.Application
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

class FlightBuddyApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
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
