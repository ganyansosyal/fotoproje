import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../services/probe.dart';

const homeUrl = 'about:home';
const homeBase = 'https://home.local/';

String shownUrl(String u) => u == homeUrl ? '' : u;

String normUrl(String? u, String fallback) {
  if (u == null) return fallback;
  if (u.startsWith('https://home.local')) return homeUrl;
  return u;
}

bool isYoutubeWatch(String url) {
  final u = url.toLowerCase();
  return u.contains('youtube.com/watch') ||
      u.contains('youtube.com/shorts') ||
      u.contains('youtu.be/');
}

class NetCand {
  final String url;
  final String kind;
  DateTime t;
  NetCand(this.url, this.kind) : t = DateTime.now();
}

class TabData {
  static int _seq = 0;
  final int id = ++_seq;
  InAppWebViewController? c;

  /// Ağ seviyesinde (isolate'ta sınıflandırılmış) yakalanan medya adayları
  final List<NetCand> netCands = [];

  void addNet(String url, String kind) {
    for (final c in netCands) {
      if (c.url == url) {
        c.t = DateTime.now();
        return;
      }
    }
    netCands.add(NetCand(url, kind));
    if (netCands.length > 40) netCands.removeAt(0);
  }

  String url;
  String title = '';
  bool desktop = false;
  int? windowId; // window.open ile açılan sekmeler için
  final progress = ValueNotifier<double>(1);
  final loading = ValueNotifier<bool>(false);
  final mediaCount = ValueNotifier<int>(0);
  final Set<String> media = <String>{};
  final Map<String, String> hints = {};
  final found = ValueNotifier<List<MediaInfo>>(const []);
  Timer? probeTimer;
  bool bannerDone = false;

  TabData(this.url, {this.windowId});

  // Sayfadaki oynatıcının son bildirimi (null: şu an oynayan video yok)
  String? playerId;
  double playerDur = 0;
  bool playerLive = false;
  int playerH = 0;
  String playerSrc = '';
  String? playerRef; // videonun bulunduğu çerçevenin adresi (Referer için)
  int probeGen = 0;

  /// Oynatıcı bildirimini işler. Değişiklik olduysa true döner.
  bool onPlayer(String raw) {
    Map m;
    try {
      final d = jsonDecode(raw);
      if (d is! Map) return false;
      m = d;
    } catch (_) {
      return false;
    }
    final id = m['id']?.toString() ?? '';
    if (m['stop'] == true) {
      if (id != playerId) return false;
      clearMedia();
      return true;
    }
    final dur = (m['d'] as num?)?.toDouble() ?? 0;
    if (id != playerId || (dur - playerDur).abs() > 1) bannerDone = false;
    playerId = id;
    playerDur = dur;
    playerLive = m['live'] == true;
    playerH = (m['h'] as num?)?.toInt() ?? 0;
    playerSrc = m['src']?.toString() ?? '';
    final ref = m['ref']?.toString() ?? '';
    playerRef = ref.startsWith('http') ? ref : null;
    media
      ..clear()
      ..addAll([
        for (final u in (m['urls'] as List? ?? const [])) u.toString()
      ]);
    mediaCount.value = media.length;
    hints.clear();
    (m['hints'] as Map?)?.forEach((k, v) => hints[k.toString()] = v.toString());
    return true;
  }

  void clearMedia() {
    netCands.clear();
    media.clear();
    hints.clear();
    mediaCount.value = 0;
    probeTimer?.cancel();
    found.value = const [];
    bannerDone = false;
    playerId = null;
    playerDur = 0;
    playerLive = false;
    playerH = 0;
    playerSrc = '';
    playerRef = null;
  }

  bool disposed = false;

  /// Notifier'lar bilerek dispose edilmez: kapanan sekmenin WebView'ı son
  /// olaylarını gönderebilir. Dinleyici kalmadığı için çöp toplayıcı temizler.
  void dispose() {
    disposed = true;
    probeTimer?.cancel();
    c = null;
  }
}
