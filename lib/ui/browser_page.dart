import 'dart:collection';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:share_plus/share_plus.dart';

import '../core/adblock.dart';
import '../core/media.dart';
import '../core/settings.dart';
import '../core/strings.dart';
import '../services/download_manager.dart';
import 'downloads_pages.dart';
import 'home_html.dart';
import 'lists.dart';
import 'settings_page.dart';
import 'tab_data.dart';
import 'tabs_panel.dart';

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

class _BrowserPageState extends State<BrowserPage> {
  final List<TabData> tabs = [TabData(homeUrl)];
  int cur = 0;
  int section = 0; // 0 sekme, 1 ilerleme, 2 bitti
  bool panelOpen = false;
  bool _dark = false;
  bool _lastAds = false;
  String _lastKey = '';

  final tc = TextEditingController();
  final focus = FocusNode();

  AppSettings get s => widget.settings;
  DownloadManager get dm => widget.downloads;
  TabData get t => tabs[cur];

  @override
  void initState() {
    super.initState();
    _lastAds = s.blockAds;
    s.addListener(_onSettings);
    focus.addListener(() {
      if (focus.hasFocus) {
        tc.selection = TextSelection(baseOffset: 0, extentOffset: tc.text.length);
      } else {
        tc.text = shownUrl(t.url);
      }
    });
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
    s.removeListener(_onSettings);
    tc.dispose();
    focus.dispose();
    for (final x in tabs) {
      x.dispose();
    }
    super.dispose();
  }

  // ---------------------------------------------------------------- ayarlar

  void _onSettings() {
    if (s.blockAds != _lastAds) {
      _lastAds = s.blockAds;
      for (final x in tabs) {
        x.c?.setSettings(
            settings: InAppWebViewSettings(
                contentBlockers: s.blockAds ? buildContentBlockers() : []));
      }
    }
    final key = '${s.lang}|${s.recentSites}|${s.engine}|${s.bookmarks.length}';
    if (key != _lastKey) {
      _lastKey = key;
      _refreshHomeTabs();
    }
  }

  void _refreshHomeTabs() {
    for (final x in tabs) {
      if (x.url == homeUrl) _loadHome(x);
    }
  }

  void _loadHome(TabData x) {
    x.c?.loadData(
        data: buildHomeHtml(s, _dark), baseUrl: WebUri(homeBase));
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

  void _toast(String m, {SnackBarAction? action}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(m),
        behavior: SnackBarBehavior.floating,
        action: action,
        duration: const Duration(seconds: 3),
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

  Future<void> _startDownload(String url, {TabData? from}) async {
    final x = from ?? t;
    final err = await dm.start(url,
        referer: x.url,
        hint: x.hints[url],
        title: x.title.isEmpty ? null : x.title,
        blobReader: _readBlob);
    _afterStart(err);
  }

  void _afterStart(String? err) {
    if (err != null) {
      _toast(err);
      return;
    }
    _toast('${S.t('download')}…',
        action: SnackBarAction(
            label: S.t('progress'), onPressed: () => setState(() => section = 1)));
  }

  Future<void> downloadPressed() async {
    if (isYoutubeWatch(t.url)) {
      _afterStart(await dm.startYoutube(t.url, title: t.title));
      return;
    }
    final urls = await _collectMedia();
    if (!mounted) return;
    if (urls.isEmpty) {
      _toast(S.t('noVideo'));
    } else if (urls.length == 1) {
      _startDownload(urls.first);
    } else {
      final x = t;
      showMediaSheet(context, x, urls, (u) => _startDownload(u, from: x));
    }
  }

  Future<List<String>> _collectMedia() async {
    dynamic r;
    try {
      r = await t.c?.evaluateJavascript(source: """
        (function(){var r=[];
          document.querySelectorAll('video,audio,source').forEach(function(e){
            var u=e.currentSrc||e.src; if(u) r.push(u)});
          (window.__media||[]).forEach(function(u){r.push(u)});
          return r;})()
      """);
    } catch (_) {}
    final all = <String>{
      ...t.media,
      if (r is List)
        for (final u in r)
          if (u is String) u
    };
    return all.where((u) => u.startsWith('http') || u.startsWith('blob:')).toList()
      ..sort((a, b) =>
          (a.startsWith('blob:') ? 1 : 0) - (b.startsWith('blob:') ? 1 : 0));
  }

  // ---------------------------------------------------------------- menü

  Future<void> _menu(String v) async {
    switch (v) {
      case 'new':
        newTab();
      case 'share':
        if (t.url != homeUrl) Share.share(t.url);
      case 'history':
        Navigator.push(
            context,
            MaterialPageRoute(
                builder: (_) => HistoryPage(s: s, open: openUrl)));
      case 'copy':
        await Clipboard.setData(ClipboardData(text: t.url));
        _toast(S.t('linkCopied'));
      case 'bookmarks':
        showBookmarksSheet(context, s, openUrl);
      case 'addbm':
        if (t.url != homeUrl) s.toggleBookmark(t.url);
      case 'desktop':
        toggleDesktop();
      case 'shareapp':
        Share.share(S.t('shareAppText'));
      case 'settings':
        Navigator.push(
            context,
            MaterialPageRoute(
                builder: (_) => SettingsPage(
                    s: s,
                    dm: dm,
                    onHistoryCleared: () {
                      for (final x in tabs) {
                        x.c?.clearHistory();
                      }
                    })));
    }
  }

  PopupMenuItem<String> _mi(String v, IconData i, String text, {Widget? trailing}) {
    final c = Theme.of(context).colorScheme.onSurfaceVariant;
    return PopupMenuItem<String>(
      value: v,
      height: 52,
      child: Row(
        children: [
          Icon(i, color: c),
          const SizedBox(width: 16),
          Expanded(
              child: Text(text,
                  style: const TextStyle(fontWeight: FontWeight.w600))),
          if (trailing != null) trailing,
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- arayüz

  Widget _topBar(ColorScheme cs) {
    final bar = Color.alphaBlend(cs.primary.withValues(alpha: .08), cs.surface);
    final bm = s.isBookmarked(t.url);
    return Material(
      color: cs.surface,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: 60,
            child: Row(
              children: [
                const SizedBox(width: 12),
                GestureDetector(
                  onTap: () => setState(() => panelOpen = !panelOpen),
                  child: Container(
                    width: 30,
                    height: 30,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      border: Border.all(color: cs.onSurface, width: 2.2),
                      borderRadius: BorderRadius.circular(5),
                    ),
                    child: Text('${tabs.length}',
                        style: const TextStyle(
                            fontWeight: FontWeight.w800, fontSize: 15)),
                  ),
                ),
                IconButton(
                    icon: const Icon(Icons.home_rounded),
                    onPressed: () => openUrl(homeUrl)),
                Expanded(
                  child: Container(
                    height: 44,
                    padding: const EdgeInsets.only(left: 16),
                    decoration: BoxDecoration(
                        color: bar, borderRadius: BorderRadius.circular(22)),
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
                            decoration: InputDecoration(
                                isCollapsed: true,
                                border: InputBorder.none,
                                hintText: S.t('search')),
                          ),
                        ),
                        ValueListenableBuilder<bool>(
                          key: ObjectKey(t),
                          valueListenable: t.loading,
                          builder: (_, l, __) => IconButton(
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
                ValueListenableBuilder<int>(
                  key: ObjectKey(t),
                  valueListenable: t.mediaCount,
                  builder: (_, n, __) {
                    final on = n > 0 || isYoutubeWatch(t.url);
                    return IconButton(
                      icon: Badge(
                        isLabelVisible: n > 0,
                        label: Text('$n'),
                        child: Icon(Icons.error_rounded,
                            size: 28, color: on ? cs.primary : cs.onSurface),
                      ),
                      onPressed: downloadPressed,
                    );
                  },
                ),
                PopupMenuButton<String>(
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
                    _mi('desktop', Icons.desktop_windows_rounded, S.t('desktop'),
                        trailing: IgnorePointer(
                            child: Checkbox(
                                value: t.desktop,
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
    final fresh = x.windowId == null;
    return InAppWebView(
      key: ObjectKey(x),
      windowId: x.windowId,
      initialUserScripts: UnmodifiableListView<UserScript>([
        UserScript(
            source: hookJs,
            injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
            forMainFrameOnly: false),
      ]),
      initialUrlRequest: fresh && x.url != homeUrl
          ? URLRequest(url: WebUri(x.url))
          : null,
      initialData: fresh && x.url == homeUrl
          ? InAppWebViewInitialData(
              data: buildHomeHtml(s, _dark), baseUrl: WebUri(homeBase))
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
        mediaPlaybackRequiresUserGesture: false,
        thirdPartyCookiesEnabled: true,
        useWideViewPort: true,
        loadWithOverviewMode: true,
        supportZoom: true,
        builtInZoomControls: true,
        displayZoomControls: false,
        useOnDownloadStart: true,
        useShouldOverrideUrlLoading: true,
        mixedContentMode: MixedContentMode.MIXED_CONTENT_COMPATIBILITY_MODE,
        // Akıcılık: hybrid composition = yerel WebView çizimi (bulanıklık/titreme yok)
        useHybridComposition: true,
        overScrollMode: OverScrollMode.NEVER,
        verticalScrollBarEnabled: false,
        horizontalScrollBarEnabled: false,
        transparentBackground: false,
        userAgent: _mobileUa,
        contentBlockers: s.blockAds ? buildContentBlockers() : [],
      ),
      onWebViewCreated: (c) {
        x.c = c;
        c.addJavaScriptHandler(
            handlerName: 'media',
            callback: (args) {
              if (args.isEmpty) return null;
              x.addMedia(args[0].toString(),
                  args.length > 1 && args[1] != null ? args[1].toString() : null);
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
        x.loading.value = true;
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
        x.url = normUrl(u?.toString(), x.url);
        s.addHistory(x.url, x.title);
        if (x == t) {
          if (!focus.hasFocus) tc.text = shownUrl(x.url);
          if (mounted) setState(() {});
        }
      },
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
        if (panelOpen) {
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
      child: Scaffold(
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              if (section == 0)
                _topBar(cs)
              else
                _sectionTitle(section == 1 ? S.t('progress') : S.t('done')),
              Expanded(
                child: Stack(
                  children: [
                    IndexedStack(
                      index: section,
                      children: [
                        IndexedStack(
                          index: cur,
                          children: [for (final x in tabs) _webView(x)],
                        ),
                        ProgressPage(dm),
                        DonePage(dm),
                      ],
                    ),
                    // Karartma
                    Positioned.fill(
                      child: IgnorePointer(
                        ignoring: !panelOpen,
                        child: GestureDetector(
                          onTap: () => setState(() => panelOpen = false),
                          child: AnimatedOpacity(
                            duration: const Duration(milliseconds: 220),
                            opacity: panelOpen ? 1 : 0,
                            child: const ColoredBox(color: Color(0x73000000)),
                          ),
                        ),
                      ),
                    ),
                    // Sekme paneli
                    Positioned(
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: panelW,
                      child: IgnorePointer(
                        ignoring: !panelOpen,
                        child: AnimatedSlide(
                          duration: const Duration(milliseconds: 260),
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
                                showFavoritesSheet(context, s, openUrl);
                              },
                              onBack: () => t.c?.goBack(),
                              onHome: () => openUrl(homeUrl),
                              onForward: () => t.c?.goForward(),
                            ),
                          ),
                        ),
                      ),
                    ),
                    // İndirme düğmesi
                    if (section == 0)
                      Positioned(
                        right: 16,
                        bottom: 16,
                        child: ValueListenableBuilder<int>(
                          key: ObjectKey(t),
                          valueListenable: t.mediaCount,
                          builder: (_, n, __) {
                            final on = n > 0 || isYoutubeWatch(t.url);
                            return AnimatedScale(
                              scale: on ? 1 : .92,
                              duration: const Duration(milliseconds: 200),
                              child: Material(
                                elevation: on ? 6 : 2,
                                shape: const CircleBorder(),
                                color: on
                                    ? cs.primary
                                    : cs.onSurface.withValues(alpha: .38),
                                child: InkWell(
                                  customBorder: const CircleBorder(),
                                  onTap: downloadPressed,
                                  child: SizedBox(
                                    width: 62,
                                    height: 62,
                                    child: Icon(Icons.download_rounded,
                                        size: 34,
                                        color: on ? cs.onPrimary : cs.surface),
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
        bottomNavigationBar: ListenableBuilder(
          listenable: dm,
          builder: (_, __) => NavigationBar(
            height: 68,
            selectedIndex: section,
            onDestinationSelected: (i) => setState(() {
              section = i;
              panelOpen = false;
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
                  icon: const Icon(Icons.folder_outlined),
                  selectedIcon: const Icon(Icons.folder_rounded),
                  label: S.t('done')),
            ],
          ),
        ),
      ),
    );
  }
}
