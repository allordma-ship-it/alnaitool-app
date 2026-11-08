import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../../../core/widgets/route_map.dart';
import '../data/booking_repository_impl.dart';
import '../domain/booking_entities.dart';
import 'booking_providers.dart';
import 'review_dialog.dart';

/// Passenger view of a booking with escrow status and actions.
class BookingDetailScreen extends ConsumerStatefulWidget {
  const BookingDetailScreen({super.key, required this.bookingId});

  final String bookingId;

  @override
  ConsumerState<BookingDetailScreen> createState() => _BookingDetailScreenState();
}

class _BookingDetailScreenState extends ConsumerState<BookingDetailScreen> {
  bool _busy = false;

  Future<void> _run(Future<void> Function() action, {String? success}) async {
    setState(() => _busy = true);
    try {
      await action();
      ref.invalidate(bookingProvider(widget.bookingId));
      ref.invalidate(myBookingsProvider);
      if (mounted && success != null) showInfo(context, success);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _cancel(Booking b) async {
    final message = b.lateCancellation
        ? 'Your pickup is less than 24 hours away. The driver will receive part of the seat price '
            'as compensation; the rest of the hold is released.'
        : 'Your payment hold will be released in full.';
    final ok = await confirmAction(
      context,
      title: 'Cancel this booking?',
      message: message,
      confirmLabel: 'Cancel booking',
      destructive: true,
    );
    if (!ok) return;
    await _run(() => ref.read(bookingRepositoryProvider).cancel(b.id), success: 'Booking cancelled');
  }

  Future<void> _review(Booking b) async {
    final input = await showReviewDialog(context, name: b.driver.firstName);
    if (input == null) return;
    await _run(
      () => ref.read(bookingRepositoryProvider).review(b.id, rating: input.rating, comment: input.comment),
      success: 'Thanks for your review!',
    );
  }

  @override
  Widget build(BuildContext context) {
    final booking = ref.watch(bookingProvider(widget.bookingId));
    return Scaffold(
      appBar: AppBar(title: const Text('Your booking')),
      body: AsyncView(
        value: booking,
        onRetry: () => ref.invalidate(bookingProvider(widget.bookingId)),
        data: (b) => RefreshIndicator(
          onRefresh: () => ref.refresh(bookingProvider(widget.bookingId).future),
          child: _body(context, b),
        ),
      ),
    );
  }

  Widget _body(BuildContext context, Booking b) {
    final theme = Theme.of(context);
    final payment = b.payment;
    final segment = sliceRoute(b.ride.route, b.pickupFraction, b.dropoffFraction);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Expanded(child: Text(formatDay(b.pickupEta), style: theme.textTheme.headlineSmall)),
            StatusChip(label: b.status.label, color: b.status.color),
          ],
        ),
        const SizedBox(height: 16),
        TripTimeline(
          fromTime: formatTime(b.pickupEta),
          fromName: b.pickup.name,
          toTime: formatTime(b.dropoffEta),
          toName: b.dropoff.name,
          middle: formatDuration(b.dropoffEta.difference(b.pickupEta).inSeconds),
        ),
        const SizedBox(height: 16),
        RouteMap(
          route: b.ride.route,
          highlight: segment,
          height: 200,
          pins: [
            MapPin(point: b.pickup.location, icon: Icons.trip_origin, color: AppTheme.accent, tooltip: 'Pickup'),
            MapPin(point: b.dropoff.location, icon: Icons.place, color: AppTheme.danger, tooltip: 'Drop-off'),
          ],
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: ProfileTile(
              profile: b.driver,
              subtitle: b.ride.vehicleDescription.isEmpty ? null : b.ride.vehicleDescription,
            ),
          ),
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                _line(context, '${b.seats} seat${b.seats > 1 ? 's' : ''}', formatMoney(b.priceCents, b.currency)),
                _line(context, 'Service fee', formatMoney(b.serviceFeeCents, b.currency)),
                const Divider(),
                _line(context, 'Total', formatMoney(b.totalCents, b.currency), bold: true),
                if (payment != null) ...[
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Icon(Icons.lock_outline, size: 18, color: theme.colorScheme.primary),
                      const SizedBox(width: 6),
                      Expanded(child: Text(_paymentExplanation(payment), style: theme.textTheme.bodySmall)),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        if (payment != null && payment.needsAction && b.status == BookingStatus.paymentPending)
          FilledButton.icon(
            icon: const Icon(Icons.credit_card),
            label: const Text('Complete payment'),
            onPressed: _busy
                ? null
                : () => _run(() => ref.read(bookingFlowProvider).completePayment(b), success: 'Payment authorized'),
          ),
        if (b.status == BookingStatus.paymentPending && payment?.provider == 'stripe')
          TextButton(
            onPressed: _busy
                ? null
                : () => _run(() => ref.read(bookingRepositoryProvider).syncPayment(b.id)),
            child: const Text('Refresh payment status'),
          ),
        if (b.canConfirmTrip)
          FilledButton.icon(
            icon: const Icon(Icons.check_circle_outline),
            label: const Text('Confirm the trip took place'),
            onPressed: _busy
                ? null
                : () => _run(
                      () => ref.read(bookingRepositoryProvider).confirmTrip(b.id),
                      success: 'Thanks! The driver has been paid.',
                    ),
          ),
        if (b.canReview)
          FilledButton.tonalIcon(
            icon: const Icon(Icons.star_outline),
            label: Text('Rate ${b.driver.firstName}'),
            onPressed: _busy ? null : () => _review(b),
          ),
        if (b.status.isActive) ...[
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: _busy ? null : () => _cancel(b),
            child: const Text('Cancel booking'),
          ),
        ],
      ],
    );
  }

  String _paymentExplanation(PaymentInfo p) => switch (p.status) {
        PaymentStatus.pending => 'Waiting for your payment authorization.',
        PaymentStatus.authorized =>
          '${formatMoney(p.amountCents, p.currency)} is reserved on your card and released to the driver after the trip.',
        PaymentStatus.captured => 'Charged ${formatMoney(p.capturedCents, p.currency)}.',
        PaymentStatus.voided => 'The hold on your card was released — you were not charged.',
        PaymentStatus.refunded => 'Refunded ${formatMoney(p.refundedCents, p.currency)}.',
        PaymentStatus.failed => 'Your card was declined. Please try another payment method.',
      };

  Widget _line(BuildContext context, String label, String value, {bool bold = false}) {
    final style = bold
        ? Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)
        : Theme.of(context).textTheme.bodyLarge;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(children: [Text(label, style: style), const Spacer(), Text(value, style: style)]),
    );
  }
}
