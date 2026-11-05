import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'strings.dart';

class HistoryEntry {
  final String url;
  final String title;
  final int time;
  HistoryEntry(this.url, this.title, this.time);
  Map<String, dynamic> toJson() => {'u': url, 't': title, 'd': time};
  factory HistoryEntry.fromJson(Map<String, dynamic> j) =>
      HistoryEntry(j['u'] as String, (j['t'] as String?) ?? '', (j['d'] as int?) ?? 0);
}

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

const searchEngines = <String, String>{
  'Google': 'https://www.google.com/search?q=',
  'Bing': 'https://www.bing.com/search?q=',
  'DuckDuckGo': 'https://duckduckgo.com/?q=',
  'Yandex': 'https://yandex.com.tr/search/?text=',
};

/// Tüm kalıcı ayarlar. Değişince dinleyicilere haber verir.
class AppSettings extends ChangeNotifier {
  late SharedPreferences _p;

  bool wifiOnly = true; // varsayılan açık, kullanıcı kapatabilir
  bool blockAds = true; // varsayılan açık, kullanıcı kapatabilir
  bool recentSites = true;
  bool syncGallery = true;
  bool publicFolder = false; // false: uygulama klasörü
  String engine = 'Google';
  String lang = 'system';
  String theme = 'light'; // light | dark
  List<String> bookmarks = [];
  List<HistoryEntry> history = [];
  List<List<String>> favorites = []; // [ad, adres]

  ThemeMode get themeMode =>
      theme == 'dark' ? ThemeMode.dark : ThemeMode.light;

  Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    wifiOnly = _p.getBool('wifiOnly') ?? true;
    blockAds = _p.getBool('blockAds') ?? true;
    recentSites = _p.getBool('recentSites') ?? true;
    syncGallery = _p.getBool('syncGallery') ?? true;
    publicFolder = _p.getBool('publicFolder') ?? false;
    engine = _p.getString('engine') ?? 'Google';
    lang = _p.getString('lang') ?? 'system';
    theme = _p.getString('theme') == 'dark' ? 'dark' : 'light';
    bookmarks = _p.getStringList('bm') ?? [];
    try {
      final f = _p.getString('favs');
      if (f != null) {
        favorites = (jsonDecode(f) as List)
            .map((e) => List<String>.from(e as List))
            .toList();
      } else {
        favorites = [for (final e in defaultSites) [e[0], e[1]]];
      }
    } catch (_) {
      favorites = [for (final e in defaultSites) [e[0], e[1]]];
    }
    try {
      final raw = _p.getString('history');
      if (raw != null) {
        history = (jsonDecode(raw) as List)
            .map((e) => HistoryEntry.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
      }
    } catch (_) {
      history = [];
    }
    S.lang = lang;
  }

  void _save() => notifyListeners();

  void setBool(String key, bool v) {
    switch (key) {
      case 'wifiOnly':
        wifiOnly = v;
      case 'blockAds':
        blockAds = v;
      case 'recentSites':
        recentSites = v;
      case 'syncGallery':
        syncGallery = v;
      case 'publicFolder':
        publicFolder = v;
    }
    _p.setBool(key, v);
    _save();
  }

  void setString(String key, String v) {
    switch (key) {
      case 'engine':
        engine = v;
      case 'lang':
        lang = v;
        S.lang = v;
      case 'theme':
        theme = v;
    }
    _p.setString(key, v);
    _save();
  }

  String searchUrl(String q) =>
      (searchEngines[engine] ?? searchEngines['Google']!) + Uri.encodeComponent(q);

  bool isBookmarked(String url) => bookmarks.contains(url);

  void toggleBookmark(String url) {
    if (!bookmarks.remove(url)) bookmarks.add(url);
    _p.setStringList('bm', bookmarks);
    _save();
  }

  void addHistory(String url, String title) {
    if (url.isEmpty || url == 'about:home' || !url.startsWith('http')) return;
    if (history.isNotEmpty && history.first.url == url) {
      return;
    }
    history.insert(0, HistoryEntry(url, title, DateTime.now().millisecondsSinceEpoch));
    if (history.length > 300) history.removeRange(300, history.length);
    _persistHistory(notify: false);
  }

  void updateHistoryTitle(String url, String title) {
    if (history.isNotEmpty && history.first.url == url && title.isNotEmpty) {
      history[0] = HistoryEntry(url, title, history.first.time);
      _persistHistory(notify: false);
    }
  }

  void clearHistory() {
    history = [];
    _persistHistory();
  }

  void _persistHistory({bool notify = true}) {
    _p.setString('history', jsonEncode(history.map((e) => e.toJson()).toList()));
    if (notify) notifyListeners();
  }

  void _saveFavs() {
    _p.setString('favs', jsonEncode(favorites));
    _save();
  }

  bool isFavorite(String url) => favorites.any((e) => e[1] == url);

  void addFavorite(String name, String url) {
    if (url.isEmpty || isFavorite(url)) return;
    final host = Uri.tryParse(url)?.host ?? url;
    favorites.add([name.trim().isEmpty ? host.replaceFirst('www.', '') : name.trim(), url]);
    _saveFavs();
  }

  void removeFavorite(String url) {
    favorites.removeWhere((e) => e[1] == url);
    _saveFavs();
  }

  /// Ana sayfada gösterilecek siteler: yer imleri + son ziyaretler + varsayılanlar
  List<List<String>> homeSites() {
    final out = <List<String>>[for (final e in favorites) [e[0], e[1]]];
    void add(String url, String name) {
      if (out.any((e) => e[1] == url)) return;
      final host = Uri.tryParse(url)?.host ?? url;
      out.add([name.isNotEmpty ? name : host.replaceFirst('www.', ''), url]);
    }

    if (recentSites) {
      final seen = <String>{};
      for (final h in history) {
        final uri = Uri.tryParse(h.url);
        if (uri == null || uri.host.isEmpty) continue;
        if (!seen.add(uri.host)) continue;
        add('${uri.scheme}://${uri.host}', '');
        if (seen.length >= 8) break;
      }
    }
    return out;
  }
}
