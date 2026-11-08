import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../../../core/payments/payment_service.dart';
import '../data/booking_repository_impl.dart';
import '../domain/booking_entities.dart';

final myBookingsProvider = FutureProvider.autoDispose<List<Booking>>(
  (ref) => ref.watch(bookingRepositoryProvider).myBookings(),
);

final bookingProvider = FutureProvider.autoDispose.family<Booking, String>(
  (ref, id) => ref.watch(bookingRepositoryProvider).get(id),
);

final rideBookingsProvider = FutureProvider.autoDispose.family<List<Booking>, String>(
  (ref, rideId) => ref.watch(bookingRepositoryProvider).forRide(rideId),
);

/// Orchestrates booking creation and escrow authorization.
class BookingFlow {
  BookingFlow(this._ref);

  final Ref _ref;

  /// Creates the booking and, when the server requires it, presents the
  /// payment sheet to authorize the escrow hold, then reconciles state.
  Future<Booking> book({
    required String rideId,
    required int seats,
    required Place pickup,
    required Place dropoff,
    String message = '',
    ThemeMode themeMode = ThemeMode.system,
  }) async {
    final repo = _ref.read(bookingRepositoryProvider);
    final booking = await repo.create(
      rideId: rideId,
      seats: seats,
      pickup: pickup,
      dropoff: dropoff,
      message: message,
    );
    final result = await completePayment(booking, themeMode: themeMode);
    _ref.invalidate(myBookingsProvider);
    return result;
  }

  /// Presents the payment sheet for a booking awaiting authorization.
  Future<Booking> completePayment(Booking booking, {ThemeMode themeMode = ThemeMode.system}) async {
    final payment = booking.payment;
    if (payment == null || !payment.needsAction) return booking;
    final outcome = await _ref.read(paymentServiceProvider).authorizeHold(
          provider: payment.provider,
          clientSecret: payment.clientSecret,
          publishableKey: payment.publishableKey,
          themeMode: themeMode,
        );
    if (outcome == PaymentOutcome.cancelled) return booking;
    final synced = await _ref.read(bookingRepositoryProvider).syncPayment(booking.id);
    _ref.invalidate(bookingProvider(booking.id));
    return synced;
  }
}

final bookingFlowProvider = Provider<BookingFlow>(BookingFlow.new);
