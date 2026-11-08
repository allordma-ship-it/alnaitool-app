import 'package:dio/dio.dart';

import '../network/api_exception.dart';
import 'l10n.dart';

/// Translates any error into a user-facing message in the current language.
///
/// The API returns stable error codes and field names; these are mapped to
/// localized texts so the UI never shows raw English server messages.
String localizedError(AppLocalizations l, Object error) {
  final e = switch (error) {
    ApiException() => error,
    DioException() => ApiException.fromDio(error),
    _ => null,
  };
  if (e == null) return l.errGeneric;

  final msg = e.message.toLowerCase();
  switch (e.code) {
    case 'offline':
      return l.errOffline;
    case 'timeout':
      return l.errTimeout;
    case 'unauthorized':
      return l.errUnauthorized;
    case 'forbidden':
      return l.errForbidden;
    case 'not_found':
      return l.errNotFound;
    case 'no_seats':
      return l.errNoSeats;
    case 'rate_limited':
      return l.errRateLimited;
    case 'payment_required':
      return l.errPaymentRequired;
    case 'upstream_unavailable':
      return l.errUpstream;
    case 'invalid_state':
      return l.errInvalidState;
    case 'conflict':
      if (msg.contains('active booking')) return l.errAlreadyBooked;
      if (msg.contains('reviewed')) return l.errAlreadyReviewed;
      return l.errConflict;
    case 'validation_failed':
      return _validation(l, e.field ?? '', msg);
  }
  return l.errGeneric;
}

String _validation(AppLocalizations l, String field, String msg) {
  final root = field.split('.').first;
  if (root == 'email' && msg.contains('already')) return l.errEmailTaken;
  if (root == 'route') return l.errNoRoute;
  if (root == 'ride_id' && msg.contains('own ride')) return l.errOwnRide;
  if (root == 'ride_id' && msg.contains('passed')) return l.errRidePassed;
  if ((root == 'pickup' || root == 'dropoff') && msg.contains('too far')) return l.errTooFar;
  if (root == 'price_per_seat_cents') return l.errPrice;
  if (root == 'departure_at') return l.errDeparture;
  return l.errValidation(_fieldLabel(l, root));
}

String _fieldLabel(AppLocalizations l, String field) => switch (field) {
      'email' => l.email,
      'password' => l.password,
      'first_name' => l.firstName,
      'last_name' => l.lastName,
      'seats' || 'seats_total' => l.seats,
      'pickup' => l.pickup,
      'dropoff' => l.dropoff,
      'origin' => l.departure,
      'destination' => l.destination,
      'message' => l.messageToDriver,
      'comment' => l.commentOptional,
      'bio' => l.aboutYou,
      _ => field,
    };
