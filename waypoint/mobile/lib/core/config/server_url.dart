import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'env.dart';

/// Normalizes user input like "192.168.1.10:8080/" to "http://192.168.1.10:8080".
String normalizeServerUrl(String input) {
  var url = input.trim();
  while (url.endsWith('/')) {
    url = url.substring(0, url.length - 1);
  }
  if (url.endsWith('/v1')) url = url.substring(0, url.length - 3);
  if (url.isNotEmpty && !url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'http://$url';
  }
  return url;
}

/// The API server address. Defaults to the build-time `API_BASE_URL` and can be
/// changed in the app (useful for testing against a computer on the same Wi-Fi).
class ServerUrlController extends Notifier<String> {
  static const _key = 'wp_server_url';

  @override
  String build() {
    _restore();
    return normalizeServerUrl(Env.apiBaseUrl);
  }

  Future<void> _restore() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final saved = prefs.getString(_key);
      if (saved != null && saved.isNotEmpty) state = saved;
    } catch (_) {
      // Preferences unavailable (tests): keep the build-time default.
    }
  }

  Future<void> set(String url) async {
    final normalized = normalizeServerUrl(url);
    if (normalized.isEmpty) return;
    state = normalized;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, normalized);
  }
}

final serverUrlProvider = NotifierProvider<ServerUrlController, String>(ServerUrlController.new);

/// Checks that a Wanees server answers at [url] (GET /healthz).
Future<bool> checkServer(String url) async {
  final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 6), receiveTimeout: const Duration(seconds: 6)));
  try {
    final res = await dio.get<dynamic>('${normalizeServerUrl(url)}/healthz');
    return res.statusCode == 200;
  } on DioException {
    return false;
  } finally {
    dio.close();
  }
}
