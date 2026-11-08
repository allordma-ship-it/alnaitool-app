import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/formatters.dart';
import '../domain/search_entities.dart';
import '../domain/search_repository.dart';

/// REST implementation of [SearchRepository].
class SearchRepositoryImpl implements SearchRepository {
  const SearchRepositoryImpl(this._api);

  final ApiClient _api;

  @override
  Future<List<RideMatch>> search(SearchQuery q) {
    return _api.get(
      '/rides/search',
      query: {
        'from_lat': q.from.location.latitude,
        'from_lng': q.from.location.longitude,
        'to_lat': q.to.location.latitude,
        'to_lng': q.to.location.longitude,
        'date': formatIsoDate(q.date),
        'tz': _timeZoneName(),
        'seats': q.seats,
        'sort': q.sort.apiValue,
      },
      parse: (d) => parseList(d, RideMatch.fromJson),
    );
  }

  @override
  Future<RideDetail> ride(String rideId) =>
      _api.get('/rides/$rideId', parse: (d) => RideDetail.fromJson(asMap(d)));

  /// The device's IANA zone is not exposed by Dart; fall back to a fixed-offset
  /// Etc/GMT zone that has the same offset (sign is inverted by POSIX convention).
  static String _timeZoneName() {
    final offset = DateTime.now().timeZoneOffset;
    if (offset.inMinutes % 60 != 0) return 'UTC';
    final hours = offset.inHours;
    if (hours == 0) return 'UTC';
    return hours > 0 ? 'Etc/GMT-$hours' : 'Etc/GMT+${-hours}';
  }
}

final searchRepositoryProvider = Provider<SearchRepository>(
  (ref) => SearchRepositoryImpl(ref.watch(apiClientProvider)),
);
