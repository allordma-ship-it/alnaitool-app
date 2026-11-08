import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_stripe/flutter_stripe.dart';

import '../config/env.dart';

/// Outcome of presenting the payment authorization UI.
enum PaymentOutcome { authorized, cancelled, notRequired }

/// Collects card details for the escrow hold with Stripe's PaymentSheet.
/// The backend creates a manual-capture PaymentIntent; confirming it here
/// only *authorizes* the amount — funds are captured after the trip.
class PaymentService {
  const PaymentService();

  Future<PaymentOutcome> authorizeHold({
    required String provider,
    required String? clientSecret,
    required String? publishableKey,
    ThemeMode themeMode = ThemeMode.system,
  }) async {
    if (provider != 'stripe' || clientSecret == null || clientSecret.isEmpty) {
      return PaymentOutcome.notRequired;
    }
    if (publishableKey == null || publishableKey.isEmpty) {
      throw StateError('Stripe publishable key missing from server response');
    }
    if (Stripe.publishableKey != publishableKey) {
      Stripe.publishableKey = publishableKey;
      Stripe.urlScheme = Env.urlScheme;
      await Stripe.instance.applySettings();
    }
    await Stripe.instance.initPaymentSheet(
      paymentSheetParameters: SetupPaymentSheetParameters(
        paymentIntentClientSecret: clientSecret,
        merchantDisplayName: Env.merchantName,
        style: themeMode,
        returnURL: '${Env.urlScheme}://stripe-redirect',
      ),
    );
    try {
      await Stripe.instance.presentPaymentSheet();
      return PaymentOutcome.authorized;
    } on StripeException catch (e) {
      if (e.error.code == FailureCode.Canceled) return PaymentOutcome.cancelled;
      rethrow;
    }
  }
}

final paymentServiceProvider = Provider<PaymentService>((ref) => const PaymentService());
