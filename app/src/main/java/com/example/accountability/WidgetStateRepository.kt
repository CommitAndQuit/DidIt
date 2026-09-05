package com.example.accountability

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object WidgetStateRepository {
    private val WIDGET_MESSAGE_KEY = stringPreferencesKey("widget_message")

    val Context.widgetMessageFlow: Flow<String?>
        get() = dataStore.data.map { preferences ->
            preferences[WIDGET_MESSAGE_KEY]
        }

    suspend fun updateWidgetMessage(context: Context, message: String?) {
        context.dataStore.edit { preferences ->
            if (message == null) {
                preferences.remove(WIDGET_MESSAGE_KEY)
            } else {
                preferences[WIDGET_MESSAGE_KEY] = message
            }
        }
    }
}
