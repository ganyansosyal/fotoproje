import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:gal/gal.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:xml/xml.dart';

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
(function(){ if(window.__hk) return; window.__hk=1; window.__media=[];
 var re=/\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv)(\?|#|\/|&|$)/i;
 function add(u){try{u=new URL(u,location.href).href;
  if(re.test(u) && window.__media.indexOf(u)<0) window.__media.push(u);}catch(e){}}
 var o=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(m,u){add(u);return o.apply(this,arguments)};
 var f=window.fetch;
 if(f) window.fetch=function(i){add(typeof i==='string'?i:(i&&i.url));return f.apply(this,arguments)};
})();
''';

String? mediaKind(String u) {
  if (u.startsWith('blob:')) return 'blob';
  final l = u.toLowerCase();
  if (l.contains('.m3u8')) return 'm3u8';
  if (l.contains('.mpd')) return 'mpd';
  final path = (Uri.tryParse(u)?.path ?? l).toLowerCase();
  return RegExp(r'\.(mp4|webm|mov|m4v|mkv|flv|3gp|ogv)$')
      .firstMatch(path)
      ?.group(1);
}

String? _attr(String line, String name) {
  final m = RegExp('(?:^|[:,])$name=("([^"]*)"|[^,]*)').firstMatch(line);
  return m == null ? null : (m.group(2) ?? m.group(1));
}

List<int> _hex(String s) {
  var h = s.replaceFirst(RegExp(r'^0[xX]'), '');
  h = h.padLeft(32, '0');
  return [for (var i = 0; i < h.length; i += 2) int.parse(h.substring(i, i + 2), radix: 16)];
}

double? isoSeconds(String? s) {
  if (s == null) return null;
  final m = RegExp(r'^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$')
      .firstMatch(s);
  if (m == null) return null;
  return (int.tryParse(m[1] ?? '') ?? 0) * 86400 +
      (int.tryParse(m[2] ?? '') ?? 0) * 3600 +
      (int.tryParse(m[3] ?? '') ?? 0) * 60 +
      (double.tryParse(m[4] ?? '') ?? 0);
}

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
  final Set<String> media = <String>{};
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

  Future<void> saveSegments(Uri? init, List<Uri> urls, String path,
      ValueNotifier<String> prog, String label, Map<String, String> h,
      {List<int> Function(List<int>, int)? decrypt,
      Future<void> Function(int)? prepare}) async {
    final dio = Dio();
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

    if (urls.isEmpty) throw 'Video parçaları bulunamadı';
    final sink = File(path).openWrite();
    try {
      if (init != null) sink.add(await bytes(init));
      for (var i = 0; i < urls.length; i += 4) {
        final end = i + 4 < urls.length ? i + 4 : urls.length;
        if (prepare != null) {
          for (var j = i; j < end; j++) {
            await prepare(j);
          }
        }
        final parts = await Future.wait([
          for (var j = i; j < end; j++) bytes(urls[j]),
        ]);
        for (var j = 0; j < parts.length; j++) {
          sink.add(decrypt == null ? parts[j] : decrypt(parts[j], i + j));
        }
        prog.value = '$label: parça $end/${urls.length}';
      }
    } finally {
      await sink.close();
    }
  }

  // Dönüş: ayrı ses dosyası yazıldı mı
  Future<bool> downloadHls(String url, String path, String audioPath,
      ValueNotifier<String> prog) async {
    final dio = Dio();
    final h = {'Referer': t.url};
    Future<String> text(String u) async => (await dio.get<String>(u,
            options: Options(headers: h, responseType: ResponseType.plain)))
        .data!;

    var pl = url;
    var lines = (await text(pl)).split('\n').map((e) => e.trim()).toList();
    String? audioUrl;
    if (lines.any((l) => l.startsWith('#EXT-X-STREAM-INF'))) {
      String? best;
      String? group;
      var bw = -1;
      for (var i = 0; i < lines.length; i++) {
        if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
        final b = int.tryParse(_attr(lines[i], 'BANDWIDTH') ?? '') ?? 0;
        var j = i + 1;
        while (
            j < lines.length && (lines[j].isEmpty || lines[j].startsWith('#'))) {
          j++;
        }
        if (j < lines.length && b > bw) {
          bw = b;
          best = lines[j];
          group = _attr(lines[i], 'AUDIO');
        }
      }
      if (best == null) throw 'Video listesi bulunamadı';
      final master = Uri.parse(pl);
      pl = master.resolve(best).toString();
      if (group != null) {
        String? any;
        String? def;
        for (final l in lines) {
          if (!l.startsWith('#EXT-X-MEDIA') ||
              _attr(l, 'TYPE') != 'AUDIO' ||
              _attr(l, 'GROUP-ID') != group) {
            continue;
          }
          final u = _attr(l, 'URI');
          if (u == null) continue;
          any ??= u;
          if (_attr(l, 'DEFAULT') == 'YES') def ??= u;
        }
        final a = def ?? any;
        if (a != null) audioUrl = master.resolve(a).toString();
      }
    }
    await _hlsMedia(pl, path, prog, 'Video', h);
    if (audioUrl != null) {
      await _hlsMedia(audioUrl, audioPath, prog, 'Ses', h);
      return true;
    }
    return false;
  }

  Future<void> _hlsMedia(String pl, String path, ValueNotifier<String> prog,
      String label, Map<String, String> h) async {
    final dio = Dio();
    final body = (await dio.get<String>(pl,
            options: Options(headers: h, responseType: ResponseType.plain)))
        .data!;
    final lines = body.split('\n').map((e) => e.trim()).toList();
    final base = Uri.parse(pl);
    var seq = 0;
    Uri? init;
    Uri? keyUri;
    List<int>? keyIv;
    final segs = <Uri>[];
    final segKey = <Uri?>[];
    final segIv = <List<int>?>[];
    for (final l in lines) {
      if (l.startsWith('#EXT-X-MEDIA-SEQUENCE:')) {
        seq = int.tryParse(l.split(':')[1]) ?? 0;
      } else if (l.startsWith('#EXT-X-KEY')) {
        final method = _attr(l, 'METHOD');
        if (method == null || method == 'NONE') {
          keyUri = null;
          keyIv = null;
        } else if (method == 'AES-128') {
          final k = _attr(l, 'URI');
          if (k == null) throw 'Şifre anahtarı bulunamadı';
          keyUri = base.resolve(k);
          final iv = _attr(l, 'IV');
          keyIv = iv == null ? null : _hex(iv);
        } else {
          throw 'Şifreli yayın ($method) desteklenmiyor';
        }
      } else if (l.startsWith('#EXT-X-MAP')) {
        final m = _attr(l, 'URI');
        if (m != null) init = base.resolve(m);
      } else if (l.isNotEmpty && !l.startsWith('#')) {
        segs.add(base.resolve(l));
        segKey.add(keyUri);
        segIv.add(keyIv);
      }
    }
    final keys = <String, List<int>>{};
    await saveSegments(
      init,
      segs,
      path,
      prog,
      label,
      h,
      prepare: (i) async {
        final k = segKey[i];
        if (k != null && !keys.containsKey(k.toString())) {
          final r = await dio.get<List<int>>(k.toString(),
              options: Options(headers: h, responseType: ResponseType.bytes));
          keys[k.toString()] = r.data!;
        }
      },
      decrypt: (data, i) {
        final k = segKey[i];
        if (k == null) return data;
        var iv = segIv[i];
        if (iv == null) {
          iv = List<int>.filled(16, 0);
          var n = seq + i;
          for (var x = 15; x >= 12; x--) {
            iv[x] = n & 0xff;
            n >>= 8;
          }
        }
        final e = enc.Encrypter(enc.AES(
            enc.Key(Uint8List.fromList(keys[k.toString()]!)),
            mode: enc.AESMode.cbc));
        return e.decryptBytes(enc.Encrypted(Uint8List.fromList(data)),
            iv: enc.IV(Uint8List.fromList(iv)));
      },
    );
  }

  Future<bool> downloadDash(String url, String path, String audioPath,
      ValueNotifier<String> prog) async {
    final h = {'Referer': t.url};
    final txt = (await Dio().get<String>(url,
            options: Options(headers: h, responseType: ResponseType.plain)))
        .data!;
    final mpd = XmlDocument.parse(txt).rootElement;
    final period = mpd.findElements('Period').first;
    final pdur = isoSeconds(period.getAttribute('duration')) ??
        isoSeconds(mpd.getAttribute('mediaPresentationDuration')) ??
        0;
    Uri baseOf(Uri b, XmlElement e) {
      final bu = e.getElement('BaseURL')?.innerText.trim();
      return bu == null || bu.isEmpty ? b : b.resolve(bu);
    }

    final b0 = baseOf(baseOf(Uri.parse(url), mpd), period);

    XmlElement? bestSet;
    XmlElement? bestRep;
    XmlElement? bestASet;
    XmlElement? bestARep;
    var vb = -1;
    var ab = -1;
    for (final s in period.findElements('AdaptationSet')) {
      for (final r in s.findElements('Representation')) {
        final mime = r.getAttribute('mimeType') ??
            s.getAttribute('mimeType') ??
            s.getAttribute('contentType') ??
            '';
        final b = int.tryParse(r.getAttribute('bandwidth') ?? '') ?? 0;
        if (mime.startsWith('video') && b > vb) {
          vb = b;
          bestSet = s;
          bestRep = r;
        } else if (mime.startsWith('audio') && b > ab) {
          ab = b;
          bestASet = s;
          bestARep = r;
        }
      }
    }
    if (bestRep == null && bestARep != null) {
      // sadece ses varsa onu ana dosya yap
      bestSet = bestASet;
      bestRep = bestARep;
      bestASet = null;
      bestARep = null;
    }
    if (bestSet == null || bestRep == null) throw 'Video bulunamadı';

    Future<void> grab(
        XmlElement s, XmlElement r, String out, String label) async {
      final id = r.getAttribute('id') ?? '';
      final bw = r.getAttribute('bandwidth') ?? '0';
      final b = baseOf(baseOf(b0, s), r);
      String fill(String t, {int number = 0, int time = 0}) => t
          .replaceAllMapped(
              RegExp(r'\$(RepresentationID|Number|Bandwidth|Time)(?:%0(\d+)d)?\$'),
              (m) {
            final w = int.tryParse(m[2] ?? '') ?? 0;
            switch (m[1]) {
              case 'RepresentationID':
                return id;
              case 'Bandwidth':
                return bw;
              case 'Number':
                return number.toString().padLeft(w, '0');
              default:
                return time.toString().padLeft(w, '0');
            }
          })
          .replaceAll(r'$$', r'$');
      final st = r.getElement('SegmentTemplate') ?? s.getElement('SegmentTemplate');
      final sl = r.getElement('SegmentList') ?? s.getElement('SegmentList');
      Uri? init;
      final urls = <Uri>[];
      if (st != null) {
        final ini = st.getAttribute('initialization');
        if (ini != null) init = b.resolve(fill(ini));
        final media = st.getAttribute('media');
        if (media == null) throw 'Geçersiz DASH';
        final ts = int.tryParse(st.getAttribute('timescale') ?? '1') ?? 1;
        final start = int.tryParse(st.getAttribute('startNumber') ?? '1') ?? 1;
        final tl = st.getElement('SegmentTimeline');
        if (tl != null) {
          var time = 0;
          var n = start;
          for (final e in tl.findElements('S')) {
            time = int.tryParse(e.getAttribute('t') ?? '') ?? time;
            final d = int.parse(e.getAttribute('d')!);
            var rep = int.tryParse(e.getAttribute('r') ?? '0') ?? 0;
            if (rep < 0) rep = ((pdur * ts - time) / d).ceil() - 1;
            for (var k = 0; k <= rep; k++) {
              urls.add(b.resolve(fill(media, number: n, time: time)));
              time += d;
              n++;
            }
          }
        } else {
          final dur = int.tryParse(st.getAttribute('duration') ?? '');
          if (dur == null || dur == 0 || pdur == 0) throw 'DASH süresi bulunamadı';
          final count = (pdur * ts / dur).ceil();
          for (var k = 0; k < count; k++) {
            urls.add(b.resolve(fill(media, number: start + k)));
          }
        }
      } else if (sl != null) {
        final i = sl.getElement('Initialization')?.getAttribute('sourceURL');
        if (i != null) init = b.resolve(i);
        for (final e in sl.findElements('SegmentURL')) {
          final m = e.getAttribute('media');
          if (m != null) urls.add(b.resolve(m));
        }
      } else {
        urls.add(b);
      }
      await saveSegments(init, urls, out, prog, label, h);
    }

    await grab(bestSet, bestRep, path, 'Video');
    if (bestASet != null && bestARep != null) {
      await grab(bestASet, bestARep, audioPath, 'Ses');
      return true;
    }
    return false;
  }

  Future<void> downloadBlob(String url, String path) async {
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
    if (v is! String || v.isEmpty) {
      throw 'Blob okunamadı (parçalı yayın olabilir, listedeki diğer bağlantıyı dene)';
    }
    await File(path).writeAsBytes(base64Decode(v));
  }

  Future<String> saveFile(String path) async {
    try {
      await Gal.putVideo(path);
      File(path).delete().ignore();
      return 'Galeriye kaydedildi';
    } catch (_) {}
    final d =
        await getExternalStorageDirectory() ?? await getApplicationDocumentsDirectory();
    final dest = '${d.path}/${path.split('/').last}';
    await File(path).copy(dest);
    File(path).delete().ignore();
    return 'Kaydedildi: $dest';
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
      final ts = DateTime.now().millisecondsSinceEpoch;
      final kind = mediaKind(url) ?? 'mp4';
      final streamLike = kind == 'm3u8' || kind == 'mpd' || kind == 'blob';
      final path = '${dir.path}/video_$ts.${streamLike ? 'mp4' : kind}';
      final apath = '${dir.path}/ses_$ts.m4a';
      var sepAudio = false;
      if (kind == 'm3u8') {
        sepAudio = await downloadHls(url, path, apath, prog);
      } else if (kind == 'mpd') {
        sepAudio = await downloadDash(url, path, apath, prog);
      } else if (kind == 'blob') {
        await downloadBlob(url, path);
      } else {
        await Dio().download(url, path,
            options: Options(headers: {'Referer': t.url}),
            onReceiveProgress: (r, tot) {
          prog.value = tot > 0
              ? 'İndiriliyor: %${r * 100 ~/ tot}'
              : 'İndiriliyor: ${(r / 1048576).toStringAsFixed(1)} MB';
        });
      }
      var msg = await saveFile(path);
      if (sepAudio) {
        final d = await getExternalStorageDirectory() ??
            await getApplicationDocumentsDirectory();
        final dest = '${d.path}/ses_$ts.m4a';
        await File(apath).copy(dest);
        File(apath).delete().ignore();
        msg += '\nSes ayrı dosya: $dest';
      }
      m.hideCurrentSnackBar();
      m.showSnackBar(SnackBar(content: Text(msg)));
    } catch (e) {
      m.hideCurrentSnackBar();
      m.showSnackBar(SnackBar(content: Text('İndirilemedi: $e')));
    }
  }

  Future<void> videoMenu() async {
    final r = await t.c?.evaluateJavascript(source: """
      (function(){var r=[];
        document.querySelectorAll('video,audio,source').forEach(function(e){
          var u=e.currentSrc||e.src; if(u) r.push(u)});
        (window.__media||[]).forEach(function(u){r.push(u)});
        return r;})()
    """);
    final all = <String>{
      ...t.media,
      if (r is List)
        for (final u in r)
          if (u is String) u
    };
    final urls = all
        .where((u) => u.startsWith('http') || u.startsWith('blob:'))
        .toList()
      ..sort((a, b) =>
          (a.startsWith('blob:') ? 1 : 0) - (b.startsWith('blob:') ? 1 : 0));
    if (!mounted) return;
    if (urls.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Video bulunamadı. Videoyu oynatıp tekrar dene.')));
    } else if (urls.length == 1) {
      downloadVideo(urls.first);
    } else {
      showModalBottomSheet(
        context: context,
        builder: (ctx) => ListView(
          children: [
            for (final u in urls)
              ListTile(
                leading: Chip(label: Text((mediaKind(u) ?? '?').toUpperCase())),
                title: Text(u, maxLines: 2, overflow: TextOverflow.ellipsis),
                trailing: const Icon(Icons.download),
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
                        final mt = (req.mimeType ?? '').toLowerCase();
                        if (mt.startsWith('video/') ||
                            mt.contains('mpegurl') ||
                            mt.contains('dash+xml') ||
                            mediaKind(u) != null) {
                          downloadVideo(u);
                        }
                      },
                      onLoadStart: (_, __) => x.media.clear(),
                      onLoadResource: (_, res) {
                        final u = res.url?.toString();
                        if (u != null && mediaKind(u) != null) x.media.add(u);
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
