import 'package:latlong2/latlong.dart';

double _d(dynamic v) => (v as num).toDouble();
DateTime _t(dynamic v) => DateTime.parse(v as String).toLocal();
DateTime? _tn(dynamic v) => v == null ? null : _t(v);

/// A named WGS84 location.
class Place {
  const Place({required this.name, required this.location});

  final String name;
  final LatLng location;

  factory Place.fromJson(Map<String, dynamic> json) => Place(
        name: json['name'] as String,
        location: latLngFromJson(json['location'] as Map<String, dynamic>),
      );

  Map<String, dynamic> toJson() => {'name': name, 'location': latLngToJson(location)};

  @override
  bool operator ==(Object other) =>
      other is Place && other.name == name && other.location == location;

  @override
  int get hashCode => Object.hash(name, location);
}

LatLng latLngFromJson(Map<String, dynamic> json) => LatLng(_d(json['lat']), _d(json['lng']));

Map<String, double> latLngToJson(LatLng p) => {'lat': p.latitude, 'lng': p.longitude};

/// Decodes a GeoJSON LineString (`[lng, lat]` pairs) into map points.
List<LatLng> routeFromGeoJson(dynamic geo) {
  if (geo is! Map<String, dynamic>) return const [];
  final coords = geo['coordinates'];
  if (coords is! List) return const [];
  return coords
      .map((c) => c as List<dynamic>)
      .map((c) => LatLng(_d(c[1]), _d(c[0])))
      .toList(growable: false);
}

/// Another member as shown publicly.
class PublicProfile {
  const PublicProfile({
    required this.id,
    required this.firstName,
    required this.lastInitial,
    required this.bio,
    required this.rating,
    required this.ratingCount,
    required this.memberSince,
    this.avatarUrl,
  });

  final String id;
  final String firstName;
  final String lastInitial;
  final String bio;
  final String? avatarUrl;
  final double rating;
  final int ratingCount;
  final DateTime memberSince;

  String get displayName => '$firstName $lastInitial'.trim();

  factory PublicProfile.fromJson(Map<String, dynamic> json) => PublicProfile(
        id: json['id'] as String,
        firstName: json['first_name'] as String,
        lastInitial: (json['last_initial'] as String?) ?? '',
        bio: (json['bio'] as String?) ?? '',
        avatarUrl: json['avatar_url'] as String?,
        rating: _d(json['rating']),
        ratingCount: json['rating_count'] as int,
        memberSince: _t(json['member_since']),
      );
}

/// The signed-in member.
class Me {
  const Me({
    required this.id,
    required this.email,
    required this.firstName,
    required this.lastName,
    required this.bio,
    required this.rating,
    required this.ratingCount,
    required this.payoutsEnabled,
    required this.createdAt,
    this.phone,
    this.avatarUrl,
  });

  final String id;
  final String email;
  final String? phone;
  final String firstName;
  final String lastName;
  final String bio;
  final String? avatarUrl;
  final double rating;
  final int ratingCount;
  final bool payoutsEnabled;
  final DateTime createdAt;

  String get fullName => '$firstName $lastName';

  factory Me.fromJson(Map<String, dynamic> json) => Me(
        id: json['id'] as String,
        email: json['email'] as String,
        phone: json['phone'] as String?,
        firstName: json['first_name'] as String,
        lastName: json['last_name'] as String,
        bio: (json['bio'] as String?) ?? '',
        avatarUrl: json['avatar_url'] as String?,
        rating: _d(json['rating']),
        ratingCount: json['rating_count'] as int,
        payoutsEnabled: json['payouts_enabled'] as bool,
        createdAt: _t(json['created_at']),
      );
}

/// Driver travel rules.
class RidePreferences {
  const RidePreferences({
    this.instantBooking = true,
    this.allowSmoking = false,
    this.allowPets = false,
    this.maxTwoBack = true,
  });

  final bool instantBooking;
  final bool allowSmoking;
  final bool allowPets;
  final bool maxTwoBack;

  factory RidePreferences.fromJson(Map<String, dynamic> json) => RidePreferences(
        instantBooking: json['instant_booking'] as bool,
        allowSmoking: json['allow_smoking'] as bool,
        allowPets: json['allow_pets'] as bool,
        maxTwoBack: json['max_two_back'] as bool,
      );

  Map<String, bool> toJson() => {
        'instant_booking': instantBooking,
        'allow_smoking': allowSmoking,
        'allow_pets': allowPets,
        'max_two_back': maxTwoBack,
      };

  RidePreferences copyWith({bool? instantBooking, bool? allowSmoking, bool? allowPets, bool? maxTwoBack}) =>
      RidePreferences(
        instantBooking: instantBooking ?? this.instantBooking,
        allowSmoking: allowSmoking ?? this.allowSmoking,
        allowPets: allowPets ?? this.allowPets,
        maxTwoBack: maxTwoBack ?? this.maxTwoBack,
      );
}

enum RideStatus { scheduled, inProgress, completed, cancelled }

RideStatus _rideStatus(String s) => switch (s) {
      'in_progress' => RideStatus.inProgress,
      'completed' => RideStatus.completed,
      'cancelled' => RideStatus.cancelled,
      _ => RideStatus.scheduled,
    };

/// A trip offered by a driver.
class Ride {
  const Ride({
    required this.id,
    required this.driverId,
    required this.origin,
    required this.destination,
    required this.distanceM,
    required this.durationS,
    required this.departureAt,
    required this.arrivalAt,
    required this.seatsTotal,
    required this.pricePerSeatCents,
    required this.currency,
    required this.preferences,
    required this.vehicleDescription,
    required this.notes,
    required this.status,
    this.route = const [],
    this.cancelledAt,
  });

  final String id;
  final String driverId;
  final Place origin;
  final Place destination;
  final int distanceM;
  final int durationS;
  final DateTime departureAt;
  final DateTime arrivalAt;
  final int seatsTotal;
  final int pricePerSeatCents;
  final String currency;
  final RidePreferences preferences;
  final String vehicleDescription;
  final String notes;
  final RideStatus status;
  final List<LatLng> route;
  final DateTime? cancelledAt;

  factory Ride.fromJson(Map<String, dynamic> json) => Ride(
        id: json['id'] as String,
        driverId: json['driver_id'] as String,
        origin: Place.fromJson(json['origin'] as Map<String, dynamic>),
        destination: Place.fromJson(json['destination'] as Map<String, dynamic>),
        distanceM: json['distance_m'] as int,
        durationS: json['duration_s'] as int,
        departureAt: _t(json['departure_at']),
        arrivalAt: _t(json['arrival_at']),
        seatsTotal: json['seats_total'] as int,
        pricePerSeatCents: json['price_per_seat_cents'] as int,
        currency: json['currency'] as String,
        preferences: RidePreferences.fromJson(json['preferences'] as Map<String, dynamic>),
        vehicleDescription: (json['vehicle_description'] as String?) ?? '',
        notes: (json['notes'] as String?) ?? '',
        status: _rideStatus(json['status'] as String),
        route: routeFromGeoJson(json['route']),
        cancelledAt: _tn(json['cancelled_at']),
      );
}

/// A ride with driver profile and full-route availability.
class RideDetail {
  const RideDetail({
    required this.ride,
    required this.driver,
    required this.seatsAvailable,
    this.pendingRequests = 0,
    this.confirmedSeats = 0,
  });

  final Ride ride;
  final PublicProfile driver;
  final int seatsAvailable;
  final int pendingRequests;
  final int confirmedSeats;

  factory RideDetail.fromJson(Map<String, dynamic> json) => RideDetail(
        ride: Ride.fromJson(json),
        driver: PublicProfile.fromJson(json['driver'] as Map<String, dynamic>),
        seatsAvailable: json['seats_available'] as int,
        pendingRequests: (json['pending_requests'] as int?) ?? 0,
        confirmedSeats: (json['confirmed_seats'] as int?) ?? 0,
      );
}
