package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/** İndirme hakkı (kredi): reklam izleyince +5, her indirmede -1. */
object Credits {
    const val PER_AD = 5
    private fun p() = Store.app.getSharedPreferences("fp_credits", Context.MODE_PRIVATE)

    val count: Int get() = p().getInt("n", 0)
    fun has() = count > 0

    @Synchronized fun add(n: Int) { p().edit().putInt("n", count + n).apply() }
    @Synchronized fun use(): Boolean {
        val c = count
        if (c <= 0) return false
        p().edit().putInt("n", c - 1).apply()
        return true
    }
}

/** AdMob ödüllü reklam. GERÇEK kimlik: kendi telefonunda AdMob panelinden test cihazı olarak ekle, reklama tıklama. */
object Ads {
    // Gerçek ödüllü reklam birimi (indirme_odul)
    private const val UNIT_ID = "ca-app-pub-1340335815297540/5900117294"

    private var ad: RewardedAd? = null
    private var loading = false

    val ready: Boolean get() = ad != null

    fun init(ctx: Context) {
        try {
            MobileAds.initialize(ctx) {}
            load(ctx)
        } catch (_: Throwable) {}
    }

    fun load(ctx: Context) {
        if (loading || ad != null) return
        loading = true
        try {
            RewardedAd.load(ctx, UNIT_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(a: RewardedAd) { ad = a; loading = false }
                override fun onAdFailedToLoad(e: LoadAdError) { ad = null; loading = false }
            })
        } catch (_: Throwable) { loading = false }
    }

    /** Ödül yalnızca kullanıcı reklamı gerçekten izleyince verilir (onReward). */
    fun show(act: Activity, onReward: () -> Unit, onFail: () -> Unit) {
        val a = ad
        if (a == null) { load(act); onFail(); return }
        var earned = false
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                load(act) // sıradaki reklam önceden yüklenir
                if (!earned) onFail()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { ad = null; load(act); onFail() }
        }
        a.show(act) { earned = true; onReward() }
    }
}
