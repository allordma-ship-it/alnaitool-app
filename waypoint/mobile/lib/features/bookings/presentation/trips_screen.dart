import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/models.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../../publish/presentation/publish_controller.dart';
import '../domain/booking_entities.dart';
import 'booking_providers.dart';

/// "Your trips": bookings as passenger and rides as driver.
class TripsScreen extends StatelessWidget {
  const TripsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Your trips'),
          bottom: const TabBar(tabs: [Tab(text: 'As passenger'), Tab(text: 'As driver')]),
        ),
        body: const TabBarView(children: [_PassengerTab(), _DriverTab()]),
      ),
    );
  }
}

class _PassengerTab extends ConsumerWidget {
  const _PassengerTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bookings = ref.watch(myBookingsProvider);
    return RefreshIndicator(
      onRefresh: () => ref.refresh(myBookingsProvider.future),
      child: AsyncView(
        value: bookings,
        onRetry: () => ref.invalidate(myBookingsProvider),
        data: (list) {
          if (list.isEmpty) {
            return ListView(
              children: [
                const SizedBox(height: 80),
                EmptyView(
                  icon: Icons.luggage_outlined,
                  title: 'No bookings yet',
                  subtitle: 'Find a ride and travel together.',
                  action: FilledButton.tonal(onPressed: () => context.go('/search'), child: const Text('Find a ride')),
                ),
              ],
            );
          }
          final now = DateTime.now();
          final upcoming = list.where((b) => b.status.isActive && b.dropoffEta.isAfter(now)).toList()
            ..sort((a, b) => a.pickupEta.compareTo(b.pickupEta));
          final past = list.where((b) => !upcoming.contains(b)).toList();
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (upcoming.isNotEmpty) ...[
                const _SectionTitle('Upcoming'),
                for (final b in upcoming) _BookingCard(booking: b),
              ],
              if (past.isNotEmpty) ...[
                const _SectionTitle('History'),
                for (final b in past) _BookingCard(booking: b),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _DriverTab extends ConsumerWidget {
  const _DriverTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final upcoming = ref.watch(myRidesProvider(true));
    final past = ref.watch(myRidesProvider(false));
    return RefreshIndicator(
      onRefresh: () {
        ref.invalidate(myRidesProvider(false));
        return ref.refresh(myRidesProvider(true).future);
      },
      child: AsyncView(
        value: upcoming,
        onRetry: () => ref.invalidate(myRidesProvider(true)),
        data: (rides) {
          final history = past.valueOrNull ?? const <RideDetail>[];
          if (rides.isEmpty && history.isEmpty) {
            return ListView(
              children: [
                const SizedBox(height: 80),
                EmptyView(
                  icon: Icons.directions_car_outlined,
                  title: 'No rides offered',
                  subtitle: 'Driving somewhere? Share your empty seats and your costs.',
                  action: FilledButton.tonal(onPressed: () => context.go('/publish'), child: const Text('Offer a ride')),
                ),
              ],
            );
          }
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (rides.isNotEmpty) ...[
                const _SectionTitle('Upcoming'),
                for (final r in rides) _DriverRideCard(detail: r),
              ],
              if (history.isNotEmpty) ...[
                const _SectionTitle('History'),
                for (final r in history) _DriverRideCard(detail: r),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 8, bottom: 8),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );
}

class _BookingCard extends StatelessWidget {
  const _BookingCard({required this.booking});

  final Booking booking;

  @override
  Widget build(BuildContext context) {
    final b = booking;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => context.push('/bookings/${b.id}'),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(formatDay(b.pickupEta), style: Theme.of(context).textTheme.titleSmall),
                    const Spacer(),
                    StatusChip(label: b.status.label, color: b.status.color),
                  ],
                ),
                const SizedBox(height: 12),
                TripTimeline(
                  fromTime: formatTime(b.pickupEta),
                  fromName: b.pickup.name,
                  toTime: formatTime(b.dropoffEta),
                  toName: b.dropoff.name,
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(child: Text('with ${b.driver.displayName}')),
                    Text(formatMoney(b.totalCents, b.currency), style: Theme.of(context).textTheme.titleMedium),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DriverRideCard extends StatelessWidget {
  const _DriverRideCard({required this.detail});

  final RideDetail detail;

  @override
  Widget build(BuildContext context) {
    final r = detail.ride;
    final (label, color) = switch (r.status) {
      RideStatus.scheduled => ('Scheduled', AppTheme.brand),
      RideStatus.inProgress => ('On the road', AppTheme.warning),
      RideStatus.completed => ('Completed', AppTheme.accent),
      RideStatus.cancelled => ('Cancelled', AppTheme.danger),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => context.push('/driver/rides/${r.id}'),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(formatDay(r.departureAt), style: Theme.of(context).textTheme.titleSmall),
                    const Spacer(),
                    StatusChip(label: label, color: color),
                  ],
                ),
                const SizedBox(height: 12),
                TripTimeline(
                  fromTime: formatTime(r.departureAt),
                  fromName: r.origin.name,
                  toTime: formatTime(r.arrivalAt),
                  toName: r.destination.name,
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(Icons.event_seat_outlined, size: 18, color: Theme.of(context).colorScheme.primary),
                    const SizedBox(width: 4),
                    Text('${detail.confirmedSeats} booked · ${detail.seatsAvailable} free'),
                    const Spacer(),
                    if (detail.pendingRequests > 0)
                      StatusChip(
                        label: '${detail.pendingRequests} request${detail.pendingRequests > 1 ? 's' : ''}',
                        color: AppTheme.warning,
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
