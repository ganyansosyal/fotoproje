package com.ganyansosyal.fotoproje

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Yerel tarayıcı ekranının (BrowserActivity) eskiden Dart'a kanaldan sorduğu işler.
 * Çağrı adları ve dönen değerler eski kanal sözleşmesiyle aynıdır, artık hepsi Kotlin.
 */
object Backend {
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newCachedThreadPool()

    /** Sonuç her zaman ana iş parçacığında döner. */
    fun call(method: String, args: Any?, onOk: (Any?) -> Unit = {}, onFail: () -> Unit = {}) {
        val a = (args as? Map<*, *>) ?: emptyMap<Any, Any>()
        fun s(k: String) = a[k]?.toString() ?: ""
        fun done(r: Any?) = ui.post { onOk(r) }
        try {
            when (method) {
                "cfg" -> done(cfg())
                "home" -> {
                    FavIcons.ensure(HomeHtml.hosts())
                    bg.execute {
                        val html = try { HomeHtml.build(Store.dark) } catch (_: Throwable) { "" }
                        done(html)
                    }
                }
                "probe" -> bg.execute {
                    try {
                        val urls = (a["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()
                        val hints = HashMap<String, String>()
                        (a["hints"] as? Map<*, *>)?.forEach { (k, v) -> hints[k.toString()] = v.toString() }
                        val items = Probe.probeAll(
                            urls, hints, s("ref"),
                            (a["dur"] as? Number)?.toDouble() ?: 0.0, s("src"),
                            (a["h"] as? Number)?.toInt() ?: 0
                        )
                        done(items.map {
                            mapOf(
                                "url" to it.url, "kind" to it.kind, "title" to it.title, "subtitle" to it.subtitle,
                                "h" to it.height, "d" to it.duration, "s" to it.size, "bw" to it.bw
                            )
                        })
                    } catch (_: Throwable) {
                        ui.post { onFail() }
                    }
                }
                "download" -> bg.execute {
                    val tab = (a["tab"] as? Number)?.toInt()
                    val kind = s("kind")
                    val title = s("title")
                    val r = Downloads.start(
                        s("url"), s("ref"), kind.ifEmpty { null }, title.ifEmpty { null }
                    ) { url, path, res ->
                        ui.post {
                            val b = BrowserActivity.current
                            if (b == null) res.fail(L.t("blobFail")) else b.readBlob(url, path, tab, res)
                        }
                    }
                    done(r)
                }
                "addHistory" -> { Store.addHistory(s("url"), s("title")); done(true) }
                "historyTitle" -> { Store.updateHistoryTitle(s("url"), s("title")); done(true) }
                "bookmarks" -> done(ArrayList(Store.bookmarks))
                "toggleBookmark" -> { Store.toggleBookmark(s("url")); done(ArrayList(Store.bookmarks)) }
                "history" -> done(Store.history.take(300).map { mapOf("u" to it.url, "t" to it.title, "d" to it.time) })
                "clearHistory" -> { Store.clearHistory(); done(true) }
                "addFavorite" -> {
                    Store.addFavorite(s("name"), s("url"))
                    FavIcons.ensure(HomeHtml.hosts())
                    done(true)
                }
                "removeFavorite" -> { Store.removeFavorite(s("url")); done(true) }
                else -> ui.post { onFail() }
            }
        } catch (_: Throwable) {
            ui.post { onFail() }
        }
    }

    fun cfg(): Map<String, Any?> = mapOf(
        "dark" to Store.dark,
        "blockAds" to Store.blockAds,
        "lang" to L.code,
        "engine" to Store.engineBase(),
        "bookmarks" to ArrayList(Store.bookmarks),
        "strings" to L.all(),
        "hook" to Media.HOOK_JS,
        "noAutoplay" to Media.NO_AUTOPLAY_JS
    )
}
