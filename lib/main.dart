import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'core/settings.dart';
import 'services/fav_icons.dart';
import 'ui/home_html.dart';
import 'services/download_manager.dart';
import 'ui/browser_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final settings = AppSettings();
  await settings.load();
  await FavIcons.i.load();
  FavIcons.i.ensure(homeHosts(settings)); // arka planda, beklenmez
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
    // Sayfa zemini: kartlar (beyaz) zeminden net ayrılsın
    final bg = light ? const Color(0xFFEEF1F6) : const Color(0xFF121314);
    final grey = light ? const Color(0xFFF1F3F4) : const Color(0xFF303134);
    final base = ColorScheme.fromSeed(
      seedColor: const Color(0xFF1A73E8),
      brightness: b,
    );
    final cs = base.copyWith(
      primary: light ? const Color(0xFF0B63E5) : const Color(0xFF8AB4F8),
      primaryContainer:
          light ? const Color(0xFFD6E4FF) : const Color(0xFF2F4A7A),
      onPrimaryContainer:
          light ? const Color(0xFF062E6F) : const Color(0xFFD6E4FF),
      outlineVariant:
          light ? const Color(0xFFD5DAE3) : const Color(0xFF3C4043),
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
        fontWeight: FontWeight.w600, fontSize: 15, color: cs.onSurface);
    return ThemeData(
      useMaterial3: true,
      colorScheme: cs,
      scaffoldBackgroundColor: bg,
      canvasColor: white,
      // Zıplama/yaylanma yok: kısa ve düz geçişler
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
      }),
      splashFactory: InkSparkle.splashFactory,
      textTheme: ThemeData(brightness: b).textTheme.copyWith(
            titleLarge: TextStyle(
                fontWeight: FontWeight.w700, fontSize: 22, color: cs.onSurface),
          ),
      cardTheme: CardThemeData(
        color: white,
        elevation: 0,
        margin: EdgeInsets.zero,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(22),
            side: BorderSide(color: cs.outlineVariant)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
            textStyle:
                const TextStyle(fontWeight: FontWeight.w800, fontSize: 15.5),
            padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24))),
      ),
      listTileTheme: ListTileThemeData(
        titleTextStyle: bold,
        subtitleTextStyle: TextStyle(
            fontWeight: FontWeight.w500,
            fontSize: 14,
            color: cs.onSurface.withValues(alpha: .68)),
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
        elevation: 2,
        indicatorColor: cs.primaryContainer,
        labelTextStyle: WidgetStateProperty.resolveWith((st) => TextStyle(
            fontWeight:
                st.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500,
            fontSize: 11,
            color: st.contains(WidgetState.selected)
                ? cs.primary
                : cs.onSurface.withValues(alpha: .72))),
        iconTheme: WidgetStateProperty.resolveWith((st) => IconThemeData(
            size: 22,
            color: st.contains(WidgetState.selected)
                ? cs.primary
                : cs.onSurface.withValues(alpha: .72))),
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

/// Listelerde kenara gelince sert durmak yerine yumuşak esneme (stretch)
/// efekti kalır; Android'in standart kaydırma fiziği kullanılır.
class _FlatScroll extends MaterialScrollBehavior {
  const _FlatScroll();
  @override
  ScrollPhysics getScrollPhysics(BuildContext context) =>
      const ClampingScrollPhysics();
}
