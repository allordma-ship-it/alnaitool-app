import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config/env.dart';
import 'api_exception.dart';
import 'token_storage.dart';

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

/// Emits whenever the session can no longer be refreshed (forces logout).
final sessionExpiredProvider = Provider<StreamController<void>>((ref) {
  final controller = StreamController<void>.broadcast();
  ref.onDispose(controller.close);
  return controller;
});

final apiClientProvider = Provider<ApiClient>((ref) {
  return ApiClient(
    baseUrl: '${Env.apiBaseUrl}/v1',
    tokens: ref.watch(tokenStorageProvider),
    onSessionExpired: () => ref.read(sessionExpiredProvider).add(null),
  );
});

/// HTTP client for the WayPoint API with bearer auth and transparent,
/// single-flight refresh-token rotation on 401 responses.
class ApiClient {
  ApiClient({
    required String baseUrl,
    required TokenStorage tokens,
    required void Function() onSessionExpired,
  })  : _tokens = tokens,
        _onSessionExpired = onSessionExpired,
        _dio = Dio(_options(baseUrl)),
        _refreshDio = Dio(_options(baseUrl)) {
    _dio.interceptors.add(
      InterceptorsWrapper(onRequest: _attachToken, onError: _handleError),
    );
  }

  static BaseOptions _options(String baseUrl) => BaseOptions(
        baseUrl: baseUrl,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 30),
        sendTimeout: const Duration(seconds: 15),
        contentType: Headers.jsonContentType,
        responseType: ResponseType.json,
      );

  final Dio _dio;
  final Dio _refreshDio;
  final TokenStorage _tokens;
  final void Function() _onSessionExpired;
  Future<AuthTokens?>? _refreshing;

  static const _retriedFlag = 'wp_retried';
  static const _skipAuthFlag = 'wp_skip_auth';

  Future<void> _attachToken(RequestOptions options, RequestInterceptorHandler handler) async {
    if (options.extra[_skipAuthFlag] != true) {
      final tokens = await _tokens.read();
      if (tokens != null) {
        options.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
      }
    }
    handler.next(options);
  }

  Future<void> _handleError(DioException err, ErrorInterceptorHandler handler) async {
    final options = err.requestOptions;
    final isAuthCall = options.path.startsWith('/auth/');
    if (err.response?.statusCode != 401 || isAuthCall || options.extra[_retriedFlag] == true) {
      return handler.next(err);
    }
    final current = await _tokens.read();
    if (current == null) return handler.next(err);

    final refreshed = await _refreshOnce(current.refreshToken);
    if (refreshed == null) {
      _onSessionExpired();
      return handler.next(err);
    }
    try {
      options.extra[_retriedFlag] = true;
      options.headers['Authorization'] = 'Bearer ${refreshed.accessToken}';
      final response = await _dio.fetch<dynamic>(options);
      return handler.resolve(response);
    } on DioException catch (e) {
      return handler.next(e);
    }
  }

  /// Coalesces concurrent refreshes so a rotated token is only used once.
  Future<AuthTokens?> _refreshOnce(String refreshToken) {
    return _refreshing ??= () async {
      try {
        final res = await _refreshDio.post<Map<String, dynamic>>(
          '/auth/refresh',
          data: {'refresh_token': refreshToken},
        );
        final tokens = AuthTokens.fromJson(res.data!['tokens'] as Map<String, dynamic>);
        await _tokens.write(tokens);
        return tokens;
      } on DioException {
        await _tokens.clear();
        return null;
      } finally {
        _refreshing = null;
      }
    }();
  }

  Future<T> _wrap<T>(Future<Response<dynamic>> Function() call, T Function(dynamic data) parse) async {
    try {
      final res = await call();
      return parse(res.data);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  Future<T> get<T>(String path, {Map<String, dynamic>? query, required T Function(dynamic) parse}) =>
      _wrap(() => _dio.get<dynamic>(path, queryParameters: query), parse);

  Future<T> post<T>(String path, {Object? body, bool skipAuth = false, required T Function(dynamic) parse}) =>
      _wrap(
        () => _dio.post<dynamic>(path, data: body, options: Options(extra: {_skipAuthFlag: skipAuth})),
        parse,
      );

  Future<T> patch<T>(String path, {Object? body, required T Function(dynamic) parse}) =>
      _wrap(() => _dio.patch<dynamic>(path, data: body), parse);
}

/// Parses `{"data": [...]}` list envelopes.
List<T> parseList<T>(dynamic data, T Function(Map<String, dynamic>) item) {
  final list = (data as Map<String, dynamic>)['data'] as List<dynamic>;
  return list.map((e) => item(e as Map<String, dynamic>)).toList(growable: false);
}

/// Casts a JSON object.
Map<String, dynamic> asMap(dynamic data) => data as Map<String, dynamic>;
