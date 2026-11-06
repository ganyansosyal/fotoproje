# AGENTS.md — fotoproje notları

Bu dosya, projede çalışan herkes (insan ya da yapay zekâ) için bağlam notudur.
Her büyük değişiklikten sonra "Yapılanlar" ve "Sıradakiler" bölümlerini güncelle.

## Proje yapısı
- Flutter uygulaması (tam özellikli tarayıcı + indirme yöneticisi), `lib/` altında.
- `native/*.kt`: build sırasında Android paketine kopyalanan Kotlin dosyaları
  (`build.yml` hepsini kopyalar; yeni `.kt` dosyası için ek ayar gerekmez).
- Yerel (Kotlin) tarayıcı: akıcı kaydırma için Flutter katmanı olmadan çalışan WebView ekranı.
  Flutter ile `app/browser` (bidirectional) ve `app/native` kanallarından konuşur.
- Ayarlar, geçmiş, yer imi, favori, indirme yöneticisi **Dart'ta** kalır; yerel ekran bunları kanaldan ister.

## Kanal sözleşmesi (`app/browser`)
Kotlin -> Dart: `cfg`, `home`, `probe`, `download`, `youtube`, `addHistory`, `historyTitle`,
`bookmarks`, `toggleBookmark`, `history`, `clearHistory`, `addFavorite`, `removeFavorite`, `section`.
Dart -> Kotlin: `readBlob` (blob videoyu sayfadan parça parça okuyup dosyaya yazar).
`app/native`: `openNative`, `cacheCfg` (Dart ayarları yerel önbelleğe yazar), diğerleri eskisi gibi.

## Yapılanlar
- Yerel tarayıcı baştan yazıldı (`native/BrowserActivity.kt`, `AdBlock.kt`, `TabState.kt`, `MainActivity.kt`):
  - Çoklu sekme (alttaki sayı kutusu, panel, yeni/kapat/hepsini kapat, window.open = sekme,
    geri tuşu sayfa -> sekme kapat), en fazla 4 canlı WebView, sekmeler kalıcı saklanır.
  - Reklam engelleme (alan adı listesi + hafif CSS gizleme), Dart ayarına bağlı.
  - Yer imi ☆/★, Yer işaretleri ve Geçmiş listeleri (basılı tut = sil, Temizle).
  - Blob video indirme (Kotlin parça parça okur, Dart indirme yöneticisi devralır).
  - Ana sayfa + favoriler (Dart `buildHomeHtml`), karanlık tema, dil (`S.all` ile), tam ekran modu,
    performans ölçer (sayfa fps), paylaş/bağlantıyı kopyala, İndirmeler/Ayarlar'a geçiş, dosya seçici.
  - "Ana ekran yap" ayarı ve açılışta otomatik açma KALDIRILDI (kullanıcı istemedi). `nativeDefault` alanı settings.dart'ta artık kullanılmıyor.
- Dart: `browser_page.dart` köprü işleyicileri, `strings.dart` yeni metinler + `S.all`,
  `settings.dart` `nativeDefault`, `settings_page.dart` anahtar, `top_bar.dart` menü etiketi.

- İndirmeler: Duraklat / Devam / Yeniden dene düğmeleri (`DownloadManager.pause/resume`),
  bitiş aşamaları ("birleştiriliyor", "kaydediliyor") belirsiz çubukla gösterilir.

- Yerel tarayıcı artık TEK tarayıcı: MainActivity açılışta onu başlatır (`boot_pending` korumalı),
  Flutter'daki "Sekme" düğmesi ve İndirmeler/Ayarlar'dan geri tuşu yerel ekrana götürür.
  Eski Flutter tarayıcı kodu (`browser_page.dart`) yedek olarak duruyor, ekrana artık çıkmıyor.
- Yerel menüden "Uygulamaya dön" kaldırıldı (Flutter tarayıcıya düşüyordu); alt çubuktaki ✕ yerine ⬇ (İndirmeler) kondu.
- Klavye yavaşlığı (Flutter tarayıcı): sayfa alanı klavyeyle artık yeniden boyutlanmıyor
  (`resizeToAvoidBottomInset: false`, alt çubuk yerinde kalıyor); sayfadaki odaklı yazı alanı klavye
  yerleşince `_revealField` ile JS kaydırmasıyla görünür yapılıyor. Denenmedi; yazı alanı klavyenin
  altında kalırsa `_revealField` içindeki `cover` hesabını ayarla.

## Bilinen durum / uyarılar
- Kotlin ve Dart kodu derlenip denenmedi; ilk build'de hata çıkabilir. Log'a göre düzelt.
- Blob video iframe içindeyse okunamayabilir.
- Yerel reklam gizleme kuralları Flutter'daki kadar geniş değil (kaydırma performansı için).
- Eski sekmeler (4'ten fazlası) seçilince adresten yeniden yüklenir, kaydırma konumu kaybolur.

## Sıradakiler (unutma)
0. Duraklat şu an iptal edip Devam'da BAŞTAN başlatır; gerçek devam (kaldığı yerden) için
   `Native.kt` direct/segments ve `engine.dart` Range tabanlı kısmi dosya devamı gerekir.
   Takılı kalan "birleştiriliyor" için zaman aşımı / hata gösterimi eklenebilir.
1. İlk build'i al, derleme hatalarını düzelt.
2. Cihazda dene: sekmeler, geri tuşu, blob indirme, karanlık tema, ana sayfa/favoriler, duraklat/devam.
3. Sistem varsayılan tarayıcısı: `AndroidManifest`'e http/https intent-filter ekle
   (`build.yml` manifest adımı) ve gelen VIEW adresini `BrowserActivity`'de aç.
4. Yerel ekrandan indirme ilerlemesini göster (şu an Flutter'a dönmek gerekiyor).
5. Site izinleri: konum / kamera / mikrofon (`onPermissionRequest`, geolocation).
6. Performans ölçeri Flutter'daki kadar ayrıntılı yap (isteğe bağlı).
7. Yerel ekran sorunsuzsa eski Flutter tarayıcı kodunu tamamen sil (şu an yedek).
8. Reklam gizleme kurallarını güvenli şekilde genişlet (kaydırmayı ölçerek).
