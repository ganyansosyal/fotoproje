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

/** 3 reklam izleyen kullanıcıya gece 12'ye kadar indirme bedava. */
object Credits {
    const val PER_AD = 3
    private fun p() = Store.app.getSharedPreferences("fp_credits", Context.MODE_PRIVATE)

    /** LevelPlay anahtarları girilmediyse sistem kapalıdır: indirme serbest, reklam sorulmaz. */
    /** Ana şalter: true iken ödüllü reklam/kredi sistemi açık; false iken kapalı (indirme serbest). */
    const val REWARD_ADS = true // Yayın: açık. Test için false yap.

    val enabled: Boolean get() = REWARD_ADS && Ads.configured

    /** Gece 12'ye kadar bedava için izlenmesi gereken reklam sayısı. */
    const val ADS_FOR_FREE = 3

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** Saat geri alındıysa (hile) bedava hak ve reklam sayacı sıfırlanır. En son görülen saat saklanır. */
    @Synchronized private fun checkClock() {
        val now = System.currentTimeMillis()
        val last = p().getLong("last", 0L)
        if (now < last - 5 * 60_000L) {
            p().edit().remove("free_day").putInt("ads", 0).putLong("last", now).apply()
        } else if (now > last) {
            p().edit().putLong("last", now).apply()
        }
    }

    /** Bugün izlenen reklam sayısı (gece 12'de sıfırlanır). */
    val adsToday: Int get() = checkClock().let { 0 } + if (p().getString("day", "") == today()) p().getInt("ads", 0) else 0

    /** Bugün 3 reklam izlendiyse gece 12'ye kadar indirme bedava. */
    val freeToday: Boolean get() = enabled && checkClock().let { true } && p().getString("free_day", "") == today()

    fun has() = !enabled || freeToday

    /** Reklam izlendi: sayacı artır. 3. reklamda true döner (gece 12'ye kadar bedava açıldı). */
    @Synchronized fun onAdWatched(): Boolean {
        checkClock()
        val t = today()
        val n = (if (p().getString("day", "") == t) p().getInt("ads", 0) else 0) + 1
        val e = p().edit().putString("day", t).putInt("ads", n)
        val unlocked = n >= ADS_FOR_FREE && p().getString("free_day", "") != t
        if (unlocked) e.putString("free_day", t)
        e.apply()
        return unlocked
    }

    fun use(): Boolean = !enabled || freeToday
    fun refund() {}
}

/** LevelPlay (içinde AdMob) ödüllü reklam. */
object Ads {
    // LevelPlay panelinden alınacak: Setup > Apps > App Key ve Setup > Ad Units > Rewarded ad unit ID
    private const val APP_KEY = "298353485"
    private const val AD_UNIT_ID = "lipy2p9ldlnctkdv"
    // Yedek: doğrudan AdMob "Ödüllü geçiş reklamı" (beta). LevelPlay reklamı yoksa bu açılır.
    private const val RI_UNIT_ID = "ca-app-pub-1340335815297540/7952111701"

    // Yedek (ödüllü geçiş) reklam açık. Kapatmak için false yap.
    private const val USE_RI = true

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
    @Volatile private var errRaw: String = ""
    val lastError: String get() = if (errRaw.isBlank()) "init=" + initDone + " yukleniyor=" + loading + " hazir=" + lpReady else errRaw

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
                    errRaw = "init: " + error.errorCode + " " + error.errorMessage
                    Log.e("Ads", errRaw)
                }
            })
        } catch (t: Throwable) { initing = false; errRaw = "init exc: " + t.message; Log.e("Ads", errRaw, t) }
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
                        errRaw = "ri: " + adError.message
                        Log.e("Ads", errRaw)
                        if (!riRetry) {
                            riRetry = true
                            ui.postDelayed({ riRetry = false; loadRi() }, 30_000)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            riLoading = false
            errRaw = "ri exc: " + t.message
            Log.e("Ads", errRaw, t)
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
                errRaw = "ri show: " + fullScreenContentError.message
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
            errRaw = "ri show exc: " + t.message
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
            errRaw = "load: " + error.errorCode + " " + error.errorMessage
            Log.e("Ads", errRaw)
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
