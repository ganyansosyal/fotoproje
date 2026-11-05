import 'dart:async';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../core/adblock.dart';

const _blockExt = {
  // resim / ikon
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'bmp', 'avif',
  // yazı tipi
  'woff', 'woff2', 'ttf', 'otf', 'eot',
  // stil
  'css',
};

/// Engellenecek mi? Anında karar verilmesi gerekir (WebView cevabı bekler), bu
/// yüzden regex yerine sabit zamanlı kontroller kullanılır.
bool shouldBlockUrl(String url) {
  var end = url.length;
  final q = url.indexOf('?');
  if (q >= 0) end = q;
  final h = url.indexOf('#');
  if (h >= 0 && h < end) end = h;
  final slash = url.lastIndexOf('/', end - 1);
  final dot = url.lastIndexOf('.', end - 1);
  if (dot > slash && dot < end - 1) {
    if (_blockExt.contains(url.substring(dot + 1, end).toLowerCase())) {
      return true;
    }
  }
  return isAdUrl(url);
}

/// Engellenen istek için boş cevap.
final WebResourceResponse blockedResponse = WebResourceResponse(
  contentType: 'text/plain',
  contentEncoding: 'utf-8',
  data: Uint8List(0),
  statusCode: 204,
  reasonPhrase: 'No Content',
);

/// Yakalanan adreslerin regex ile sınıflandırılması ayrı bir isolate'ta yapılır;
/// arayüz iş parçacığına yalnızca sonuç döner.
class NetClassifier {
  NetClassifier._();
  static final NetClassifier instance = NetClassifier._();

  /// (sekme no, adres, tür: m3u8 | mpd | mp4 | webm ...)
  void Function(int tab, String url, String kind)? onMedia;

  SendPort? _send;
  Future<void>? _starting;

  Future<void> _start() async {
    final recv = ReceivePort();
    final ready = Completer<SendPort>();
    recv.listen((m) {
      if (m is SendPort) {
        ready.complete(m);
      } else if (m is List && m.length == 3) {
        onMedia?.call(m[0] as int, m[1] as String, m[2] as String);
      }
    });
    await Isolate.spawn(_worker, recv.sendPort);
    _send = await ready.future;
  }

  void submit(int tab, String url) {
    final s = _send;
    if (s != null) {
      s.send([tab, url]);
      return;
    }
    (_starting ??= _start()).then((_) => _send?.send([tab, url]));
  }
}

void _worker(SendPort main) {
  final port = ReceivePort();
  main.send(port.sendPort);
  final media = RegExp(r'\.(m3u8|mpd|mp4|webm|m4v|mov|mkv|flv)(?:$|[?#/])',
      caseSensitive: false);
  final bad = RegExp(
      r'(imasdk|adservice|adsystem|preroll|midroll|vpaid|tracking|beacon|analytics|thumb|sprite|storyboard|preview|[/_.-]ads?[/_.-]|[/_.-]advert|[/_.-]vast[/_.?-])',
      caseSensitive: false);
  final seg = RegExp(
      r'((seg|segment|chunk|frag|fragment)[-_.]?\d|/init[-_.][^/]*$|[?&](bytestart|byterange|range|sq|seg|segment|chunk)=)',
      caseSensitive: false);
  port.listen((m) {
    final tab = m[0] as int;
    final url = m[1] as String;
    final k = media.firstMatch(url);
    if (k == null || bad.hasMatch(url) || seg.hasMatch(url)) return;
    main.send([tab, url, k.group(1)!.toLowerCase()]);
  });
}
