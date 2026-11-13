package com.alphamessenger.media

import com.alphamessenger.R

/**
 * An attachment's weight at all three stages of its life.
 *
 * Three numbers, not one, because these are three DIFFERENT quantities that
 * must not be substituted for one another:
 *
 *  - [sourceBytes] — how much what the person picked weighs. Known at once.
 *  - [estimatedOutputBytes] — how much the result will weigh. Computed from
 *    the export plan before any work; the editor needs it to show the
 *    segment's weight.
 *  - [actualOutputBytes] — how much the result ACTUALLY weighs. Appears only
 *    after the finished file is verified.
 *
 * Mixing the first two with the third caused "first 0/350 MB, then 0/65 MB":
 * the upload denominator got the sources' weight because the result's did
 * not exist yet. The rule is now simple and checkable: **only
 * [actualOutputBytes] takes part in network progress**, and until it exists
 * the progress shows no numbers at all.
 */
data class MediaSizeInfo(
    val sourceBytes: Long = 0L,
    val estimatedOutputBytes: Long = 0L,
    val actualOutputBytes: Long? = null,
) {
    /** What to show the person right now. */
    val display: Long get() = actualOutputBytes ?: estimatedOutputBytes

    /** The number is measured, not computed — can be shown without "about". */
    val isExact: Boolean get() = actualOutputBytes != null

    /** Bytes for the NETWORK progress. No file yet — no bytes. */
    val forTransfer: Long get() = actualOutputBytes ?: 0L

    fun measured(bytes: Long): MediaSizeInfo = copy(actualOutputBytes = bytes)
}

/**
 * The ONE weight format in the UI.
 *
 * There used to be six, agreeing neither in vocabulary nor in precision: the
 * same file could show Russian units in the bubble, "65.0 MB" in the overlay
 * and Russian units again in the editor. To a user that reads as different
 * numbers about different things.
 */
object MediaSizeFormat {

    private const val KB = 1024.0

    /** Units resolved from resources in the app language by [install].
     *  Null only before [install] ran — unreachable in practice because
     *  AlphaMessengerApp.onCreate installs them at process start. */
    @Volatile
    private var installed: MediaSizeUnits? = null

    private class MediaSizeUnits(
        val locale: java.util.Locale,
        val bytes: String,
        val kb: String,
        val mb: String,
        val gb: String,
    )

    /** Loads the unit patterns in the app-chosen language; mirrors
     *  util.Strings localization (per-app locales via AppCompatDelegate). */
    fun install(context: android.content.Context) {
        val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val ctx = if (locales.isEmpty) context else context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
            }
        )
        val res = ctx.resources
        installed = MediaSizeUnits(
            locale = res.configuration.locales[0],
            bytes = res.getString(R.string.size_unit_bytes),
            kb = res.getString(R.string.size_unit_kb),
            mb = res.getString(R.string.size_unit_mb),
            gb = res.getString(R.string.size_unit_gb),
        )
    }

    /**
     * The locale is pinned to the UI language, not taken from the system.
     *
     * The unit words here are Russian, so the number must be written the Russian
     * way — with a decimal comma. The former formatters called `String.format`
     * without a locale, i.e. depended on the PHONE's language: an English system
     * produced a decimal point beside Russian unit words, and a system with a
     * different numeral system would put outright wrong digits into the weight.
     */
    private val LOCALE = java.util.Locale("ru")

    fun short(bytes: Long): String {
        val units = installed
        // Fallback (pre-install): hardcoded Russian units. Unreachable in
        // practice once AlphaMessengerApp.onCreate has run install().
        if (units == null) {
            if (bytes <= 0L) return "0 Б"
            val b = bytes.toDouble()
            return when {
                b < KB -> "$bytes Б"
                b < KB * KB -> String.format(LOCALE, "%.0f КБ", b / KB)
                b < KB * KB * KB -> String.format(LOCALE, "%.1f МБ", b / (KB * KB))
                else -> String.format(LOCALE, "%.2f ГБ", b / (KB * KB * KB))
            }
        }
        if (bytes <= 0L) return String.format(units.locale, units.bytes, 0L)
        val b = bytes.toDouble()
        return when {
            b < KB -> String.format(units.locale, units.bytes, bytes)
            b < KB * KB -> String.format(units.locale, units.kb, b / KB)
            b < KB * KB * KB -> String.format(units.locale, units.mb, b / (KB * KB))
            else -> String.format(units.locale, units.gb, b / (KB * KB * KB))
        }
    }

    /** "about N" — when the weight is computed, not measured. */
    fun approx(bytes: Long): String = "≈ ${short(bytes)}"

    /** Weight by state: measured is shown exactly, computed — with "about". */
    fun of(info: MediaSizeInfo): String =
        if (info.isExact) short(info.display) else approx(info.display)
}
