package de.rolfwalker.flightbuddy.core.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import de.rolfwalker.flightbuddy.core.normalizeAppLanguage
import java.util.Locale

fun Context.withAppLocale(language: String): Context {
    val locale = Locale.forLanguageTag(normalizeAppLanguage(language))
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))
    return createConfigurationContext(config)
}

fun deviceFirstName(context: Context): String? {
    val raw = runCatching {
        context.getSystemService(UserManager::class.java)?.userName?.trim()
    }.getOrNull().orEmpty()
    if (raw.isBlank()) return null
    val first = raw.split(Regex("\\s+")).firstOrNull().orEmpty()
    val skip = setOf("owner", "besitzer", "user", "benutzer")
    if (first.length < 2 || first.lowercase(Locale.ROOT) in skip) return null
    return first.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

fun initialsOf(name: String?): String {
    val parts = name?.trim()?.split(Regex("\\s+")).orEmpty().filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).joinToString("") { it.first().uppercase() }
}

@Composable
fun LocalizedContent(language: String, content: @Composable () -> Unit) {
    val tag = normalizeAppLanguage(language)
    val base = LocalContext.current
    val localized = remember(tag, base) { base.withAppLocale(tag) }
    key(tag) {
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides localized.resources.configuration,
            content = content,
        )
    }
}
