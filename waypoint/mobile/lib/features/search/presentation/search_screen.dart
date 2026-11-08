import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/l10n/l10n.dart';
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
    final l = context.l10n;

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
          SliverAppBar.large(title: Text(l.searchTitle)),
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
                                    label: l.leavingFrom,
                                    icon: Icons.trip_origin,
                                    value: form.from,
                                    onChanged: ctrl.setFrom,
                                  ),
                                  const SizedBox(height: 12),
                                  PlaceField(
                                    label: l.goingTo,
                                    icon: Icons.place,
                                    value: form.to,
                                    onChanged: ctrl.setTo,
                                  ),
                                ],
                              ),
                            ),
                            IconButton(
                              tooltip: l.swap,
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
                                  decoration: InputDecoration(
                                    prefixIcon: const Icon(Icons.calendar_today_outlined),
                                    labelText: l.date,
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
                          label: Text(l.search),
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
                Text(l.howItWorks, style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                _Hint(icon: Icons.alt_route, text: l.hintMatching),
                _Hint(icon: Icons.lock_outline, text: l.hintEscrow),
                _Hint(icon: Icons.event_busy, text: l.hintCancel),
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
