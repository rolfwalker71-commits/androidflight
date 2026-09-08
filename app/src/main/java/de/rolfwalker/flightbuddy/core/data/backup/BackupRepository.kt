package de.rolfwalker.flightbuddy.core.data.backup

import android.content.Context
import android.net.Uri
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupImportResult(
    val flightCount: Int,
    val settings: UserPrefs,
)

class BackupRepository(
    private val keysStore: KeysStore,
    private val flights: FlightRepository,
    private val prefsStore: PrefsStore,
) {
    suspend fun writeExport(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val json = BackupCodec.encode(
            DeviceBackup(
                keys = keysStore.snapshot(),
                settings = prefsStore.snapshot(),
                flights = flights.listFlights(),
            ),
        )
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("cannot write backup")
    }

    suspend fun readImport(context: Context, uri: Uri): BackupImportResult = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("cannot read backup")
        val backup = BackupCodec.decode(json)
        keysStore.save(backup.keys)
        prefsStore.update { backup.settings }
        val count = flights.importBackupFlights(backup.flights)
        BackupImportResult(flightCount = count, settings = backup.settings)
    }
}
