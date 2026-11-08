import 'package:latlong2/latlong.dart';

import '../../../core/models/models.dart';

enum SearchSort {
  departure('departure'),
  price('price'),
  proximity('proximity');

  const SearchSort(this.apiValue);
  final String apiValue;
}

/// A passenger's search request. Value-equal so it can key providers.
class SearchQuery {
  const SearchQuery({
    required this.from,
    required this.to,
    required this.date,
    this.seats = 1,
    this.sort = SearchSort.departure,
  });

  final Place from;
  final Place to;
  final DateTime date;
  final int seats;
  final SearchSort sort;

  DateTime get day => DateTime(date.year, date.month, date.day);

  SearchQuery copyWith({Place? from, Place? to, DateTime? date, int? seats, SearchSort? sort}) => SearchQuery(
        from: from ?? this.from,
        to: to ?? this.to,
        date: date ?? this.date,
        seats: seats ?? this.seats,
        sort: sort ?? this.sort,
      );

  @override
  bool operator ==(Object other) =>
      other is SearchQuery &&
      other.from == from &&
      other.to == to &&
      other.day == day &&
      other.seats == seats &&
      other.sort == sort;

  @override
  int get hashCode => Object.hash(from, to, day, seats, sort);
}

/// How the passenger's trip maps onto the driver's route.
class SegmentMatch {
  const SegmentMatch({
    required this.pickup,
    required this.dropoff,
    required this.pickupFraction,
    required this.dropoffFraction,
    required this.pickupDistanceM,
    required this.dropoffDistanceM,
    required this.pickupEta,
    required this.dropoffEta,
  });

  final LatLng pickup;
  final LatLng dropoff;
  final double pickupFraction;
  final double dropoffFraction;
  final double pickupDistanceM;
  final double dropoffDistanceM;
  final DateTime pickupEta;
  final DateTime dropoffEta;

  factory SegmentMatch.fromJson(Map<String, dynamic> json) => SegmentMatch(
        pickup: latLngFromJson(json['pickup'] as Map<String, dynamic>),
        dropoff: latLngFromJson(json['dropoff'] as Map<String, dynamic>),
        pickupFraction: (json['pickup_fraction'] as num).toDouble(),
        dropoffFraction: (json['dropoff_fraction'] as num).toDouble(),
        pickupDistanceM: (json['pickup_distance_m'] as num).toDouble(),
        dropoffDistanceM: (json['dropoff_distance_m'] as num).toDouble(),
        pickupEta: DateTime.parse(json['pickup_eta'] as String).toLocal(),
        dropoffEta: DateTime.parse(json['dropoff_eta'] as String).toLocal(),
      );
}

/// A search result.
class RideMatch {
  const RideMatch({
    required this.ride,
    required this.driver,
    required this.segment,
    required this.seatsAvailable,
    required this.pricePerSeatCents,
    required this.score,
  });

  final Ride ride;
  final PublicProfile driver;
  final SegmentMatch segment;
  final int seatsAvailable;
  final int pricePerSeatCents;
  final double score;

  factory RideMatch.fromJson(Map<String, dynamic> json) => RideMatch(
        ride: Ride.fromJson(json['ride'] as Map<String, dynamic>),
        driver: PublicProfile.fromJson(json['driver'] as Map<String, dynamic>),
        segment: SegmentMatch.fromJson(json['segment'] as Map<String, dynamic>),
        seatsAvailable: json['seats_available'] as int,
        pricePerSeatCents: json['price_per_seat_cents'] as int,
        score: (json['score'] as num).toDouble(),
      );
}

/// Arguments for the ride detail screen.
class RideDetailArgs {
  const RideDetailArgs({required this.rideId, this.match, this.query});

  final String rideId;
  final RideMatch? match;
  final SearchQuery? query;
}
