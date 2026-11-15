package com.alphamessenger.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ══════════════════════════════════════════════════════════════════════
// ThemeViewModel — persists and exposes the selected theme variant
// ══════════════════════════════════════════════════════════════════════

private val KEY_THEME_VARIANT = stringPreferencesKey("theme_variant")
private val KEY_UI_FONT = stringPreferencesKey("ui_font_size")
private val KEY_MSG_FONT = stringPreferencesKey("msg_font_size")
private val KEY_MSG_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("msg_font_scale")
private val KEY_BUBBLE_RADIUS = androidx.datastore.preferences.core.floatPreferencesKey("bubble_radius")
private val KEY_WALLPAPER_ID = stringPreferencesKey("chat_wallpaper_id")
private val KEY_WALLPAPER_URI = stringPreferencesKey("chat_wallpaper_uri")
/** The backgrounds the user made, newest first (ids, ";"-joined). */
private val KEY_CUSTOM_WALLPAPERS = stringPreferencesKey("custom_wallpapers")
/** The user's own themes: one per line, "id|code" ([CustomTheme.encode]). */
private val KEY_CUSTOM_THEMES = stringPreferencesKey("custom_themes")
/** The own theme in use (its id); absent = a built-in theme or the device's mode. */
private val KEY_ACTIVE_CUSTOM = stringPreferencesKey("active_custom_theme")
/** The pair the quick switch moves between: "v:<variant>" or "c:<own theme id>". */
private val KEY_DAY_THEME = stringPreferencesKey("day_theme")
private val KEY_NIGHT_THEME = stringPreferencesKey("night_theme")

private fun parseCustomThemes(raw: String?): List<CustomTheme> =
    raw.orEmpty().lines().mapNotNull { line ->
        val id = line.substringBefore('|', "")
        if (id.isBlank()) null else CustomTheme.decode(line.substringAfter('|'), id)
    }

/**
 * ViewModel that persists the user's chosen theme to DataStore
 * and exposes it as a [StateFlow]<[AlphaThemeVariant]>.
 *
 * Usage in a composable:
 * ```
 * val themeVariant by themeViewModel.themeVariant.collectAsStateWithLifecycle()
 * AlphaTheme(variant = themeVariant) { … }
 * ```
 *
 * To change the theme:
 * ```
 * themeViewModel.setTheme(AlphaThemeVariant.HACKTIVIST)
 * ```
 */
/** Themes used while the app follows the device's dark/light mode. */
val SYSTEM_DARK_THEME = AlphaThemeVariant.GREY
val SYSTEM_LIGHT_THEME = AlphaThemeVariant.DAYLIGHT

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    /**
     * The currently selected theme variant, read from DataStore.
     * Falls back to [AlphaThemeVariant.DEFAULT] (VEIL) if no preference is stored.
     */
    /** The device's dark mode, pushed in by the Activity (see [onSystemDarkChanged]). */
    private val systemDark = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** The user's own themes, in the order made. */
    val customThemes: StateFlow<List<CustomTheme>> = dataStore.data
        .map { prefs -> parseCustomThemes(prefs[KEY_CUSTOM_THEMES]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The theme in effect: an explicit choice, else the day or night one of the pair by the device's mode. */
    private val resolved = kotlinx.coroutines.flow.combine(dataStore.data, systemDark) { prefs, dark ->
        val customs = parseCustomThemes(prefs[KEY_CUSTOM_THEMES])
        val custom = prefs[KEY_ACTIVE_CUSTOM]?.let { id -> customs.firstOrNull { it.id == id } }
        val variant = prefs[KEY_THEME_VARIANT]?.let { runCatching { AlphaThemeVariant.valueOf(it) }.getOrDefault(AlphaThemeVariant.DEFAULT) }
        when {
            custom != null -> custom.base to custom
            variant != null -> variant to null
            else -> slotTheme(prefs[if (dark) KEY_NIGHT_THEME else KEY_DAY_THEME], customs, night = dark)
        }
    }

    /** The own theme in use, or null. */
    val activeCustom: StateFlow<CustomTheme?> = resolved.map { it.second }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** No explicit choice: the pair follows the device's dark/light mode. */
    val followsSystem: StateFlow<Boolean> = dataStore.data.map { it[KEY_THEME_VARIANT] == null && it[KEY_ACTIVE_CUSTOM] == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The built-in variant in effect; with an own theme, the base it is built on. */
    val themeVariant: StateFlow<AlphaThemeVariant> = resolved.map { it.first }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AlphaThemeVariant.DEFAULT
    )

    fun onSystemDarkChanged(dark: Boolean) { systemDark.value = dark }

    /**
     * Persist a new theme selection to DataStore.
     */
    fun setTheme(variant: AlphaThemeVariant) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_THEME_VARIANT] = variant.name
                prefs.remove(KEY_ACTIVE_CUSTOM)
                prefs[if (variant.isLight) KEY_DAY_THEME else KEY_NIGHT_THEME] = "v:" + variant.name
            }
        }
    }

    // ── Day / night pair ──────────────────────────────────────────────
    // A theme chosen in settings becomes the day or the night one by its own
    // lightness; the quick switch puts on the other of the pair.

    /** The theme now on is a dark one. */
    val isDark: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(themeVariant, activeCustom) { v, c -> !(c?.isLight ?: v.isLight) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The day and the night theme, as their names ("" = the default one). */
    val dayNight: StateFlow<Pair<String, String>> = dataStore.data
        .map { prefs -> slotName(prefs, KEY_DAY_THEME, SYSTEM_LIGHT_THEME) to slotName(prefs, KEY_NIGHT_THEME, SYSTEM_DARK_THEME) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SYSTEM_LIGHT_THEME.displayName to SYSTEM_DARK_THEME.displayName)

    private fun slotName(prefs: Preferences, key: Preferences.Key<String>, fallback: AlphaThemeVariant): String {
        val v = prefs[key].orEmpty()
        return when {
            v.startsWith("c:") -> parseCustomThemes(prefs[KEY_CUSTOM_THEMES]).firstOrNull { it.id == v.drop(2) }?.name
            v.startsWith("v:") -> runCatching { AlphaThemeVariant.valueOf(v.drop(2)) }.getOrNull()?.displayName
            else -> null
        } ?: fallback.displayName
    }

    /** What a pair slot holds, if it still exists and is of the wanted kind; else the default one. */
    private fun slotTheme(slot: String?, customs: List<CustomTheme>, night: Boolean): Pair<AlphaThemeVariant, CustomTheme?> {
        val v = slot.orEmpty()
        customs.firstOrNull { v == "c:" + it.id && it.isLight != night }?.let { return it.base to it }
        val variant = v.removePrefix("v:").takeIf { v.startsWith("v:") }
            ?.let { runCatching { AlphaThemeVariant.valueOf(it) }.getOrNull() }
            ?.takeIf { it.isLight != night }
        return (variant ?: if (night) SYSTEM_DARK_THEME else SYSTEM_LIGHT_THEME) to null
    }

    /** Put on the night theme if a light one is on, the day theme otherwise. */
    fun toggleDayNight() {
        val toNight = !isDark.value
        val leaving = activeCustom.value?.let { "c:" + it.id } ?: ("v:" + themeVariant.value.name)
        viewModelScope.launch {
            dataStore.edit { prefs ->
                // The theme being left is this user's one of its kind (it may predate the pair).
                prefs[if (toNight) KEY_DAY_THEME else KEY_NIGHT_THEME] = leaving
                val (variant, custom) = slotTheme(prefs[if (toNight) KEY_NIGHT_THEME else KEY_DAY_THEME], parseCustomThemes(prefs[KEY_CUSTOM_THEMES]), night = toNight)
                if (custom != null) {
                    prefs[KEY_ACTIVE_CUSTOM] = custom.id
                } else {
                    prefs[KEY_THEME_VARIANT] = variant.name
                    prefs.remove(KEY_ACTIVE_CUSTOM)
                }
            }
        }
    }

    /** Back to following the device's dark/light mode, with the theme on now kept as its kind's one. */
    fun followSystem() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                keepAsPair(prefs, overwrite = true)
                prefs.remove(KEY_THEME_VARIANT); prefs.remove(KEY_ACTIVE_CUSTOM)
            }
        }
    }

    /** The explicit theme on now goes into its slot of the pair ([overwrite] = false: only into an empty one). */
    private fun keepAsPair(prefs: androidx.datastore.preferences.core.MutablePreferences, overwrite: Boolean) {
        val custom = prefs[KEY_ACTIVE_CUSTOM]?.let { id -> parseCustomThemes(prefs[KEY_CUSTOM_THEMES]).firstOrNull { it.id == id } }
        val variant = prefs[KEY_THEME_VARIANT]?.let { runCatching { AlphaThemeVariant.valueOf(it) }.getOrNull() }
        val (key, value) = when {
            custom != null -> kotlin.Pair(if (custom.isLight) KEY_DAY_THEME else KEY_NIGHT_THEME, "c:" + custom.id)
            variant != null -> kotlin.Pair(if (variant.isLight) KEY_DAY_THEME else KEY_NIGHT_THEME, "v:" + variant.name)
            else -> return
        }
        if (overwrite || prefs[key] == null) prefs[key] = value
    }

    // A theme chosen before the pair existed becomes its kind's one, so an update
    // keeps what the user had instead of falling back to the defaults.
    init { viewModelScope.launch { dataStore.edit { keepAsPair(it, overwrite = false) } } }

    // ── Own themes ────────────────────────────────────────────────────

    /** Keep [t] (new or changed; at most 20) and put it on. */
    fun saveCustomTheme(t: CustomTheme) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val list = parseCustomThemes(prefs[KEY_CUSTOM_THEMES])
                val next = if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else (list + t).takeLast(20)
                prefs[KEY_CUSTOM_THEMES] = next.joinToString("\n") { it.id + "|" + it.encode() }
                prefs[KEY_ACTIVE_CUSTOM] = t.id
                prefs[if (t.isLight) KEY_DAY_THEME else KEY_NIGHT_THEME] = "c:" + t.id
            }
        }
    }

    /** Put on an own theme already kept. */
    fun useCustomTheme(id: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_ACTIVE_CUSTOM] = id
                parseCustomThemes(prefs[KEY_CUSTOM_THEMES]).firstOrNull { it.id == id }
                    ?.let { prefs[if (it.isLight) KEY_DAY_THEME else KEY_NIGHT_THEME] = "c:" + id }
            }
        }
    }

    /** Forget an own theme (if it is on, the built-in one chosen before comes back). */
    fun deleteCustomTheme(id: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_CUSTOM_THEMES] = parseCustomThemes(prefs[KEY_CUSTOM_THEMES]).filter { it.id != id }
                    .joinToString("\n") { it.id + "|" + it.encode() }
                if (prefs[KEY_ACTIVE_CUSTOM] == id) prefs.remove(KEY_ACTIVE_CUSTOM)
                // A day or night slot that named it falls back to the built-in one of its kind.
                for (slot in listOf(KEY_DAY_THEME, KEY_NIGHT_THEME)) if (prefs[slot] == "c:" + id) prefs.remove(slot)
            }
        }
    }

    // ── UI font size (global interface typography scale) ──────────────

    val uiFontSize: StateFlow<FontSize> = dataStore.data
        .map { prefs ->
            prefs[KEY_UI_FONT]?.let { runCatching { FontSize.valueOf(it) }.getOrDefault(FontSize.DEFAULT) }
                ?: FontSize.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontSize.DEFAULT)

    fun setUiFontSize(size: FontSize) {
        viewModelScope.launch { dataStore.edit { it[KEY_UI_FONT] = size.name } }
    }

    // ── Message text size (chat bubbles only) ─────────────────────────

    /** A free scale from the slider; before it existed, the old step chosen. */
    val messageFontScale: StateFlow<Float> = dataStore.data
        .map { prefs ->
            prefs[KEY_MSG_SCALE] ?: prefs[KEY_MSG_FONT]?.let { runCatching { FontSize.valueOf(it).factor }.getOrNull() } ?: 1f
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    fun setMessageFontScale(scale: Float) {
        viewModelScope.launch { dataStore.edit { it[KEY_MSG_SCALE] = scale.coerceIn(MSG_FONT_MIN, MSG_FONT_MAX) } }
    }

    // ── Bubble corner radius ──────────────────────────────────────────

    val bubbleRadius: StateFlow<Float> = dataStore.data
        .map { it[KEY_BUBBLE_RADIUS] ?: BUBBLE_RADIUS_DEFAULT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BUBBLE_RADIUS_DEFAULT)

    fun setBubbleRadius(dp: Float) {
        viewModelScope.launch { dataStore.edit { it[KEY_BUBBLE_RADIUS] = dp.coerceIn(BUBBLE_RADIUS_MIN, BUBBLE_RADIUS_MAX) } }
    }

    // ── Chat wallpaper (default / solid / gradient / gallery) ─────────

    val chatWallpaper: StateFlow<ChatWallpaper> = dataStore.data
        .map { prefs -> resolveWallpaper(prefs[KEY_WALLPAPER_ID], prefs[KEY_WALLPAPER_URI]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatWallpaper.Default)

    /** "Mine": the backgrounds the user made, newest first. */
    val customWallpapers: StateFlow<List<ChatWallpaper.Custom>> = dataStore.data
        .map { prefs -> prefs[KEY_CUSTOM_WALLPAPERS].orEmpty().split(';').mapNotNull { parseCustomWallpaper(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keep a made background in "Mine" (at most 12) and put it on. */
    fun saveCustomWallpaper(w: ChatWallpaper.Custom) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val old = prefs[KEY_CUSTOM_WALLPAPERS].orEmpty().split(';').filter { it.isNotBlank() }
                prefs[KEY_CUSTOM_WALLPAPERS] = (listOf(w.id) + old.filter { it != w.id }).take(12).joinToString(";")
                prefs[KEY_WALLPAPER_ID] = w.id
                prefs.remove(KEY_WALLPAPER_URI)
            }
        }
    }

    /** Take one out of "Mine" (if it is on, the chat goes back to the default). */
    fun deleteCustomWallpaper(w: ChatWallpaper.Custom) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val old = prefs[KEY_CUSTOM_WALLPAPERS].orEmpty().split(';').filter { it.isNotBlank() }
                prefs[KEY_CUSTOM_WALLPAPERS] = old.filter { it != w.id }.joinToString(";")
                if (prefs[KEY_WALLPAPER_ID] == w.id) prefs[KEY_WALLPAPER_ID] = ChatWallpaper.DEFAULT_ID
            }
        }
    }

    fun setChatWallpaper(wallpaper: ChatWallpaper) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_WALLPAPER_ID] = wallpaper.persistId()
                if (wallpaper is ChatWallpaper.Gallery) {
                    prefs[KEY_WALLPAPER_URI] = wallpaper.uri
                } else {
                    prefs.remove(KEY_WALLPAPER_URI)
                }
            }
        }
    }
}
