package com.example.accountability

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object WidgetStateRepository {
    private val WIDGET_MESSAGE_KEY = stringPreferencesKey("widget_message")
    private val WIDGET_ACK_KEY = stringPreferencesKey("widget_ack")

    /** The sarcastic prompt to nag the user with (null when there's nothing to track). */
    val Context.widgetMessageFlow: Flow<String?>
        get() = dataStore.data.map { it[WIDGET_MESSAGE_KEY] }

    /** A short acknowledgement shown after the user taps "done" (e.g. "Good job"). */
    val Context.widgetAckFlow: Flow<String?>
        get() = dataStore.data.map { it[WIDGET_ACK_KEY] }

    /** Sets a fresh prompt and clears any stale acknowledgement. */
    suspend fun updateWidgetMessage(context: Context, message: String?) {
        context.dataStore.edit { prefs ->
            prefs.remove(WIDGET_ACK_KEY)
            if (message == null) prefs.remove(WIDGET_MESSAGE_KEY)
            else prefs[WIDGET_MESSAGE_KEY] = message
        }
    }

    /** Records that the task was completed; the widget then shows [praise]. */
    suspend fun acknowledge(context: Context, praise: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(WIDGET_MESSAGE_KEY)
            prefs[WIDGET_ACK_KEY] = praise
        }
    }

    /** Clears everything back to the idle state. */
    suspend fun reset(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(WIDGET_MESSAGE_KEY)
            prefs.remove(WIDGET_ACK_KEY)
        }
    }
}
