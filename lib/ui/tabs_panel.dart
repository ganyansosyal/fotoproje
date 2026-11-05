import 'package:flutter/material.dart';

import '../core/strings.dart';
import 'tab_data.dart';

class Favicon extends StatelessWidget {
  final String url;
  final double size;
  const Favicon(this.url, {super.key, this.size = 24});

  @override
  Widget build(BuildContext context) {
    final host = Uri.tryParse(url)?.host ?? '';
    final fallback = Icon(Icons.public, size: size);
    if (host.isEmpty) return Icon(Icons.home_rounded, size: size);
    return Image.network(
      'https://www.google.com/s2/favicons?domain=$host&sz=64',
      width: size,
      height: size,
      gaplessPlayback: true,
      errorBuilder: (_, __, ___) => fallback,
    );
  }
}

/// Soldan kayan sekme paneli (ekran görüntüsündeki "Sekmeler" çekmecesi).
class TabsPanel extends StatelessWidget {
  final List<TabData> tabs;
  final int cur;
  final ValueChanged<int> onSelect;
  final ValueChanged<int> onClose;
  final VoidCallback onNew;
  final VoidCallback onCloseAll;
  final VoidCallback onFavorites;
  final VoidCallback onBack;
  final VoidCallback onHome;
  final VoidCallback onForward;

  const TabsPanel({
    super.key,
    required this.tabs,
    required this.cur,
    required this.onSelect,
    required this.onClose,
    required this.onNew,
    required this.onCloseAll,
    required this.onFavorites,
    required this.onBack,
    required this.onHome,
    required this.onForward,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bar = Color.alphaBlend(cs.primary.withValues(alpha: .08), cs.surface);
    return Material(
      color: cs.surface,
      elevation: 8,
      child: Column(
        children: [
          Container(
            color: bar,
            padding: const EdgeInsets.fromLTRB(16, 8, 4, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(S.t('tabs'),
                      style: Theme.of(context).textTheme.titleLarge),
                ),
                PopupMenuButton<int>(
                  icon: const Icon(Icons.more_vert),
                  onSelected: (v) => v == 0 ? onNew() : onCloseAll(),
                  itemBuilder: (_) => [
                    PopupMenuItem(value: 0, child: Text(S.t('newTab'))),
                    PopupMenuItem(value: 1, child: Text(S.t('closeAll'))),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: tabs.length,
              itemBuilder: (_, i) {
                final t = tabs[i];
                final sel = i == cur;
                final title = t.url == homeUrl
                    ? S.t('home')
                    : (t.title.isEmpty ? t.url : t.title);
                return Material(
                  color: sel ? bar : Colors.transparent,
                  child: InkWell(
                    onTap: () => onSelect(i),
                    child: SizedBox(
                      height: 60,
                      child: Row(
                        children: [
                          const SizedBox(width: 16),
                          Favicon(t.url, size: 26),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Text(title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context)
                                    .textTheme
                                    .titleMedium
                                    ?.copyWith(fontWeight: FontWeight.w600)),
                          ),
                          IconButton(
                              icon: const Icon(Icons.close),
                              onPressed: () => onClose(i)),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          const Divider(height: 1),
          ListTile(
            leading: Icon(Icons.card_giftcard_rounded, color: cs.primary),
            title: Text(S.t('favSites'),
                style: Theme.of(context).textTheme.titleMedium),
            onTap: onFavorites,
          ),
          Container(
            color: bar,
            height: 64,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                IconButton(icon: const Icon(Icons.arrow_back), onPressed: onBack),
                IconButton(icon: const Icon(Icons.home_rounded), onPressed: onHome),
                IconButton(
                    icon: const Icon(Icons.arrow_forward), onPressed: onForward),
                IconButton(icon: const Icon(Icons.add), onPressed: onNew),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
