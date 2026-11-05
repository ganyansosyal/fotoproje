import 'package:flutter/material.dart';

import '../core/media.dart';
import '../core/strings.dart';
import '../services/probe.dart';
import 'tab_data.dart';

const _green = Color(0xFF1E9E4A);

/// Üst çubuk: sekme sayısı, ana sayfa, adres kutusu, bulunan videolar, menü.
class TopBar extends StatelessWidget {
  final TabData tab;
  final int tabCount;
  final bool bookmarked;
  final bool editing;
  final bool meterOn;
  final TextEditingController controller;
  final FocusNode focus;
  final VoidCallback onTabs;
  final VoidCallback onHome;
  final ValueChanged<String> onGo;
  final VoidCallback onFound;
  final ValueChanged<String> onMenu;

  const TopBar({
    super.key,
    required this.tab,
    required this.tabCount,
    required this.bookmarked,
    required this.editing,
    required this.meterOn,
    required this.controller,
    required this.focus,
    required this.onTabs,
    required this.onHome,
    required this.onGo,
    required this.onFound,
    required this.onMenu,
  });

  PopupMenuItem<String> _mi(BuildContext context, String v, IconData i, String text,
      {Widget? trailing}) {
    final c = Theme.of(context).colorScheme.onSurfaceVariant;
    return PopupMenuItem<String>(
      value: v,
      height: 46,
      child: Row(
        children: [
          Icon(i, color: c, size: 20),
          const SizedBox(width: 14),
          Expanded(
              child: Text(text,
                  style: const TextStyle(
                      fontWeight: FontWeight.w500, fontSize: 14.5))),
          if (trailing != null) trailing,
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bar = cs.surfaceContainerHighest;
    final t = tab;
    return Material(
      color: cs.surface,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: 52,
            child: Row(
              children: [
                const SizedBox(width: 10),
                GestureDetector(
                  onTap: onTabs,
                  child: Container(
                    width: 24,
                    height: 24,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      border: Border.all(color: cs.onSurface, width: 1.6),
                      borderRadius: BorderRadius.circular(5),
                    ),
                    child: Text('$tabCount',
                        style: const TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 12)),
                  ),
                ),
                IconButton(
                    iconSize: 22,
                    icon: const Icon(Icons.home_rounded),
                    onPressed: onHome),
                Expanded(
                  child: Container(
                    height: 40,
                    padding: const EdgeInsets.only(left: 16),
                    decoration: BoxDecoration(
                        color: bar, borderRadius: BorderRadius.circular(20)),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: controller,
                            focusNode: focus,
                            onSubmitted: onGo,
                            keyboardType: TextInputType.url,
                            textInputAction: TextInputAction.go,
                            maxLines: 1,
                            style: const TextStyle(
                                fontSize: 14, fontWeight: FontWeight.w500),
                            decoration: InputDecoration(
                                isCollapsed: true,
                                border: InputBorder.none,
                                hintText: S.t('search')),
                          ),
                        ),
                        if (editing)
                          IconButton(
                            iconSize: 20,
                            visualDensity: VisualDensity.compact,
                            icon: const Icon(Icons.keyboard_return_rounded),
                            onPressed: () => onGo(controller.text),
                          )
                        else
                          ValueListenableBuilder<bool>(
                            key: ObjectKey(t),
                            valueListenable: t.loading,
                            builder: (_, l, __) => IconButton(
                              iconSize: 20,
                              visualDensity: VisualDensity.compact,
                              icon: Icon(l ? Icons.close : Icons.refresh),
                              onPressed: () =>
                                  l ? t.c?.stopLoading() : t.c?.reload(),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
                ValueListenableBuilder<List<MediaInfo>>(
                  key: ObjectKey(t),
                  valueListenable: t.found,
                  builder: (_, list, __) {
                    final n = list.length;
                    return IconButton(
                      iconSize: 22,
                      tooltip: S.t('videosFound'),
                      icon: Badge(
                        isLabelVisible: n > 0,
                        label: Text('$n'),
                        backgroundColor: _green,
                        child: Icon(Icons.error_rounded,
                            size: 22, color: n > 0 ? _green : cs.onSurface),
                      ),
                      onPressed: onFound,
                    );
                  },
                ),
                PopupMenuButton<String>(
                  iconSize: 22,
                  icon: const Icon(Icons.more_vert),
                  onSelected: onMenu,
                  itemBuilder: (_) => [
                    _mi(context, 'new', Icons.add_box_rounded, S.t('newTab')),
                    _mi(context, 'share', Icons.share, S.t('share')),
                    _mi(context, 'history', Icons.history, S.t('history')),
                    _mi(context, 'copy', Icons.copy_rounded, S.t('copyLink')),
                    _mi(context, 'bookmarks', Icons.bookmark_border,
                        S.t('bookmarks')),
                    _mi(
                        context,
                        'addbm',
                        bookmarked
                            ? Icons.bookmark_remove_outlined
                            : Icons.bookmark_add_outlined,
                        bookmarked
                            ? S.t('removeBookmark')
                            : S.t('addBookmark')),
                    _mi(context, 'full', Icons.fullscreen_rounded, 'Tam ekran'),
                    _mi(context, 'desktop', Icons.desktop_windows_rounded,
                        S.t('desktop'),
                        trailing: IgnorePointer(
                            child: Checkbox(
                                value: t.desktop,
                                onChanged: (_) {},
                                visualDensity: VisualDensity.compact))),
                    _mi(context, 'meter', Icons.speed, S.t('perfMeter'),
                        trailing: IgnorePointer(
                            child: Checkbox(
                                value: meterOn,
                                onChanged: (_) {},
                                visualDensity: VisualDensity.compact))),
                    _mi(context, 'native', Icons.bolt_rounded,
                        'Yerel tarayıcı (deneme)'),
                    _mi(context, 'shareapp', Icons.group, S.t('shareApp')),
                    _mi(context, 'settings', Icons.settings, S.t('settings')),
                  ],
                ),
              ],
            ),
          ),
          SizedBox(
            height: 2,
            child: ValueListenableBuilder<bool>(
              key: ObjectKey(t),
              valueListenable: t.loading,
              builder: (_, l, __) => l
                  ? ValueListenableBuilder<double>(
                      valueListenable: t.progress,
                      builder: (_, p, __) =>
                          LinearProgressIndicator(value: p, minHeight: 2),
                    )
                  : const SizedBox.shrink(),
            ),
          ),
        ],
      ),
    );
  }
}
