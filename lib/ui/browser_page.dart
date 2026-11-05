import 'dart:async';
import 'dart:collection';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:share_plus/share_plus.dart';

import '../core/adblock.dart';
import '../core/media.dart';
import '../core/settings.dart';
import '../core/strings.dart';
import '../services/download_manager.dart';
import '../services/fav_icons.dart';
import '../services/probe.dart';
import 'perf_meter.dart';
import 'downloads_pages.dart';
import 'home_html.dart';
import 'lists.dart';
import 'settings_page.dart';
import 'tab_data.dart';
import 'tabs_panel.dart';

const _green = Color(0xFF1E9E4A);

const _mobileUa =
    'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
    'Chrome/124.0.0.0 Mobile Safari/537.36';
const _desktopUa =
    'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) '
    'Chrome/124.0.0.0 Safari/537.36';

class BrowserPage extends StatefulWidget {
  final AppSettings settings;
  final DownloadManager downloads;
  const BrowserPage({super.key, required this.settings, required this.downloads});

  @override
  State<BrowserPage> createState() => _BrowserPageState();
}

class _BrowserPageState extends State<BrowserPage>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  final List<TabData> tabs = [TabData(homeUrl)];
  int cur = 0;
  int section = 0; // 0 sekme, 1 ilerleme, 2 bitti
  bool panelOpen = false;
  bool _dark = false;
  bool barShown = true; // üst bar (kaydırınca gizlenir)
  bool fullScreen = false; // tarayıcı tam ekran: tüm çubuklar gizli, sayfa tüm ekran
  void _setFull(bool v) {
    if (fullScreen == v) return;
    setState(() {
      fullScreen = v;
      panelOpen = false;
      if (v) section = 0;
    });
    SystemChrome.setEnabledSystemUIMode(
        v ? SystemUiMode.immersiveSticky : SystemUiMode.edgeToEdge);
  }

  // Üst bar kaydırınca gizlensin mi? Sayfa (WebView) hiç taşınmaz/boyutlanmaz;
  // bar bağımsız olarak sayfanın üstünde kayar (GPU, sadece bar yeniden çizilir).
  // Sorun çıkarsa false yapmak eski sabit bara döndürür.
  static const bool _autoHideBar = false;
  static const double _barH = 54; // üst bar yüksekliği (52 + 2 ilerleme çizgisi)
  // 1 = bar görünür, 0 = gizli. Sadece barı yeniden çizer.
  late final AnimationController _barAnim = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 180), value: 1);
  double _barTarget = 1;
  void _syncBar() {
    final want = barShown ? 1.0 : 0.0;
    if (want == _barTarget) return;
    _barTarget = want;
    _barAnim.animateTo(want, curve: Curves.easeOutCubic);
  }

  // Üst bar sayfanın üstüne biner; kaplamalar barın altından başlar
  double get _top => (section == 0 && barShown && !fullScreen) ? _barH : 0;
  final _meter = PerfMeter();
  bool _meterOn = false;
  DateTime _lastBarToggle = DateTime.fromMillisecondsSinceEpoch(0);
  MediaInfo? _bannerInfo;
  bool _bannerOn = false;
  Timer? _bannerTimer;
  String _lastAds = '';
  String _lastKey = '';

  final tc = TextEditingController();
  final focus = FocusNode();
  bool editing = false; // adres çubuğu tam ekran düzenleme modu
  bool _kbWas = false;
  bool _hold = false; // klavye tam kapanana kadar WebView boyutlanmasın

  AppSettings get s => widget.settings;
  DownloadManager get dm => widget.downloads;
  TabData get t => tabs[cur];

  @override
  void initState() {
    super.initState();
    _lastAds = '${s.blockAds}';
    s.addListener(_onSettings);
    WidgetsBinding.instance.addObserver(this);
    focus.addListener(() {
      if (focus.hasFocus) {
        setState(() {
          barShown = true;
          editing = true;
          _hold = true;
        });
        // Dokunuşun imleci yerleştirmesinden sonra tümünü seç
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (focus.hasFocus) {
            tc.selection =
                TextSelection(baseOffset: 0, extentOffset: tc.text.length);
          }
        });
      } else {
        tc.text = shownUrl(t.url);
        if (editing) setState(() => editing = false);
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && View.of(context).viewInsets.bottom == 0 && _hold) {
            setState(() => _hold = false);
          }
        });
      }
    });
  }

  // Klavye kapanınca (geri tuşu) düzenleme modundan çık
  @override
  void didChangeMetrics() {
    final b = View.of(context).viewInsets.bottom;
    if (_kbWas && b == 0 && editing) focus.unfocus();
    if (b == 0 && _hold && !editing && mounted) setState(() => _hold = false);
    _kbWas = b > 0;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final d = Theme.of(context).brightness == Brightness.dark;
    if (d != _dark) {
      _dark = d;
      _refreshHomeTabs();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    s.removeListener(_onSettings);
    _bannerTimer?.cancel();
    _meter.stop();
    if (fullScreen) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    _barAnim.dispose();
    tc.dispose();
    focus.dispose();
    for (final x in tabs) {
      x.dispose();
    }
    super.dispose();
  }

  // ---------------------------------------------------------------- ayarlar

  List<ContentBlocker> _blockers() =>
      s.blockAds ? buildContentBlockers(ads: true) : <ContentBlocker>[];

  void _onSettings() {
    final adKey = '${s.blockAds}';
    if (adKey != _lastAds) {
      _lastAds = adKey;
      for (final x in tabs) {
        x.c?.setSettings(
            settings: InAppWebViewSettings(contentBlockers: _blockers()));
      }
    }
    final key = '${s.lang}|${s.recentSites}|${s.engine}|${s.bookmarks.length}';
    if (key != _lastKey) {
      _lastKey = key;
      FavIcons.i.ensure(homeHosts(s));
      _refreshHomeTabs();
    }
  }

  void _refreshHomeTabs() {
    for (final x in tabs) {
      if (x.url == homeUrl) _loadHome(x);
    }
  }

  void _loadHome(TabData x) {
    x.homeHtml = buildHomeHtml(s, _dark);
    x.c?.loadData(data: x.homeHtml!, baseUrl: WebUri(homeBase));
  }

  // ---------------------------------------------------------------- gezinme

  void go(String text) {
    text = text.trim();
    if (text.isEmpty) return;
    final isUrl = text.contains('.') && !text.contains(' ');
    final url = isUrl
        ? (text.startsWith('http') ? text : 'https://$text')
        : s.searchUrl(text);
    focus.unfocus();
    t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(url)));
  }

  void openUrl(String u) {
    setState(() {
      section = 0;
      panelOpen = false;
    });
    if (u == homeUrl) {
      t.url = homeUrl;
      _loadHome(t);
      tc.text = '';
    } else {
      t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(u)));
    }
  }

  void selectTab(int i) {
    if (i == cur) {
      setState(() => panelOpen = false);
      return;
    }
    final old = t;
    setState(() {
      cur = i;
      panelOpen = false;
      section = 0;
      barShown = true;
      _bannerOn = false;
    });
    try {
      old.c?.pause();
      t.c?.resume();
    } catch (_) {}
    tc.text = shownUrl(t.url);
  }

  void newTab([String? url]) {
    final old = t;
    setState(() {
      tabs.add(TabData(url ?? homeUrl));
      cur = tabs.length - 1;
      panelOpen = false;
      section = 0;
    });
    try {
      old.c?.pause();
    } catch (_) {}
    tc.text = url ?? '';
  }

  void closeTab(int i) {
    setState(() {
      final removed = tabs[i];
      if (tabs.length == 1) {
        tabs[0] = TabData(homeUrl);
        cur = 0;
      } else {
        tabs.removeAt(i);
        if (cur >= tabs.length) {
          cur = tabs.length - 1;
        } else if (i < cur) {
          cur--;
        }
      }
      // WebView widget'ı kaldırılmadan önce dispose etme (çökme olmasın)
      WidgetsBinding.instance.addPostFrameCallback((_) => removed.dispose());
    });
    try {
      t.c?.resume();
    } catch (_) {}
    tc.text = shownUrl(t.url);
  }

  void closeAll() {
    final old = List<TabData>.from(tabs);
    setState(() {
      tabs
        ..clear()
        ..add(TabData(homeUrl));
      cur = 0;
      panelOpen = false;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      for (final x in old) {
        x.dispose();
      }
    });
    tc.text = '';
  }

  Future<void> toggleDesktop() async {
    final x = t;
    x.desktop = !x.desktop;
    await x.c?.setSettings(
        settings: InAppWebViewSettings(
      userAgent: x.desktop ? _desktopUa : _mobileUa,
      preferredContentMode: x.desktop
          ? UserPreferredContentMode.DESKTOP
          : UserPreferredContentMode.MOBILE,
    ));
    if (x.url != homeUrl) x.c?.reload();
    if (mounted) setState(() {});
  }

  void _toast(String m, {SnackBarAction? action, int ms = 2500}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(m),
        behavior: SnackBarBehavior.floating,
        action: action,
        duration: Duration(milliseconds: ms),
      ));
  }

  // ---------------------------------------------------------------- indirme

  Future<String?> _readBlob(String url) async {
    final r = await t.c?.callAsyncJavaScript(functionBody: '''
      const b = await (await fetch(url)).blob();
      return await new Promise((res, rej) => {
        const f = new FileReader();
        f.onload = () => res(f.result.split(',')[1]);
        f.onerror = rej;
        f.readAsDataURL(b);
      });
    ''', arguments: {'url': url});
    final v = r?.value;
    return v is String ? v : null;
  }

  Future<void> _startDownload(String url, {TabData? from, String? hint}) async {
    final x = from ?? t;
    final err = await dm.start(url,
        referer: x.playerRef ?? x.url,
        hint: hint ?? x.hints[url],
        title: x.title.isEmpty ? null : x.title,
        blobReader: _readBlob);
    _afterStart(err);
  }

  void _startInfo(MediaInfo i) => _startDownload(i.url, hint: i.kind);

  void _afterStart(String? err) {
    if (err != null) {
      _toast(err);
      return;
    }
    _toast(S.t('downloadStarted'), ms: 1200);
  }

  /// Yeşil düğme: en iyi videoyu hemen indirir.
  Future<void> downloadPressed() async {
    if (isYoutubeWatch(t.url)) {
      _afterStart(await dm.startYoutube(t.url, title: t.title));
      return;
    }
    final items = await _findNow();
    if (!mounted) return;
    if (items.isEmpty) {
      _toast(S.t('noVideo'));
      return;
    }
    _hideBanner();
    _startInfo(items.first);
  }

  /// "!" düğmesi: bulunan tüm videoları açıklamalı listeler.
  Future<void> showFound() async {
    final x = t;
    final items = await _findNow();
    if (!mounted) return;
    if (items.isEmpty) {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(S.t('videosFound')),
          content: Text('${S.t('detectInfo')}\n\n${S.t('noVideo')}'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx), child: Text(S.t('ok')))
          ],
        ),
      );
      return;
    }
    showMediaSheet(context, items, (i) => _startDownload(i.url, from: x, hint: i.kind));
  }

  Future<List<MediaInfo>> _findNow() async {
    final x = t;
    // Oynayan/açık video yoksa arama yapılmaz.
    if (x.playerId == null) return const [];
    var items = x.found.value;
    if (items.isNotEmpty) return items;
    _toast(S.t('checking'));
    final gen = ++x.probeGen;
    items = await _probePlayer(x);
    if (gen == x.probeGen && x.playerId != null) x.found.value = items;
    return items;
  }

  Future<List<MediaInfo>> _probePlayer(TabData x) {
    final now = DateTime.now();
    final net = [
      for (final c in x.netCands)
        if (now.difference(c.t).inSeconds < 90) c
    ];
    final urls = <String>{
      ...x.media,
      for (final c in net) c.url,
    }.where((u) => u.startsWith('http') || u.startsWith('blob:')).toList();
    final hints = {...x.hints, for (final c in net) c.url: c.kind};
    return probeAll(urls, hints, x.playerRef ?? x.url,
        expectDur: x.playerLive ? 0 : x.playerDur,
        exactSrc: x.playerSrc,
        height: x.playerH);
  }

  void _scheduleProbe(TabData x) {
    x.probeTimer?.cancel();
    if (x.playerId == null) {
      x.found.value = const [];
      return;
    }
    x.probeTimer = Timer(const Duration(milliseconds: 600), () async {
      if (x.disposed) return;
      final gen = ++x.probeGen;
      final res = await _probePlayer(x);
      if (x.disposed || gen != x.probeGen || x.playerId == null) return;
      x.found.value = res;
      if (res.isNotEmpty && x == t && mounted && !x.bannerDone) {
        x.bannerDone = true;
        _showBanner(res.first);
      }
    });
  }

  void _showBanner(MediaInfo i) {
    _bannerTimer?.cancel();
    setState(() {
      _bannerInfo = i;
      _bannerOn = true;
    });
    _bannerTimer = Timer(const Duration(seconds: 9), _hideBanner);
  }

  void _hideBanner() {
    _bannerTimer?.cancel();
    if (_bannerOn && mounted) setState(() => _bannerOn = false);
  }

  // ---------------------------------------------------------------- menü

  Future<void> _menu(String v) async {
    switch (v) {
      case 'new':
        newTab();
      case 'share':
        if (t.url != homeUrl) Share.share(t.url);
      case 'history':
        Navigator.push(context, fastRoute(HistoryPage(s: s, open: openUrl)));
      case 'copy':
        await Clipboard.setData(ClipboardData(text: t.url));
        _toast(S.t('linkCopied'));
      case 'bookmarks':
        showBookmarksSheet(context, s, openUrl);
      case 'addbm':
        if (t.url != homeUrl) s.toggleBookmark(t.url);
      case 'desktop':
        toggleDesktop();
      case 'full':
        _setFull(true);
      case 'meter':
        _toggleMeter();
      case 'shareapp':
        Share.share(S.t('shareAppText'));
      case 'settings':
        setState(() {
          section = 3;
          panelOpen = false;
        });
    }
  }

  void _toggleMeter() {
    setState(() => _meterOn = !_meterOn);
    if (_meterOn) {
      _meter.start();
      for (final x in tabs) {
        x.c?.evaluateJavascript(source: perfStartJs);
      }
    } else {
      _meter.stop();
      for (final x in tabs) {
        x.c?.evaluateJavascript(source: perfStopJs);
      }
    }
  }

  PopupMenuItem<String> _mi(String v, IconData i, String text, {Widget? trailing}) {
    final c = Theme.of(context).colorScheme.onSurfaceVariant;
    return PopupMenuItem<String>(
      value: v,
      height: 46,
      child: Row(
        children: [
          Icon(i, color: c, size: 20),
          const SizedBox(width: 14),
          Expanded(
              child: Text(text,
                  style: const TextStyle(
                      fontWeight: FontWeight.w500, fontSize: 14.5))),
          if (trailing != null) trailing,
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- arayüz

  Widget _topBar(ColorScheme cs) {
    final bar = cs.surfaceContainerHighest;
    final bm = s.isBookmarked(t.url);
    return Material(
      color: cs.surface,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: 52,
            child: Row(
              children: [
                const SizedBox(width: 10),
                GestureDetector(
                  onTap: () => setState(() => panelOpen = !panelOpen),
                  child: Container(
                    width: 24,
                    height: 24,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      border: Border.all(color: cs.onSurface, width: 1.6),
                      borderRadius: BorderRadius.circular(5),
                    ),
                    child: Text('${tabs.length}',
                        style: const TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 12)),
                  ),
                ),
                IconButton(
                    iconSize: 22,
                    icon: const Icon(Icons.home_rounded),
                    onPressed: () => openUrl(homeUrl)),
                Expanded(
                  child: Container(
                    height: 40,
                    padding: const EdgeInsets.only(left: 16),
                    decoration: BoxDecoration(
                        color: bar, borderRadius: BorderRadius.circular(20)),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: tc,
                            focusNode: focus,
                            onSubmitted: go,
                            keyboardType: TextInputType.url,
                            textInputAction: TextInputAction.go,
                            maxLines: 1,
                            style: const TextStyle(
                                fontSize: 14, fontWeight: FontWeight.w500),
                            decoration: InputDecoration(
                                isCollapsed: true,
                                border: InputBorder.none,
                                hintText: S.t('search')),
                          ),
                        ),
                        if (editing)
                          IconButton(
                            iconSize: 20,
                            visualDensity: VisualDensity.compact,
                            icon: const Icon(Icons.keyboard_return_rounded),
                            onPressed: () => go(tc.text),
                          )
                        else
                          ValueListenableBuilder<bool>(
                            key: ObjectKey(t),
                            valueListenable: t.loading,
                            builder: (_, l, __) => IconButton(
                              iconSize: 20,
                              visualDensity: VisualDensity.compact,
                              icon: Icon(l ? Icons.close : Icons.refresh),
                              onPressed: () =>
                                  l ? t.c?.stopLoading() : t.c?.reload(),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
                ValueListenableBuilder<List<MediaInfo>>(
                  key: ObjectKey(t),
                  valueListenable: t.found,
                  builder: (_, list, __) {
                    final n = list.length;
                    return IconButton(
                      iconSize: 22,
                      tooltip: S.t('videosFound'),
                      icon: Badge(
                        isLabelVisible: n > 0,
                        label: Text('$n'),
                        backgroundColor: _green,
                        child: Icon(Icons.error_rounded,
                            size: 22, color: n > 0 ? _green : cs.onSurface),
                      ),
                      onPressed: showFound,
                    );
                  },
                ),
                PopupMenuButton<String>(
                  iconSize: 22,
                  icon: const Icon(Icons.more_vert),
                  onSelected: _menu,
                  itemBuilder: (_) => [
                    _mi('new', Icons.add_box_rounded, S.t('newTab')),
                    _mi('share', Icons.share, S.t('share')),
                    _mi('history', Icons.history, S.t('history')),
                    _mi('copy', Icons.copy_rounded, S.t('copyLink')),
                    _mi('bookmarks', Icons.bookmark_border, S.t('bookmarks')),
                    _mi(
                        'addbm',
                        bm ? Icons.bookmark_remove_outlined : Icons.bookmark_add_outlined,
                        bm ? S.t('removeBookmark') : S.t('addBookmark')),
                    _mi('full', Icons.fullscreen_rounded, 'Tam ekran'),
                    _mi('desktop', Icons.desktop_windows_rounded, S.t('desktop'),
                        trailing: IgnorePointer(
                            child: Checkbox(
                                value: t.desktop,
                                onChanged: (_) {},
                                visualDensity: VisualDensity.compact))),
                    _mi('meter', Icons.speed, S.t('perfMeter'),
                        trailing: IgnorePointer(
                            child: Checkbox(
                                value: _meterOn,
                                onChanged: (_) {},
                                visualDensity: VisualDensity.compact))),
                    _mi('shareapp', Icons.group, S.t('shareApp')),
                    _mi('settings', Icons.settings, S.t('settings')),
                  ],
                ),
              ],
            ),
          ),
          SizedBox(
            height: 2,
            child: ValueListenableBuilder<bool>(
              key: ObjectKey(t),
              valueListenable: t.loading,
              builder: (_, l, __) => l
                  ? ValueListenableBuilder<double>(
                      valueListenable: t.progress,
                      builder: (_, p, __) =>
                          LinearProgressIndicator(value: p, minHeight: 2),
                    )
                  : const SizedBox.shrink(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _webView(TabData x) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _syncBar();
    });
    final fresh = x.windowId == null;
    return InAppWebView(
      key: ObjectKey(x),
      windowId: x.windowId,
      initialUserScripts: UnmodifiableListView<UserScript>([
        UserScript(
            source: hookJs,
            injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
            forMainFrameOnly: false),
        UserScript(
            source: viewportJs,
            injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
            forMainFrameOnly: true),
        // Sayfa hep tam boyda; bar altında kalan üst boşluk sayfa içinden verilir
        // (sayfayla birlikte kayıp gider, WebView hiç oynamaz)
        if (_autoHideBar)
          UserScript(
              source: '(function(){var H=${_barH.toInt()};function a(){'
                  'var sw=screen.width||window.innerWidth||360;'
                  'var r=Math.max(1,(window.innerWidth||sw)/sw);'
                  'var e=document.documentElement; if(!e) return;'
                  'if(location.href.indexOf("$homeBase")===0) return;'
                  'e.style.setProperty("box-sizing","border-box","important");'
                  'e.style.setProperty("padding-top",(H*r)+"px","important");}'
                  'a(); window.addEventListener("resize",a);'
                  'document.addEventListener("DOMContentLoaded",a);})();',
              injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
              forMainFrameOnly: true),
        // Kaydırma dinleyicisi yalnızca bar gizleme açıkken eklenir
        if (_autoHideBar)
          UserScript(
              source: scrollJs,
              injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
              forMainFrameOnly: true),
      ]),
      initialUrlRequest: fresh && x.url != homeUrl
          ? URLRequest(url: WebUri(x.url))
          : null,
      initialData: fresh && x.url == homeUrl
          ? InAppWebViewInitialData(
              // Her yeniden çizimde HTML tekrar üretilmesin
              data: x.homeHtml ??= buildHomeHtml(s, _dark),
              baseUrl: WebUri(homeBase))
          : null,
      initialSettings: InAppWebViewSettings(
        javaScriptEnabled: true,
        javaScriptCanOpenWindowsAutomatically: true,
        supportMultipleWindows: true,
        domStorageEnabled: true,
        databaseEnabled: true,
        cacheEnabled: true,
        cacheMode: CacheMode.LOAD_DEFAULT,
        safeBrowsingEnabled: false,
        hardwareAcceleration: true,
        mediaPlaybackRequiresUserGesture: true, // videolar kendiliğinden oynamasın
        thirdPartyCookiesEnabled: true,
        useWideViewPort: true,
        loadWithOverviewMode: true,
        supportZoom: true,
        builtInZoomControls: true,
        displayZoomControls: false,
        useOnDownloadStart: true,
        useShouldOverrideUrlLoading: true,
        mixedContentMode: MixedContentMode.MIXED_CONTENT_COMPATIBILITY_MODE,
        // Akıcılık: hybrid composition = yerel WebView (dokunma/kaydırma doğrudan sisteme gider)
        useHybridComposition: true,
        // Performans: sayfaları zorla koyulaştırma (kaydırmayı çok yavaşlatır)
        forceDark: ForceDark.OFF,
        algorithmicDarkeningAllowed: false,
        // Sayfa başı/sonunda sert durmasın: Android'in yumuşak esneme (stretch)
        // / parlama efekti ile yumuşak iniş yapar.
        overScrollMode: OverScrollMode.ALWAYS,
        verticalScrollBarEnabled: false,
        horizontalScrollBarEnabled: false,
        transparentBackground: false,
        userAgent: _mobileUa,
        contentBlockers: _blockers(),
      ),
      onWebViewCreated: (c) {
        x.c = c;
        c.addJavaScriptHandler(
            handlerName: 'perf',
            callback: (args) {
              if (x == t && args.length >= 3) {
                _meter.page.value = [
                  for (final a in args.take(3)) (a as num).toInt()
                ];
              }
              return null;
            });
        c.addJavaScriptHandler(
            handlerName: 'player',
            callback: (args) {
              if (args.isEmpty || x.disposed) return null;
              if (x.onPlayer(args[0].toString())) {
                if (x.playerId == null && x == t) _hideBanner();
                _scheduleProbe(x);
              }
              return null;
            });
        c.addJavaScriptHandler(
            handlerName: 'scrollDir',
            callback: (args) {
              if (!_autoHideBar ||
                  x != t ||
                  section != 0 ||
                  args.isEmpty ||
                  !mounted) {
                return null;
              }
              final show = args[0].toString() != 'down';
              final now = DateTime.now();
              // Çok sık açılıp kapanmasın
              if (now.difference(_lastBarToggle).inMilliseconds < 500) {
                return null;
              }
              if (show != barShown && !focus.hasFocus) {
                _lastBarToggle = now;
                barShown = show;
                _syncBar();
                if (_bannerOn) setState(() {});
              }
              return null;
            });
        c.addJavaScriptHandler(
            handlerName: 'focusBar',
            callback: (args) {
              if (mounted && x == t) focus.requestFocus();
              return null;
            });
        c.addJavaScriptHandler(
            handlerName: 'addFav',
            callback: (args) {
              if (mounted) showAddFavDialog(context, s);
              return null;
            });
        c.addJavaScriptHandler(
            handlerName: 'favMenu',
            callback: (args) {
              if (args.length < 2 || !mounted) return null;
              final u = args[0].toString();
              if (s.isFavorite(u)) {
                confirmRemoveFav(context, s, u, args[1].toString());
              }
              return null;
            });
      },
      onCreateWindow: (c, action) async {
        final nt = TabData(action.request.url?.toString() ?? 'about:blank',
            windowId: action.windowId);
        setState(() {
          tabs.add(nt);
          cur = tabs.length - 1;
          section = 0;
        });
        return true;
      },
      onCloseWindow: (c) {
        final i = tabs.indexOf(x);
        if (i >= 0) closeTab(i);
      },
      shouldOverrideUrlLoading: (c, action) async {
        final sch = action.request.url?.scheme ?? 'https';
        const ok = ['http', 'https', 'about', 'file', 'data', 'blob', 'javascript'];
        return ok.contains(sch)
            ? NavigationActionPolicy.ALLOW
            : NavigationActionPolicy.CANCEL;
      },
      onDownloadStartRequest: (_, req) {
        final u = req.url.toString();
        final mt = (req.mimeType ?? '').toLowerCase();
        if (mt.startsWith('video/') ||
            mt.contains('mpegurl') ||
            mt.contains('dash+xml') ||
            mediaKind(u) != null) {
          _startDownload(u, from: x);
        }
      },
      onLoadStart: (_, u) {
        x.clearMedia();
        final u0 = u?.toString() ?? '';
        final internal = u0.startsWith('about:blank') ||
            u0.startsWith('https://home.local');
        x.loading.value = !internal;
        if (x == t && mounted) {
          if (!barShown || _bannerOn) {
            setState(() {
              barShown = true;
              _bannerOn = false;
            });
          }
        }
      },
      // Video tam ekran (YouTube vb.): sistem çubukları gizlenir (ekran dönmez);
      // çıkınca eski haline döner.
      onEnterFullscreen: (_) {
        SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
      },
      onExitFullscreen: (_) {
        SystemChrome.setEnabledSystemUIMode(fullScreen
            ? SystemUiMode.immersiveSticky
            : SystemUiMode.edgeToEdge);
      },
      onProgressChanged: (_, p) => x.progress.value = p / 100,
      onTitleChanged: (_, title) {
        x.title = title ?? '';
        s.updateHistoryTitle(x.url, x.title);
        if (panelOpen && mounted) setState(() {});
      },
      onUpdateVisitedHistory: (_, u, __) {
        final nu = normUrl(u?.toString(), x.url);
        final changed = nu != x.url;
        x.url = nu;
        if (x == t) {
          if (!focus.hasFocus) tc.text = shownUrl(nu);
          if (changed && mounted) setState(() {});
        }
      },
      onLoadStop: (_, u) {
        x.loading.value = false;
        if (_meterOn) x.c?.evaluateJavascript(source: perfStartJs);
        x.url = normUrl(u?.toString(), x.url);
        s.addHistory(x.url, x.title);
        if (x == t) {
          if (!focus.hasFocus) tc.text = shownUrl(x.url);
          if (mounted) setState(() {});
        }
      },
    );
  }

  Widget _banner(ColorScheme cs) {
    final i = _bannerInfo;
    return Material(
      elevation: 6,
      color: cs.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Row(
          children: [
            const Icon(Icons.play_circle_fill_rounded, color: _green, size: 30),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(S.t('videoFound'),
                      style: const TextStyle(fontWeight: FontWeight.w700)),
                  if (i != null)
                    Text(i.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            IconButton(
                icon: const Icon(Icons.close, size: 20), onPressed: _hideBanner),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
        child: Align(
            alignment: Alignment.centerLeft,
            child: Text(text, style: Theme.of(context).textTheme.titleLarge)),
      );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final panelW = MediaQuery.of(context).size.width * .76;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (fullScreen) {
          _setFull(false);
        } else if (panelOpen) {
          setState(() => panelOpen = false);
        } else if (section != 0) {
          setState(() => section = 0);
        } else if (t.c != null && await t.c!.canGoBack()) {
          t.c!.goBack();
        } else if (tabs.length > 1) {
          closeTab(cur);
        } else {
          SystemNavigator.pop();
        }
      },
      // Klavye animasyonu sürerken WebView her karede yeniden boyutlanmasın;
      // klavye yerleşince bir kez boyutlanır.
      child: SettledInsets(
          child: Scaffold(
        // Adres düzenlerken WebView klavyeyle yeniden boyutlanmasın (hızlı klavye)
        resizeToAvoidBottomInset: !(editing || _hold),
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              // Üst bar sayfayı yeniden boyutlandırmaz: sayfa sabit boyutta kalır,
              // bar kayarak gizlenir/görünür (zıplama ve yeniden yerleşim yok).
              if (section != 0)
                _sectionTitle(section == 1
                    ? S.t('progress')
                    : section == 2
                        ? S.t('done')
                        : S.t('settings')),
              Expanded(
                child: Stack(
                  children: [
                    IndexedStack(
                      index: section,
                      children: [
                        LayoutBuilder(builder: (ctx, box) {
                          final h = box.maxHeight;
                          return Stack(
                            clipBehavior: Clip.hardEdge,
                            children: [
                              // Sayfa (platform görünümü) animasyonla kaydırılmaz:
                              // her karede yeniden konumlandırmak takılma ve
                              // bulanıklık yapar. Tek karede yer değiştirir.
                              Positioned(
                                left: 0,
                                right: 0,
                                top: (_autoHideBar || fullScreen) ? 0 : _barH,
                                height: (_autoHideBar || fullScreen) ? h : h - _barH,
                                child: IndexedStack(
                                  index: cur,
                                  children: [for (final x in tabs) _webView(x)],
                                ),
                              ),
                              if (!fullScreen)
                              Positioned(
                                left: 0,
                                right: 0,
                                top: 0,
                                height: _barH,
                                child: RepaintBoundary(
                                  child: AnimatedBuilder(
                                    animation: _barAnim,
                                    builder: (_, c) => Transform.translate(
                                        offset: Offset(
                                            0, -_barH * (1 - _barAnim.value)),
                                        child: c),
                                    child: _topBar(cs),
                                  ),
                                ),
                              ),
                            ],
                          );
                        }),
                        ProgressPage(dm),
                        DonePage(dm),
                        SettingsPage(
                            s: s,
                            dm: dm,
                            embedded: true,
                            onHistoryCleared: () {
                              for (final x in tabs) {
                                x.c?.clearHistory();
                              }
                            }),
                      ],
                    ),
                    // Adres düzenleme: tam ekran beyaz alan
                    if (editing)
                      Positioned(
                        left: 0,
                        right: 0,
                        top: _top,
                        bottom: 0,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () => focus.unfocus(),
                          child: ColoredBox(color: cs.surface),
                        ),
                      ),
                    // Karartma
                    Positioned(
                      left: 0,
                      right: 0,
                      top: _top,
                      bottom: 0,
                      child: IgnorePointer(
                        ignoring: !panelOpen,
                        child: GestureDetector(
                          onTap: () => setState(() => panelOpen = false),
                          child: AnimatedOpacity(
                            duration: const Duration(milliseconds: 150),
                            opacity: panelOpen ? 1 : 0,
                            child: const ColoredBox(color: Color(0x73000000)),
                          ),
                        ),
                      ),
                    ),
                    // Tam ekrandan çıkış: küçük, yarı saydam düğme (geri tuşu da çıkarır)
                    if (fullScreen && section == 0)
                      Positioned(
                        top: 10,
                        right: 10,
                        child: Material(
                          color: Colors.black.withValues(alpha: .45),
                          shape: const CircleBorder(),
                          child: InkWell(
                            customBorder: const CircleBorder(),
                            onTap: () => _setFull(false),
                            child: const Padding(
                              padding: EdgeInsets.all(7),
                              child: Icon(Icons.fullscreen_exit_rounded,
                                  size: 20, color: Colors.white),
                            ),
                          ),
                        ),
                      ),
                    if (_meterOn)
                      Positioned(
                        top: _top + 6,
                        right: 6,
                        child: IgnorePointer(child: PerfPanel(m: _meter)),
                      ),
                    // Sekme paneli
                    Positioned(
                      left: 0,
                      top: _top,
                      bottom: 0,
                      width: panelW,
                      child: IgnorePointer(
                        ignoring: !panelOpen,
                        child: AnimatedSlide(
                          duration: const Duration(milliseconds: 190),
                          curve: Curves.easeOutCubic,
                          offset: panelOpen ? Offset.zero : const Offset(-1.05, 0),
                          child: RepaintBoundary(
                            child: TabsPanel(
                              tabs: tabs,
                              cur: cur,
                              onSelect: selectTab,
                              onClose: closeTab,
                              onNew: newTab,
                              onCloseAll: closeAll,
                              onFavorites: () {
                                setState(() => panelOpen = false);
                                showFavoritesSheet(context, s, openUrl,
                                    currentUrl: t.url, currentTitle: t.title);
                              },
                              onBack: () => t.c?.goBack(),
                              onHome: () => openUrl(homeUrl),
                              onForward: () => t.c?.goForward(),
                            ),
                          ),
                        ),
                      ),
                    ),
                    // "Video bulundu" kartı
                    Positioned(
                      left: 12,
                      right: 110,
                      bottom: 18,
                      child: IgnorePointer(
                        ignoring: !_bannerOn || section != 0,
                        child: AnimatedSlide(
                          duration: const Duration(milliseconds: 170),
                          curve: Curves.easeOutCubic,
                          offset: _bannerOn && section == 0
                              ? Offset.zero
                              : const Offset(0, 1.6),
                          child: AnimatedOpacity(
                            duration: const Duration(milliseconds: 120),
                            opacity: _bannerOn && section == 0 ? 1 : 0,
                            child: _banner(cs),
                          ),
                        ),
                      ),
                    ),
                    // İndirme düğmesi (video bulununca yeşil)
                    if (section == 0)
                      Positioned(
                        right: 16,
                        bottom: 16,
                        child: ValueListenableBuilder<List<MediaInfo>>(
                          key: ObjectKey(t),
                          valueListenable: t.found,
                          builder: (_, list, __) {
                            final on = list.isNotEmpty || isYoutubeWatch(t.url);
                            return AnimatedSize(
                              duration: const Duration(milliseconds: 150),
                              curve: Curves.easeOutCubic,
                              alignment: Alignment.centerRight,
                              child: Material(
                                elevation: on ? 6 : 2,
                                shape: const StadiumBorder(),
                                color: on
                                    ? _green
                                    : cs.onSurface.withValues(alpha: .38),
                                child: InkWell(
                                  customBorder: const StadiumBorder(),
                                  onTap: downloadPressed,
                                  child: Container(
                                    height: 40,
                                    padding: EdgeInsets.symmetric(
                                        horizontal: on ? 14 : 9),
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Icon(Icons.download_rounded,
                                            size: 20,
                                            color: on ? Colors.white : cs.surface),
                                        if (on) ...[
                                          const SizedBox(width: 8),
                                          Text(S.t('getVideo'),
                                              style: const TextStyle(
                                                  color: Colors.white,
                                                  fontWeight: FontWeight.w600,
                                                  fontSize: 13)),
                                        ],
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        // Alt çubuk klavyeyle birlikte yukarı kaymaz (takılma yapıyordu):
        // adres düzenlerken yerinde durur, klavye üstüne kayıp onu örter;
        // sayfadaki bir alana yazarken ise klavye açıkken gizlenir.
        bottomNavigationBar: fullScreen ? null : _KbHide(
            editing: editing || _hold,
            child: ListenableBuilder(
          listenable: dm,
          builder: (_, __) {
            // Bitti sekmesi açıkken kırmızı sayıyı sıfırla
            if (section == 2 && dm.unseen > 0) {
              WidgetsBinding.instance.addPostFrameCallback((_) => dm.markSeen());
            }
            return NavigationBar(
            height: 52,
            selectedIndex: section,
            onDestinationSelected: (i) => setState(() {
              section = i;
              panelOpen = false;
              barShown = true; // sekmeye dönünce bar görünür olsun
            }),
            destinations: [
              NavigationDestination(
                  icon: const Icon(Icons.layers_outlined),
                  selectedIcon: const Icon(Icons.layers_rounded),
                  label: S.t('tab')),
              NavigationDestination(
                  icon: Badge(
                      isLabelVisible: dm.active.isNotEmpty,
                      label: Text('${dm.active.length}'),
                      child: const Icon(Icons.download_outlined)),
                  selectedIcon: const Icon(Icons.download_rounded),
                  label: S.t('progress')),
              NavigationDestination(
                  icon: Badge(
                      isLabelVisible: dm.unseen > 0,
                      backgroundColor: Colors.red,
                      label: Text('${dm.unseen}'),
                      child: const Icon(Icons.folder_outlined)),
                  selectedIcon: Badge(
                      isLabelVisible: dm.unseen > 0,
                      backgroundColor: Colors.red,
                      label: Text('${dm.unseen}'),
                      child: const Icon(Icons.folder_rounded)),
                  label: S.t('done')),
              NavigationDestination(
                  icon: const Icon(Icons.settings_outlined),
                  selectedIcon: const Icon(Icons.settings_rounded),
                  label: S.t('settings')),
            ],
          );
          },
        )),
      )),
    );
  }
}

/// Alt çubuk klavyeyle hiç kaymaz ve hiçbir şeyi yeniden boyutlandırmaz.
/// - Adres düzenlerken: çubuk yerinde görünür kalır, klavye açılırken/kapanırken
///   üstüne kayıp onu örter (sayfa ve çubuk oynamaz).
/// - Sayfadaki bir alana yazarken: klavye açıkken tek seferde gizlenir.
class _KbHide extends StatelessWidget {
  final Widget child;
  final bool editing;
  const _KbHide({required this.child, required this.editing});

  @override
  Widget build(BuildContext context) {
    if (!editing && MediaQuery.viewInsetsOf(context).bottom > 0) {
      return const SizedBox.shrink();
    }
    return child;
  }
}

/// Klavye yüksekliğini animasyon bitince tek seferde uygular. Böylece klavye
/// açılıp kapanırken WebView (platform görünümü) her karede yeniden boyutlanmaz.
class SettledInsets extends StatefulWidget {
  final Widget child;
  const SettledInsets({super.key, required this.child});
  @override
  State<SettledInsets> createState() => _SettledInsetsState();
}

class _SettledInsetsState extends State<SettledInsets> {
  double _settled = 0; // sayfaya uygulanan klavye yüksekliği
  double _prev = 0; // önceki karedeki gerçek klavye yüksekliği
  double _full = 0; // en son görülen tam klavye yüksekliği
  Timer? _timer;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final cur = MediaQuery.viewInsetsOf(context).bottom;
    final opening = cur > _prev;
    final closing = cur < _prev;
    _prev = cur;
    _timer?.cancel();
    if (cur == _settled) return;
    // Düzen klavyeyi beklemez, önden gider: sayfa tek seferde, animasyonun
    // başında yerleşir. Böylece klavye "geç/yavaş" hissettirmez.
    if (cur == 0 || closing) {
      _settled = 0; // kapanırken hemen tam boyuta dön
    } else if (opening && _full > 0) {
      _settled = _full; // açılırken bilinen klavye yüksekliğini hemen uygula
    }
    // Yükseklik farklı çıkarsa (başka klavye/öneri çubuğu) durulunca düzelt
    if (cur != 0) {
      _timer = Timer(const Duration(milliseconds: 60), () {
        if (!mounted) return;
        final now = MediaQuery.viewInsetsOf(context).bottom;
        if (now > 0) _full = now;
        if (now != _settled) setState(() => _settled = now);
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    return MediaQuery(
      data: mq.copyWith(viewInsets: mq.viewInsets.copyWith(bottom: _settled)),
      child: widget.child,
    );
  }
}
