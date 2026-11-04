import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:shared_preferences/shared_preferences.dart';

final darkMode = ValueNotifier<bool>(true);
const home = 'https://google.com';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final p = await SharedPreferences.getInstance();
  darkMode.value = p.getBool('dark') ?? true;
  runApp(ValueListenableBuilder<bool>(
    valueListenable: darkMode,
    builder: (_, d, __) => MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: d ? Brightness.dark : Brightness.light,
      ),
      home: const Browser(),
    ),
  ));
}

class TabData {
  InAppWebViewController? c;
  String url;
  String title = '';
  double progress = 0;
  TabData(this.url);
}

class Browser extends StatefulWidget {
  const Browser({super.key});
  @override
  State<Browser> createState() => _BrowserState();
}

class _BrowserState extends State<Browser> {
  final List<TabData> tabs = [TabData(home)];
  int cur = 0;
  final tc = TextEditingController(text: home);
  List<String> bookmarks = [];
  SharedPreferences? prefs;

  TabData get t => tabs[cur];

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) {
      prefs = p;
      setState(() => bookmarks = p.getStringList('bm') ?? []);
    });
  }

  void go(String text) {
    text = text.trim();
    if (text.isEmpty) return;
    final isUrl = text.contains('.') && !text.contains(' ');
    final url = isUrl
        ? (text.startsWith('http') ? text : 'https://$text')
        : 'https://www.google.com/search?q=${Uri.encodeComponent(text)}';
    t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(url)));
  }

  void selectTab(int i) {
    setState(() => cur = i);
    tc.text = t.url;
  }

  void newTab() {
    setState(() {
      tabs.add(TabData(home));
      cur = tabs.length - 1;
    });
    tc.text = home;
  }

  void closeTab(int i) {
    setState(() {
      if (tabs.length == 1) {
        tabs[0] = TabData(home);
        cur = 0;
      } else {
        tabs.removeAt(i);
        if (cur >= tabs.length) {
          cur = tabs.length - 1;
        } else if (i < cur) {
          cur--;
        }
      }
    });
    tc.text = t.url;
  }

  void toggleBookmark() {
    setState(() {
      if (bookmarks.contains(t.url)) {
        bookmarks.remove(t.url);
      } else {
        bookmarks.add(t.url);
      }
    });
    prefs?.setStringList('bm', bookmarks);
  }

  void showTabs() {
    showModalBottomSheet(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (ctx, setS) => ListView(
          children: [
            for (int i = 0; i < tabs.length; i++)
              ListTile(
                selected: i == cur,
                title: Text(tabs[i].title.isEmpty ? tabs[i].url : tabs[i].title,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(tabs[i].url,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () {
                  selectTab(i);
                  Navigator.pop(ctx);
                },
                trailing: IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () {
                    closeTab(i);
                    setS(() {});
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }

  void showBookmarks() {
    showModalBottomSheet(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (ctx, setS) => bookmarks.isEmpty
            ? const Center(child: Text('Yer imi yok'))
            : ListView(
                children: [
                  for (final b in List<String>.from(bookmarks))
                    ListTile(
                      title: Text(b, maxLines: 1, overflow: TextOverflow.ellipsis),
                      onTap: () {
                        Navigator.pop(ctx);
                        t.c?.loadUrl(urlRequest: URLRequest(url: WebUri(b)));
                      },
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () {
                          setState(() => bookmarks.remove(b));
                          prefs?.setStringList('bm', bookmarks);
                          setS(() {});
                        },
                      ),
                    ),
                ],
              ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isBm = bookmarks.contains(t.url);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (t.c != null && await t.c!.canGoBack()) {
          t.c!.goBack();
        } else if (tabs.length > 1) {
          closeTab(cur);
        } else {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 12,
          title: TextField(
            controller: tc,
            onSubmitted: go,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
                border: InputBorder.none, hintText: 'Ara veya adres yaz'),
          ),
          actions: [
            IconButton(
                icon: const Icon(Icons.refresh), onPressed: () => t.c?.reload()),
          ],
          bottom: t.progress < 1
              ? PreferredSize(
                  preferredSize: const Size.fromHeight(3),
                  child: LinearProgressIndicator(value: t.progress))
              : null,
        ),
        body: IndexedStack(
          index: cur,
          children: [
            for (final x in tabs)
              InAppWebView(
                key: ObjectKey(x),
                initialUrlRequest: URLRequest(url: WebUri(x.url)),
                onWebViewCreated: (c) => x.c = c,
                onProgressChanged: (_, p) =>
                    setState(() => x.progress = p / 100),
                onTitleChanged: (_, title) => x.title = title ?? '',
                onLoadStop: (_, u) {
                  x.url = u?.toString() ?? x.url;
                  if (x == t) tc.text = x.url;
                  setState(() {});
                },
              ),
          ],
        ),
        bottomNavigationBar: BottomAppBar(
          height: 56,
          padding: EdgeInsets.zero,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              IconButton(
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => t.c?.goBack()),
              IconButton(
                  icon: const Icon(Icons.arrow_forward),
                  onPressed: () => t.c?.goForward()),
              IconButton(icon: const Icon(Icons.add), onPressed: newTab),
              IconButton(
                icon: Badge(
                    label: Text('${tabs.length}'),
                    child: const Icon(Icons.tab)),
                onPressed: showTabs,
              ),
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert),
                onSelected: (v) async {
                  if (v == 'bm') toggleBookmark();
                  if (v == 'list') showBookmarks();
                  if (v == 'dark') {
                    darkMode.value = !darkMode.value;
                    prefs?.setBool('dark', darkMode.value);
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(
                      value: 'bm',
                      child: Text(isBm ? 'Yer imini kaldır' : 'Yer imi ekle')),
                  const PopupMenuItem(value: 'list', child: Text('Yer imleri')),
                  const PopupMenuItem(
                      value: 'dark', child: Text('Karanlık / açık mod')),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
