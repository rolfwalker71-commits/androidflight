package de.rolfwalker.flightbuddy.di

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.AppDatabase
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.feature.alerts.AlertsViewModel
import de.rolfwalker.flightbuddy.feature.flights.AddFlightViewModel
import de.rolfwalker.flightbuddy.feature.flights.FlightDetailViewModel
import de.rolfwalker.flightbuddy.feature.flights.HomeViewModel
import de.rolfwalker.flightbuddy.feature.logbook.LogbookViewModel
import de.rolfwalker.flightbuddy.feature.map.MapViewModel
import de.rolfwalker.flightbuddy.feature.settings.SettingsViewModel
import de.rolfwalker.flightbuddy.feature.widget.WidgetUpdater
import de.rolfwalker.flightbuddy.tracking.AlertDispatcher
import de.rolfwalker.flightbuddy.tracking.PollEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flights ADD COLUMN baggageBelt TEXT")
    }
}

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "flightbuddy.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().flights() }
    single { get<AppDatabase>().positions() }
    single { get<AppDatabase>().alerts() }
    single { get<AppDatabase>().objects() }
    single { get<AppDatabase>().airports() }
    single { get<AppDatabase>().apiLogs() }
    single { KeysStore(androidContext()) }
    single { PrefsStore(androidContext()) }
    single { ProviderClients(get()) }
    single { FlightRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { AlertDispatcher(androidContext(), get()) }
    single { PollEngine(get(), get(), get(), get(), get()) }
    single { WidgetUpdater(androidContext()) }
    viewModel { HomeViewModel(get(), get()) }
    viewModel { AddFlightViewModel(get()) }
    viewModel { params -> FlightDetailViewModel(params.get(), get(), get(), get()) }
    viewModel { MapViewModel(get(), get(), get()) }
    viewModel { LogbookViewModel(get()) }
    viewModel { AlertsViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
}
