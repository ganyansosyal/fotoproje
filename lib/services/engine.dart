import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:xml/xml.dart';

import '../core/media.dart';

const dlUserAgent =
    'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
    'Chrome/124.0.0.0 Mobile Safari/537.36';

String _origin(String u) {
  final p = Uri.tryParse(u);
  if (p == null || !p.hasAuthority || !p.scheme.startsWith('http')) return '';
  return '${p.scheme}://${p.host}${p.hasPort ? ':${p.port}' : ''}';
}

/// Tarayıcıdaki gibi istek başlıkları: Referer, Origin, User-Agent ve sitenin çerezleri.
Future<Map<String, String>> dlHeaders(String url, String referer) async {
  final h = <String, String>{
    'User-Agent': dlUserAgent,
    'Accept': '*/*',
    'Accept-Language': 'tr-TR,tr;q=0.9,en;q=0.8',
  };
  if (referer.isNotEmpty) {
    h['Referer'] = referer;
    final o = _origin(referer);
    if (o.isNotEmpty) h['Origin'] = o;
  }
  try {
    final u = Uri.tryParse(url);
    if (u != null && u.scheme.startsWith('http')) {
      final cs = await CookieManager.instance().getCookies(url: WebUri(url));
      if (cs.isNotEmpty) {
        h['Cookie'] = cs.map((c) => '${c.name}=${c.value}').join('; ');
      }
    }
  } catch (_) {}
  return h;
}

/// Bazı CDN'ler yalnızca belirli Referer/Origin kabul eder. Sırayla denenecek
/// başlık takımları: oynatıcı sayfası, onun kökü, adresin kendi sitesi, hiçbiri.
Future<List<Map<String, String>>> dlHeaderVariants(
    String url, String referer) async {
  final refs = <String>[];
  void add(String r) {
    if (!refs.contains(r)) refs.add(r);
  }

  if (referer.isNotEmpty) {
    add(referer);
    final o = _origin(referer);
    if (o.isNotEmpty) add('$o/');
  }
  final self = _origin(url);
  if (self.isNotEmpty) add('$self/');
  add('');
  return [for (final r in refs) await dlHeaders(url, r)];
}

/// Çalışan başlık takımını siteye göre hatırlar.
final _hdrIdx = <String, int>{};

class TextGot {
  final Map<String, String> headers;
  final String body;
  TextGot(this.headers, this.body);
}

bool _retryOther(Object e) {
  if (e is! DioException || CancelToken.isCancel(e)) return false;
  final c = e.response?.statusCode;
  return c == 400 || c == 401 || c == 403 || c == 406 || c == 429;
}

/// Metin (liste) getirir; erişim reddedilirse başka başlık takımlarını dener.
Future<TextGot> fetchTextSmart(String url, String referer,
    {CancelToken? cancel}) async {
  final vars = await dlHeaderVariants(url, referer);
  final key = '${Uri.tryParse(url)?.host}|$referer';
  final start = (_hdrIdx[key] ?? 0).clamp(0, vars.length - 1);
  final dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30)));
  Object? last;
  for (var k = 0; k < vars.length; k++) {
    final i = (start + k) % vars.length;
    try {
      final r = await dio.get<String>(url,
          cancelToken: cancel,
          options: Options(headers: vars[i], responseType: ResponseType.plain));
      _hdrIdx[key] = i;
      return TextGot(vars[i], r.data ?? '');
    } catch (e) {
      last = e;
      if (!_retryOther(e)) rethrow;
    }
  }
  throw last!;
}

/// Doğrudan dosya için çalışan başlık takımını bulur (1 baytlık aralık isteğiyle).
Future<Map<String, String>> smartHeaders(String url, String referer,
    {CancelToken? cancel}) async {
  final vars = await dlHeaderVariants(url, referer);
  final key = '${Uri.tryParse(url)?.host}|$referer';
  final start = (_hdrIdx[key] ?? 0).clamp(0, vars.length - 1);
  final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 15)));
  for (var k = 0; k < vars.length; k++) {
    final i = (start + k) % vars.length;
    try {
      final h = Map<String, String>.of(vars[i])..['Range'] = 'bytes=0-0';
      final r = await dio.get<ResponseBody>(url,
          cancelToken: cancel,
          options: Options(headers: h, responseType: ResponseType.stream));
      await r.data?.stream.listen((_) {}).cancel();
      _hdrIdx[key] = i;
      return vars[i];
    } catch (e) {
      if (!_retryOther(e)) return vars[start];
    }
  }
  return vars.first;
}

/// (0..1 arası ilerleme, etiket)
typedef Report = void Function(double progress, String label);

class CanceledException implements Exception {
  @override
  String toString() => 'İptal edildi';
}

void _check(CancelToken c) {
  if (c.isCancelled) throw CanceledException();
}

/// Bazı siteler TS parçalarının başına sahte resim başlığı (PNG/JPG/GIF) ekler.
/// Gerçek TS paket dizisi (her 188 baytta 0x47) başlayana kadar baştaki fazlalığı atar.
List<int> cleanSegment(List<int> d) {
  if (d.length < 600) return d;
  if (d[0] == 0x47 && d[188] == 0x47) return d; // zaten temiz TS
  final fmp4 = d[4] == 0x66 && d[5] == 0x74 && d[6] == 0x79 && d[7] == 0x70 || // ftyp
      d[4] == 0x6D && d[5] == 0x6F && d[6] == 0x6F && d[7] == 0x66 || // moof
      d[4] == 0x73 && d[5] == 0x74 && d[6] == 0x79 && d[7] == 0x70; // styp
  if (fmp4) return d;
  final lim = math.min(d.length - 377, 16384);
  for (var i = 1; i < lim; i++) {
    if (d[i] == 0x47 && d[i + 188] == 0x47 && d[i + 376] == 0x47) {
      return d.sublist(i);
    }
  }
  return d;
}

/// Parçalı (segment) indirmeleri tek dosyaya birleştirir.
/// [start]..[start+span] aralığında ilerleme bildirir.
Future<void> saveSegments({
  required Uri? init,
  required List<Uri> urls,
  required String path,
  required Report report,
  required String label,
  required Map<String, String> headers,
  required CancelToken cancel,
  double start = 0,
  double span = 1,
  List<int> Function(List<int>, int)? decrypt,
  Future<void> Function(int)? prepare,
}) async {
  final dio = Dio();
  Future<List<int>> bytes(Uri u) async {
    for (var k = 0;; k++) {
      try {
        _check(cancel);
        final r = await dio.get<List<int>>(u.toString(),
            cancelToken: cancel,
            options: Options(headers: headers, responseType: ResponseType.bytes));
        return r.data!;
      } catch (e) {
        if (e is CanceledException || (e is DioException && CancelToken.isCancel(e))) {
          throw CanceledException();
        }
        if (k >= 2) rethrow;
      }
    }
  }

  if (urls.isEmpty) throw 'Video parçaları bulunamadı';
  final sink = File(path).openWrite();
  try {
    if (init != null) sink.add(await bytes(init));
    for (var i = 0; i < urls.length; i += 4) {
      _check(cancel);
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
        sink.add(cleanSegment(
            decrypt == null ? parts[j] : decrypt(parts[j], i + j)));
      }
      report(start + span * end / urls.length, '$label: $end/${urls.length}');
    }
  } finally {
    await sink.close();
  }
}

/// Dönüş: ayrı ses dosyası yazıldı mı
Future<bool> downloadHls(String url, String path, String audioPath,
    String referer, Report report, CancelToken cancel) async {
  final got = await fetchTextSmart(url, referer, cancel: cancel);
  final h = got.headers;

  var pl = url;
  final lines = got.body.split('\n').map((e) => e.trim()).toList();
  String? audioUrl;
  if (lines.any((l) => l.startsWith('#EXT-X-STREAM-INF'))) {
    String? best;
    String? group;
    var bw = -1;
    for (var i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
      final b = int.tryParse(hlsAttr(lines[i], 'BANDWIDTH') ?? '') ?? 0;
      var j = i + 1;
      while (j < lines.length && (lines[j].isEmpty || lines[j].startsWith('#'))) {
        j++;
      }
      if (j < lines.length && b > bw) {
        bw = b;
        best = lines[j];
        group = hlsAttr(lines[i], 'AUDIO');
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
            hlsAttr(l, 'TYPE') != 'AUDIO' ||
            hlsAttr(l, 'GROUP-ID') != group) {
          continue;
        }
        final u = hlsAttr(l, 'URI');
        if (u == null) continue;
        any ??= u;
        if (hlsAttr(l, 'DEFAULT') == 'YES') def ??= u;
      }
      final a = def ?? any;
      if (a != null) audioUrl = master.resolve(a).toString();
    }
  }
  final hasAudio = audioUrl != null;
  await _hlsMedia(pl, path, report, 'Video', h, cancel, 0, hasAudio ? 0.8 : 1);
  if (hasAudio) {
    await _hlsMedia(audioUrl, audioPath, report, 'Ses', h, cancel, 0.8, 0.2);
    return true;
  }
  return false;
}

Future<void> _hlsMedia(String pl, String path, Report report, String label,
    Map<String, String> h, CancelToken cancel, double start, double span) async {
  final dio = Dio();
  final got = await fetchTextSmart(pl, h['Referer'] ?? '', cancel: cancel);
  h = got.headers;
  final body = got.body;
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
      final method = hlsAttr(l, 'METHOD');
      if (method == null || method == 'NONE') {
        keyUri = null;
        keyIv = null;
      } else if (method == 'AES-128') {
        final k = hlsAttr(l, 'URI');
        if (k == null) throw 'Şifre anahtarı bulunamadı';
        keyUri = base.resolve(k);
        final iv = hlsAttr(l, 'IV');
        keyIv = iv == null ? null : hexBytes(iv);
      } else {
        throw 'Şifreli yayın ($method) desteklenmiyor';
      }
    } else if (l.startsWith('#EXT-X-MAP')) {
      final m = hlsAttr(l, 'URI');
      if (m != null) init = base.resolve(m);
    } else if (l.isNotEmpty && !l.startsWith('#')) {
      segs.add(base.resolve(l));
      segKey.add(keyUri);
      segIv.add(keyIv);
    }
  }
  final keys = <String, List<int>>{};
  await saveSegments(
    init: init,
    urls: segs,
    path: path,
    report: report,
    label: label,
    headers: h,
    cancel: cancel,
    start: start,
    span: span,
    prepare: (i) async {
      final k = segKey[i];
      if (k != null && !keys.containsKey(k.toString())) {
        final r = await dio.get<List<int>>(k.toString(),
            cancelToken: cancel,
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
    String referer, Report report, CancelToken cancel) async {
  final got = await fetchTextSmart(url, referer, cancel: cancel);
  final h = got.headers;
  final txt = got.body;
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
    bestSet = bestASet;
    bestRep = bestARep;
    bestASet = null;
    bestARep = null;
  }
  if (bestSet == null || bestRep == null) throw 'Video bulunamadı';
  final hasAudio = bestASet != null && bestARep != null;

  Future<void> grab(XmlElement s, XmlElement r, String out, String label,
      double start, double span) async {
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
      final startN = int.tryParse(st.getAttribute('startNumber') ?? '1') ?? 1;
      final tl = st.getElement('SegmentTimeline');
      if (tl != null) {
        var time = 0;
        var n = startN;
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
          urls.add(b.resolve(fill(media, number: startN + k)));
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
    await saveSegments(
        init: init,
        urls: urls,
        path: out,
        report: report,
        label: label,
        headers: h,
        cancel: cancel,
        start: start,
        span: span);
  }

  await grab(bestSet, bestRep, path, 'Video', 0, hasAudio ? 0.8 : 1);
  if (hasAudio) {
    await grab(bestASet!, bestARep!, audioPath, 'Ses', 0.8, 0.2);
    return true;
  }
  return false;
}

/// blob: adreslerini WebView içinden base64 olarak okur.
Future<void> writeBase64(String? b64, String path) async {
  if (b64 == null || b64.isEmpty) {
    throw 'Blob okunamadı (parçalı yayın olabilir, listedeki diğer bağlantıyı dene)';
  }
  await File(path).writeAsBytes(base64Decode(b64));
}
