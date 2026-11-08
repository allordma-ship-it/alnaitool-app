import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/l10n/error_text.dart';
import '../../../core/l10n/l10n.dart';
import '../../../core/l10n/labels.dart';
import '../../../core/models/models.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../../../core/widgets/route_map.dart';
import '../../publish/data/publish_repository_impl.dart';
import '../../publish/presentation/publish_controller.dart';
import '../../search/presentation/search_providers.dart';
import '../data/booking_repository_impl.dart';
import '../domain/booking_entities.dart';
import 'booking_providers.dart';
import 'review_dialog.dart';

/// Driver dashboard for a single ride: passengers, requests and lifecycle.
class DriverRideScreen extends ConsumerStatefulWidget {
  const DriverRideScreen({super.key, required this.rideId});

  final String rideId;

  @override
  ConsumerState<DriverRideScreen> createState() => _DriverRideScreenState();
}

class _DriverRideScreenState extends ConsumerState<DriverRideScreen> {
  bool _busy = false;

  void _refresh() {
    ref.invalidate(rideDetailProvider(widget.rideId));
    ref.invalidate(rideBookingsProvider(widget.rideId));
    ref.invalidate(myRidesProvider(true));
    ref.invalidate(myRidesProvider(false));
  }

  Future<void> _run(Future<void> Function() action, {String? success}) async {
    setState(() => _busy = true);
    try {
      await action();
      _refresh();
      if (mounted && success != null) showInfo(context, success);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(rideDetailProvider(widget.rideId));
    final bookings = ref.watch(rideBookingsProvider(widget.rideId));
    return Scaffold(
      appBar: AppBar(title: Text(context.l10n.yourRide)),
      body: AsyncView(
        value: detail,
        onRetry: _refresh,
        data: (d) => RefreshIndicator(
          onRefresh: () async {
            _refresh();
            await ref.read(rideDetailProvider(widget.rideId).future);
          },
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _header(context, d),
              const SizedBox(height: 16),
              RouteMap(
                route: d.ride.route,
                height: 200,
                pins: [
                  MapPin(point: d.ride.origin.location, icon: Icons.trip_origin, color: AppTheme.accent),
                  MapPin(point: d.ride.destination.location, icon: Icons.place, color: AppTheme.danger),
                  for (final b in bookings.valueOrNull ?? const <Booking>[])
                    if (b.status.isActive)
                      MapPin(
                        point: b.pickup.location,
                        icon: Icons.person_pin_circle,
                        color: AppTheme.brand,
                        tooltip: '${b.passenger.firstName}: ${b.pickup.name}',
                      ),
                ],
              ),
              const SizedBox(height: 20),
              Text(context.l10n.passengers, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              bookings.when(
                loading: () => const Padding(
                  padding: EdgeInsets.all(24),
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (e, _) => ErrorView(message: localizedError(context.l10n, e), onRetry: _refresh),
                data: (list) {
                  final visible = list.where((b) => b.status != BookingStatus.paymentPending).toList();
                  if (visible.isEmpty) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: Text(context.l10n.noPassengers),
                    );
                  }
                  return Column(children: [for (final b in visible) _passengerCard(context, d.ride, b)]);
                },
              ),
              const SizedBox(height: 20),
              ..._lifecycleActions(context, d.ride),
            ],
          ),
        ),
      ),
    );
  }

  Widget _header(BuildContext context, RideDetail d) {
    final r = d.ride;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(formatDay(r.departureAt), style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        TripTimeline(
          fromTime: formatTime(r.departureAt),
          fromName: r.origin.name,
          toTime: formatTime(r.arrivalAt),
          toName: r.destination.name,
          middle: formatDuration(context.l10n, r.durationS),
        ),
        const SizedBox(height: 8),
        Text(context.l10n.pricePerSeatInfo(formatMoney(r.pricePerSeatCents, r.currency), d.seatsAvailable, r.seatsTotal)),
      ],
    );
  }

  Widget _passengerCard(BuildContext context, Ride ride, Booking b) {
    final repo = ref.read(bookingRepositoryProvider);
    final l = context.l10n;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ProfileTile(
                profile: b.passenger,
                trailing: StatusChip(label: b.status.label(l), color: b.status.color),
              ),
              Text('${l.seatsCount(b.seats)} · ${l.routeFromTo(b.pickup.name, b.dropoff.name)}'),
              Text(
                l.pickupYouReceive(formatTime(b.pickupEta), formatMoney(b.priceCents, b.currency)),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (b.message.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('“${b.message}”', style: const TextStyle(fontStyle: FontStyle.italic)),
              ],
              const SizedBox(height: 8),
              if (b.status == BookingStatus.requested)
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _busy ? null : () => _run(() => repo.reject(b.id), success: l.requestDeclined),
                        child: Text(l.decline),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: _busy ? null : () => _run(() => repo.accept(b.id), success: l.passengerConfirmed),
                        child: Text(l.accept),
                      ),
                    ),
                  ],
                ),
              if (b.status == BookingStatus.confirmed && ride.status == RideStatus.scheduled)
                TextButton(
                  onPressed: _busy
                      ? null
                      : () async {
                          final ok = await confirmAction(
                            context,
                            title: l.removePassengerTitle(b.passenger.firstName),
                            message: l.removePassengerBody,
                            confirmLabel: l.remove,
                            destructive: true,
                          );
                          if (ok) await _run(() => repo.removePassenger(b.id), success: l.passengerRemoved);
                        },
                  child: Text(l.removePassenger),
                ),
              if (b.canReview)
                TextButton.icon(
                  icon: const Icon(Icons.star_outline),
                  label: Text(l.rateName(b.passenger.firstName)),
                  onPressed: _busy
                      ? null
                      : () async {
                          final input = await showReviewDialog(context, name: b.passenger.firstName);
                          if (input != null) {
                            await _run(
                              () => repo.review(b.id, rating: input.rating, comment: input.comment),
                              success: l.reviewSaved,
                            );
                          }
                        },
                ),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _lifecycleActions(BuildContext context, Ride ride) {
    final repo = ref.read(publishRepositoryProvider);
    final l = context.l10n;
    final now = DateTime.now();
    return [
      if (ride.status == RideStatus.scheduled && now.isAfter(ride.departureAt.subtract(const Duration(hours: 1))))
        FilledButton.icon(
          icon: const Icon(Icons.play_arrow),
          label: Text(l.startRide),
          onPressed: _busy ? null : () => _run(() => repo.start(ride.id), success: l.safeTrip),
        ),
      if ((ride.status == RideStatus.scheduled || ride.status == RideStatus.inProgress) &&
          now.isAfter(ride.departureAt))
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: FilledButton.tonalIcon(
            icon: const Icon(Icons.flag),
            label: Text(l.completeRide),
            onPressed: _busy ? null : () => _run(() => repo.complete(ride.id), success: l.rideCompletedMsg),
          ),
        ),
      if (ride.status == RideStatus.scheduled)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: OutlinedButton(
            onPressed: _busy
                ? null
                : () async {
                    final ok = await confirmAction(
                      context,
                      title: l.cancelRideTitle,
                      message: l.cancelRideBody,
                      confirmLabel: l.cancelRide,
                      destructive: true,
                    );
                    if (ok) await _run(() => repo.cancel(ride.id), success: l.rideCancelledMsg);
                  },
            child: Text(l.cancelRide),
          ),
        ),
    ];
  }
}
