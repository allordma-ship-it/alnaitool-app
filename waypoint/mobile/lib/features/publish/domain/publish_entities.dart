import 'package:latlong2/latlong.dart';

import '../../../core/models/models.dart';

/// Server-computed route and price guidance for a prospective ride.
class RoutePreview {
  const RoutePreview({
    required this.distanceM,
    required this.durationS,
    required this.suggestedPriceCents,
    required this.minPriceCents,
    required this.maxPriceCents,
    required this.route,
  });

  final int distanceM;
  final int durationS;
  final int suggestedPriceCents;
  final int minPriceCents;
  final int maxPriceCents;
  final List<LatLng> route;

  factory RoutePreview.fromJson(Map<String, dynamic> json) => RoutePreview(
        distanceM: json['distance_m'] as int,
        durationS: json['duration_s'] as int,
        suggestedPriceCents: json['suggested_price_cents'] as int,
        minPriceCents: json['min_price_cents'] as int,
        maxPriceCents: json['max_price_cents'] as int,
        route: routeFromGeoJson(json['route']),
      );
}

/// Everything a driver submits to publish a ride.
class PublishRideInput {
  const PublishRideInput({
    required this.origin,
    required this.destination,
    required this.departureAt,
    required this.seatsTotal,
    required this.pricePerSeatCents,
    required this.preferences,
    this.currency = 'EUR',
    this.vehicleDescription = '',
    this.notes = '',
  });

  final Place origin;
  final Place destination;
  final DateTime departureAt;
  final int seatsTotal;
  final int pricePerSeatCents;
  final String currency;
  final RidePreferences preferences;
  final String vehicleDescription;
  final String notes;

  Map<String, dynamic> toJson() => {
        'origin': origin.toJson(),
        'destination': destination.toJson(),
        'departure_at': departureAt.toUtc().toIso8601String(),
        'seats_total': seatsTotal,
        'price_per_seat_cents': pricePerSeatCents,
        'currency': currency,
        'preferences': preferences.toJson(),
        'vehicle_description': vehicleDescription,
        'notes': notes,
      };
}
