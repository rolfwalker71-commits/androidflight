package de.rolfwalker.flightbuddy.core.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.rolfwalker.flightbuddy.BuildConfig
import de.rolfwalker.flightbuddy.core.network.AERO_API_MARKET_BASE_URL
import de.rolfwalker.flightbuddy.core.network.AERO_API_MARKET_HOST
import de.rolfwalker.flightbuddy.core.network.resolveAeroEndpoint
import de.rolfwalker.flightbuddy.core.network.withResolvedAeroDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ApiKeys(
    val openSkyUsername: String = "",
    val openSkyPassword: String = "",
    val aeroKey: String = "",
    val aeroBaseUrl: String = "",
    val aeroHost: String = "",
    val fr24Token: String = "",
    val fr24Enabled: Boolean = true,
    val fr24MinIntervalMs: Int = 180_000,
    val openSkyMinIntervalMs: Int = 90_000,
)

/** Sentinel stored/shown instead of OkHttp messages that echo a secret header value. */
const val INVALID_API_CREDENTIAL_CHARS = "INVALID_API_CREDENTIAL_CHARS"

/**
 * Strip paste / local.properties junk (newlines, quotes) so OkHttp headers stay valid.
 * OkHttp rejects header values containing `\n` / `\r`.
 */
fun sanitizeApiCredential(raw: String): String {
    var s = raw.replace("\r", "").replace("\n", "").trim()
    if (s.length >= 2) {
        val quote = s.first()
        if ((quote == '"' || quote == '\'') && s.last() == quote) {
            s = s.substring(1, s.lastIndex).replace("\r", "").replace("\n", "").trim()
        }
    }
    return s
}

fun ApiKeys.sanitized(): ApiKeys = copy(
    openSkyUsername = sanitizeApiCredential(openSkyUsername),
    openSkyPassword = sanitizeApiCredential(openSkyPassword),
    aeroKey = sanitizeApiCredential(aeroKey),
    aeroBaseUrl = sanitizeApiCredential(aeroBaseUrl),
    aeroHost = sanitizeApiCredential(aeroHost),
    fr24Token = sanitizeApiCredential(fr24Token),
)

fun isUnsafeHeaderError(message: String?): Boolean {
    val m = message.orEmpty()
    if (m.isBlank()) return false
    return m.contains("Unexpected char", ignoreCase = true) ||
        m.contains("x-api-market-key", ignoreCase = true) ||
        m.contains("x-magicapi-key", ignoreCase = true) ||
        m.contains("X-RapidAPI-Key", ignoreCase = true) ||
        m.contains("header value", ignoreCase = true) ||
        m.contains(INVALID_API_CREDENTIAL_CHARS)
}

fun isInvalidApiCredentialException(e: Throwable): Boolean = isUnsafeHeaderError(e.message)

fun redactProviderError(message: String?): String? {
    if (message.isNullOrBlank()) return null
    // Keep "HTTP 403: invalid x-api-market-key" visible; only hide OkHttp header-construction leaks.
    if (isUnsafeHeaderError(message) && !message.startsWith("HTTP ")) return INVALID_API_CREDENTIAL_CHARS
    return message
}

class KeysStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { encryptedPrefs() }
    private val _keys = MutableStateFlow(read())
    val keys: StateFlow<ApiKeys> = _keys

    private fun encryptedPrefs(): SharedPreferences {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app,
            "flightbuddy_secrets",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun readRaw(): ApiKeys = ApiKeys(
        openSkyUsername = prefs.getString("opensky_user", "") ?: "",
        openSkyPassword = prefs.getString("opensky_pass", "") ?: "",
        aeroKey = prefs.getString("aero_key", "") ?: "",
        aeroBaseUrl = prefs.getString("aero_base", "") ?: "",
        aeroHost = prefs.getString("aero_host", "") ?: "",
        fr24Token = prefs.getString("fr24_token", "") ?: "",
        fr24Enabled = prefs.getBoolean("fr24_enabled", true),
        fr24MinIntervalMs = prefs.getInt("fr24_min", 180_000),
        openSkyMinIntervalMs = prefs.getInt("opensky_min", 90_000),
    )

    private fun read(): ApiKeys = readRaw().sanitized()

    /**
     * Seed blank fields from BuildConfig (`local.properties`).
     * If a stored value still equals the last applied seed, refresh it when
     * BuildConfig changed. Never overwrite a user-edited different key/host.
     */
    suspend fun seedFromBuildConfigIfEmpty() = withContext(Dispatchers.IO) {
        val current = read()
        val seedKey = sanitizeApiCredential(BuildConfig.SEED_AERODATABOX_KEY)
        val seedHost = sanitizeApiCredential(BuildConfig.SEED_AERODATABOX_HOST)
        val seedBase = sanitizeApiCredential(BuildConfig.SEED_AERODATABOX_BASE_URL)
        val seedUser = sanitizeApiCredential(
            BuildConfig.SEED_OPENSKY_CLIENT_ID.ifBlank { BuildConfig.SEED_OPENSKY_USERNAME },
        )
        val seedPass = sanitizeApiCredential(
            BuildConfig.SEED_OPENSKY_CLIENT_SECRET.ifBlank { BuildConfig.SEED_OPENSKY_PASSWORD },
        )
        val seedFr24 = sanitizeApiCredential(BuildConfig.SEED_FR24_API_TOKEN)

        val nextKey = pickSeed(current.aeroKey, prefs.getString("aero_key_from_seed", ""), seedKey)
        val nextHost = pickSeed(current.aeroHost, prefs.getString("aero_host_from_seed", ""), seedHost)
            .ifBlank { seedHost.ifBlank { AERO_API_MARKET_HOST } }
        val nextBase = pickSeed(current.aeroBaseUrl, prefs.getString("aero_base_from_seed", ""), seedBase)
            .ifBlank { resolveAeroEndpoint(seedBase, nextHost).aeroBaseUrl.ifBlank { AERO_API_MARKET_BASE_URL } }
        val nextUser = pickSeed(current.openSkyUsername, prefs.getString("opensky_user_from_seed", ""), seedUser)
        val nextPass = pickSeed(current.openSkyPassword, prefs.getString("opensky_pass_from_seed", ""), seedPass)
        val nextFr24 = pickSeed(current.fr24Token, prefs.getString("fr24_token_from_seed", ""), seedFr24)

        val seeded = current.copy(
            openSkyUsername = nextUser,
            openSkyPassword = nextPass,
            aeroKey = nextKey,
            aeroHost = nextHost,
            aeroBaseUrl = nextBase,
            fr24Token = nextFr24,
            fr24Enabled = if (current.fr24Token.isBlank() && seedFr24.isNotBlank()) {
                BuildConfig.SEED_FR24_ENABLED != "false" && BuildConfig.SEED_FR24_ENABLED != "0"
            } else current.fr24Enabled,
            fr24MinIntervalMs = if (current.fr24Token.isBlank() && seedFr24.isNotBlank()) {
                BuildConfig.SEED_FR24_MIN_INTERVAL_MS
            } else current.fr24MinIntervalMs,
            openSkyMinIntervalMs = if (current.openSkyUsername.isBlank() && seedUser.isNotBlank()) {
                BuildConfig.SEED_OPENSKY_MIN_INTERVAL_MS
            } else current.openSkyMinIntervalMs,
        )
        prefs.edit()
            .putString("aero_key_from_seed", seedKey)
            .putString("aero_host_from_seed", seedHost)
            .putString("aero_base_from_seed", seedBase)
            .putString("opensky_user_from_seed", seedUser)
            .putString("opensky_pass_from_seed", seedPass)
            .putString("fr24_token_from_seed", seedFr24)
            .apply()
        val dirtyStored = readRaw() != current
        if (seeded != current || dirtyStored) save(seeded) else _keys.value = current.withResolvedAeroDefaults()
    }

    private fun pickSeed(current: String?, lastAppliedSeed: String?, newSeed: String): String {
        val stored = current.orEmpty()
        val last = lastAppliedSeed.orEmpty()
        if (newSeed.isBlank()) return stored
        if (stored.isBlank()) return newSeed
        if (stored == last && stored != newSeed) return newSeed
        // Short leftover placeholders (e.g. 9-char) must not block a real API.Market seed.
        if (newSeed.length >= 20 && stored.length in 1..12) return newSeed
        return stored
    }

    suspend fun save(next: ApiKeys) = withContext(Dispatchers.IO) {
        val resolved = next.sanitized().withResolvedAeroDefaults()
        prefs.edit()
            .putString("opensky_user", resolved.openSkyUsername)
            .putString("opensky_pass", resolved.openSkyPassword)
            .putString("aero_key", resolved.aeroKey)
            .putString("aero_base", resolved.aeroBaseUrl)
            .putString("aero_host", resolved.aeroHost)
            .putString("fr24_token", resolved.fr24Token)
            .putBoolean("fr24_enabled", resolved.fr24Enabled)
            .putInt("fr24_min", resolved.fr24MinIntervalMs)
            .putInt("opensky_min", resolved.openSkyMinIntervalMs)
            .apply()
        _keys.value = resolved
    }

    fun snapshot(): ApiKeys = _keys.value
}
