import 'package:latlong2/latlong.dart';

import '../../../core/models/models.dart';
import 'publish_entities.dart';

/// Driver-side ride management.
abstract interface class PublishRepository {
  Future<RoutePreview> preview(LatLng origin, LatLng destination);

  Future<RideDetail> publish(PublishRideInput input);

  Future<List<RideDetail>> myRides({bool upcoming = true});

  Future<RideDetail> cancel(String rideId);

  Future<RideDetail> start(String rideId);

  Future<RideDetail> complete(String rideId);
}
