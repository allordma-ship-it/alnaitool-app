import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../data/auth_repository_impl.dart';

/// Holds the signed-in member (null when signed out).
class AuthController extends AsyncNotifier<Me?> {
  StreamSubscription<void>? _expiredSub;

  @override
  Future<Me?> build() async {
    _expiredSub ??= ref.read(sessionExpiredProvider).stream.listen((_) {
      state = const AsyncData(null);
    });
    ref.onDispose(() => _expiredSub?.cancel());
    return ref.read(authRepositoryProvider).restoreSession();
  }

  Future<void> login(String email, String password) async {
    final me = await ref.read(authRepositoryProvider).login(email: email, password: password);
    state = AsyncData(me);
  }

  Future<void> register({
    required String email,
    required String password,
    required String firstName,
    required String lastName,
    String? phone,
  }) async {
    final me = await ref.read(authRepositoryProvider).register(
          email: email,
          password: password,
          firstName: firstName,
          lastName: lastName,
          phone: phone,
        );
    state = AsyncData(me);
  }

  Future<void> logout() async {
    await ref.read(authRepositoryProvider).logout();
    state = const AsyncData(null);
  }

  /// Replaces the cached profile after an update elsewhere.
  void setMe(Me me) => state = AsyncData(me);

  Future<void> reload() async {
    final me = await ref.read(authRepositoryProvider).me();
    state = AsyncData(me);
  }
}

final authControllerProvider = AsyncNotifierProvider<AuthController, Me?>(AuthController.new);
