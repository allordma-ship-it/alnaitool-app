import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/app_config.dart';
import '../../../core/models/models.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/formatters.dart';
import '../domain/search_entities.dart';
import '../domain/search_repository.dart';

/// REST implementation of [SearchRepository].
class SearchRepositoryImpl implements SearchRepository {
  const SearchRepositoryImpl(this._api, {required this.timezone});

  final ApiClient _api;

  /// The market's IANA zone: the search day is interpreted in local (Syrian) time.
  final String timezone;

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
        'tz': timezone,
        'seats': q.seats,
        'sort': q.sort.apiValue,
      },
      parse: (d) => parseList(d, RideMatch.fromJson),
    );
  }

  @override
  Future<RideDetail> ride(String rideId) =>
      _api.get('/rides/$rideId', parse: (d) => RideDetail.fromJson(asMap(d)));
}

final searchRepositoryProvider = Provider<SearchRepository>(
  (ref) => SearchRepositoryImpl(ref.watch(apiClientProvider), timezone: ref.watch(appConfigProvider).timezone),
);
