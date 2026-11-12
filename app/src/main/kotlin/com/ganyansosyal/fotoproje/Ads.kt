package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener

/** İndirme hakkı (kredi): reklam izleyince +5, her indirmede -1. */
object Credits {
    const val PER_AD = 5
    private fun p() = Store.app.getSharedPreferences("fp_credits", Context.MODE_PRIVATE)

    /** LevelPlay anahtarları girilmediyse sistem kapalıdır: indirme serbest, reklam sorulmaz. */
    val enabled: Boolean get() = Ads.configured

    val count: Int get() = p().getInt("n", 0)
    fun has() = !enabled || count > 0

    @Synchronized fun add(n: Int) { p().edit().putInt("n", count + n).apply() }
    @Synchronized fun use(): Boolean {
        if (!enabled) return true
        val c = count
        if (c <= 0) return false
        p().edit().putInt("n", c - 1).apply()
        return true
    }
}

/** LevelPlay (içinde AdMob) ödüllü reklam. */
object Ads {
    // LevelPlay panelinden alınacak: Setup > Apps > App Key ve Setup > Ad Units > Rewarded ad unit ID
    private const val APP_KEY = "298353485"
    private const val AD_UNIT_ID = "lipy2p9ldlnctkdv"

    val configured: Boolean
        get() = !APP_KEY.startsWith("BURAYA") && !AD_UNIT_ID.startsWith("BURAYA")

    private val ui = Handler(Looper.getMainLooper())
    private var ad: LevelPlayRewardedAd? = null
    private var initDone = false
    private var initing = false
    private var loading = false

    private var cbReward: (() -> Unit)? = null
    private var cbFail: (() -> Unit)? = null
    private var earned = false

    val ready: Boolean get() = try { ad?.isAdReady() == true } catch (_: Throwable) { false }

    fun init(ctx: Context) {
        if (!configured || initDone || initing) return
        initing = true
        try {
            val req = LevelPlayInitRequest.Builder(APP_KEY).build()
            LevelPlay.init(ctx.applicationContext, req, object : LevelPlayInitListener {
                override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                    initing = false
                    initDone = true
                    val a = LevelPlayRewardedAd(AD_UNIT_ID)
                    a.setListener(listener)
                    ad = a
                    load(ctx)
                }
                override fun onInitFailed(error: LevelPlayInitError) {
                    initing = false // sonraki load() çağrısında yeniden denenir
                }
            })
        } catch (_: Throwable) { initing = false }
    }

    fun load(ctx: Context) {
        if (!configured) return
        if (!initDone) { init(ctx); return }
        if (loading || ready) return
        loading = true
        try { ad?.loadAd() } catch (_: Throwable) { loading = false }
    }

    /** Ödül yalnızca kullanıcı reklamı gerçekten izleyince verilir (onReward). */
    fun show(act: Activity, onReward: () -> Unit, onFail: () -> Unit) {
        val a = ad
        if (a == null || !ready) { load(act); onFail(); return }
        cbReward = onReward
        cbFail = onFail
        earned = false
        try { a.showAd(act) } catch (_: Throwable) { finish(false) }
    }

    private fun finish(ok: Boolean) {
        val r = cbReward; val f = cbFail
        cbReward = null; cbFail = null
        if (ok) r?.invoke() else f?.invoke()
    }

    private val listener = object : LevelPlayRewardedAdListener {
        override fun onAdLoaded(adInfo: LevelPlayAdInfo) { loading = false }

        override fun onAdLoadFailed(error: LevelPlayAdError) {
            loading = false
            // Reklam yoksa 30 sn sonra yeniden dene
            ui.postDelayed({ ad?.let { load(Store.app) } }, 30_000)
        }

        override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) {
            earned = true
            finish(true)
        }

        override fun onAdClosed(adInfo: LevelPlayAdInfo) {
            load(Store.app) // sıradaki reklam önceden yüklenir
            // onAdRewarded, onAdClosed'dan sonra gelebilir: kısa süre beklenir
            ui.postDelayed({ if (!earned) finish(false) }, 1500)
        }

        override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
            load(Store.app)
            finish(false)
        }

        override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
        override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
        override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
    }
}
