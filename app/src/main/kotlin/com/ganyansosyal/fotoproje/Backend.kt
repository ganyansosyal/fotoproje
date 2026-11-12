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
                        val ref0 = s("ref")
                        val h0 = (a["h"] as? Number)?.toInt() ?: 0
                        var items = Probe.probeAll(
                            urls, hints, ref0,
                            (a["dur"] as? Number)?.toDouble() ?: 0.0, s("src"), h0
                        )
                        // Yedek: normal sitede yalnızca kısa (fragman/reklam) ya da hiç video bulunduysa sayfa kaynağını tara
                        if (ref0.startsWith("http") && Probe.isAdSite(ref0) &&
                            items.none { it.duration >= 60 || (it.isStream && it.duration <= 0) }
                        ) {
                            val extra = try { PageScan.scan(ref0, force = true) { false }.map { it.url } } catch (_: Throwable) { emptyList() }
                                .filter { it !in urls }
                            if (extra.isNotEmpty()) {
                                val h2 = HashMap(hints)
                                for (u in extra) h2[u] = Media.kind(u) ?: "mp4"
                                // Oynayan videonun süresi güvenilmez (fragman olabilir): süre eşleştirmesi yok
                                val more = Probe.probeAll(urls + extra, h2, ref0, 0.0, "", h0)
                                if (more.isNotEmpty()) items = more
                            }
                        }
                        val out = ArrayList<Map<String, Any?>>(items.map {
                            mapOf(
                                "url" to it.url, "kind" to it.kind, "title" to it.title, "subtitle" to it.subtitle,
                                "h" to it.height, "d" to it.duration, "s" to it.size, "bw" to it.bw
                            )
                        })
                        // Siteye özel çözüm: hâlâ uzun video yoksa yt-dlp'nin kendi site çözücüleri sayfayı dener.
                        // Bulursa sayfa adresi aday olur; indirme de yt-dlp ile yapılır.
                        if (ref0.startsWith("http") && Probe.isAdSite(ref0) &&
                            items.none { it.duration >= 60 || (it.isStream && it.duration <= 0) }
                        ) {
                            val inf = Ytdlp.info(Store.app, ref0)
                            if (inf != null && inf.duration >= 60) {
                                out.add(0, mapOf(
                                    "url" to ref0, "kind" to "mp4", "title" to inf.title,
                                    "subtitle" to "Sayfadan çözüldü",
                                    "h" to 0, "d" to inf.duration, "s" to 0L, "bw" to 0
                                ))
                            }
                        }
                        done(out)
                    } catch (_: Throwable) {
                        ui.post { onFail() }
                    }
                }
                "download" -> bg.execute {
                    val tab = (a["tab"] as? Number)?.toInt()
                    val kind = s("kind")
                    val title = s("title")
                    val alts = (a["alts"] as? List<*>)?.mapNotNull { x ->
                        val sx = x.toString()
                        val i = sx.indexOf('|')
                        if (i > 0) sx.substring(0, i) to sx.substring(i + 1) else null
                    } ?: emptyList()
                    val r = Downloads.start(
                        s("url"), s("ref"), kind.ifEmpty { null }, title.ifEmpty { null }, alts = alts
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
