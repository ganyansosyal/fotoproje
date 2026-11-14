package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
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
    const val PER_AD = 3
    private fun p() = Store.app.getSharedPreferences("fp_credits", Context.MODE_PRIVATE)

    /** LevelPlay anahtarları girilmediyse sistem kapalıdır: indirme serbest, reklam sorulmaz. */
    /** Ana şalter: true iken ödüllü reklam/kredi sistemi açık; false iken kapalı (indirme serbest). */
    const val REWARD_ADS = true // Yayın: açık. Test için false yap.

    val enabled: Boolean get() = REWARD_ADS && Ads.configured

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
    // Yedek: doğrudan AdMob "Ödüllü geçiş reklamı" (beta). LevelPlay reklamı yoksa bu açılır.
    private const val RI_UNIT_ID = "ca-app-pub-1340335815297540/7952111701"

    // Test için false: yedek (ödüllü geçiş) reklam kapalı, sadece LevelPlay denenir. Tekrar açmak için true yap.
    private const val USE_RI = false

    val configured: Boolean
        get() = !APP_KEY.startsWith("BURAYA") && !AD_UNIT_ID.startsWith("BURAYA")

    private val ui = Handler(Looper.getMainLooper())
    private var ad: LevelPlayRewardedAd? = null
    private var initDone = false
    private var initing = false
    private var loading = false

    private var ri: RewardedInterstitialAd? = null
    private var riLoading = false
    private var riRetry = false

    /** Son hata (teşhis için): init veya yükleme başarısız olursa buraya yazılır. */
    @Volatile var lastError: String = ""

    private var cbReward: (() -> Unit)? = null
    private var cbFail: (() -> Unit)? = null
    private var earned = false

    private val lpReady: Boolean get() = try { ad?.isAdReady() == true } catch (_: Throwable) { false }

    val ready: Boolean get() = lpReady || ri != null

    fun init(ctx: Context) {
        if (!Credits.REWARD_ADS || !configured || initDone || initing) return
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
                    lastError = "init: " + error.errorCode + " " + error.errorMessage
                    Log.e("Ads", lastError)
                }
            })
        } catch (t: Throwable) { initing = false; lastError = "init exc: " + t.message; Log.e("Ads", lastError, t) }
    }

    fun load(ctx: Context) {
        if (!configured) return
        if (!initDone) { init(ctx); return }
        loadRi()
        if (loading || lpReady) return
        loading = true
        try { ad?.loadAd() } catch (_: Throwable) { loading = false }
    }

    /** Yedek reklam: AdMob ödüllü geçiş reklamı (doğrudan AdMob SDK). */
    private fun loadRi() {
        if (!USE_RI || riLoading || ri != null) return
        riLoading = true
        try {
            RewardedInterstitialAd.load(
                AdRequest.Builder(RI_UNIT_ID).build(),
                object : AdLoadCallback<RewardedInterstitialAd> {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        riLoading = false
                        ri = ad
                        Log.d("Ads", "ri yüklendi")
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        riLoading = false
                        lastError = "ri: " + adError.message
                        Log.e("Ads", lastError)
                        if (!riRetry) {
                            riRetry = true
                            ui.postDelayed({ riRetry = false; loadRi() }, 30_000)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            riLoading = false
            lastError = "ri exc: " + t.message
            Log.e("Ads", lastError, t)
        }
    }

    private fun showRi(act: Activity, r: RewardedInterstitialAd) {
        r.adEventCallback = object : RewardedInterstitialAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                ri = null
                ui.post { load(Store.app) }
                ui.postDelayed({ if (!earned) finish(false) }, 1500)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                ri = null
                lastError = "ri show: " + fullScreenContentError.message
                ui.post { load(Store.app); finish(false) }
            }
        }
        try {
            r.show(act, object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    earned = true
                    ui.post { finish(true) }
                }
            })
        } catch (t: Throwable) {
            ri = null
            lastError = "ri show exc: " + t.message
            finish(false)
        }
    }

    /** Ödül yalnızca kullanıcı reklamı gerçekten izleyince verilir (onReward). */
    fun show(act: Activity, onReward: () -> Unit, onFail: () -> Unit) {
        val a = ad
        val r = ri
        if (a != null && lpReady) {
            cbReward = onReward
            cbFail = onFail
            earned = false
            try { a.showAd(act) } catch (_: Throwable) { finish(false) }
        } else if (r != null) {
            cbReward = onReward
            cbFail = onFail
            earned = false
            showRi(act, r)
        } else { load(act); onFail() }
    }

    private fun finish(ok: Boolean) {
        val r = cbReward; val f = cbFail
        cbReward = null; cbFail = null
        if (ok) r?.invoke() else f?.invoke()
    }

    private val listener = object : LevelPlayRewardedAdListener {
        override fun onAdLoaded(adInfo: LevelPlayAdInfo) { loading = false; Log.d("Ads", "yüklendi") }

        override fun onAdLoadFailed(error: LevelPlayAdError) {
            loading = false
            lastError = "load: " + error.errorCode + " " + error.errorMessage
            Log.e("Ads", lastError)
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
