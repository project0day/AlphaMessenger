package com.alphamessenger.ui.settings

/** The app's languages by tag, each in its own name: someone who can't read the
 *  current language still finds theirs. */
internal val APP_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English",
    "ru" to "Русский",
    "fr" to "Français",
    "es" to "Español",
)

internal fun nativeLanguageName(tag: String): String? = APP_LANGUAGES.firstOrNull { it.first == tag }?.second
