import 'package:flutter/material.dart';

import '../core/strings.dart';
import '../services/download_manager.dart';

/// Alt çubuk: Sekme / İlerleme / Bitti / Ayarlar.
class BottomBar extends StatelessWidget {
  final DownloadManager dm;
  final int section;
  final ValueChanged<int> onSelect;

  const BottomBar({
    super.key,
    required this.dm,
    required this.section,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: dm,
      builder: (_, __) {
        // Bitti sekmesi açıkken kırmızı sayıyı sıfırla
        if (section == 2 && dm.unseen > 0) {
          WidgetsBinding.instance.addPostFrameCallback((_) => dm.markSeen());
        }
        return NavigationBar(
          height: 52,
          selectedIndex: section,
          onDestinationSelected: onSelect,
          destinations: [
            NavigationDestination(
                icon: const Icon(Icons.layers_outlined),
                selectedIcon: const Icon(Icons.layers_rounded),
                label: S.t('tab')),
            NavigationDestination(
                icon: Badge(
                    isLabelVisible: dm.active.isNotEmpty,
                    label: Text('${dm.active.length}'),
                    child: const Icon(Icons.download_outlined)),
                selectedIcon: const Icon(Icons.download_rounded),
                label: S.t('progress')),
            NavigationDestination(
                icon: Badge(
                    isLabelVisible: dm.unseen > 0,
                    backgroundColor: Colors.red,
                    label: Text('${dm.unseen}'),
                    child: const Icon(Icons.folder_outlined)),
                selectedIcon: Badge(
                    isLabelVisible: dm.unseen > 0,
                    backgroundColor: Colors.red,
                    label: Text('${dm.unseen}'),
                    child: const Icon(Icons.folder_rounded)),
                label: S.t('done')),
            NavigationDestination(
                icon: const Icon(Icons.settings_outlined),
                selectedIcon: const Icon(Icons.settings_rounded),
                label: S.t('settings')),
          ],
        );
      },
    );
  }
}
