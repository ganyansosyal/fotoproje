import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

void main() => runApp(const MaterialApp(
    debugShowCheckedModeBanner: false, home: Browser()));

class Browser extends StatefulWidget {
  const Browser({super.key});
  @override
  State<Browser> createState() => _BrowserState();
}

class _BrowserState extends State<Browser> {
  InAppWebViewController? c;
  final tc = TextEditingController(text: 'https://google.com');
  double progress = 0;

  void go(String t) {
    t = t.trim();
    final isUrl = t.contains('.') && !t.contains(' ');
    final url = isUrl
        ? (t.startsWith('http') ? t : 'https://$t')
        : 'https://www.google.com/search?q=${Uri.encodeComponent(t)}';
    c?.loadUrl(urlRequest: URLRequest(url: WebUri(url)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: tc,
          onSubmitted: go,
          decoration: const InputDecoration(border: InputBorder.none),
        ),
        actions: [
          IconButton(
              icon: const Icon(Icons.arrow_back), onPressed: () => c?.goBack()),
          IconButton(
              icon: const Icon(Icons.arrow_forward),
              onPressed: () => c?.goForward()),
          IconButton(
              icon: const Icon(Icons.refresh), onPressed: () => c?.reload()),
        ],
        bottom: progress < 1
            ? PreferredSize(
                preferredSize: const Size.fromHeight(3),
                child: LinearProgressIndicator(value: progress))
            : null,
      ),
      body: InAppWebView(
        initialUrlRequest: URLRequest(url: WebUri('https://google.com')),
        onWebViewCreated: (x) => c = x,
        onProgressChanged: (_, p) => setState(() => progress = p / 100),
        onLoadStop: (_, u) => tc.text = u?.toString() ?? '',
      ),
    );
  }
}
