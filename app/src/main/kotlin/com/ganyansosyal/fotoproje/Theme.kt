package com.ganyansosyal.fotoproje

/**
 * Tasarım sistemi "Kâğıt": açık kırık beyaz zemin (koyu modda sıcak mürekkep), deniz yeşili vurgu,
 * ince çizgiler ve keskin (8dp) köşeler. Tüm ekranlar renkleri BURADAN alır; elle renk yazma.
 * Renk değiştirmek için yalnızca bu dosyayı düzenle.
 */
object Theme {
    class P(
        val bg: Int,        // sayfa / alt sayfa zemini
        val surface: Int,   // kart, kutucuk
        val surface2: Int,  // alan, seçili / vurgulu yüzey
        val fg: Int,        // ana metin
        val mut: Int,       // soluk metin
        val line: Int,      // ince çizgi
        val accent: Int,    // vurgu (düğme, ilerleme, seçili)
        val onAccent: Int,  // vurgu üstündeki metin
        val accentSoft: Int,
        val danger: Int,
        val dangerSoft: Int,
        val star: Int       // yer imi yıldızı
    )

    val LIGHT = P(
        bg = 0xFFF7F6F2.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFECEBE5.toInt(),
        fg = 0xFF161A18.toInt(), mut = 0xFF66706B.toInt(), line = 0xFFD9D8D0.toInt(),
        accent = 0xFF0F766E.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFDCEEEB.toInt(),
        danger = 0xFFB42318.toInt(), dangerSoft = 0xFFFBE9E7.toInt(), star = 0xFFB7791F.toInt()
    )
    val DARK = P(
        bg = 0xFF121413.toInt(), surface = 0xFF1B1E1C.toInt(), surface2 = 0xFF262A27.toInt(),
        fg = 0xFFECEEEA.toInt(), mut = 0xFF98A19B.toInt(), line = 0xFF2F3532.toInt(),
        accent = 0xFF2DD4BF.toInt(), onAccent = 0xFF04201C.toInt(), accentSoft = 0xFF16302D.toInt(),
        danger = 0xFFF08A80.toInt(), dangerSoft = 0xFF3A2523.toInt(), star = 0xFFE0A93B.toInt()
    )

    fun of(dark: Boolean): P = if (dark) DARK else LIGHT

    // Köşe yuvarlaklıkları (dp)
    const val R = 8      // kart, düğme, kutucuk
    const val R_SHEET = 14 // alt sayfanın üst köşeleri
    const val R_PILL = 10  // yüzen bildirim

    /** WebView/HTML için "#rrggbb". */
    fun hex(c: Int): String = String.format("#%06x", c and 0xFFFFFF)
}
