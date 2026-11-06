package com.ganyansosyal.fotoproje

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class HistoryEntry(val url: String, val title: String, val time: Long)

/** TR/EN metin sistemi (eski Dart `S` sınıfı). lang: system | tr | en */
object L {
    @Volatile
    var lang = "system"

    val code: String
        get() = if (lang == "tr" || lang == "en") lang
        else if (Locale.getDefault().language == "tr") "tr" else "en"

    fun t(k: String): String {
        val m = if (code == "tr") Strs.TR else Strs.EN
        return m[k] ?: Strs.TR[k] ?: k
    }

    fun all(): Map<String, String> = if (code == "tr") Strs.TR else Strs.TR + Strs.EN
}

val defaultSites = listOf(
    listOf("Google", "https://www.google.com"),
    listOf("YouTube", "https://m.youtube.com"),
    listOf("Instagram", "https://www.instagram.com"),
    listOf("X", "https://x.com"),
    listOf("Haberler", "https://www.ntv.com.tr"),
    listOf("Harita", "https://maps.google.com"),
    listOf("GitHub", "https://github.com"),
    listOf("Vikipedi", "https://tr.wikipedia.org")
)

val searchEngines = linkedMapOf(
    "Google" to "https://www.google.com/search?q=",
    "Bing" to "https://www.bing.com/search?q=",
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Yandex" to "https://yandex.com.tr/search/?text="
)

const val APP_VERSION = "3.0.0"

/** Tüm kalıcı ayarlar, geçmiş, yer imleri ve favoriler (eski Dart `AppSettings`). */
object Store {
    lateinit var app: Application
    private lateinit var p: SharedPreferences

    @Volatile var wifiOnly = true
    @Volatile var blockAds = true
    @Volatile var recentSites = true
    @Volatile var syncGallery = true
    @Volatile var publicFolder = false
    @Volatile var engine = "Google"
    @Volatile var theme = "light" // light | dark

    var bookmarks = ArrayList<String>()
    var history = ArrayList<HistoryEntry>()
    var favorites = ArrayList<MutableList<String>>() // [ad, adres]

    val lang: String get() = L.lang
    val dark: Boolean get() = theme == "dark"

    @Synchronized
    fun init(a: Application) {
        app = a
        p = a.getSharedPreferences("fp_settings", Context.MODE_PRIVATE)
        wifiOnly = p.getBoolean("wifiOnly", true)
        blockAds = p.getBoolean("blockAds", true)
        recentSites = p.getBoolean("recentSites", true)
        syncGallery = p.getBoolean("syncGallery", true)
        publicFolder = p.getBoolean("publicFolder", false)
        engine = p.getString("engine", "Google") ?: "Google"
        L.lang = p.getString("lang", "system") ?: "system"
        theme = if (p.getString("theme", "light") == "dark") "dark" else "light"
        bookmarks = ArrayList(p.getString("bm", null)?.let { jsonList(it) } ?: emptyList())
        favorites = try {
            val f = p.getString("favs", null)
            if (f != null) {
                val arr = JSONArray(f)
                val out = ArrayList<MutableList<String>>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    out.add(mutableListOf(e.getString(0), e.getString(1)))
                }
                out
            } else defaultFavs()
        } catch (_: Throwable) {
            defaultFavs()
        }
        history = try {
            val raw = p.getString("history", null)
            val out = ArrayList<HistoryEntry>()
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(HistoryEntry(o.getString("u"), o.optString("t"), o.optLong("d")))
                }
            }
            out
        } catch (_: Throwable) {
            ArrayList()
        }
    }

    private fun jsonList(s: String): List<String> = try {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun defaultFavs() = ArrayList(defaultSites.map { it.toMutableList() })

    @Synchronized
    fun setBool(key: String, v: Boolean) {
        when (key) {
            "wifiOnly" -> wifiOnly = v
            "blockAds" -> blockAds = v
            "recentSites" -> recentSites = v
            "syncGallery" -> syncGallery = v
            "publicFolder" -> publicFolder = v
        }
        p.edit().putBoolean(key, v).apply()
    }

    @Synchronized
    fun setString(key: String, v: String) {
        when (key) {
            "engine" -> engine = v
            "lang" -> L.lang = v
            "theme" -> theme = v
        }
        p.edit().putString(key, v).apply()
    }

    fun searchUrl(q: String): String =
        (searchEngines[engine] ?: searchEngines["Google"]!!) + URLEncoder.encode(q, "UTF-8")

    fun engineBase(): String = searchEngines[engine] ?: searchEngines["Google"]!!

    @Synchronized
    fun toggleBookmark(url: String) {
        if (!bookmarks.remove(url)) bookmarks.add(url)
        p.edit().putString("bm", JSONArray(bookmarks).toString()).apply()
    }

    @Synchronized
    fun addHistory(url: String, title: String) {
        if (url.isEmpty() || url == "about:home" || !url.startsWith("http")) return
        if (history.isNotEmpty() && history[0].url == url) return
        history.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        while (history.size > 300) history.removeAt(history.size - 1)
        persistHistory()
    }

    @Synchronized
    fun updateHistoryTitle(url: String, title: String) {
        if (history.isNotEmpty() && history[0].url == url && title.isNotEmpty()) {
            history[0] = HistoryEntry(url, title, history[0].time)
            persistHistory()
        }
    }

    @Synchronized
    fun clearHistory() {
        history = ArrayList()
        persistHistory()
    }

    private fun persistHistory() {
        val a = JSONArray()
        for (h in history) a.put(JSONObject().put("u", h.url).put("t", h.title).put("d", h.time))
        p.edit().putString("history", a.toString()).apply()
    }

    private fun saveFavs() {
        val a = JSONArray()
        for (f in favorites) a.put(JSONArray().put(f[0]).put(f[1]))
        p.edit().putString("favs", a.toString()).apply()
    }

    fun isFavorite(url: String) = favorites.any { it[1] == url }

    @Synchronized
    fun addFavorite(name: String, url: String) {
        if (url.isEmpty() || isFavorite(url)) return
        val host = hostOf(url).ifEmpty { url }
        favorites.add(mutableListOf(if (name.trim().isEmpty()) host.removePrefix("www.") else name.trim(), url))
        saveFavs()
    }

    @Synchronized
    fun removeFavorite(url: String) {
        favorites.removeAll { it[1] == url }
        saveFavs()
    }

    /** Ana sayfada gösterilecek siteler: favoriler + son ziyaret edilenler */
    @Synchronized
    fun homeSites(): List<List<String>> {
        val out = ArrayList<List<String>>(favorites.map { listOf(it[0], it[1]) })
        if (recentSites) {
            val seen = HashSet<String>()
            for (h in history) {
                val u = (try { URI(h.url) } catch (_: Throwable) { null }) ?: continue
                val host = u.host ?: continue
                if (!seen.add(host)) continue
                val base = "${u.scheme}://$host"
                if (out.none { it[1] == base }) out.add(listOf(host.removePrefix("www."), base))
                if (seen.size >= 8) break
            }
        }
        return out
    }
}

fun hostOf(u: String): String = try {
    URI(if (u.contains("://")) u else "https://$u").host ?: ""
} catch (_: Throwable) {
    ""
}
