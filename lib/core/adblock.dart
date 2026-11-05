import 'package:flutter_inappwebview/flutter_inappwebview.dart';

/// Bilinen reklam / izleyici alan adları. WebView içinde ağ seviyesinde engellenir.
const _adHosts = <String>[
  'doubleclick.net',
  'googlesyndication.com',
  'googleadservices.com',
  'google-analytics.com',
  'googletagservices.com',
  'adservice.google.com',
  'adnxs.com',
  'adsrvr.org',
  'taboola.com',
  'outbrain.com',
  'criteo.com',
  'criteo.net',
  'pubmatic.com',
  'rubiconproject.com',
  'openx.net',
  'moatads.com',
  'amazon-adsystem.com',
  'scorecardresearch.com',
  'quantserve.com',
  'adform.net',
  'smartadserver.com',
  'yieldmo.com',
  'mgid.com',
  'popads.net',
  'propellerads.com',
  'exoclick.com',
  'juicyads.com',
  'adsterra.com',
  'admob.com',
  'facebook.net/en_US/fbevents.js',
  'ads.yahoo.com',
  'advertising.com',
  'casalemedia.com',
  'serving-sys.com',
  'teads.tv',
  'zedo.com',
];

final _adSet = {for (final h in _adHosts) if (!h.contains('/')) h};
final _adPaths = [for (final h in _adHosts) if (h.contains('/')) h];

/// Reklam/izleyici adresi mi? Regex yok: alan adı sonekleri küme araması ile bakılır.
bool isAdUrl(String url) {
  final u = Uri.tryParse(url);
  if (u == null) return false;
  var h = u.host;
  while (h.isNotEmpty) {
    if (_adSet.contains(h)) return true;
    final i = h.indexOf('.');
    if (i < 0) break;
    h = h.substring(i + 1);
  }
  for (final p in _adPaths) {
    if (url.contains(p)) return true;
  }
  return false;
}

/// Sayfadaki yaygın reklam kutularını gizleyen CSS seçicileri.
const _hideCss =
    'ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],'
    '[class*="ad-banner"],[class*="adbanner"],[class*="ad-container"],'
    '[id*="ad-slot"],[class*="sponsored"],iframe[src*="doubleclick"],'
    'iframe[src*="googlesyndication"]';

List<ContentBlocker> buildContentBlockers({bool ads = true, bool fast = false}) {
  final list = <ContentBlocker>[];
  // Hızlı mod: resim ve yazı tipi dosyalarını hiç indirme (CSS/JS'e dokunulmaz;
  // oynatıcı ve sayfa düzeni bozulmasın). Kurallar WebView'ın kendi içinde (native) çalışır.
  if (fast) {
    list.add(ContentBlocker(
      trigger: ContentBlockerTrigger(
        urlFilter: '.*',
        resourceType: [
          ContentBlockerTriggerResourceType.IMAGE,
          ContentBlockerTriggerResourceType.FONT,
        ],
      ),
      action: ContentBlockerAction(type: ContentBlockerActionType.BLOCK),
    ));
  }
  if (!ads) return list;
  // Her istek tüm kurallara karşı denendiği için tek birleşik kural kullan.
  final alt = _adHosts
      .map((h) => h.replaceAll('.', r'\.').replaceAll('/', r'\/'))
      .join('|');
  list.add(ContentBlocker(
    trigger: ContentBlockerTrigger(urlFilter: '.*($alt).*'),
    action: ContentBlockerAction(type: ContentBlockerActionType.BLOCK),
  ));
  list.add(ContentBlocker(
    trigger: ContentBlockerTrigger(urlFilter: '.*'),
    action: ContentBlockerAction(
        type: ContentBlockerActionType.CSS_DISPLAY_NONE, selector: _hideCss),
  ));
  return list;
}
