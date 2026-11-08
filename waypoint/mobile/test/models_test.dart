import 'package:flutter_test/flutter_test.dart';
import 'package:latlong2/latlong.dart';
import 'package:waypoint/core/models/models.dart';
import 'package:waypoint/core/utils/formatters.dart';
import 'package:waypoint/core/widgets/route_map.dart';
import 'package:waypoint/features/bookings/domain/booking_entities.dart';
import 'package:waypoint/features/search/domain/search_entities.dart';

Map<String, dynamic> _rideJson() => {
      'id': 'r1',
      'driver_id': 'd1',
      'origin': {
        'name': 'Stuttgart',
        'location': {'lat': 48.7758, 'lng': 9.1829},
      },
      'destination': {
        'name': 'München',
        'location': {'lat': 48.1372, 'lng': 11.5755},
      },
      'distance_m': 231000,
      'duration_s': 8400,
      'departure_at': '2026-12-01T08:00:00Z',
      'arrival_at': '2026-12-01T10:20:00Z',
      'seats_total': 3,
      'price_per_seat_cents': 2000,
      'currency': 'EUR',
      'preferences': {'instant_booking': true, 'allow_smoking': false, 'allow_pets': true, 'max_two_back': true},
      'vehicle_description': 'Blue Golf',
      'notes': '',
      'status': 'scheduled',
      'route': {
        'type': 'LineString',
        'coordinates': [
          [9.1829, 48.7758],
          [9.9876, 48.4011],
          [11.5755, 48.1372],
        ],
      },
    };

Map<String, dynamic> _profile(String id) => {
      'id': id,
      'first_name': 'Sam',
      'last_initial': 'T.',
      'bio': '',
      'rating': 4.5,
      'rating_count': 2,
      'member_since': '2025-01-01T00:00:00Z',
    };

void main() {
  test('Ride parses GeoJSON route in lat/lng order', () {
    final ride = Ride.fromJson(_rideJson());
    expect(ride.route, hasLength(3));
    expect(ride.route.first, const LatLng(48.7758, 9.1829));
    expect(ride.preferences.allowPets, isTrue);
    expect(ride.status, RideStatus.scheduled);
  });

  test('RideMatch parses search result', () {
    final match = RideMatch.fromJson({
      'ride': _rideJson(),
      'driver': _profile('d1'),
      'segment': {
        'pickup': {'lat': 48.40, 'lng': 9.99},
        'dropoff': {'lat': 48.37, 'lng': 10.89},
        'pickup_fraction': 0.3,
        'dropoff_fraction': 0.6,
        'pickup_distance_m': 120,
        'dropoff_distance_m': 340,
        'pickup_eta': '2026-12-01T08:45:00Z',
        'dropoff_eta': '2026-12-01T09:20:00Z',
      },
      'seats_available': 2,
      'price_per_seat_cents': 600,
      'score': 88.5,
    });
    expect(match.pricePerSeatCents, 600);
    expect(match.segment.pickupFraction, 0.3);
    expect(match.driver.displayName, 'Sam T.');
  });

  test('Booking parses escrow payment and derives actions', () {
    final booking = Booking.fromJson({
      'id': 'b1',
      'ride_id': 'r1',
      'passenger_id': 'p1',
      'seats': 1,
      'pickup': {
        'name': 'Ulm',
        'location': {'lat': 48.40, 'lng': 9.99},
      },
      'dropoff': {
        'name': 'Augsburg',
        'location': {'lat': 48.37, 'lng': 10.89},
      },
      'pickup_fraction': 0.3,
      'dropoff_fraction': 0.6,
      'pickup_eta': '2020-01-01T08:45:00Z',
      'dropoff_eta': '2020-01-01T09:20:00Z',
      'price_cents': 600,
      'service_fee_cents': 110,
      'total_cents': 710,
      'currency': 'EUR',
      'status': 'confirmed',
      'message': '',
      'created_at': '2019-12-30T10:00:00Z',
      'ride': _rideJson(),
      'driver': _profile('d1'),
      'passenger': _profile('p1'),
      'payment': {
        'provider': 'simulated',
        'status': 'authorized',
        'amount_cents': 710,
        'captured_cents': 0,
        'refunded_cents': 0,
        'currency': 'EUR',
      },
      'reviewed': false,
    });
    expect(booking.status, BookingStatus.confirmed);
    expect(booking.payment!.status, PaymentStatus.authorized);
    expect(booking.payment!.needsAction, isFalse);
    expect(booking.canConfirmTrip, isTrue);
    expect(booking.canReview, isFalse);
  });

  test('SearchQuery equality ignores time of day', () {
    const from = Place(name: 'A', location: LatLng(48, 9));
    const to = Place(name: 'B', location: LatLng(48, 11));
    final a = SearchQuery(from: from, to: to, date: DateTime(2026, 12, 1, 8));
    final b = SearchQuery(from: from, to: to, date: DateTime(2026, 12, 1, 20));
    expect(a, equals(b));
    expect(a.hashCode, b.hashCode);
  });

  test('sliceRoute keeps vertices inside the segment', () {
    final route = [const LatLng(48, 9), const LatLng(48, 10), const LatLng(48, 11), const LatLng(48, 12)];
    final mid = sliceRoute(route, 0.3, 0.7);
    expect(mid, [const LatLng(48, 10), const LatLng(48, 11)]);
    expect(sliceRoute(route, 0, 1), route);
  });

  test('formatters', () {
    expect(formatDuration(8400), '2 h 20 min');
    expect(formatDuration(1800), '30 min');
    expect(formatDistance(850), '850 m');
    expect(formatDistance(231000), '231 km');
  });
}
