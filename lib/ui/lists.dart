import 'package:flutter/material.dart';

import '../core/media.dart';
import '../core/settings.dart';
import '../core/strings.dart';
import 'tab_data.dart';
import 'tabs_panel.dart';

/// Yer imleri
void showBookmarksSheet(
    BuildContext context, AppSettings s, ValueChanged<String> open) {
  showModalBottomSheet(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (ctx) => ListenableBuilder(
      listenable: s,
      builder: (_, __) => s.bookmarks.isEmpty
          ? SizedBox(
              height: 200, child: Center(child: Text(S.t('noBookmarks'))))
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

/// Favori siteler (hazır liste + yer imleri + son kullanılanlar)
void showFavoritesSheet(
    BuildContext context, AppSettings s, ValueChanged<String> open) {
  final sites = s.homeSites();
  showModalBottomSheet(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (ctx) => ConstrainedBox(
      constraints:
          BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * .7),
      child: ListView(
        shrinkWrap: true,
        children: [
          for (final e in sites)
            ListTile(
              leading: Favicon(e[1]),
              title: Text(e[0]),
              subtitle: Text(e[1], maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: () {
                Navigator.pop(ctx);
                open(e[1]);
              },
            ),
        ],
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

/// Algılanan videoların listesi. Seçilen bağlantı [pick] ile döner.
void showMediaSheet(BuildContext context, TabData t, List<String> urls,
    ValueChanged<String> pick) {
  showModalBottomSheet(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (ctx) => ConstrainedBox(
      constraints:
          BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * .7),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(S.t('videosFound'),
                style: Theme.of(ctx).textTheme.titleMedium),
          ),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              children: [
                for (final u in urls)
                  ListTile(
                    leading: Chip(
                        label: Text(
                            (mediaKind(u) ?? t.hints[u] ?? 'VIDEO').toUpperCase())),
                    title: Text(u, maxLines: 2, overflow: TextOverflow.ellipsis),
                    trailing: const Icon(Icons.download_rounded),
                    onTap: () {
                      Navigator.pop(ctx);
                      pick(u);
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
