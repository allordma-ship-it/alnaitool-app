import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:latlong2/latlong.dart';

import '../../../core/models/models.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../../../core/widgets/route_map.dart';
import '../../auth/presentation/auth_controller.dart';
import '../../bookings/presentation/booking_providers.dart';
import '../domain/search_entities.dart';
import 'search_providers.dart';

/// Ride detail with route map, driver, price breakdown and booking.
class RideDetailScreen extends ConsumerStatefulWidget {
  const RideDetailScreen({super.key, required this.args});

  final RideDetailArgs args;

  @override
  ConsumerState<RideDetailScreen> createState() => _RideDetailScreenState();
}

class _RideDetailScreenState extends ConsumerState<RideDetailScreen> {
  late int _seats = widget.args.query?.seats ?? 1;
  final _message = TextEditingController();
  bool _booking = false;

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Future<void> _book(RideDetail detail) async {
    final match = widget.args.match;
    final query = widget.args.query;
    final ride = detail.ride;
    // Search results carry the snapped meeting points; deep links book the full route.
    final pickup = match != null
        ? Place(name: query?.from.name ?? ride.origin.name, location: match.segment.pickup)
        : ride.origin;
    final dropoff = match != null
        ? Place(name: query?.to.name ?? ride.destination.name, location: match.segment.dropoff)
        : ride.destination;

    setState(() => _booking = true);
    try {
      final booking = await ref.read(bookingFlowProvider).book(
            rideId: ride.id,
            seats: _seats,
            pickup: pickup,
            dropoff: dropoff,
            message: _message.text,
            themeMode: Theme.of(context).brightness == Brightness.dark ? ThemeMode.dark : ThemeMode.light,
          );
      if (!mounted) return;
      ref.invalidate(rideDetailProvider(ride.id));
      context.go('/trips');
      unawaitedPush(context, '/bookings/${booking.id}');
    } on ApiException catch (e) {
      if (mounted) showError(context, e);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _booking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(rideDetailProvider(widget.args.rideId));
    final me = ref.watch(authControllerProvider).valueOrNull;
    return Scaffold(
      appBar: AppBar(title: const Text('Ride details')),
      body: AsyncView(
        value: detail,
        onRetry: () => ref.invalidate(rideDetailProvider(widget.args.rideId)),
        data: (d) => _body(context, d, isOwnRide: me?.id == d.ride.driverId),
      ),
    );
  }

  Widget _body(BuildContext context, RideDetail d, {required bool isOwnRide}) {
    final theme = Theme.of(context);
    final ride = d.ride;
    final match = widget.args.match;
    final seg = match?.segment;
    final pricePerSeat = match?.pricePerSeatCents ?? ride.pricePerSeatCents;
    final maxSeats = (match?.seatsAvailable ?? d.seatsAvailable).clamp(0, 8);
    final seats = _seats.clamp(1, maxSeats < 1 ? 1 : maxSeats);
    final List<LatLng> highlight =
        seg == null ? const [] : sliceRoute(ride.route, seg.pickupFraction, seg.dropoffFraction);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(formatDay(seg?.pickupEta ?? ride.departureAt), style: theme.textTheme.headlineSmall),
        const SizedBox(height: 16),
        TripTimeline(
          fromTime: formatTime(seg?.pickupEta ?? ride.departureAt),
          fromName: widget.args.query?.from.name ?? ride.origin.name,
          toTime: formatTime(seg?.dropoffEta ?? ride.arrivalAt),
          toName: widget.args.query?.to.name ?? ride.destination.name,
          middle: formatDuration(
            (seg?.dropoffEta ?? ride.arrivalAt).difference(seg?.pickupEta ?? ride.departureAt).inSeconds,
          ),
        ),
        if (seg != null) ...[
          const SizedBox(height: 8),
          Text(
            'Driver travels ${ride.origin.name} → ${ride.destination.name}. '
            'Meeting point ${formatDistance(seg.pickupDistanceM)} from your start, '
            'drop-off ${formatDistance(seg.dropoffDistanceM)} from your destination.',
            style: theme.textTheme.bodySmall,
          ),
        ],
        const SizedBox(height: 16),
        RouteMap(
          route: ride.route,
          highlight: highlight,
          pins: [
            MapPin(
              point: seg?.pickup ?? ride.origin.location,
              icon: Icons.trip_origin,
              color: AppTheme.accent,
              tooltip: 'Pickup',
            ),
            MapPin(
              point: seg?.dropoff ?? ride.destination.location,
              icon: Icons.place,
              color: AppTheme.danger,
              tooltip: 'Drop-off',
            ),
          ],
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: ProfileTile(
              profile: d.driver,
              subtitle: ride.vehicleDescription.isEmpty ? null : ride.vehicleDescription,
            ),
          ),
        ),
        if (ride.notes.isNotEmpty) ...[
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(ride.notes),
            ),
          ),
        ],
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _pref(ride.preferences.instantBooking, Icons.bolt, 'Instant booking', 'Driver approves requests'),
            _pref(ride.preferences.maxTwoBack, Icons.airline_seat_recline_normal, 'Max. 2 in the back', '3 in the back'),
            _pref(ride.preferences.allowPets, Icons.pets, 'Pets welcome', 'No pets'),
            _pref(ride.preferences.allowSmoking, Icons.smoking_rooms, 'Smoking allowed', 'No smoking'),
          ],
        ),
        const SizedBox(height: 20),
        if (isOwnRide)
          const EmptyView(icon: Icons.drive_eta, title: 'This is your ride')
        else if (ride.status != RideStatus.scheduled || maxSeats < 1)
          EmptyView(
            icon: Icons.event_busy,
            title: ride.status == RideStatus.cancelled ? 'This ride was cancelled' : 'This ride is full',
          )
        else ...[
          Row(
            children: [
              Text('Seats', style: theme.textTheme.titleMedium),
              const Spacer(),
              IconButton(
                onPressed: seats > 1 ? () => setState(() => _seats = seats - 1) : null,
                icon: const Icon(Icons.remove_circle_outline),
              ),
              Text('$seats', style: theme.textTheme.titleMedium),
              IconButton(
                onPressed: seats < maxSeats ? () => setState(() => _seats = seats + 1) : null,
                icon: const Icon(Icons.add_circle_outline),
              ),
            ],
          ),
          _priceRow(context, '$seats × ${formatMoney(pricePerSeat, ride.currency)}', pricePerSeat * seats, ride.currency),
          Text(
            'A service fee is added at checkout. Your card is only charged after the trip.',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _message,
            maxLength: 500,
            maxLines: 2,
            decoration: const InputDecoration(labelText: 'Message to the driver (optional)'),
          ),
          const SizedBox(height: 8),
          FilledButton(
            onPressed: _booking ? null : () => _book(d),
            child: _booking
                ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(ride.preferences.instantBooking ? 'Book' : 'Request to book'),
          ),
        ],
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _pref(bool on, IconData icon, String yes, String no) => Chip(
        avatar: Icon(icon, size: 18),
        label: Text(on ? yes : no),
      );

  Widget _priceRow(BuildContext context, String label, int cents, String currency) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Text(label, style: theme.textTheme.bodyLarge),
        const Spacer(),
        Text(formatMoney(cents, currency), style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
      ],
    );
  }
}

/// Pushes a route after the current frame (used after switching tabs with `go`).
void unawaitedPush(BuildContext context, String location) {
  final router = GoRouter.of(context);
  WidgetsBinding.instance.addPostFrameCallback((_) => router.push(location));
}
