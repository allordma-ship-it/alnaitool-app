import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../network/api_client.dart';

/// Market configuration served by the API at `GET /v1/config`.
class AppConfig {
  const AppConfig({
    required this.country,
    required this.currency,
    required this.timezone,
    required this.paymentProvider,
    required this.payoutsRequired,
    required this.priceStepMinor,
  });

  /// Syria defaults, used until the server configuration has loaded.
  static const syria = AppConfig(
    country: 'SY',
    currency: 'SYP',
    timezone: 'Asia/Damascus',
    paymentProvider: 'cash',
    payoutsRequired: false,
    priceStepMinor: 5000,
  );

  final String country;
  final String currency;
  final String timezone;
  final String paymentProvider;
  final bool payoutsRequired;
  final int priceStepMinor;

  /// Passengers pay the driver in cash during the trip.
  bool get isCash => paymentProvider == 'cash';

  factory AppConfig.fromJson(Map<String, dynamic> json) => AppConfig(
        country: json['country'] as String,
        currency: json['currency'] as String,
        timezone: json['timezone'] as String,
        paymentProvider: json['payment_provider'] as String,
        payoutsRequired: json['payouts_required'] as bool,
        priceStepMinor: json['price_step_minor'] as int,
      );
}

/// Loads the configuration once per app session.
final appConfigLoaderProvider = FutureProvider<AppConfig>((ref) {
  return ref.watch(apiClientProvider).get('/config', parse: (d) => AppConfig.fromJson(asMap(d)));
});

/// The effective configuration (Syria defaults while loading or offline).
final appConfigProvider = Provider<AppConfig>(
  (ref) => ref.watch(appConfigLoaderProvider).valueOrNull ?? AppConfig.syria,
);
