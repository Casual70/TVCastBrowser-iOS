package com.tvcast.browser.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gestisce in modo persistente:
 * 1. La Pagina Principale (Home URL) e il relativo Dominio Principale per il blocco popup/redirect.
 * 2. L'attivazione/disattivazione del blocco rigoroso sul dominio principale (Domain Lock).
 * 3. La Cronologia recente limitata agli ultimi 20 siti visitati.
 */
class BrowserPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _homeUrl = MutableStateFlow(
        prefs.getString(KEY_HOME_URL, DEFAULT_HOME_URL) ?: DEFAULT_HOME_URL
    )
    val homeUrl: StateFlow<String> = _homeUrl.asStateFlow()

    private val _domainLockEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DOMAIN_LOCK_ENABLED, true)
    )
    val domainLockEnabled: StateFlow<Boolean> = _domainLockEnabled.asStateFlow()

    private val _recentHistory = MutableStateFlow(loadHistoryFromPrefs())
    val recentHistory: StateFlow<List<HistoryItem>> = _recentHistory.asStateFlow()

    /**
     * Imposta l'URL fornito come nuova Pagina Principale e attiva il blocco sul suo dominio.
     */
    fun setHomePage(url: String, enableDomainLock: Boolean = true) {
        val cleaned = url.trim()
        if (cleaned.isEmpty()) return
        prefs.edit()
            .putString(KEY_HOME_URL, cleaned)
            .putBoolean(KEY_DOMAIN_LOCK_ENABLED, enableDomainLock)
            .apply()
        _homeUrl.value = cleaned
        _domainLockEnabled.value = enableDomainLock
    }

    fun setDomainLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOMAIN_LOCK_ENABLED, enabled).apply()
        _domainLockEnabled.value = enabled
    }

    /**
     * Restituisce il dominio base (es. "streamingcommunityz.boo") della Pagina Principale salvata.
     */
    fun getLockedBaseDomain(): String? {
        return extractBaseDomain(_homeUrl.value)
    }

    /**
     * Verifica se un URL appartiene allo stesso dominio (o sottodominio) della Pagina Principale.
     */
    fun isUrlAllowedByDomainLock(targetUrl: String): Boolean {
        if (!_domainLockEnabled.value) return true
        val lockedDomain = getLockedBaseDomain() ?: return true
        // Non bloccare se la home è ancora Google e l'utente sta cercando
        if (lockedDomain == "google.com") return true

        val targetDomain = extractBaseDomain(targetUrl) ?: return false
        return targetDomain == lockedDomain
    }

    /**
     * Aggiunge una pagina visitata in cima alla cronologia, rimuovendo eventuali duplicati
     * dello stesso URL e mantenendo al massimo 20 elementi.
     */
    fun addHistoryEntry(url: String, title: String) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return
        if (trimmedUrl == "about:blank") return

        val displayTitle = title.trim().ifEmpty { trimmedUrl }
        val current = _recentHistory.value.toMutableList()

        // Rimuove se già presente per riportarlo in cima
        current.removeAll { it.url.equals(trimmedUrl, ignoreCase = true) }

        current.add(
            0,
            HistoryItem(
                url = trimmedUrl,
                title = displayTitle,
                timestamp = System.currentTimeMillis()
            )
        )

        val limited = current.take(MAX_HISTORY_ITEMS)
        _recentHistory.value = limited
        saveHistoryToPrefs(limited)
    }

    fun removeHistoryEntry(url: String) {
        val updated = _recentHistory.value.filterNot { it.url == url }
        _recentHistory.value = updated
        saveHistoryToPrefs(updated)
    }

    fun clearHistory() {
        _recentHistory.value = emptyList()
        prefs.edit().remove(KEY_RECENT_HISTORY).apply()
    }

    private fun loadHistoryFromPrefs(): List<HistoryItem> {
        val rawJson = prefs.getString(KEY_RECENT_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until minOf(array.length(), MAX_HISTORY_ITEMS)) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url")
                val title = obj.optString("title", url)
                val ts = obj.optLong("timestamp", 0L)
                if (url.isNotBlank()) {
                    list.add(HistoryItem(url = url, title = title, timestamp = ts))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistoryToPrefs(items: List<HistoryItem>) {
        val array = JSONArray()
        for (item in items.take(MAX_HISTORY_ITEMS)) {
            val obj = JSONObject().apply {
                put("url", item.url)
                put("title", item.title)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "tv_cast_browser_prefs"
        private const val KEY_HOME_URL = "home_url"
        private const val KEY_DOMAIN_LOCK_ENABLED = "domain_lock_enabled"
        private const val KEY_RECENT_HISTORY = "recent_history_json"

        const val DEFAULT_HOME_URL = "https://www.google.com"
        const val MAX_HISTORY_ITEMS = 20

        /**
         * Estrae il dominio principale (es. da "www.sub.example.com" -> "example.com").
         */
        fun extractBaseDomain(url: String): String? {
            return try {
                val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return null
                val parts = host.split(".")
                if (parts.size >= 2) {
                    parts.takeLast(2).joinToString(".")
                } else {
                    host
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

