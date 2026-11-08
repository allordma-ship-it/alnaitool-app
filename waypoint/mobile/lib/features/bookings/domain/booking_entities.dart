import 'package:flutter/material.dart';

import '../../../core/models/models.dart';
import '../../../core/theme/app_theme.dart';

enum BookingStatus {
  paymentPending('payment_pending', 'Awaiting payment'),
  requested('requested', 'Awaiting driver'),
  confirmed('confirmed', 'Confirmed'),
  rejected('rejected', 'Declined'),
  cancelledByPassenger('cancelled_by_passenger', 'Cancelled'),
  cancelledByDriver('cancelled_by_driver', 'Cancelled by driver'),
  expired('expired', 'Expired'),
  completed('completed', 'Completed');

  const BookingStatus(this.apiValue, this.label);
  final String apiValue;
  final String label;

  static BookingStatus parse(String s) =>
      BookingStatus.values.firstWhere((v) => v.apiValue == s, orElse: () => BookingStatus.expired);

  bool get isActive =>
      this == BookingStatus.paymentPending || this == BookingStatus.requested || this == BookingStatus.confirmed;

  Color get color => switch (this) {
        BookingStatus.confirmed || BookingStatus.completed => AppTheme.accent,
        BookingStatus.paymentPending || BookingStatus.requested => AppTheme.warning,
        _ => AppTheme.danger,
      };
}

enum PaymentStatus {
  pending('pending', 'Payment pending'),
  authorized('authorized', 'Held in escrow'),
  captured('captured', 'Paid'),
  voided('voided', 'Released'),
  refunded('refunded', 'Refunded'),
  failed('failed', 'Payment failed');

  const PaymentStatus(this.apiValue, this.label);
  final String apiValue;
  final String label;

  static PaymentStatus parse(String s) =>
      PaymentStatus.values.firstWhere((v) => v.apiValue == s, orElse: () => PaymentStatus.failed);
}

/// Escrow payment as seen by the passenger or driver.
class PaymentInfo {
  const PaymentInfo({
    required this.provider,
    required this.status,
    required this.amountCents,
    required this.capturedCents,
    required this.refundedCents,
    required this.currency,
    this.clientSecret,
    this.publishableKey,
  });

  final String provider;
  final PaymentStatus status;
  final int amountCents;
  final int capturedCents;
  final int refundedCents;
  final String currency;
  final String? clientSecret;
  final String? publishableKey;

  bool get needsAction =>
      (status == PaymentStatus.pending || status == PaymentStatus.failed) &&
      provider == 'stripe' &&
      (clientSecret?.isNotEmpty ?? false);

  factory PaymentInfo.fromJson(Map<String, dynamic> json) => PaymentInfo(
        provider: json['provider'] as String,
        status: PaymentStatus.parse(json['status'] as String),
        amountCents: json['amount_cents'] as int,
        capturedCents: json['captured_cents'] as int,
        refundedCents: json['refunded_cents'] as int,
        currency: json['currency'] as String,
        clientSecret: json['client_secret'] as String?,
        publishableKey: json['publishable_key'] as String?,
      );
}

/// A reservation on a ride segment.
class Booking {
  const Booking({
    required this.id,
    required this.rideId,
    required this.passengerId,
    required this.seats,
    required this.pickup,
    required this.dropoff,
    required this.pickupFraction,
    required this.dropoffFraction,
    required this.pickupEta,
    required this.dropoffEta,
    required this.priceCents,
    required this.serviceFeeCents,
    required this.totalCents,
    required this.currency,
    required this.status,
    required this.message,
    required this.createdAt,
    required this.ride,
    required this.driver,
    required this.passenger,
    required this.reviewed,
    this.payment,
  });

  final String id;
  final String rideId;
  final String passengerId;
  final int seats;
  final Place pickup;
  final Place dropoff;
  final double pickupFraction;
  final double dropoffFraction;
  final DateTime pickupEta;
  final DateTime dropoffEta;
  final int priceCents;
  final int serviceFeeCents;
  final int totalCents;
  final String currency;
  final BookingStatus status;
  final String message;
  final DateTime createdAt;
  final Ride ride;
  final PublicProfile driver;
  final PublicProfile passenger;
  final PaymentInfo? payment;
  final bool reviewed;

  bool get canConfirmTrip => status == BookingStatus.confirmed && DateTime.now().isAfter(pickupEta);
  bool get canReview => status == BookingStatus.completed && !reviewed;
  bool get lateCancellation =>
      status == BookingStatus.confirmed && pickupEta.difference(DateTime.now()) < const Duration(hours: 24);

  factory Booking.fromJson(Map<String, dynamic> json) => Booking(
        id: json['id'] as String,
        rideId: json['ride_id'] as String,
        passengerId: json['passenger_id'] as String,
        seats: json['seats'] as int,
        pickup: Place.fromJson(json['pickup'] as Map<String, dynamic>),
        dropoff: Place.fromJson(json['dropoff'] as Map<String, dynamic>),
        pickupFraction: (json['pickup_fraction'] as num).toDouble(),
        dropoffFraction: (json['dropoff_fraction'] as num).toDouble(),
        pickupEta: DateTime.parse(json['pickup_eta'] as String).toLocal(),
        dropoffEta: DateTime.parse(json['dropoff_eta'] as String).toLocal(),
        priceCents: json['price_cents'] as int,
        serviceFeeCents: json['service_fee_cents'] as int,
        totalCents: json['total_cents'] as int,
        currency: json['currency'] as String,
        status: BookingStatus.parse(json['status'] as String),
        message: (json['message'] as String?) ?? '',
        createdAt: DateTime.parse(json['created_at'] as String).toLocal(),
        ride: Ride.fromJson(json['ride'] as Map<String, dynamic>),
        driver: PublicProfile.fromJson(json['driver'] as Map<String, dynamic>),
        passenger: PublicProfile.fromJson(json['passenger'] as Map<String, dynamic>),
        payment: json['payment'] == null ? null : PaymentInfo.fromJson(json['payment'] as Map<String, dynamic>),
        reviewed: (json['reviewed'] as bool?) ?? false,
      );
}
