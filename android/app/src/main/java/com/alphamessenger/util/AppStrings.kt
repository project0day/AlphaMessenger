package com.alphamessenger.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/** Global string resolver for non-composable converters that hold no Context
 *  (e.g. frozen media-conversion code that may not change signatures). Installed
 *  once in Application.onCreate; resolves in the app-chosen language, mirroring
 *  [Strings]. Returns "" if called before [install] (no UI runs that early). */
object AppStrings {
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val context = appContext ?: return ""
        val locales = AppCompatDelegate.getApplicationLocales()
        val ctx = if (locales.isEmpty) context else context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
            }
        )
        return ctx.getString(id, *args)
    }

    /** String-array lookup (e.g. month-name arrays) in the app locale. */
    fun getStringArray(@ArrayRes id: Int): Array<String> {
        val context = appContext ?: return emptyArray()
        val locales = AppCompatDelegate.getApplicationLocales()
        val ctx = if (locales.isEmpty) context else context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
            }
        )
        return ctx.resources.getStringArray(id)
    }
}
