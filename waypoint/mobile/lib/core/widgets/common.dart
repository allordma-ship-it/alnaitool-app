import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/error_text.dart';
import '../l10n/l10n.dart';
import '../models/models.dart';

/// Renders an [AsyncValue] with loading and retryable error states.
class AsyncView<T> extends StatelessWidget {
  const AsyncView({super.key, required this.value, required this.data, this.onRetry});

  final AsyncValue<T> value;
  final Widget Function(T data) data;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return value.when(
      data: data,
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => ErrorView(message: localizedError(context.l10n, e), onRetry: onRetry),
    );
  }
}

/// Centered error with optional retry.
class ErrorView extends StatelessWidget {
  const ErrorView({super.key, required this.message, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.cloud_off, size: 48, color: Theme.of(context).colorScheme.error),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              OutlinedButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: Text(context.l10n.retry)),
            ],
          ],
        ),
      ),
    );
  }
}

/// Friendly empty-state placeholder.
class EmptyView extends StatelessWidget {
  const EmptyView({super.key, required this.icon, required this.title, this.subtitle, this.action});

  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 56, color: theme.colorScheme.primary),
            const SizedBox(height: 16),
            Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
            if (subtitle != null) ...[
              const SizedBox(height: 8),
              Text(subtitle!, style: theme.textTheme.bodyMedium, textAlign: TextAlign.center),
            ],
            if (action != null) ...[const SizedBox(height: 20), action!],
          ],
        ),
      ),
    );
  }
}

/// Avatar + name + rating row for a member.
class ProfileTile extends StatelessWidget {
  const ProfileTile({super.key, required this.profile, this.trailing, this.subtitle});

  final PublicProfile profile;
  final Widget? trailing;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundImage: profile.avatarUrl != null ? NetworkImage(profile.avatarUrl!) : null,
        child: profile.avatarUrl == null ? Text(profile.firstName.characters.first.toUpperCase()) : null,
      ),
      title: Text(profile.displayName, style: theme.textTheme.titleMedium),
      subtitle: Row(
        children: [
          Icon(Icons.star_rounded, size: 18, color: theme.colorScheme.tertiary),
          const SizedBox(width: 2),
          Flexible(
            child: Text(
              profile.ratingCount == 0
                  ? context.l10n.newMember
                  : context.l10n.ratingSummary(profile.rating.toStringAsFixed(1), profile.ratingCount),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (subtitle != null) ...[const Text(' · '), Flexible(child: Text(subtitle!, overflow: TextOverflow.ellipsis))],
        ],
      ),
      trailing: trailing,
    );
  }
}

/// Status pill.
class StatusChip extends StatelessWidget {
  const StatusChip({super.key, required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(20)),
      child: Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12)),
    );
  }
}

/// Vertical departure → arrival timeline.
class TripTimeline extends StatelessWidget {
  const TripTimeline({
    super.key,
    required this.fromTime,
    required this.fromName,
    required this.toTime,
    required this.toName,
    this.middle,
  });

  final String fromTime;
  final String fromName;
  final String toTime;
  final String toName;
  final String? middle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dot = Container(
      width: 12,
      height: 12,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(color: theme.colorScheme.primary, width: 3),
      ),
    );
    Widget row(String time, String name) => Row(
          children: [
            SizedBox(width: 52, child: Text(time, style: theme.textTheme.titleSmall)),
            dot,
            const SizedBox(width: 12),
            Expanded(child: Text(name, style: theme.textTheme.titleSmall, maxLines: 2, overflow: TextOverflow.ellipsis)),
          ],
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        row(fromTime, fromName),
        Row(
          children: [
            SizedBox(
              width: 52,
              child: middle == null ? null : Text(middle!, style: theme.textTheme.labelSmall),
            ),
            Container(margin: const EdgeInsetsDirectional.only(start: 5), width: 2, height: 24, color: theme.colorScheme.primary),
          ],
        ),
        row(toTime, toName),
      ],
    );
  }
}

/// Shows a snackbar with an error message.
void showError(BuildContext context, Object error) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(localizedError(context.l10n, error)), behavior: SnackBarBehavior.floating));
}

/// Shows a snackbar with an info message.
void showInfo(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), behavior: SnackBarBehavior.floating));
}
