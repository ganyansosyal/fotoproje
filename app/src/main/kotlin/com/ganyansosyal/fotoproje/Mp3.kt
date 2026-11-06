package com.ganyansosyal.fotoproje

import java.io.IOException

/**
 * MP3 dönüştürme şimdilik KAPALI (LAME kütüphanesi bağlanana kadar).
 * Hata fırlatınca Downloads.kt otomatik olarak M4A olarak kaydeder.
 */
object Mp3 {
    fun encode(src: String, dest: String, cancel: CancelFlag, progress: (Double) -> Unit) {
        throw IOException("MP3 kodlayıcı yok")
    }
}
