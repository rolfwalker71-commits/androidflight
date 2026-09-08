package de.rolfwalker.flightbuddy.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** App language tags: `de` (default) or `en`. */
fun normalizeAppLanguage(language: String?): String {
    val tag = language?.trim().orEmpty()
    return if (tag.startsWith("en", ignoreCase = true)) "en" else "de"
}

fun currentAppLanguage(): String {
    val stored = AppCompatDelegate.getApplicationLocales()
        .toLanguageTags()
        .substringBefore(",")
        .substringBefore("-")
        .lowercase()
    return if (stored.isBlank()) "de" else normalizeAppLanguage(stored)
}

/**
 * Per-app locale (Android 13+ [android.app.LocaleManager] + AppCompat backport).
 * Recreates AppCompat activities so `stringResource` and dates follow `values` / `values-en`.
 */
fun applyAppLanguage(language: String?) {
    val tag = normalizeAppLanguage(language)
    val current = AppCompatDelegate.getApplicationLocales()
        .toLanguageTags()
        .substringBefore(",")
        .substringBefore("-")
        .lowercase()
    if (current == tag) return
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}
