import '../../../core/models/models.dart';
import 'search_entities.dart';

/// Ride discovery.
abstract interface class SearchRepository {
  Future<List<RideMatch>> search(SearchQuery query);

  Future<RideDetail> ride(String rideId);
}
