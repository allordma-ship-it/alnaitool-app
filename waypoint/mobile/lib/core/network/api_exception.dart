import 'package:dio/dio.dart';

/// A typed API failure carrying the server's error code and message.
class ApiException implements Exception {
  const ApiException({
    required this.code,
    required this.message,
    this.statusCode,
    this.field,
  });

  final String code;
  final String message;
  final int? statusCode;
  final String? field;

  bool get isUnauthorized => statusCode == 401;
  bool get isPaymentRequired => statusCode == 402;
  bool get isConflict => statusCode == 409;
  bool get isValidation => statusCode == 422;

  factory ApiException.fromDio(DioException e) {
    final response = e.response;
    final data = response?.data;
    if (data is Map && data['error'] is Map) {
      final err = (data['error'] as Map).cast<String, dynamic>();
      final field = err['field'] as String?;
      final msg = (err['message'] as String?) ?? 'Request failed';
      return ApiException(
        code: (err['code'] as String?) ?? 'unknown',
        message: field != null && field.isNotEmpty && err['code'] == 'validation_failed'
            ? '${_humanize(field)} $msg'
            : _capitalize(msg),
        statusCode: response?.statusCode,
        field: field,
      );
    }
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return const ApiException(
          code: 'timeout',
          message: 'The server took too long to respond. Please try again.',
        );
      case DioExceptionType.connectionError:
        return const ApiException(
          code: 'offline',
          message: 'No connection to the server. Check your internet connection.',
        );
      default:
        return ApiException(
          code: 'unknown',
          message: 'Something went wrong (${response?.statusCode ?? 'network'}).',
          statusCode: response?.statusCode,
        );
    }
  }

  static String _humanize(String field) {
    final last = field.split('.').last.replaceAll('_', ' ');
    return _capitalize(last);
  }

  static String _capitalize(String s) =>
      s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);

  @override
  String toString() => message;
}

/// Extracts a user-facing message from any error.
String errorMessage(Object error) {
  if (error is ApiException) return error.message;
  if (error is DioException) return ApiException.fromDio(error).message;
  return 'Unexpected error: $error';
}
