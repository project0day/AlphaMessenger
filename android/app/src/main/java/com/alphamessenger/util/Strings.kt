package com.alphamessenger.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves string resources in the app language chosen by the user (or the system language). */
@Singleton
class Strings @Inject constructor(@ApplicationContext private val context: Context) {

    private fun localized(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return context.createConfigurationContext(config)
    }

    fun get(@StringRes id: Int, vararg args: Any): String = localized().getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        localized().resources.getQuantityString(id, count, *args)
}

/** An error's reason as it may be shown: a long one (a whole SQL batch) would stall layout. */
fun Throwable.shortReason(): String = message.orEmpty().take(300)

/** The core refused an add: the group is at its member limit. */
fun Throwable.isGroupFull(): Boolean = message?.contains("group full", ignoreCase = true) == true

/** Members a group or channel holds (the core's MAX_GROUP_MEMBERS). */
const val MAX_GROUP_MEMBERS = 1000
