import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/utils/formatters.dart';
import '../../../core/widgets/place_search_screen.dart';
import '../domain/search_entities.dart';
import 'search_providers.dart';

/// Passenger search form: from, to, date and seats.
class SearchScreen extends ConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final form = ref.watch(searchFormProvider);
    final ctrl = ref.read(searchFormProvider.notifier);
    final theme = Theme.of(context);

    Future<void> pickDate() async {
      final now = DateTime.now();
      final picked = await showDatePicker(
        context: context,
        initialDate: form.date.isBefore(now) ? now : form.date,
        firstDate: DateTime(now.year, now.month, now.day),
        lastDate: now.add(const Duration(days: 180)),
      );
      if (picked != null) ctrl.setDate(picked);
    }

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          const SliverAppBar.large(title: Text('Where are you going?')),
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: SliverList.list(
              children: [
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Column(
                                children: [
                                  PlaceField(
                                    label: 'Leaving from',
                                    icon: Icons.trip_origin,
                                    value: form.from,
                                    onChanged: ctrl.setFrom,
                                  ),
                                  const SizedBox(height: 12),
                                  PlaceField(
                                    label: 'Going to',
                                    icon: Icons.place,
                                    value: form.to,
                                    onChanged: ctrl.setTo,
                                  ),
                                ],
                              ),
                            ),
                            IconButton(
                              tooltip: 'Swap',
                              onPressed: ctrl.swap,
                              icon: const Icon(Icons.swap_vert),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: InkWell(
                                borderRadius: BorderRadius.circular(12),
                                onTap: pickDate,
                                child: InputDecorator(
                                  decoration: const InputDecoration(
                                    prefixIcon: Icon(Icons.calendar_today_outlined),
                                    labelText: 'Date',
                                  ),
                                  child: Text(formatDay(form.date)),
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            _SeatStepper(value: form.seats, onChanged: ctrl.setSeats),
                          ],
                        ),
                        const SizedBox(height: 20),
                        FilledButton.icon(
                          icon: const Icon(Icons.search),
                          label: const Text('Search'),
                          onPressed: form.isComplete
                              ? () => context.push(
                                    '/search/results',
                                    extra: SearchQuery(
                                      from: form.from!,
                                      to: form.to!,
                                      date: form.date,
                                      seats: form.seats,
                                    ),
                                  )
                              : null,
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                Text('How it works', style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                const _Hint(
                  icon: Icons.alt_route,
                  text: 'We match you with drivers whose route passes near your start and destination — '
                      'even if they travel further.',
                ),
                const _Hint(
                  icon: Icons.lock_outline,
                  text: 'Your payment is held securely and only released to the driver after the trip.',
                ),
                const _Hint(
                  icon: Icons.event_busy,
                  text: 'Free cancellation until 24 hours before pickup.',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon, color: Theme.of(context).colorScheme.primary),
      title: Text(text),
    );
  }
}

class _SeatStepper extends StatelessWidget {
  const _SeatStepper({required this.value, required this.onChanged});

  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: const Icon(Icons.remove),
            onPressed: value > 1 ? () => onChanged(value - 1) : null,
          ),
          Row(
            children: [
              const Icon(Icons.person_outline, size: 18),
              Text('$value', style: Theme.of(context).textTheme.titleMedium),
            ],
          ),
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: value < 8 ? () => onChanged(value + 1) : null,
          ),
        ],
      ),
    );
  }
}
