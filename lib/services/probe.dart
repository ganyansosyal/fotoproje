import 'package:dio/dio.dart';
import 'package:xml/xml.dart';

import '../core/media.dart';
import '../core/strings.dart';
import 'engine.dart';

/// Bulunan bir video bağlantısının incelenmiş hâli.
class MediaInfo {
  final String url;
  final String kind; // m3u8 | mpd | mp4 | blob ...
  double duration = 0; // saniye
  int height = 0;
  int size = 0;
  bool usable = true;

  MediaInfo(this.url, this.kind);

  bool get isStream => kind == 'm3u8' || kind == 'mpd';

  String get host => Uri.tryParse(url)?.host ?? '';

  String get title {
    final p = <String>[];
    if (height > 0) p.add('${height}p');
    if (duration > 0) p.add(fmtDuration(duration));
    if (size > 0) p.add(fmtBytes(size));
    if (p.isEmpty) {
      return kind == 'blob' ? S.t('protected') : kind.toUpperCase();
    }
    return p.join(' · ');
  }

  String get subtitle {
    final k = isStream ? S.t('stream') : (kind == 'blob' ? 'Blob' : S.t('file'));
    return '$k · ${kind.toUpperCase()} · $host';
  }

  /// Büyük = daha iyi
  double get rank {
    if (!usable) return -1;
    if (kind == 'blob') return 0;
    return 1000 + duration * 10 + height + size / 1e6;
  }
}

String fmtDuration(double s) {
  final t = s.round();
  final h = t ~/ 3600, m = (t % 3600) ~/ 60, sec = t % 60;
  String two(int n) => n.toString().padLeft(2, '0');
  return h > 0 ? '$h:${two(m)}:${two(sec)}' : '$m:${two(sec)}';
}

String fmtBytes(int b) {
  if (b < 1048576) return '${(b / 1024).toStringAsFixed(0)} KB';
  if (b < 1073741824) return '${(b / 1048576).toStringAsFixed(1)} MB';
  return '${(b / 1073741824).toStringAsFixed(2)} GB';
}

final _dio = Dio(BaseOptions(
  connectTimeout: const Duration(seconds: 6),
  receiveTimeout: const Duration(seconds: 8),
  validateStatus: (s) => s != null && s < 400,
));

double _extinfTotal(List<String> lines) {
  var total = 0.0;
  for (final l in lines) {
    if (l.startsWith('#EXTINF:')) {
      total += double.tryParse(l.substring(8).split(',').first) ?? 0;
    }
  }
  return total;
}

Future<String> _text(String url, String referer) async =>
    (await _dio.get<String>(url,
            options: Options(
                headers: await dlHeaders(url, referer),
                responseType: ResponseType.plain)))
        .data ??
    '';

Future<MediaInfo> _probeOne(String url, String hint, String referer) async {
  final kind = mediaKind(url) ?? (hint.isNotEmpty ? hint : 'mp4');
  final info = MediaInfo(url, kind);
  try {
    if (kind == 'blob') return info;

    if (kind == 'm3u8') {
      var lines = (await _text(url, referer)).split('\n').map((e) => e.trim()).toList();
      var base = Uri.parse(url);
      if (lines.any((l) => l.startsWith('#EXT-X-STREAM-INF'))) {
        var bw = -1;
        String? best;
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
            final res = hlsAttr(lines[i], 'RESOLUTION');
            if (res != null && res.contains('x')) {
              info.height = int.tryParse(res.split('x').last) ?? 0;
            }
          }
        }
        if (best == null) {
          info.usable = false;
          return info;
        }
        final vu = base.resolve(best);
        lines = (await _text(vu.toString(), referer)).split('\n').map((e) => e.trim()).toList();
        base = vu;
      }
      final segs = lines.where((l) => l.isNotEmpty && !l.startsWith('#')).toList();
      if (segs.isEmpty) {
        info.usable = false;
        return info;
      }
      final first = segs.first.toLowerCase().split('?').first;
      if (first.endsWith('.vtt') ||
          first.endsWith('.webvtt') ||
          first.endsWith('.srt') ||
          first.endsWith('.jpg') ||
          first.endsWith('.jpeg') ||
          first.endsWith('.png') ||
          first.endsWith('.webp')) {
        info.usable = false; // altyazı / küçük resim listesi
        return info;
      }
      for (final l in lines) {
        if (l.startsWith('#EXT-X-KEY')) {
          final m = hlsAttr(l, 'METHOD');
          if (m != null && m != 'NONE' && m != 'AES-128') info.usable = false;
        }
      }
      info.duration = _extinfTotal(lines);
      return info;
    }

    if (kind == 'mpd') {
      final mpd = XmlDocument.parse(await _text(url, referer)).rootElement;
      info.duration = isoSeconds(mpd.getAttribute('mediaPresentationDuration')) ??
          isoSeconds(mpd.findElements('Period').firstOrNull?.getAttribute('duration')) ??
          0;
      var h = 0;
      for (final r in mpd.findAllElements('Representation')) {
        final hh = int.tryParse(r.getAttribute('height') ?? '') ?? 0;
        if (hh > h) h = hh;
      }
      info.height = h;
      return info;
    }

    // Doğrudan dosya: başlığa bak
    final r = await _dio.head(url,
        options: Options(headers: await dlHeaders(url, referer)));
    final ct = (r.headers.value('content-type') ?? '').toLowerCase();
    final len = int.tryParse(r.headers.value('content-length') ?? '') ?? 0;
    if (ct.startsWith('image/') || ct.startsWith('text/') || ct.contains('json')) {
      info.usable = false;
    }
    info.size = len;
    if (len > 0 && len < 150 * 1024) info.usable = false; // çok küçük: önizleme/boş
  } catch (_) {
    // erişilemedi: yine de dene (usable kalır, düşük sıralı)
    info.duration = 0;
  }
  return info;
}

/// Adayları paralel inceler; kullanılabilir olanları iyiden kötüye sıralar,
/// aynı süre/kaliteye sahip kopyaları eler.
Future<List<MediaInfo>> probeAll(
    List<String> urls, Map<String, String> hints, String referer) async {
  final list = await Future.wait([
    for (final u in urls.take(14)) _probeOne(u, hints[u] ?? '', referer),
  ]);
  final ok = list.where((e) => e.usable).toList()
    ..sort((a, b) => b.rank.compareTo(a.rank));
  final seen = <String>{};
  final out = <MediaInfo>[];
  for (final e in ok) {
    final key = e.duration > 0
        ? '${e.kind}|${e.duration.round() ~/ 3}|${e.height}'
        : e.url;
    if (seen.add(key)) out.add(e);
  }
  return out;
}
