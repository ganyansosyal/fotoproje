package com.ganyansosyal.fotoproje

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Ana sayfa logolarını (favicon) diske kaydeder: ana sayfa her açılışta ağdan beklemez. */
object FavIcons {
    private val uri = ConcurrentHashMap<String, String>() // alan adı -> data: adresi
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val file: File get() = File(Store.app.filesDir, "favicons.json")

    fun get(host: String): String? = uri[host]

    fun load() {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.optString(k)
                    if (v.startsWith("data:")) uri[k] = v
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Eksik logoları arka planda indirir; bir sonraki ana sayfa açılışında hazır olur. */
    fun ensure(hosts: Collection<String>) {
        val todo = hosts.toSet().filter { it.isNotEmpty() && !uri.containsKey(it) && busy.add(it) }
        if (todo.isEmpty()) return
        Thread {
            var changed = false
            for (h in todo) {
                val u = fetch(h)
                busy.remove(h)
                if (u != null) {
                    uri[h] = u
                    changed = true
                }
            }
            if (changed) try {
                file.writeText(JSONObject(uri as Map<*, *>).toString())
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun fetch(host: String): String? {
        val urls = listOf(
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
            "https://icons.duckduckgo.com/ip3/$host.ico"
        )
        for (u in urls) {
            try {
                val (b, ct) = Http.get(u, mapOf("User-Agent" to Net.UA), null, CancelFlag()) { c ->
                    Pair(c.inputStream.use { it.readBytes() }, c.contentType ?: "image/png")
                }
                if (b.size < 400 || !ct.startsWith("image/")) continue
                return "data:" + ct.substringBefore(';') + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
