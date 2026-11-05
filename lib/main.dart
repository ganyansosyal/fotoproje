import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'core/settings.dart';
import 'services/download_manager.dart';
import 'ui/browser_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final settings = AppSettings();
  await settings.load();
  final downloads = DownloadManager(settings);
  await downloads.load();
  runApp(App(settings: settings, downloads: downloads));
}

class App extends StatelessWidget {
  final AppSettings settings;
  final DownloadManager downloads;
  const App({super.key, required this.settings, required this.downloads});

  ThemeData _theme(Brightness b) {
    final cs = ColorScheme.fromSeed(
      seedColor: const Color(0xFF1A73E8),
      brightness: b,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: cs,
      scaffoldBackgroundColor: cs.surface,
      // Sayfa geçişleri: sade ve akıcı
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
      }),
      splashFactory: InkSparkle.splashFactory,
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: Color.alphaBlend(
            cs.primary.withValues(alpha: .08), cs.surface),
        elevation: 0,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    // Sadece tema/dil değişince yeniden kurulur; WebView durumu korunur.
    return ListenableBuilder(
      listenable: settings,
      builder: (context, _) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Tarayıcı',
        theme: _theme(Brightness.light),
        darkTheme: _theme(Brightness.dark),
        themeMode: settings.themeMode,
        builder: (context, child) {
          final dark = Theme.of(context).brightness == Brightness.dark;
          return AnnotatedRegion<SystemUiOverlayStyle>(
            value: SystemUiOverlayStyle(
              statusBarColor: Colors.transparent,
              statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
              systemNavigationBarColor: Theme.of(context)
                  .colorScheme
                  .surface,
              systemNavigationBarIconBrightness:
                  dark ? Brightness.light : Brightness.dark,
            ),
            child: child!,
          );
        },
        home: BrowserPage(settings: settings, downloads: downloads),
      ),
    );
  }
}
