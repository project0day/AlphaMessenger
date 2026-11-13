package com.alphamessenger.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Privacy preferences that other layers must obey (same DataStore as Settings). */
@Singleton
class PrivacyPrefs @Inject constructor(dataStore: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** When false, the app never sends "typing"/"recording"/"paused" chat states. */
    val typingIndicators: StateFlow<Boolean> = dataStore.data
        .map { it[TYPING_INDICATORS] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    companion object {
        val TYPING_INDICATORS = booleanPreferencesKey("typing_indicators")
    }
}
