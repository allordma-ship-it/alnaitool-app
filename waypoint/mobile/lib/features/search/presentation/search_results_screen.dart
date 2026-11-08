import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/l10n/l10n.dart';
import '../../../core/l10n/labels.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/common.dart';
import '../domain/search_entities.dart';
import 'search_providers.dart';

/// Ranked list of rides matching a search.
class SearchResultsScreen extends ConsumerStatefulWidget {
  const SearchResultsScreen({super.key, required this.query});

  final SearchQuery query;

  @override
  ConsumerState<SearchResultsScreen> createState() => _SearchResultsScreenState();
}

class _SearchResultsScreenState extends ConsumerState<SearchResultsScreen> {
  late SearchQuery _query = widget.query;

  @override
  Widget build(BuildContext context) {
    final results = ref.watch(searchResultsProvider(_query));
    final theme = Theme.of(context);
    final l = context.l10n;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l.routeFromTo(_query.from.name, _query.to.name), overflow: TextOverflow.ellipsis),
            Text(
              '${formatDay(_query.date)} · ${l.passengersCount(_query.seats)}',
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          SizedBox(
            height: 56,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              children: [
                for (final s in SearchSort.values)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(s.label(l)),
                      selected: _query.sort == s,
                      onSelected: (_) => setState(() => _query = _query.copyWith(sort: s)),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () => ref.refresh(searchResultsProvider(_query).future),
              child: AsyncView(
                value: results,
                onRetry: () => ref.invalidate(searchResultsProvider(_query)),
                data: (matches) {
                  if (matches.isEmpty) {
                    return ListView(
                      children: [
                        const SizedBox(height: 80),
                        EmptyView(
                          icon: Icons.directions_car_outlined,
                          title: l.noRidesTitle,
                          subtitle: l.noRidesSubtitle,
                          action: OutlinedButton(
                            onPressed: () => setState(
                              () => _query = _query.copyWith(date: _query.date.add(const Duration(days: 1))),
                            ),
                            child: Text(l.searchNextDay),
                          ),
                        ),
                      ],
                    );
                  }
                  return ListView.separated(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                    itemCount: matches.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 12),
                    itemBuilder: (context, i) => _RideMatchCard(
                      match: matches[i],
                      query: _query,
                      onTap: () => context.push(
                        '/rides/${matches[i].ride.id}',
                        extra: RideDetailArgs(rideId: matches[i].ride.id, match: matches[i], query: _query),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _RideMatchCard extends StatelessWidget {
  const _RideMatchCard({required this.match, required this.query, required this.onTap});

  final RideMatch match;
  final SearchQuery query;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l = context.l10n;
    final seg = match.segment;
    final duration = seg.dropoffEta.difference(seg.pickupEta).inSeconds;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: TripTimeline(
                      fromTime: formatTime(seg.pickupEta),
                      // The passenger's own segment; the driver's full trip is shown below.
                      fromName: query.from.name,
                      toTime: formatTime(seg.dropoffEta),
                      toName: query.to.name,
                      middle: formatDuration(l, duration),
                    ),
                  ),
                  Text(
                    formatMoney(match.pricePerSeatCents, match.ride.currency),
                    style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                l.driverRoute(l.routeFromTo(match.ride.origin.name, match.ride.destination.name)),
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 4),
              Wrap(
                spacing: 12,
                runSpacing: 4,
                children: [
                  _Detour(icon: Icons.directions_walk, label: l.toPickup(formatDistance(l, seg.pickupDistanceM))),
                  _Detour(icon: Icons.flag_outlined, label: l.fromDropoff(formatDistance(l, seg.dropoffDistanceM))),
                ],
              ),
              const Divider(height: 24),
              ProfileTile(
                profile: match.driver,
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (match.ride.preferences.instantBooking)
                      Tooltip(
                        message: l.instantBooking,
                        child: Icon(Icons.bolt, color: theme.colorScheme.secondary),
                      ),
                    const SizedBox(width: 4),
                    Text(l.seatsLeft(match.seatsAvailable)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Detour extends StatelessWidget {
  const _Detour({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.bodySmall;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [Icon(icon, size: 16), const SizedBox(width: 4), Text(label, style: style)],
    );
  }
}
