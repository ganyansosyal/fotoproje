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
    final light = b == Brightness.light;
    final white = light ? Colors.white : const Color(0xFF202124);
    final grey = light ? const Color(0xFFF1F3F4) : const Color(0xFF303134);
    final base = ColorScheme.fromSeed(
      seedColor: const Color(0xFF1A73E8),
      brightness: b,
    );
    final cs = base.copyWith(
      primary: light ? const Color(0xFF1A73E8) : const Color(0xFF8AB4F8),
      surface: white,
      surfaceTint: Colors.transparent,
      surfaceContainerLowest: white,
      surfaceContainerLow: white,
      surfaceContainer: white,
      surfaceContainerHigh: white,
      surfaceContainerHighest: grey,
      onSurface: light ? const Color(0xFF202124) : const Color(0xFFE8EAED),
    );
    final bold = TextStyle(
        fontWeight: FontWeight.w600, fontSize: 16.5, color: cs.onSurface);
    return ThemeData(
      useMaterial3: true,
      colorScheme: cs,
      scaffoldBackgroundColor: white,
      canvasColor: white,
      // Zıplama/yaylanma yok: kısa ve düz geçişler
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
      }),
      splashFactory: InkSparkle.splashFactory,
      textTheme: ThemeData(brightness: b).textTheme.copyWith(
            titleLarge: TextStyle(
                fontWeight: FontWeight.w800, fontSize: 26, color: cs.onSurface),
          ),
      listTileTheme: ListTileThemeData(
        titleTextStyle: bold,
        contentPadding: const EdgeInsets.symmetric(horizontal: 22),
        minVerticalPadding: 14,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: white,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
        titleTextStyle: TextStyle(
            fontWeight: FontWeight.w800, fontSize: 22, color: cs.onSurface),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: white,
        surfaceTintColor: Colors.transparent,
      ),
      dropdownMenuTheme: DropdownMenuThemeData(
        menuStyle: MenuStyle(
            backgroundColor: WidgetStatePropertyAll(white),
            surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent)),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: white,
        surfaceTintColor: Colors.transparent,
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: white,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.black,
        elevation: 6,
        indicatorColor:
            light ? const Color(0xFFE8F0FE) : const Color(0xFF394457),
        labelTextStyle: WidgetStatePropertyAll(TextStyle(
            fontWeight: FontWeight.w700, fontSize: 12.5, color: cs.onSurface)),
        iconTheme: WidgetStatePropertyAll(
            IconThemeData(size: 26, color: cs.onSurface)),
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
        scrollBehavior: const _FlatScroll(),
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

/// Kenarda yaylanma / uzama efekti olmayan, düz kaydırma.
class _FlatScroll extends MaterialScrollBehavior {
  const _FlatScroll();
  @override
  Widget buildOverscrollIndicator(
          BuildContext context, Widget child, ScrollableDetails details) =>
      child;
  @override
  ScrollPhysics getScrollPhysics(BuildContext context) =>
      const ClampingScrollPhysics();
}
