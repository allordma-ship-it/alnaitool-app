import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/token_storage.dart';
import '../domain/auth_repository.dart';

/// REST implementation of [AuthRepository].
class AuthRepositoryImpl implements AuthRepository {
  const AuthRepositoryImpl(this._api, this._tokens);

  final ApiClient _api;
  final TokenStorage _tokens;

  Future<Me> _consumeAuth(dynamic data) async {
    final map = asMap(data);
    await _tokens.write(AuthTokens.fromJson(map['tokens'] as Map<String, dynamic>));
    return Me.fromJson(map['user'] as Map<String, dynamic>);
  }

  @override
  Future<Me?> restoreSession() async {
    if (await _tokens.read() == null) return null;
    try {
      return await me();
    } on ApiException catch (e) {
      if (e.isUnauthorized) {
        await _tokens.clear();
        return null;
      }
      rethrow;
    }
  }

  @override
  Future<Me> login({required String email, required String password}) async {
    final data = await _api.post(
      '/auth/login',
      body: {'email': email.trim(), 'password': password},
      skipAuth: true,
      parse: (d) => d,
    );
    return _consumeAuth(data);
  }

  @override
  Future<Me> register({
    required String email,
    required String password,
    required String firstName,
    required String lastName,
    String? phone,
  }) async {
    final data = await _api.post(
      '/auth/register',
      body: {
        'email': email.trim(),
        'password': password,
        'first_name': firstName.trim(),
        'last_name': lastName.trim(),
        if (phone != null && phone.trim().isNotEmpty) 'phone': phone.trim(),
      },
      skipAuth: true,
      parse: (d) => d,
    );
    return _consumeAuth(data);
  }

  @override
  Future<void> logout() async {
    final tokens = await _tokens.read();
    try {
      if (tokens != null) {
        await _api.post('/auth/logout', body: {'refresh_token': tokens.refreshToken}, parse: (_) => null);
      }
    } on ApiException {
      // Server-side revocation is best effort; local tokens are always cleared.
    } finally {
      await _tokens.clear();
    }
  }

  @override
  Future<Me> me() => _api.get('/me', parse: (d) => Me.fromJson(asMap(d)));

  @override
  Future<Me> updateProfile({String? firstName, String? lastName, String? bio, String? phone}) {
    return _api.patch(
      '/me',
      body: {
        if (firstName != null) 'first_name': firstName,
        if (lastName != null) 'last_name': lastName,
        if (bio != null) 'bio': bio,
        if (phone != null) 'phone': phone,
      },
      parse: (d) => Me.fromJson(asMap(d)),
    );
  }

  @override
  Future<({String url, bool payoutsEnabled})> startPayoutOnboarding({String country = 'DE'}) {
    return _api.post(
      '/me/payouts/onboarding',
      body: {'country': country},
      parse: (d) {
        final m = asMap(d);
        return (url: m['onboarding_url'] as String, payoutsEnabled: m['payouts_enabled'] as bool);
      },
    );
  }

  @override
  Future<Me> refreshPayoutStatus() =>
      _api.post('/me/payouts/refresh', parse: (d) => Me.fromJson(asMap(d)));
}

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepositoryImpl(ref.watch(apiClientProvider), ref.watch(tokenStorageProvider)),
);
