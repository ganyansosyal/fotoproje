import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:gal/gal.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

final darkMode = ValueNotifier<bool>(true);
const home = 'about:home';
const homeBase = 'https://home.local/';
const defaultSites = <List<String>>[
  ['Google', 'https://www.google.com'],
  ['YouTube', 'https://m.youtube.com'],
  ['Instagram', 'https://www.instagram.com'],
  ['X', 'https://x.com'],
  ['Haberler', 'https://www.ntv.com.tr'],
  ['Harita', 'https://maps.google.com'],
  ['GitHub', 'https://github.com'],
  ['Vikipedi', 'https://tr.wikipedia.org'],
];

const hookJs = r'''
(function(){ if(window.__hk) return; window.__hk=1; window.__m3u8=[];
 function add(u){try{u=new URL(u,location.href).href;
  if(u.indexOf('.m3u8')>-1 && window.__m3u8.indexOf(u)<0) window.__m3u8.push(u);}catch(e){}}
 var o=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(m,u){add(u);return o.apply(this,arguments)};
 var f=window.fetch;
 if(f) window.fetch=function(i){add(typeof i==='string'?i:(i&&i.url));return f.apply(this,arguments)};
})();
''';

String shown(String u) => u == home ? '' : u;
String norm(String? u, String fallback) {
  if (u == null) return fallback;
  if (u.startsWith('https://home.local')) return home;
  return u;
}

String buildHome(List<String> bookmarks, bool dark) {
  const esc = HtmlEscape(HtmlEscapeMode.attribute);
  final sites = <List<String>>[...defaultSites];
  for (final b in bookmarks) {
    if (sites.any((e) => e[1] == b)) continue;
    final host = Uri.tryParse(b)?.host ?? b;
    sites.add([host.replaceFirst('www.', ''), b]);
  }
  final tiles = StringBuffer();
  for (final e in sites) {
    final name = e[0];
    final letter = name.isEmpty ? '?' : name[0].toUpperCase();
    tiles.write('<a class="t" href="${esc.convert(e[1])}">'
        '<div class="i">${esc.convert(letter)}</div>'
        '<span>${esc.convert(name)}</span></a>');
  }
  final bg = dark ? '#121212' : '#f5f5f5';
  final fg = dark ? '#eeeeee' : '#111111';
  final card = dark ? '#2a2a2e' : '#ffffff';
  return '''<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;background:$bg;color:$fg;font-family:sans-serif;padding:28px 16px}
h1{text-align:center;font-weight:300;margin:30px 0 20px}
form{max-width:520px;margin:0 auto 30px}
input{width:100%;box-sizing:border-box;padding:14px 18px;border-radius:28px;border:0;
background:$card;color:$fg;font-size:16px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:18px 8px;max-width:520px;margin:auto}
.t{text-decoration:none;color:$fg;text-align:center;font-size:12px}
.i{width:56px;height:56px;border-radius:50%;background:$card;margin:0 auto 6px;
display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:600}
.t span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style></head><body>
<h1>Ana Sayfa</h1>
<form action="https://www.google.com/search" method="get">
<input name="q" placeholder="Ara veya adres yaz" autocomplete="off"></form>
<div class="g">$tiles</div></body></html>''';
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final p = await SharedPreferences.getInstance();
  darkMode.value = p.getBool('dark') ?? true;
  runApp(ValueListenableBuilder<bool>(
    valueListenable: darkMode,
    builder: (_, d, __) => MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: d ? Brightness.dark : Brightness.light,
      ),
      home: const Browser(),
    ),
  ));
}

class TabData {
  InAppWebViewController? c;
  String url;
  String title = '';
  final ValueNotifier<double> progress = ValueNotifier<double>(0);
  bool opened = false;
  TabData(this.url);
}

class Browser extends StatefulWidget {
  const Browser({super.key});
  @override
  State<Browser> createState() => _BrowserState();
}

class _BrowserState extends State<Browser> {
  final List<TabData> tabs = [TabData(home)..opened = true];
  int cur = 0;
  final tc = TextEditingController(text: '');
  List<String> bookmarks = [];
  SharedPreferences? prefs;

  TabData get t => tabs[cur];

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) {
      prefs = p;
      setState(() => bookmarks = p.getStringList('bm') ?? []);
      if (t.url == home) {
        t.c?.loadData(
            data: buildHome(bookmarks, darkMode.value),
            baseUrl: WebUri(homeBase));
      }
    });
  }

  void go(String text) {
    text = text.trim();
    if (text.isEmpty) return;
    final isUrl = text.contains('.') && !text.contains(' ');
    final url = isUrl
        ? (text.startsWith('http') ? text : 'https://$text')
        : 'https://www.google.com/search?q=${Uri.encodeComponent(text)}';
    t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(url)));
  }

  void selectTab(int i) {
    setState(() {
      cur = i;
      t.opened = true;
    });
    tc.text = shown(t.url);
  }

  void newTab() {
    setState(() {
      tabs.add(TabData(home)..opened = true);
      cur = tabs.length - 1;
    });
    tc.text = '';
  }

  void closeTab(int i) {
    setState(() {
      if (tabs.length == 1) {
        tabs[0] = TabData(home)..opened = true;
        cur = 0;
      } else {
        tabs.removeAt(i);
        if (cur >= tabs.length) {
          cur = tabs.length - 1;
        } else if (i < cur) {
          cur--;
        }
      }
      t.opened = true;
    });
    tc.text = shown(t.url);
  }

  void toggleBookmark() {
    if (t.url == home) return;
    setState(() {
      if (bookmarks.contains(t.url)) {
        bookmarks.remove(t.url);
      } else {
        bookmarks.add(t.url);
      }
    });
    prefs?.setStringList('bm', bookmarks);
  }

  Future<void> downloadHls(
      String url, String path, ValueNotifier<String> prog) async {
    final dio = Dio();
    final h = {'Referer': t.url};
    Future<String> text(String u) async => (await dio.get<String>(u,
            options: Options(headers: h, responseType: ResponseType.plain)))
        .data!;
    Future<List<int>> bytes(Uri u) async {
      for (var k = 0;; k++) {
        try {
          final r = await dio.get<List<int>>(u.toString(),
              options: Options(headers: h, responseType: ResponseType.bytes));
          return r.data!;
        } catch (e) {
          if (k >= 2) rethrow;
        }
      }
    }

    var pl = url;
    var body = await text(pl);
    var lines = body.split('\n').map((e) => e.trim()).toList();
    String? best;
    var bw = -1;
    for (var i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
      final m = RegExp(r'BANDWIDTH=(\d+)').firstMatch(lines[i]);
      final b = int.tryParse(m?.group(1) ?? '') ?? 0;
      var j = i + 1;
      while (j < lines.length && (lines[j].isEmpty || lines[j].startsWith('#'))) {
        j++;
      }
      if (j < lines.length && b > bw) {
        bw = b;
        best = lines[j];
      }
    }
    if (best != null) {
      pl = Uri.parse(pl).resolve(best).toString();
      body = await text(pl);
      lines = body.split('\n').map((e) => e.trim()).toList();
    }
    final base = Uri.parse(pl);
    Uri? init;
    final segs = <Uri>[];
    for (final l in lines) {
      if (l.startsWith('#EXT-X-KEY') && !l.contains('METHOD=NONE')) {
        throw 'Şifreli yayın desteklenmiyor';
      }
      if (l.startsWith('#EXT-X-MAP')) {
        final m = RegExp(r'URI="([^"]+)"').firstMatch(l);
        if (m != null) init = base.resolve(m.group(1)!);
      } else if (l.isNotEmpty && !l.startsWith('#')) {
        segs.add(base.resolve(l));
      }
    }
    if (segs.isEmpty) throw 'Video parçaları bulunamadı';
    final sink = File(path).openWrite();
    try {
      if (init != null) sink.add(await bytes(init));
      for (var i = 0; i < segs.length; i += 4) {
        final end = i + 4 < segs.length ? i + 4 : segs.length;
        final parts = await Future.wait([
          for (var j = i; j < end; j++) bytes(segs[j]),
        ]);
        for (final p in parts) {
          sink.add(p);
        }
        prog.value = 'İndiriliyor: parça $end/${segs.length}';
      }
    } finally {
      await sink.close();
    }
  }

  Future<void> downloadVideo(String url) async {
    final m = ScaffoldMessenger.of(context);
    final prog = ValueNotifier<String>('Video indiriliyor...');
    m.hideCurrentSnackBar();
    m.showSnackBar(SnackBar(
      content: ValueListenableBuilder<String>(
          valueListenable: prog, builder: (_, v, __) => Text(v)),
      duration: const Duration(minutes: 30),
    ));
    try {
      final dir = await getTemporaryDirectory();
      final path =
          '${dir.path}/video_${DateTime.now().millisecondsSinceEpoch}.mp4';
      if (url.contains('.m3u8')) {
        await downloadHls(url, path, prog);
      } else {
        await Dio().download(url, path,
            options: Options(headers: {'Referer': t.url}));
      }
      await Gal.putVideo(path);
      File(path).delete().ignore();
      m.hideCurrentSnackBar();
      m.showSnackBar(const SnackBar(content: Text('Galeriye kaydedildi')));
    } catch (e) {
      m.hideCurrentSnackBar();
      m.showSnackBar(SnackBar(content: Text('İndirilemedi: $e')));
    }
  }

  Future<void> videoMenu() async {
    final r = await t.c?.evaluateJavascript(source: """
      (function(){var r=[];
        document.querySelectorAll('video,source').forEach(function(e){
          var u=e.currentSrc||e.src; if(u) r.push(u)});
        (window.__m3u8||[]).forEach(function(u){r.push(u)});
        return r;})()
    """);
    final urls = <String>{
      if (r is List)
        for (final u in r)
          if (u is String && u.startsWith('http')) u
    }.toList();
    if (!mounted) return;
    if (urls.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('İndirilebilir video bulunamadı (blob veya şifreli yayın olabilir)')));
    } else if (urls.length == 1) {
      downloadVideo(urls.first);
    } else {
      showModalBottomSheet(
        context: context,
        builder: (ctx) => ListView(
          children: [
            for (final u in urls)
              ListTile(
                leading: const Icon(Icons.download),
                title: Text(u, maxLines: 2, overflow: TextOverflow.ellipsis),
                onTap: () {
                  Navigator.pop(ctx);
                  downloadVideo(u);
                },
              ),
          ],
        ),
      );
    }
  }

  void showTabs() {
    showModalBottomSheet(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (ctx, setS) => ListView(
          children: [
            for (int i = 0; i < tabs.length; i++)
              ListTile(
                selected: i == cur,
                title: Text(tabs[i].title.isEmpty ? tabs[i].url : tabs[i].title,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(tabs[i].url,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () {
                  selectTab(i);
                  Navigator.pop(ctx);
                },
                trailing: IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () {
                    closeTab(i);
                    setS(() {});
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }

  void showBookmarks() {
    showModalBottomSheet(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (ctx, setS) => bookmarks.isEmpty
            ? const Center(child: Text('Yer imi yok'))
            : ListView(
                children: [
                  for (final b in List<String>.from(bookmarks))
                    ListTile(
                      title: Text(b, maxLines: 1, overflow: TextOverflow.ellipsis),
                      onTap: () {
                        Navigator.pop(ctx);
                        t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(b)));
                      },
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () {
                          setState(() => bookmarks.remove(b));
                          prefs?.setStringList('bm', bookmarks);
                          setS(() {});
                        },
                      ),
                    ),
                ],
              ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isBm = bookmarks.contains(t.url);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (t.c != null && await t.c!.canGoBack()) {
          t.c!.goBack();
        } else if (tabs.length > 1) {
          closeTab(cur);
        } else {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 12,
          title: TextField(
            controller: tc,
            onSubmitted: go,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
                border: InputBorder.none, hintText: 'Ara veya adres yaz'),
          ),
          actions: [
            IconButton(
                icon: const Icon(Icons.refresh), onPressed: () => t.c?.reload()),
          ],
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(3),
            child: ValueListenableBuilder<double>(
              valueListenable: t.progress,
              builder: (_, p, __) => p < 1
                  ? LinearProgressIndicator(value: p)
                  : const SizedBox(height: 3),
            ),
          ),
        ),
        body: IndexedStack(
          index: cur,
          children: [
            for (final x in tabs)
              x.opened
                  ? InAppWebView(
                      key: ObjectKey(x),
                      initialUserScripts: UnmodifiableListView<UserScript>([
                        UserScript(
                            source: hookJs,
                            injectionTime:
                                UserScriptInjectionTime.AT_DOCUMENT_START),
                      ]),
                      initialUrlRequest: x.url == home
                          ? null
                          : URLRequest(url: WebUri(x.url)),
                      initialData: x.url == home
                          ? InAppWebViewInitialData(
                              data: buildHome(bookmarks, darkMode.value),
                              baseUrl: WebUri(homeBase))
                          : null,
                      initialSettings: InAppWebViewSettings(
                        javaScriptEnabled: true,
                        domStorageEnabled: true,
                        databaseEnabled: true,
                        cacheEnabled: true,
                        cacheMode: CacheMode.LOAD_DEFAULT,
                        safeBrowsingEnabled: false,
                        hardwareAcceleration: true,
                        mediaPlaybackRequiresUserGesture: false,
                        thirdPartyCookiesEnabled: true,
                        useWideViewPort: true,
                        allowsInlineMediaPlayback: true,
                        useOnDownloadStart: true,
                        useHybridComposition: false,
                      ),
                      onWebViewCreated: (c) => x.c = c,
                      onDownloadStartRequest: (_, req) {
                        final u = req.url.toString();
                        if ((req.mimeType ?? '').startsWith('video/') ||
                            u.contains('.mp4') ||
                            u.contains('.webm')) {
                          downloadVideo(u);
                        }
                      },
                      onProgressChanged: (_, p) => x.progress.value = p / 100,
                      onTitleChanged: (_, title) => x.title = title ?? '',
                      onUpdateVisitedHistory: (_, u, __) {
                        x.url = norm(u?.toString(), x.url);
                        if (x == t) tc.text = shown(x.url);
                      },
                      onLoadStop: (_, u) {
                        x.url = norm(u?.toString(), x.url);
                        if (x == t) {
                          tc.text = shown(x.url);
                          setState(() {});
                        }
                      },
                    )
                  : const SizedBox.shrink(),
          ],
        ),
        bottomNavigationBar: BottomAppBar(
          height: 56,
          padding: EdgeInsets.zero,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              IconButton(
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => t.c?.goBack()),
              IconButton(
                  icon: const Icon(Icons.arrow_forward),
                  onPressed: () => t.c?.goForward()),
              IconButton(icon: const Icon(Icons.add), onPressed: newTab),
              IconButton(
                icon: Badge(
                    label: Text('${tabs.length}'),
                    child: const Icon(Icons.tab)),
                onPressed: showTabs,
              ),
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert),
                onSelected: (v) async {
                  if (v == 'bm') toggleBookmark();
                  if (v == 'list') showBookmarks();
                  if (v == 'video') videoMenu();
                  if (v == 'dark') {
                    darkMode.value = !darkMode.value;
                    prefs?.setBool('dark', darkMode.value);
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(
                      value: 'bm',
                      child: Text(isBm ? 'Yer imini kaldır' : 'Yer imi ekle')),
                  const PopupMenuItem(value: 'list', child: Text('Yer imleri')),
                  const PopupMenuItem(
                      value: 'video', child: Text('Videoyu indir')),
                  const PopupMenuItem(
                      value: 'dark', child: Text('Karanlık / açık mod')),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
