import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

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

class TabData {
  InAppWebViewController? c;
  String url;
  String title = '';
  bool desktop = false;
  int? windowId; // window.open ile açılan sekmeler için
  final progress = ValueNotifier<double>(1);
  final loading = ValueNotifier<bool>(false);
  final mediaCount = ValueNotifier<int>(0);
  final Set<String> media = <String>{};
  final Map<String, String> hints = {};

  TabData(this.url, {this.windowId});

  void addMedia(String u, [String? hint]) {
    if (media.add(u)) mediaCount.value = media.length;
    if (hint != null && hint.isNotEmpty) hints[u] = hint;
  }

  void clearMedia() {
    media.clear();
    mediaCount.value = 0;
  }

  bool disposed = false;

  /// Notifier'lar bilerek dispose edilmez: kapanan sekmenin WebView'ı son
  /// olaylarını gönderebilir. Dinleyici kalmadığı için çöp toplayıcı temizler.
  void dispose() {
    disposed = true;
    c = null;
  }
}
