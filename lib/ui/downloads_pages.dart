import 'package:flutter/material.dart';
import 'package:open_filex/open_filex.dart';
import 'package:share_plus/share_plus.dart';

import '../core/strings.dart';
import '../services/download_manager.dart';

String fmtSize(int b) {
  if (b <= 0) return '';
  if (b < 1048576) return '${(b / 1024).toStringAsFixed(0)} KB';
  if (b < 1073741824) return '${(b / 1048576).toStringAsFixed(1)} MB';
  return '${(b / 1073741824).toStringAsFixed(2)} GB';
}

class _Empty extends StatelessWidget {
  final IconData icon;
  final String text;
  const _Empty(this.icon, this.text);
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme.onSurfaceVariant;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 64, color: c.withValues(alpha: .5)),
          const SizedBox(height: 12),
          Text(text, style: TextStyle(color: c)),
        ],
      ),
    );
  }
}

/// Net kenarlı, yuvarlak beyaz kart (zeminden belirgin ayrılır)
class _CardBox extends StatelessWidget {
  final Widget child;
  const _CardBox({required this.child});
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
      child: Material(
        color: cs.surface,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(22),
            side: BorderSide(color: cs.outlineVariant)),
        child: child,
      ),
    );
  }
}

/// "İlerleme" sekmesi: devam eden / başarısız indirmeler
class ProgressPage extends StatelessWidget {
  final DownloadManager dm;
  const ProgressPage(this.dm, {super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: dm,
      builder: (context, _) {
        if (dm.active.isEmpty) {
          return _Empty(Icons.downloading_rounded, S.t('noActive'));
        }
        return ListView.builder(
          padding: const EdgeInsets.symmetric(vertical: 10),
          itemCount: dm.active.length,
          itemBuilder: (_, i) => _ActiveTile(dm, dm.active[i]),
        );
      },
    );
  }
}

class _ActiveTile extends StatelessWidget {
  final DownloadManager dm;
  final DownloadTask t;
  const _ActiveTile(this.dm, this.t);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final failed = t.status == DlStatus.failed;
    return RepaintBoundary(
      child: _CardBox(child: ListTile(
        leading: CircleAvatar(
          backgroundColor: failed ? cs.errorContainer : cs.primaryContainer,
          child: Icon(failed ? Icons.error_outline : Icons.downloading_rounded,
              color: failed ? cs.onErrorContainer : cs.onPrimaryContainer),
        ),
        title: Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: failed
            ? Text('${S.t('failed')}: ${t.error ?? ''}',
                maxLines: 2, overflow: TextOverflow.ellipsis,
                style: TextStyle(color: cs.error))
            : ValueListenableBuilder<double>(
                valueListenable: t.progress,
                builder: (_, p, __) => Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: LinearProgressIndicator(
                            value: p < 0 ? null : p.clamp(0, 1), minHeight: 8),
                      ),
                      const SizedBox(height: 4),
                      ValueListenableBuilder<String>(
                        valueListenable: t.label,
                        builder: (_, l, __) => Text(l.isEmpty ? S.t('queued') : l,
                            style: Theme.of(context).textTheme.bodySmall),
                      ),
                    ],
                  ),
                ),
              ),
        trailing: IconButton(
          icon: Icon(failed ? Icons.close : Icons.cancel_outlined),
          onPressed: () => failed ? dm.removeFailed(t) : dm.cancel(t),
        ),
      )),
    );
  }
}

/// "Bitti" sekmesi: tamamlanan indirmeler
class DonePage extends StatelessWidget {
  final DownloadManager dm;
  const DonePage(this.dm, {super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: dm,
      builder: (context, _) {
        if (dm.done.isEmpty) {
          return _Empty(Icons.folder_open_rounded, S.t('noDownloads'));
        }
        final cs = Theme.of(context).colorScheme;
        return ListView.builder(
          padding: const EdgeInsets.symmetric(vertical: 10),
          itemCount: dm.done.length,
          itemBuilder: (_, i) {
            final t = dm.done[i];
            return RepaintBoundary(
              child: _CardBox(child: Dismissible(
                key: ValueKey(t.id),
                direction: DismissDirection.endToStart,
                background: Container(
                  color: cs.errorContainer,
                  alignment: Alignment.centerRight,
                  padding: const EdgeInsets.only(right: 20),
                  child: Icon(Icons.delete_outline, color: cs.onErrorContainer),
                ),
                onDismissed: (_) => dm.deleteDone(t),
                child: ListTile(
                  leading: CircleAvatar(
                    backgroundColor: cs.primaryContainer,
                    child: Icon(Icons.play_arrow_rounded,
                        color: cs.onPrimaryContainer),
                  ),
                  title: Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text(t.note == null ? fmtSize(t.size) : '${fmtSize(t.size)} · ${t.note}'),
                  onTap: () {
                    if (t.path != null) OpenFilex.open(t.path!);
                  },
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.share_outlined),
                        onPressed: () {
                          if (t.path != null) {
                            Share.shareXFiles([XFile(t.path!)]);
                          }
                        },
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () => dm.deleteDone(t),
                      ),
                    ],
                  ),
                ),
              )),
            );
          },
        );
      },
    );
  }
}
