import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart';

import '../models/models.dart';
import '../network/api_client.dart';

/// A geocoder suggestion.
class PlaceSuggestion {
  const PlaceSuggestion({required this.name, required this.label, required this.location, required this.kind});

  final String name;
  final String label;
  final LatLng location;
  final String kind;

  Place toPlace() => Place(name: name, location: location);

  factory PlaceSuggestion.fromJson(Map<String, dynamic> json) => PlaceSuggestion(
        name: json['name'] as String,
        label: json['label'] as String,
        location: latLngFromJson(json['location'] as Map<String, dynamic>),
        kind: (json['kind'] as String?) ?? '',
      );
}

/// Place autocomplete backed by the API's OpenStreetMap geocoder.
class GeocodingRepository {
  const GeocodingRepository(this._api);

  final ApiClient _api;

  Future<List<PlaceSuggestion>> search(String query, {LatLng? near, String lang = 'en'}) {
    return _api.get(
      '/geocode',
      query: {
        'q': query,
        'lang': lang,
        if (near != null) 'lat': near.latitude,
        if (near != null) 'lng': near.longitude,
      },
      parse: (d) => parseList(d, PlaceSuggestion.fromJson),
    );
  }
}

final geocodingRepositoryProvider = Provider<GeocodingRepository>(
  (ref) => GeocodingRepository(ref.watch(apiClientProvider)),
);
