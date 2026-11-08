import '../../features/bookings/domain/booking_entities.dart';
import '../../features/search/domain/search_entities.dart';
import '../models/models.dart';
import 'l10n.dart';

/// Localized labels for domain enums (kept out of the domain layer).
extension BookingStatusL10n on BookingStatus {
  String label(AppLocalizations l) => switch (this) {
        BookingStatus.paymentPending => l.bsPaymentPending,
        BookingStatus.requested => l.bsRequested,
        BookingStatus.confirmed => l.bsConfirmed,
        BookingStatus.rejected => l.bsRejected,
        BookingStatus.cancelledByPassenger => l.bsCancelled,
        BookingStatus.cancelledByDriver => l.bsCancelledByDriver,
        BookingStatus.expired => l.bsExpired,
        BookingStatus.completed => l.bsCompleted,
      };
}

extension RideStatusL10n on RideStatus {
  String label(AppLocalizations l) => switch (this) {
        RideStatus.scheduled => l.rideScheduled,
        RideStatus.inProgress => l.rideOnTheRoad,
        RideStatus.completed => l.rideCompleted,
        RideStatus.cancelled => l.rideCancelled,
      };
}

extension SearchSortL10n on SearchSort {
  String label(AppLocalizations l) => switch (this) {
        SearchSort.departure => l.sortEarliest,
        SearchSort.price => l.sortCheapest,
        SearchSort.proximity => l.sortClosest,
      };
}
