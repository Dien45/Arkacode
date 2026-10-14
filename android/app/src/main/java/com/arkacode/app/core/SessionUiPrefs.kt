package com.arkacode.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uiPrefsStore by preferencesDataStore(name = "arkacode_ui")

/**
 * Override tampilan daftar sesi (F1+): ganti nama + sembunyikan.
 * Server v1.19 tidak menyediakan rename/hapus sesi, jadi keduanya murni
 * lokal — data di server tidak diubah/dihapus.
 */
class SessionUiPrefs(private val context: Context) {
    suspend fun titleOverrides(): Map<String, String> =
        context.uiPrefsStore.data.map { prefs ->
            buildMap {
                for ((k, v) in prefs.asMap()) {
                    if (k.name.startsWith("title:") && v is String) {
                        put(k.name.removePrefix("title:"), v)
                    }
                }
            }
        }.first()

    suspend fun setTitle(id: String, title: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("title:$id")] = title }
    }

    suspend fun clearTitle(id: String) {
        context.uiPrefsStore.edit { it.remove(stringPreferencesKey("title:$id")) }
    }

    suspend fun hiddenIds(): Set<String> =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("hidden_sessions")]
                ?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }.first()

    suspend fun setHidden(id: String, hidden: Boolean) {
        context.uiPrefsStore.edit { prefs ->
            val cur = prefs[stringPreferencesKey("hidden_sessions")]
                ?.split("\n")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            if (hidden) cur.add(id) else cur.remove(id)
            prefs[stringPreferencesKey("hidden_sessions")] = cur.joinToString("\n")
        }
    }
}
