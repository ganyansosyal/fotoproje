import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';

/// Ana sayfa logolarını (favicon) diske kaydeder. Böylece ana sayfa her
/// açıldığında logolar ağdan gelmez; boş daire -> logo geçişi (titreme) olmaz.
class FavIcons {
  FavIcons._();
  static final FavIcons i = FavIcons._();

  final Map<String, String> _uri = {}; // alan adı -> data: adresi
  final Set<String> _busy = {};
  File? _file;

  String? get(String host) => _uri[host];

  /// Diskteki kayıtları okur (hızlıdır, uygulama açılırken beklenir).
  Future<void> load() async {
    try {
      final dir = await getApplicationSupportDirectory();
      _file = File('${dir.path}/favicons.json');
      if (await _file!.exists()) {
        final m = jsonDecode(await _file!.readAsString());
        if (m is Map) {
          m.forEach((k, v) {
            if (v is String && v.startsWith('data:')) _uri[k.toString()] = v;
          });
        }
      }
    } catch (_) {}
  }

  /// Eksik logoları arka planda indirir; bir sonraki ana sayfa açılışında hazır olur.
  Future<void> ensure(Iterable<String> hosts) async {
    final todo = [
      for (final h in hosts.toSet())
        if (h.isNotEmpty && !_uri.containsKey(h) && _busy.add(h)) h
    ];
    if (todo.isEmpty) return;
    final res = await Future.wait(todo.map(_fetch));
    var changed = false;
    for (var k = 0; k < todo.length; k++) {
      _busy.remove(todo[k]);
      final u = res[k];
      if (u != null) {
        _uri[todo[k]] = u;
        changed = true;
      }
    }
    if (changed) {
      try {
        await _file?.writeAsString(jsonEncode(_uri));
      } catch (_) {}
    }
  }

  Future<String?> _fetch(String host) async {
    final dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 6),
      receiveTimeout: const Duration(seconds: 6),
      responseType: ResponseType.bytes,
      validateStatus: (s) => s == 200,
    ));
    final urls = [
      'https://www.google.com/s2/favicons?domain=$host&sz=128',
      'https://icons.duckduckgo.com/ip3/$host.ico',
    ];
    for (final u in urls) {
      try {
        final r = await dio.get<List<int>>(u);
        final b = r.data;
        final ct = r.headers.value('content-type') ?? 'image/png';
        // Çok küçük dosya = yer tutucu simge, kaydetme
        if (b == null || b.length < 400 || !ct.startsWith('image/')) continue;
        return 'data:${ct.split(';').first};base64,${base64Encode(b)}';
      } catch (_) {}
    }
    return null;
  }
}
