import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show FontFeature, FrameTiming;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

class PerfStats {
  final int fps;
  final double worstMs;
  final double rasterMs;
  final int jank;
  const PerfStats(this.fps, this.worstMs, this.rasterMs, this.jank);
}

/// Performans ölçer: Flutter kare süreleri + sayfanın (WebView) kare hızı.
class PerfMeter {
  final ui = ValueNotifier<PerfStats>(const PerfStats(0, 0, 0, 0));

  /// [sayfa fps, en kötü kare ms, takılma sayısı] - sayfadaki JS'ten gelir
  final page = ValueNotifier<List<int>>(const [0, 0, 0]);

  final _frames = <FrameTiming>[];
  Timer? _timer;
  bool _on = false;

  void start() {
    if (_on) return;
    _on = true;
    SchedulerBinding.instance.addTimingsCallback(_onTimings);
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _tick());
  }

  void stop() {
    if (!_on) return;
    _on = false;
    SchedulerBinding.instance.removeTimingsCallback(_onTimings);
    _timer?.cancel();
    _frames.clear();
    ui.value = const PerfStats(0, 0, 0, 0);
    page.value = const [0, 0, 0];
  }

  void _onTimings(List<FrameTiming> t) => _frames.addAll(t);

  void _tick() {
    final f = List<FrameTiming>.of(_frames);
    _frames.clear();
    var worst = 0.0;
    var raster = 0.0;
    var jank = 0;
    for (final x in f) {
      final ms = x.totalSpan.inMicroseconds / 1000;
      worst = math.max(worst, ms);
      raster = math.max(raster, x.rasterDuration.inMicroseconds / 1000);
      if (ms > 33) jank++;
    }
    ui.value = PerfStats(f.length * 2, worst, raster, jank);
  }
}

Color _fpsColor(int fps) => fps >= 50
    ? const Color(0xFF4CAF50)
    : fps >= 30
        ? const Color(0xFFFFC107)
        : const Color(0xFFF44336);

class PerfPanel extends StatelessWidget {
  final PerfMeter m;
  const PerfPanel({super.key, required this.m});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<List<int>>(
      valueListenable: m.page,
      builder: (_, p, __) => ValueListenableBuilder<PerfStats>(
        valueListenable: m.ui,
        builder: (_, u, __) => Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(
              color: const Color(0xCC000000),
              borderRadius: BorderRadius.circular(10)),
          child: DefaultTextStyle(
            style: const TextStyle(
                color: Colors.white,
                fontSize: 11.5,
                height: 1.35,
                fontFeatures: [FontFeature.tabularFigures()]),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('SAYFA (WebView)',
                    style: TextStyle(fontWeight: FontWeight.w700)),
                Text('${p[0]} fps',
                    style: TextStyle(
                        color: _fpsColor(p[0]),
                        fontSize: 16,
                        fontWeight: FontWeight.w800)),
                Text('en kötü kare ${p[1]} ms · takılma ${p[2]}'),
                const SizedBox(height: 6),
                const Text('ARAYÜZ (Flutter)',
                    style: TextStyle(fontWeight: FontWeight.w700)),
                Text(u.fps == 0
                    ? 'çizim yok (boşta)'
                    : '${u.fps} fps · en kötü ${u.worstMs.toStringAsFixed(0)} ms'
                        ' · takılma ${u.jank}'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
