# AGENTS.md — fotoproje notları

Bu dosya, projede çalışan herkes (insan ya da yapay zekâ) için bağlam notudur.
Her büyük değişiklikten sonra "Yapılanlar" ve "Sıradakiler" bölümlerini güncelle.

## Proje yapısı (v3.0 — Flutter tamamen kaldırıldı, saf Kotlin)
Standart Gradle Android projesi: `app/src/main/kotlin/com/ganyansosyal/fotoproje/`

| Dosya | Görev |
|---|---|
| `BrowserActivity.kt` | Tarayıcı ekranı (sekmeler, WebView, menü, blob indirme, probe/indirme tetikleme) |
| `Backend.kt` | BrowserActivity'nin istekleri (`cfg`, `home`, `probe`, `download`, `youtube`, geçmiş, yer imi, favori) |
| `Store.kt` | Ayarlar, geçmiş, yer imleri, favoriler (SharedPreferences) + `L` (TR/EN metin sistemi) |
| `Strs.kt` | TR/EN metinler (eski `strings.dart`'tan otomatik aktarıldı) |
| `Downloads.kt` | İndirme yöneticisi (kuyruk, duraklat/devam, birleştirme `Mux`, galeriye ekleme `Gallery`) |
| `Streams.kt` | HLS / DASH / doğrudan dosya indirme (`Native.segments` / `Native.direct` kullanır) |
| `Native.kt` | Paralel indirme motoru (`Http`, `Native`, `DlService`) |
| `Probe.kt` | Bulunan video adaylarını inceler (süre, çözünürlük, boyut) |
| `Net.kt` | İstek başlıkları (Referer/Origin/Cookie), metin getirme |
| `YouTube.kt` | YouTube akışı: NewPipeExtractor |
| `Media.kt` | Video algılama JS'i, no-autoplay JS'i, biçim yardımcıları |
| `HomeHtml.kt`, `FavIcons.kt` | Ana sayfa HTML'i ve logo önbelleği |
| `DownloadsActivity.kt`, `SettingsActivity.kt` | İndirmeler ve Ayarlar ekranları (saf Android görünümleri) |
| `AdBlock.kt`, `TabState.kt` | Reklam engelleme, sekme durumu |

Derleme: `.github/workflows/build.yml` → `gradle :app:assembleRelease` → `app-release.apk` (debug anahtarıyla imzalı).

## Yapılanlar
- Flutter/Dart tamamen çıkarıldı; `lib/`, `pubspec.yaml`, `flutter create` adımları yok.
- Tüm Dart işlevleri Kotlin'e taşındı: ayarlar, geçmiş, yer imi, favori, i18n, indirme yöneticisi,
  HLS/DASH, probe, favicon önbelleği, ana sayfa, İndirmeler ve Ayarlar ekranları.
- `BrowserActivity` aynı `dart(...)` çağrı adlarını korur ama artık `Backend.call` doğrudan Kotlin'i çağırır.
- Sistem varsayılan tarayıcısı olabilir (http/https VIEW intent-filter, `singleTask` + `onNewIntent`).
- Uygulama doğrudan `BrowserActivity` ile açılır (Flutter motoru beklemek yok).
- YouTube: `youtube_explode_dart` yerine NewPipeExtractor (JitPack, `v0.24.4`).

- Video sniffer güçlendirildi: JS hook'u (`Media.HOOK_JS`) bulduğu her yeni adayı `callHandler('cand', url, kind)` ile
  hemen Kotlin'e bildirir (`onJs` -> `noteNet(..., forceKind)`); JSON API yanıtlarındaki `.m3u8/.mpd/.mp4` adresleri
  tüm sitelerde taranır (`apiScan`). Ağ katmanı (`shouldInterceptRequest` -> `noteNet`) aynen korundu.

## Bilinen durum / uyarılar
- **Kod derlenip denenmedi** (taşıma ortamında Kotlin derleyicisi / ağ yoktu). İlk build'de hata çıkabilir; log'a göre düzelt.
- NewPipeExtractor sürümü (`v0.24.4`) doğrulanmadı; JitPack'te yoksa güncel etiketi kullan. `Downloader` arayüzü sürüme göre
  küçük farklar gösterebilir (`YouTube.kt`).
- Eski Flutter verileri (SharedPreferences `flutter.*` anahtarları) taşınmadı: ayarlar/geçmiş/favoriler sıfırdan başlar.
  Eski sürümün üstüne kurulursa `fp_native` sekmeleri korunur.
- Duraklat şu an iptal edip Devam'da BAŞTAN başlatır (gerçek devam için Range tabanlı kısmi dosya gerekir).
- Blob video iframe içindeyse okunamayabilir.
- Galeriye ekleme Android 10+ ta MediaStore ile yapılır (Movies/VideoDownloader).

## Sıradakiler
1. İlk build'i al, derleme hatalarını düzelt.
2. Cihazda dene: açılış, sekmeler, geri tuşu, ana sayfa/favoriler, ayarlar (tema/dil), indirme (mp4/HLS/DASH/blob/YouTube), duraklat/devam, paylaş/aç.
3. Yerel ekrandan indirme ilerlemesini göster (şu an İndirmeler ekranına gitmek gerekiyor).
4. Site izinleri: konum / kamera / mikrofon (`onPermissionRequest`).
5. Gerçek "kaldığı yerden devam" (Range + kısmi dosya).
6. Uygulama simgesi (şu an basit vektör).
