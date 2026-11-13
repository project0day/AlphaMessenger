package com.alphamessenger.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection

/** Language of the app. Room below for message translation later. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .substringBefore(',').substringBefore('-')
    // Native names: a person who can't read the current language still finds theirs.
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "en" to "English",
        "ru" to "Русский",
        "fr" to "Français",
        "es" to "Español",
    )
    OptionScreen(title = stringResource(R.string.settings_language_title), onBack = onBack) {
        OptionSection(stringResource(R.string.language_section_app))
        OptionGroup {
            options.forEachIndexed { i, (tag, label) ->
                if (i > 0) OptionDivider()
                OptionChoiceRow(label, null, current == tag) {
                    val list = if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    else androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(list)
                }
            }
        }
        OptionNote(stringResource(R.string.language_note))
    }
}
