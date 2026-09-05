package com.example.accountability

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserProfileRepository(private val context: Context) {

    companion object {
        val USER_PROFILE_KEY = stringPreferencesKey("user_profile")
    }

    val userProfileFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[USER_PROFILE_KEY]
        }

    suspend fun saveUserProfile(profile: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_PROFILE_KEY] = profile
        }
    }
}
