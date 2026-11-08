import '../../../core/models/models.dart';

/// Authentication and account operations.
abstract interface class AuthRepository {
  /// Returns the signed-in member, or null if there is no stored session.
  Future<Me?> restoreSession();

  Future<Me> login({required String email, required String password});

  Future<Me> register({
    required String email,
    required String password,
    required String firstName,
    required String lastName,
    String? phone,
  });

  Future<void> logout();

  Future<Me> me();

  Future<Me> updateProfile({String? firstName, String? lastName, String? bio, String? phone});

  /// Starts (or resumes) payout onboarding; returns the hosted onboarding URL.
  Future<({String url, bool payoutsEnabled})> startPayoutOnboarding({String country = 'DE'});

  Future<Me> refreshPayoutStatus();
}
