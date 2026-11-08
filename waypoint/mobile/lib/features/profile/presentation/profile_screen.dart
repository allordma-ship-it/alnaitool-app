import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/l10n/l10n.dart';
import '../../../core/models/models.dart';
import '../../../core/widgets/common.dart';
import '../../auth/data/auth_repository_impl.dart';
import '../../auth/presentation/auth_controller.dart';

/// Account, payouts and sign-out.
class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> with WidgetsBindingObserver {
  bool _busy = false;
  bool _awaitingOnboarding = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Re-check payout status when returning from the hosted onboarding page.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _awaitingOnboarding) {
      _awaitingOnboarding = false;
      _refreshPayouts();
    }
  }

  Future<void> _startOnboarding() async {
    setState(() => _busy = true);
    try {
      final res = await ref.read(authRepositoryProvider).startPayoutOnboarding();
      final uri = Uri.parse(res.url);
      if (uri.scheme == 'https') {
        _awaitingOnboarding = true;
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      } else {
        await _refreshPayouts();
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshPayouts() async {
    try {
      final me = await ref.read(authRepositoryProvider).refreshPayoutStatus();
      ref.read(authControllerProvider.notifier).setMe(me);
      if (mounted && me.payoutsEnabled) showInfo(context, context.l10n.payoutsActive);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _editBio(Me me) async {
    final controller = TextEditingController(text: me.bio);
    final bio = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(ctx.l10n.aboutYou),
        content: TextField(
          controller: controller,
          maxLines: 4,
          maxLength: 1000,
          decoration: InputDecoration(hintText: ctx.l10n.bioHint),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(ctx.l10n.cancel)),
          FilledButton(onPressed: () => Navigator.pop(ctx, controller.text.trim()), child: Text(ctx.l10n.save)),
        ],
      ),
    );
    controller.dispose();
    if (bio == null) return;
    try {
      final updated = await ref.read(authRepositoryProvider).updateProfile(bio: bio);
      ref.read(authControllerProvider.notifier).setMe(updated);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = ref.watch(authControllerProvider).valueOrNull;
    final theme = Theme.of(context);
    final l = context.l10n;
    final locale = ref.watch(localeProvider);
    if (me == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));

    return Scaffold(
      appBar: AppBar(title: Text(l.profile)),
      body: RefreshIndicator(
        onRefresh: () => ref.read(authControllerProvider.notifier).reload(),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 36,
                  backgroundImage: me.avatarUrl != null ? NetworkImage(me.avatarUrl!) : null,
                  child: me.avatarUrl == null
                      ? Text(me.firstName.characters.first.toUpperCase(), style: theme.textTheme.headlineMedium)
                      : null,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(me.fullName, style: theme.textTheme.titleLarge),
                      Row(
                        children: [
                          Icon(Icons.star_rounded, size: 18, color: theme.colorScheme.tertiary),
                          const SizedBox(width: 4),
                          Text(me.ratingCount == 0
                              ? l.noReviewsYet
                              : l.ratingSummary(me.rating.toStringAsFixed(1), me.ratingCount)),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Card(
              child: ListTile(
                title: Text(l.aboutYou),
                subtitle: Text(me.bio.isEmpty ? l.addBio : me.bio),
                trailing: const Icon(Icons.edit_outlined),
                onTap: () => _editBio(me),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Column(
                children: [
                  ListTile(leading: const Icon(Icons.email_outlined), title: Text(me.email)),
                  if (me.phone != null) ListTile(leading: const Icon(Icons.phone_outlined), title: Text(me.phone!)),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          me.payoutsEnabled ? Icons.verified : Icons.account_balance_outlined,
                          color: me.payoutsEnabled ? theme.colorScheme.secondary : theme.colorScheme.primary,
                        ),
                        const SizedBox(width: 8),
                        Text(l.payouts, style: theme.textTheme.titleMedium),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(me.payoutsEnabled ? l.payoutsActiveBody : l.payoutsSetupBody),
                    const SizedBox(height: 12),
                    if (!me.payoutsEnabled)
                      FilledButton(
                        onPressed: _busy ? null : _startOnboarding,
                        child: Text(l.setUpPayouts),
                      )
                    else
                      OutlinedButton(
                        onPressed: _busy ? null : _startOnboarding,
                        child: Text(l.managePayouts),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.translate, color: theme.colorScheme.primary),
                        const SizedBox(width: 8),
                        Text(l.language, style: theme.textTheme.titleMedium),
                      ],
                    ),
                    const SizedBox(height: 12),
                    SegmentedButton<Locale>(
                      segments: [
                        for (final loc in appLocales)
                          ButtonSegment(value: loc, label: Text(localeDisplayName(loc))),
                      ],
                      selected: {locale},
                      onSelectionChanged: (s) => ref.read(localeProvider.notifier).setLocale(s.first),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              icon: const Icon(Icons.logout),
              label: Text(l.logOut),
              onPressed: () => ref.read(authControllerProvider.notifier).logout(),
            ),
          ],
        ),
      ),
    );
  }
}
