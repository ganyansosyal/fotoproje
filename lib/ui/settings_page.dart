import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:share_plus/share_plus.dart';

import '../core/settings.dart';
import '../core/strings.dart';
import '../services/download_manager.dart';

const appVersion = '2.9.0';

class SettingsPage extends StatelessWidget {
  final AppSettings s;
  final DownloadManager dm;
  final VoidCallback onHistoryCleared;
  final bool embedded; // alt çubuktaki sekme olarak (başlık çubuğu yok)
  const SettingsPage(
      {super.key,
      required this.s,
      required this.dm,
      required this.onHistoryCleared,
      this.embedded = false});

  void _toast(BuildContext c, String m) {
    ScaffoldMessenger.of(c)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
          content: Text(m), behavior: SnackBarBehavior.floating));
  }

  Future<void> _pick<T>(BuildContext context, String title, List<MapEntry<T, String>> items,
      T current, void Function(T) onPick) async {
    final r = await showDialog<T>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(title),
        children: [
          for (final e in items)
            RadioListTile<T>(
              value: e.key,
              groupValue: current,
              title: Text(e.value),
              onChanged: (v) => Navigator.pop(ctx, v),
            ),
        ],
      ),
    );
    if (r != null) onPick(r);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: s,
      builder: (context, _) {
        return Scaffold(
          appBar: embedded ? null : AppBar(title: Text(S.t('settings'))),
          body: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            children: [
              _Header(S.t('download')),
              _Group([
              ListTile(
                title: Text(S.t('downloadAddr')),
                subtitle: Text(dm.displayPath),
                onTap: () => _pick<bool>(
                  context,
                  S.t('downloadAddr'),
                  [
                    MapEntry(false, S.t('appFolder')),
                    MapEntry(true, S.t('publicFolder')),
                  ],
                  s.publicFolder,
                  (v) => s.setBool('publicFolder', v),
                ),
              ),
              _Switch(S.t('wifiOnly'), null, s.wifiOnly,
                  (v) => s.setBool('wifiOnly', v)),
              ]),
              _Header(S.t('browser')),
              _Group([
              _Switch(S.t('blockAds'), s.blockAds ? S.t('on') : S.t('off'),
                  s.blockAds, (v) => s.setBool('blockAds', v)),
              _Switch(S.t('recentSites'), null, s.recentSites,
                  (v) => s.setBool('recentSites', v)),
              _Switch(S.t('nativeDefault'), S.t('nativeDefaultInfo'),
                  s.nativeDefault, (v) => s.setBool('nativeDefault', v)),
              ListTile(
                title: Text(S.t('searchEngine')),
                subtitle: Text(s.engine),
                onTap: () => _pick<String>(
                  context,
                  S.t('engine'),
                  [for (final k in searchEngines.keys) MapEntry(k, k)],
                  s.engine,
                  (v) => s.setString('engine', v),
                ),
              ),
              ListTile(
                title: Text(S.t('clearCache')),
                onTap: () async {
                  await InAppWebViewController.clearAllCache();
                  if (context.mounted) _toast(context, S.t('cleared'));
                },
              ),
              ListTile(
                title: Text(S.t('clearHistory')),
                onTap: () async {
                  s.clearHistory();
                  onHistoryCleared();
                  if (context.mounted) _toast(context, S.t('cleared'));
                },
              ),
              ListTile(
                title: Text(S.t('clearCookies')),
                onTap: () async {
                  await CookieManager.instance().deleteAllCookies();
                  if (context.mounted) _toast(context, S.t('cleared'));
                },
              ),
              ]),
              _Header(S.t('general')),
              _Group([
              ListTile(
                title: Text(S.t('language')),
                trailing: DropdownButton<String>(
                  value: s.lang,
                  underline: const SizedBox.shrink(),
                  items: [
                    DropdownMenuItem(
                        value: 'system', child: Text(S.t('langDefault'))),
                    const DropdownMenuItem(value: 'tr', child: Text('Türkçe')),
                    const DropdownMenuItem(value: 'en', child: Text('English')),
                  ],
                  onChanged: (v) {
                    if (v != null) s.setString('lang', v);
                  },
                ),
              ),
              ListTile(
                title: Text(S.t('theme')),
                trailing: DropdownButton<String>(
                  value: s.theme,
                  underline: const SizedBox.shrink(),
                  items: [
                    DropdownMenuItem(
                        value: 'light', child: Text(S.t('themeLight'))),
                    DropdownMenuItem(
                        value: 'dark', child: Text(S.t('themeDark'))),
                  ],
                  onChanged: (v) {
                    if (v != null) s.setString('theme', v);
                  },
                ),
              ),
              _Switch(S.t('syncGallery'), s.syncGallery ? S.t('on') : S.t('off'),
                  s.syncGallery, (v) => s.setBool('syncGallery', v)),
              ]),
              _Header(S.t('help')),
              _Group([
              ListTile(
                title: Text(S.t('howTo')),
                onTap: () => _info(context, S.t('howTo'), S.t('howToText')),
              ),
              ListTile(
                title: Text(S.t('feedback')),
                onTap: () => _feedback(context),
              ),
              ListTile(
                title: Text(S.t('privacy')),
                onTap: () => _info(context, S.t('privacy'), S.t('privacyText')),
              ),
              const ListTile(title: Text(appVersion)),
              ]),
              const SizedBox(height: 24),
            ],
          ),
        );
      },
    );
  }

  void _info(BuildContext context, String title, String body) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: SingleChildScrollView(child: Text(body)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(S.t('ok')))
        ],
      ),
    );
  }

  void _feedback(BuildContext context) {
    final c = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(S.t('feedbackTitle')),
        content: TextField(
          controller: c,
          maxLines: 5,
          autofocus: true,
          decoration: InputDecoration(
              hintText: S.t('feedbackHint'), border: const OutlineInputBorder()),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: Text(S.t('cancel'))),
          FilledButton(
            onPressed: () {
              final text = c.text.trim();
              Navigator.pop(ctx);
              if (text.isNotEmpty) {
                Share.share('[$appVersion] $text',
                    subject: S.t('feedbackTitle'));
              }
            },
            child: Text(S.t('send')),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final String text;
  const _Header(this.text);
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 22, 8, 8),
        child: Text(text,
            style: TextStyle(
                color: Theme.of(context).colorScheme.primary,
                fontWeight: FontWeight.w800,
                fontSize: 16)),
      );
}

/// Bölümün ayarlarını tek, net kenarlı yuvarlak kartta toplar.
class _Group extends StatelessWidget {
  final List<Widget> children;
  const _Group(this.children);
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: cs.surface,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: BorderSide(color: cs.outlineVariant)),
      child: Column(
        children: ListTile.divideTiles(
                context: context, tiles: children, color: cs.outlineVariant)
            .toList(),
      ),
    );
  }
}

class _Switch extends StatelessWidget {
  final String title;
  final String? sub;
  final bool value;
  final ValueChanged<bool> onChanged;
  const _Switch(this.title, this.sub, this.value, this.onChanged);
  @override
  Widget build(BuildContext context) => SwitchListTile(
        title: Text(title),
        subtitle: sub == null ? null : Text(sub!),
        value: value,
        onChanged: onChanged,
      );
}
