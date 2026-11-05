import 'package:flutter/material.dart';

import '../core/settings.dart';
import '../core/strings.dart';
import '../services/probe.dart';
import 'tab_data.dart';
import 'tabs_panel.dart';

/// Hızlı, zıplamayan alt pencere animasyonu.
const sheetAnim = AnimationStyle(
  duration: Duration(milliseconds: 170),
  reverseDuration: Duration(milliseconds: 120),
  curve: Curves.easeOutCubic,
  reverseCurve: Curves.easeInCubic,
);

Future<T?> showSnapSheet<T>(BuildContext context, WidgetBuilder builder) {
  return showModalBottomSheet<T>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    sheetAnimationStyle: sheetAnim,
    builder: builder,
  );
}

/// Kısa, zıplamayan sayfa geçişi
Route<T> fastRoute<T>(Widget page) => PageRouteBuilder<T>(
      transitionDuration: const Duration(milliseconds: 160),
      reverseTransitionDuration: const Duration(milliseconds: 120),
      pageBuilder: (_, __, ___) => page,
      transitionsBuilder: (_, a, __, child) => FadeTransition(
          opacity: CurvedAnimation(parent: a, curve: Curves.easeOut),
          child: child),
    );

/// Yer imleri
void showBookmarksSheet(
    BuildContext context, AppSettings s, ValueChanged<String> open) {
  showSnapSheet(
    context,
    (ctx) => ListenableBuilder(
      listenable: s,
      builder: (_, __) => s.bookmarks.isEmpty
          ? SizedBox(height: 200, child: Center(child: Text(S.t('noBookmarks'))))
          : ConstrainedBox(
              constraints: BoxConstraints(
                  maxHeight: MediaQuery.of(ctx).size.height * .7),
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final b in List<String>.from(s.bookmarks))
                    ListTile(
                      leading: Favicon(b),
                      title: Text(b, maxLines: 1, overflow: TextOverflow.ellipsis),
                      onTap: () {
                        Navigator.pop(ctx);
                        open(b);
                      },
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () => s.toggleBookmark(b),
                      ),
                    ),
                ],
              ),
            ),
    ),
  );
}

/// Favori ekleme penceresi (ad + adres)
Future<void> showAddFavDialog(BuildContext context, AppSettings s,
    {String name = '', String url = ''}) async {
  final n = TextEditingController(text: name);
  final u = TextEditingController(text: url);
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(S.t('addFav')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
              controller: n,
              decoration: InputDecoration(labelText: S.t('nameLbl'))),
          const SizedBox(height: 8),
          TextField(
              controller: u,
              keyboardType: TextInputType.url,
              decoration: InputDecoration(labelText: S.t('addrLbl'))),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(S.t('cancel'))),
        FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(S.t('add'))),
      ],
    ),
  );
  if (ok == true) {
    var link = u.text.trim();
    if (link.isEmpty) return;
    if (!link.startsWith('http')) link = 'https://$link';
    s.addFavorite(n.text, link);
  }
}

Future<void> confirmRemoveFav(
    BuildContext context, AppSettings s, String url, String name) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(name),
      content: Text(S.t('removeFavQ')),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(S.t('cancel'))),
        FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(S.t('delete'))),
      ],
    ),
  );
  if (ok == true) s.removeFavorite(url);
}

/// Favori siteler: açma, silme ve ekleme
void showFavoritesSheet(BuildContext context, AppSettings s,
    ValueChanged<String> open, {String? currentUrl, String? currentTitle}) {
  showSnapSheet(
    context,
    (ctx) => ListenableBuilder(
      listenable: s,
      builder: (_, __) => ConstrainedBox(
        constraints:
            BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * .75),
        child: ListView(
          shrinkWrap: true,
          children: [
            if (currentUrl != null &&
                currentUrl != homeUrl &&
                !s.isFavorite(currentUrl))
              ListTile(
                leading: const Icon(Icons.star_border_rounded),
                title: Text(S.t('addCurrent')),
                subtitle: Text(currentUrl,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () => s.addFavorite(currentTitle ?? '', currentUrl),
              ),
            ListTile(
              leading: const Icon(Icons.add_rounded),
              title: Text(S.t('addFav')),
              onTap: () => showAddFavDialog(ctx, s),
            ),
            const Divider(height: 1),
            for (final e in List<List<String>>.from(s.favorites))
              ListTile(
                leading: Favicon(e[1]),
                title: Text(e[0]),
                subtitle:
                    Text(e[1], maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () {
                  Navigator.pop(ctx);
                  open(e[1]);
                },
                trailing: IconButton(
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => s.removeFavorite(e[1]),
                ),
              ),
          ],
        ),
      ),
    ),
  );
}

/// Geçmiş sayfası
class HistoryPage extends StatelessWidget {
  final AppSettings s;
  final ValueChanged<String> open;
  const HistoryPage({super.key, required this.s, required this.open});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: s,
      builder: (context, _) => Scaffold(
        appBar: AppBar(
          title: Text(S.t('history')),
          actions: [
            if (s.history.isNotEmpty)
              IconButton(
                  icon: const Icon(Icons.delete_sweep_outlined),
                  onPressed: s.clearHistory),
          ],
        ),
        body: s.history.isEmpty
            ? Center(child: Text(S.t('noHistory')))
            : ListView.builder(
                itemCount: s.history.length,
                itemBuilder: (_, i) {
                  final h = s.history[i];
                  return ListTile(
                    leading: Favicon(h.url),
                    title: Text(h.title.isEmpty ? h.url : h.title,
                        maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: Text(h.url,
                        maxLines: 1, overflow: TextOverflow.ellipsis),
                    onTap: () {
                      Navigator.pop(context);
                      open(h.url);
                    },
                  );
                },
              ),
      ),
    );
  }
}

/// Bulunan videolar (incelenmiş, iyiden kötüye sıralı). Seçim [pick] ile döner.
void showMediaSheet(BuildContext context, List<MediaInfo> items,
    ValueChanged<MediaInfo> pick) {
  showSnapSheet(
    context,
    (ctx) => ConstrainedBox(
      constraints:
          BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * .7),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(S.t('videosFound'), style: Theme.of(ctx).textTheme.titleMedium),
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 6, 24, 10),
            child: Text(S.t('detectInfo'),
                textAlign: TextAlign.center,
                style: Theme.of(ctx).textTheme.bodySmall),
          ),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              children: [
                for (var i = 0; i < items.length; i++)
                  ListTile(
                    leading: CircleAvatar(
                      backgroundColor: i == 0
                          ? const Color(0xFF1E9E4A)
                          : Theme.of(ctx).colorScheme.surfaceContainerHighest,
                      child: Icon(Icons.play_arrow_rounded,
                          color: i == 0
                              ? Colors.white
                              : Theme.of(ctx).colorScheme.onSurface),
                    ),
                    title: Text(items[i].title),
                    subtitle: Text(
                        i == 0
                            ? '${S.t('best')} · ${items[i].subtitle}'
                            : items[i].subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis),
                    trailing: const Icon(Icons.download_rounded),
                    onTap: () {
                      Navigator.pop(ctx);
                      pick(items[i]);
                    },
                  ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
