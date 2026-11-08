import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../domain/booking_entities.dart';
import '../domain/booking_repository.dart';

/// REST implementation of [BookingRepository].
class BookingRepositoryImpl implements BookingRepository {
  const BookingRepositoryImpl(this._api);

  final ApiClient _api;

  Booking _one(dynamic d) => Booking.fromJson(asMap(d));

  Future<Booking> _action(String bookingId, String action) =>
      _api.post('/bookings/$bookingId/$action', parse: _one);

  @override
  Future<Booking> create({
    required String rideId,
    required int seats,
    required Place pickup,
    required Place dropoff,
    String message = '',
  }) {
    return _api.post(
      '/rides/$rideId/bookings',
      body: {
        'seats': seats,
        'pickup': pickup.toJson(),
        'dropoff': dropoff.toJson(),
        'message': message,
      },
      parse: _one,
    );
  }

  @override
  Future<List<Booking>> myBookings() => _api.get('/me/bookings', parse: (d) => parseList(d, Booking.fromJson));

  @override
  Future<List<Booking>> forRide(String rideId) =>
      _api.get('/rides/$rideId/bookings', parse: (d) => parseList(d, Booking.fromJson));

  @override
  Future<Booking> get(String bookingId) => _api.get('/bookings/$bookingId', parse: _one);

  @override
  Future<Booking> syncPayment(String bookingId) => _action(bookingId, 'payment/sync');

  @override
  Future<Booking> cancel(String bookingId) => _action(bookingId, 'cancel');

  @override
  Future<Booking> confirmTrip(String bookingId) => _action(bookingId, 'confirm-trip');

  @override
  Future<Booking> accept(String bookingId) => _action(bookingId, 'accept');

  @override
  Future<Booking> reject(String bookingId) => _action(bookingId, 'reject');

  @override
  Future<Booking> removePassenger(String bookingId) => _action(bookingId, 'driver-cancel');

  @override
  Future<void> review(String bookingId, {required int rating, String comment = ''}) => _api.post(
        '/bookings/$bookingId/review',
        body: {'rating': rating, 'comment': comment},
        parse: (_) {},
      );
}

final bookingRepositoryProvider = Provider<BookingRepository>(
  (ref) => BookingRepositoryImpl(ref.watch(apiClientProvider)),
);
