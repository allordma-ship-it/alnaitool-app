import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../data/search_repository_impl.dart';
import '../domain/search_entities.dart';

/// Results for a search query.
final searchResultsProvider = FutureProvider.autoDispose.family<List<RideMatch>, SearchQuery>(
  (ref, query) => ref.watch(searchRepositoryProvider).search(query),
);

/// Full ride detail including route geometry.
final rideDetailProvider = FutureProvider.autoDispose.family<RideDetail, String>(
  (ref, rideId) => ref.watch(searchRepositoryProvider).ride(rideId),
);

/// Search form state, kept while navigating between tabs.
class SearchFormState {
  const SearchFormState({this.from, this.to, required this.date, this.seats = 1});

  final Place? from;
  final Place? to;
  final DateTime date;
  final int seats;

  bool get isComplete => from != null && to != null;

  SearchFormState copyWith({Place? from, Place? to, DateTime? date, int? seats}) => SearchFormState(
        from: from ?? this.from,
        to: to ?? this.to,
        date: date ?? this.date,
        seats: seats ?? this.seats,
      );

  SearchFormState swapped() => SearchFormState(from: to, to: from, date: date, seats: seats);
}

class SearchFormController extends Notifier<SearchFormState> {
  @override
  SearchFormState build() => SearchFormState(date: DateTime.now());

  void setFrom(Place p) => state = state.copyWith(from: p);
  void setTo(Place p) => state = state.copyWith(to: p);
  void setDate(DateTime d) => state = state.copyWith(date: d);
  void setSeats(int s) => state = state.copyWith(seats: s.clamp(1, 8));
  void swap() => state = state.swapped();
}

final searchFormProvider = NotifierProvider<SearchFormController, SearchFormState>(SearchFormController.new);
