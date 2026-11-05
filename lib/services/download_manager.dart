import 'dart:convert';
import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:gal/gal.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

import '../core/media.dart';
import '../core/settings.dart';
import '../core/strings.dart';
import 'engine.dart';

enum DlStatus { running, done, failed, canceled }

class DownloadTask {
  final String id;
  final String url;
  final String referer;
  String title;
  final int createdAt;
  DlStatus status = DlStatus.running;
  String? path;
  String? error;
  String? note;
  int size = 0;
  final progress = ValueNotifier<double>(0);
  final label = ValueNotifier<String>('');
  final cancel = CancelToken();

  DownloadTask(this.id, this.url, this.referer, this.title)
      : createdAt = DateTime.now().millisecondsSinceEpoch;

  DownloadTask.restored(this.id, this.url, this.title, this.path, this.size,
      this.createdAt)
      : referer = '',
        status = DlStatus.done {
    progress.value = 1;
  }

  Map<String, dynamic> toJson() =>
      {'id': id, 'u': url, 't': title, 'p': path, 's': size, 'c': createdAt};
}

typedef BlobReader = Future<String?> Function(String url);

class DownloadManager extends ChangeNotifier {
  final AppSettings settings;
  DownloadManager(this.settings);

  final List<DownloadTask> active = [];
  final List<DownloadTask> done = [];
  int unseen = 0; // Bitti sekmesinde henüz görülmemiş biten indirme sayısı

  void markSeen() {
    if (unseen == 0) return;
    unseen = 0;
    notifyListeners();
  }
  late SharedPreferences _p;
  String _appDir = '';

  Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    try {
      final base = await getExternalStorageDirectory() ??
          await getApplicationDocumentsDirectory();
      _appDir = '${base.path}/VideoDownloader';
    } catch (_) {
      _appDir = '';
    }
    try {
      final raw = _p.getString('done');
      if (raw != null) {
        for (final e in jsonDecode(raw) as List) {
          final m = Map<String, dynamic>.from(e as Map);
          final path = m['p'] as String?;
          if (path == null || !File(path).existsSync()) continue;
          done.add(DownloadTask.restored(m['id'] as String, m['u'] as String,
              m['t'] as String, path, (m['s'] as int?) ?? 0, (m['c'] as int?) ?? 0));
        }
      }
    } catch (_) {}
  }

  String get displayPath => settings.publicFolder
      ? '/storage/emulated/0/Download/VideoDownloader'
      : _appDir;

  void _persist() {
    _p.setString('done', jsonEncode(done.map((e) => e.toJson()).toList()));
  }

  Future<bool> _wifiOk() async {
    if (!settings.wifiOnly) return true;
    try {
      final r = await Connectivity().checkConnectivity();
      return r.contains(ConnectivityResult.wifi) ||
          r.contains(ConnectivityResult.ethernet);
    } catch (_) {
      return true;
    }
  }

  /// Hata mesajı döner (null: başladı).
  Future<String?> start(String url,
      {required String referer,
      String? hint,
      String? title,
      BlobReader? blobReader}) async {
    if (!await _wifiOk()) return S.t('wifiBlocked');
    final kind = mediaKind(url) ?? hint ?? 'mp4';
    final name = title?.trim().isNotEmpty == true ? title!.trim() : _nameOf(url);
    final task = DownloadTask(
        DateTime.now().microsecondsSinceEpoch.toString(), url, referer, name);
    active.insert(0, task);
    notifyListeners();
    _run(task, () async {
      final tmp = await getTemporaryDirectory();
      final ts = task.createdAt;
      final stream = kind == 'm3u8' || kind == 'mpd' || kind == 'blob';
      final path = '${tmp.path}/video_$ts.${stream ? 'mp4' : kind}';
      final apath = '${tmp.path}/ses_$ts.m4a';
      void report(double p, String l) {
        task.progress.value = p;
        task.label.value = l;
      }

      var sepAudio = false;
      if (kind == 'm3u8') {
        sepAudio = await downloadHls(url, path, apath, referer, report, task.cancel);
      } else if (kind == 'mpd') {
        sepAudio = await downloadDash(url, path, apath, referer, report, task.cancel);
      } else if (kind == 'blob') {
        if (blobReader == null) throw 'Blob okunamadı';
        await writeBase64(await blobReader(url), path);
      } else {
        await Dio().download(url, path,
            cancelToken: task.cancel,
            options: Options(headers: {'Referer': referer}),
            onReceiveProgress: (r, tot) {
          task.progress.value = tot > 0 ? r / tot : -1;
          task.label.value = tot > 0
              ? '%${r * 100 ~/ tot}'
              : '${(r / 1048576).toStringAsFixed(1)} MB';
        });
      }
      await _finish(task, path, sepAudio ? apath : null);
    });
    return null;
  }

  Future<String?> startYoutube(String url, {String? title}) async {
    if (!await _wifiOk()) return S.t('wifiBlocked');
    final task = DownloadTask(DateTime.now().microsecondsSinceEpoch.toString(),
        url, url, title?.isNotEmpty == true ? title! : 'YouTube');
    task.progress.value = -1;
    active.insert(0, task);
    notifyListeners();
    _run(task, () async {
      final yt = YoutubeExplode();
      try {
        final manifest = await yt.videos.streamsClient.getManifest(VideoId(url));
        final s = manifest.muxed.withHighestBitrate();
        final tmp = await getTemporaryDirectory();
        final path = '${tmp.path}/yt_${task.createdAt}.mp4';
        final sink = File(path).openWrite();
        final total = s.size.totalBytes;
        var got = 0;
        try {
          await for (final c in yt.videos.streamsClient.get(s)) {
            if (task.cancel.isCancelled) throw CanceledException();
            got += c.length;
            sink.add(c);
            task.progress.value = total > 0 ? got / total : -1;
            task.label.value = total > 0
                ? '%${got * 100 ~/ total}'
                : '${(got / 1048576).toStringAsFixed(1)} MB';
          }
        } finally {
          await sink.close();
        }
        await _finish(task, path, null);
      } finally {
        yt.close();
      }
    });
    return null;
  }

  Future<void> _run(DownloadTask task, Future<void> Function() body) async {
    try {
      await body();
      task.status = DlStatus.done;
      task.progress.value = 1;
      active.remove(task);
      done.insert(0, task);
      unseen++;
      _persist();
    } catch (e) {
      final canceled = e is CanceledException ||
          (e is DioException && CancelToken.isCancel(e));
      task.status = canceled ? DlStatus.canceled : DlStatus.failed;
      task.error = canceled ? S.t('canceled') : '$e';
      if (canceled) active.remove(task);
    }
    notifyListeners();
  }

  void cancel(DownloadTask t) {
    if (!t.cancel.isCancelled) t.cancel.cancel();
    if (t.status == DlStatus.failed) {
      active.remove(t);
      notifyListeners();
    }
  }

  void removeFailed(DownloadTask t) {
    active.remove(t);
    notifyListeners();
  }

  Future<void> deleteDone(DownloadTask t, {bool deleteFile = true}) async {
    done.remove(t);
    if (deleteFile && t.path != null) {
      try {
        await File(t.path!).delete();
      } catch (_) {}
    }
    _persist();
    notifyListeners();
  }

  Future<Directory> _targetDir() async {
    if (settings.publicFolder) {
      try {
        final d = Directory('/storage/emulated/0/Download/VideoDownloader');
        await d.create(recursive: true);
        final f = File('${d.path}/.w');
        await f.writeAsString('1');
        await f.delete();
        return d;
      } catch (_) {
        folderFallback = true;
      }
    }
    final d = Directory(_appDir);
    await d.create(recursive: true);
    return d;
  }

  bool folderFallback = false;

  static const _mux = MethodChannel('app/mux');

  Future<void> _finish(DownloadTask task, String tmpPath, String? audioTmp) async {
    // Ayrı ses varsa yerel MediaMuxer ile tek mp4'e birleştir.
    var src = tmpPath;
    var sepAudio = audioTmp;
    if (audioTmp != null) {
      task.label.value = '...';
      final out = '${tmpPath}_m.mp4';
      try {
        await _mux.invokeMethod('mux',
            {'video': tmpPath, 'audio': audioTmp, 'out': out});
        File(tmpPath).delete().ignore();
        File(audioTmp).delete().ignore();
        src = out;
        sepAudio = null;
      } catch (_) {
        task.note = S.t('mergeFail');
        File(out).delete().ignore();
      }
    }
    final dir = await _targetDir();
    final base = _safe(task.title);
    final ext = src.split('.').last;
    var dest = '${dir.path}/$base.$ext';
    var n = 1;
    while (File(dest).existsSync()) {
      dest = '${dir.path}/$base (${n++}).$ext';
    }
    await File(src).copy(dest);
    File(src).delete().ignore();
    task.path = dest;
    task.size = await File(dest).length();
    if (sepAudio != null) {
      final ad = dest.replaceFirst(RegExp(r'\.[^.]+$'), '_ses.m4a');
      await File(sepAudio).copy(ad);
      File(sepAudio).delete().ignore();
    }
    if (settings.syncGallery) {
      try {
        await Gal.putVideo(dest);
      } catch (_) {}
    }
  }

  String _nameOf(String url) {
    final seg = (Uri.tryParse(url)?.pathSegments ?? const <String>[])
        .where((e) => e.isNotEmpty)
        .toList();
    var n = seg.isEmpty ? 'video' : seg.last;
    n = n.replaceFirst(RegExp(r'\.[A-Za-z0-9]{2,5}$'), '');
    if (n.length < 2 || n == 'index' || n == 'master' || n == 'playlist') {
      n = 'video_${DateTime.now().millisecondsSinceEpoch ~/ 1000}';
    }
    return n;
  }

  String _safe(String s) {
    var r = s.replaceAll(RegExp(r'[\\/:*?"<>|\n\r]'), '_').trim();
    if (r.isEmpty) r = 'video';
    return r.length > 80 ? r.substring(0, 80) : r;
  }
}
