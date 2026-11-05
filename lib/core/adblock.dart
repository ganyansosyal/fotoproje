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

/// Sayfadaki yaygın reklam kutularını gizleyen CSS seçicileri.
const _hideCss =
    'ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],'
    '[class*="ad-banner"],[class*="adbanner"],[class*="ad-container"],'
    '[id*="ad-slot"],[class*="sponsored"],iframe[src*="doubleclick"],'
    'iframe[src*="googlesyndication"]';

List<ContentBlocker> buildContentBlockers() {
  final list = <ContentBlocker>[];
  for (final h in _adHosts) {
    final esc = h.replaceAll('.', r'\.').replaceAll('/', r'\/');
    list.add(ContentBlocker(
      trigger: ContentBlockerTrigger(urlFilter: '.*$esc.*'),
      action: ContentBlockerAction(type: ContentBlockerActionType.BLOCK),
    ));
  }
  list.add(ContentBlocker(
    trigger: ContentBlockerTrigger(urlFilter: '.*'),
    action: ContentBlockerAction(
        type: ContentBlockerActionType.CSS_DISPLAY_NONE, selector: _hideCss),
  ));
  return list;
}
