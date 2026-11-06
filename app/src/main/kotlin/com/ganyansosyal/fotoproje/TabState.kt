package com.ganyansosyal.fotoproje

import android.webkit.WebView
import androidx.webkit.ScriptHandler

/** Dart'ın incelediği ve indirmeye hazır bulduğu video. */
class FoundItem(
    val url: String, val kind: String, val title: String, val subtitle: String,
    val height: Int = 0, val duration: Double = 0.0, val size: Long = 0L
)

/** Ağ seviyesinde yakalanan medya adayı. */
class NetCand(val url: String, val kind: String, var t: Long)

/**
 * Bir sekmenin tüm durumu. WebView yalnızca sekme canlıysa vardır; bellek için
 * eski sekmelerin WebView'ı kapatılır ([web] = null), seçilince adresten yeniden açılır.
 */
class Tab(val id: Int, var url: String) {
    var web: WebView? = null
    var title = ""
    var desktop = false
    var isHome = false
    var homeLoading = false
    var homeGen = 0
    var loaded = false // WebView'a yükleme komutu verildi mi
    var parentId = -1 // window.open ile açıldıysa açan sekme
    var progress = 100
    var loading = false
    var lastUsed = System.currentTimeMillis()
    val scripts = ArrayList<ScriptHandler>()

    // ---- video yakalama (sekme başına)
    var playerId: String? = null
    var pDur = 0.0
    var pLive = false
    var pH = 0
    var pSrc = ""
    var pRef: String? = null
    val pUrls = ArrayList<String>()
    val pHints = HashMap<String, String>()
    var found: List<FoundItem> = emptyList()
    var probeGen = 0
    var bannerDone = false
    var autoShow = false // Link ile indir'den açıldı: video bulununca seçenekleri kendiliğinden göster
    val netCands = ArrayList<NetCand>()
    var probeRun: Runnable? = null
}
