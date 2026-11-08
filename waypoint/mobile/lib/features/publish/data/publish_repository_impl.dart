import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../domain/publish_entities.dart';
import '../domain/publish_repository.dart';

/// REST implementation of [PublishRepository].
class PublishRepositoryImpl implements PublishRepository {
  const PublishRepositoryImpl(this._api);

  final ApiClient _api;

  RideDetail _detail(dynamic d) => RideDetail.fromJson(asMap(d));

  @override
  Future<RoutePreview> preview(LatLng origin, LatLng destination) => _api.post(
        '/rides/preview',
        body: {'origin': latLngToJson(origin), 'destination': latLngToJson(destination)},
        parse: (d) => RoutePreview.fromJson(asMap(d)),
      );

  @override
  Future<RideDetail> publish(PublishRideInput input) =>
      _api.post('/rides', body: input.toJson(), parse: _detail);

  @override
  Future<List<RideDetail>> myRides({bool upcoming = true}) => _api.get(
        '/me/rides',
        query: {'scope': upcoming ? 'upcoming' : 'past'},
        parse: (d) => parseList(d, RideDetail.fromJson),
      );

  @override
  Future<RideDetail> cancel(String rideId) => _api.post('/rides/$rideId/cancel', parse: _detail);

  @override
  Future<RideDetail> start(String rideId) => _api.post('/rides/$rideId/start', parse: _detail);

  @override
  Future<RideDetail> complete(String rideId) => _api.post('/rides/$rideId/complete', parse: _detail);
}

final publishRepositoryProvider = Provider<PublishRepository>(
  (ref) => PublishRepositoryImpl(ref.watch(apiClientProvider)),
);
