import '../../../core/models/models.dart';
import 'booking_entities.dart';

/// Reservations and escrow actions.
abstract interface class BookingRepository {
  Future<Booking> create({
    required String rideId,
    required int seats,
    required Place pickup,
    required Place dropoff,
    String message = '',
  });

  Future<List<Booking>> myBookings();

  Future<List<Booking>> forRide(String rideId);

  Future<Booking> get(String bookingId);

  Future<Booking> syncPayment(String bookingId);

  Future<Booking> cancel(String bookingId);

  Future<Booking> confirmTrip(String bookingId);

  Future<Booking> accept(String bookingId);

  Future<Booking> reject(String bookingId);

  Future<Booking> removePassenger(String bookingId);

  Future<void> review(String bookingId, {required int rating, String comment = ''});
}
