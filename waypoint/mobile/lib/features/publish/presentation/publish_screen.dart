import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/l10n/error_text.dart';
import '../../../core/l10n/l10n.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../../../core/widgets/place_search_screen.dart';
import '../../../core/widgets/route_map.dart';
import 'publish_controller.dart';

/// Driver flow to offer seats on a trip.
class PublishScreen extends ConsumerWidget {
  const PublishScreen({super.key});

  Future<void> _submit(BuildContext context, WidgetRef ref) async {
    try {
      final ride = await ref.read(publishControllerProvider.notifier).submit();
      ref.invalidate(myRidesProvider(true));
      if (!context.mounted) return;
      showInfo(context, context.l10n.ridePublished);
      ref.invalidate(publishControllerProvider);
      context.go('/trips');
      final router = GoRouter.of(context);
      WidgetsBinding.instance.addPostFrameCallback((_) => router.push('/driver/rides/${ride.ride.id}'));
    } on ApiException catch (e) {
      if (!context.mounted) return;
      if (e.isPaymentRequired) {
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: Text(ctx.l10n.payoutsFirstTitle),
            content: Text(ctx.l10n.payoutsFirstBody),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: Text(ctx.l10n.later)),
              FilledButton(
                onPressed: () {
                  Navigator.pop(ctx);
                  context.go('/profile');
                },
                child: Text(ctx.l10n.setUpPayouts),
              ),
            ],
          ),
        );
      } else {
        showError(context, e);
      }
    } catch (e) {
      if (context.mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(publishControllerProvider);
    final c = ref.read(publishControllerProvider.notifier);
    final theme = Theme.of(context);
    final preview = s.preview;
    final l = context.l10n;

    return Scaffold(
      appBar: AppBar(title: Text(l.offerRide)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          PlaceField(label: l.departure, icon: Icons.trip_origin, value: s.origin, onChanged: c.setOrigin),
          const SizedBox(height: 12),
          PlaceField(label: l.destination, icon: Icons.place, value: s.destination, onChanged: c.setDestination),
          const SizedBox(height: 16),
          if (s.previewLoading) const LinearProgressIndicator(),
          if (s.previewError != null)
            ErrorView(message: localizedError(l, s.previewError!), onRetry: c.retryPreview),
          if (preview != null) ...[
            RouteMap(
              route: preview.route,
              height: 200,
              pins: [
                if (s.origin != null)
                  MapPin(point: s.origin!.location, icon: Icons.trip_origin, color: AppTheme.accent),
                if (s.destination != null)
                  MapPin(point: s.destination!.location, icon: Icons.place, color: AppTheme.danger),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              l.distanceAbout(formatDistance(l, preview.distanceM), formatDuration(l, preview.durationS)),
              style: theme.textTheme.bodyMedium,
            ),
          ],
          const SizedBox(height: 20),
          Text(l.when, style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.calendar_today_outlined),
                  label: Text(formatDay(s.date)),
                  onPressed: () async {
                    final now = DateTime.now();
                    final d = await showDatePicker(
                      context: context,
                      initialDate: s.date.isBefore(now) ? now : s.date,
                      firstDate: DateTime(now.year, now.month, now.day),
                      lastDate: now.add(const Duration(days: 180)),
                    );
                    if (d != null) c.setDate(d);
                  },
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.schedule),
                  label: Text(s.time.format(context)),
                  onPressed: () async {
                    final t = await showTimePicker(context: context, initialTime: s.time);
                    if (t != null) c.setTime(t);
                  },
                ),
              ),
            ],
          ),
          if (!s.departureAt.isAfter(DateTime.now().add(const Duration(minutes: 30))))
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                l.departureTooSoon,
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          const SizedBox(height: 20),
          Row(
            children: [
              Text(l.seatsOffered, style: theme.textTheme.titleMedium),
              const Spacer(),
              IconButton(
                onPressed: s.seats > 1 ? () => c.setSeats(s.seats - 1) : null,
                icon: const Icon(Icons.remove_circle_outline),
              ),
              Text('${s.seats}', style: theme.textTheme.titleMedium),
              IconButton(
                onPressed: s.seats < 8 ? () => c.setSeats(s.seats + 1) : null,
                icon: const Icon(Icons.add_circle_outline),
              ),
            ],
          ),
          if (preview != null && s.priceCents != null) ...[
            const SizedBox(height: 12),
            Text(l.pricePerSeat, style: theme.textTheme.titleMedium),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton.filledTonal(
                  onPressed: s.priceCents! > preview.minPriceCents ? () => c.adjustPrice(-50) : null,
                  icon: const Icon(Icons.remove),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    formatMoney(s.priceCents!, 'EUR'),
                    style: theme.textTheme.displaySmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                IconButton.filledTonal(
                  onPressed: s.priceCents! < preview.maxPriceCents ? () => c.adjustPrice(50) : null,
                  icon: const Icon(Icons.add),
                ),
              ],
            ),
            Center(
              child: Text(
                s.priceCents == preview.suggestedPriceCents
                    ? l.recommendedPrice
                    : l.recommendedPriceIs(formatMoney(preview.suggestedPriceCents, 'EUR')),
                style: theme.textTheme.bodySmall,
              ),
            ),
            Center(
              child: Text(
                l.proportionalPriceNote,
                style: theme.textTheme.bodySmall,
              ),
            ),
          ],
          const SizedBox(height: 20),
          Text(l.preferences, style: theme.textTheme.titleMedium),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l.instantBooking),
            subtitle: Text(l.instantBookingSubtitle),
            value: s.preferences.instantBooking,
            onChanged: (v) => c.setPreferences(s.preferences.copyWith(instantBooking: v)),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l.prefMaxTwoBack),
            value: s.preferences.maxTwoBack,
            onChanged: (v) => c.setPreferences(s.preferences.copyWith(maxTwoBack: v)),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l.petsAllowed),
            value: s.preferences.allowPets,
            onChanged: (v) => c.setPreferences(s.preferences.copyWith(allowPets: v)),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l.smokingAllowed),
            value: s.preferences.allowSmoking,
            onChanged: (v) => c.setPreferences(s.preferences.copyWith(allowSmoking: v)),
          ),
          const SizedBox(height: 8),
          TextFormField(
            initialValue: s.vehicle,
            maxLength: 200,
            decoration: InputDecoration(labelText: l.carLabel),
            onChanged: c.setVehicle,
          ),
          const SizedBox(height: 8),
          TextFormField(
            initialValue: s.notes,
            maxLength: 2000,
            maxLines: 3,
            decoration: InputDecoration(labelText: l.notesLabel),
            onChanged: c.setNotes,
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: s.canSubmit ? () => _submit(context, ref) : null,
            child: s.submitting
                ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(l.publishRide),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}
