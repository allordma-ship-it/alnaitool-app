import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

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
      if (mounted && me.payoutsEnabled) showInfo(context, 'Payouts are active');
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _editBio(Me me) async {
    final controller = TextEditingController(text: me.bio);
    final bio = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('About you'),
        content: TextField(
          controller: controller,
          maxLines: 4,
          maxLength: 1000,
          decoration: const InputDecoration(hintText: 'Chatty? Music lover? Tell travellers about yourself.'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, controller.text.trim()), child: const Text('Save')),
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
    if (me == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));

    return Scaffold(
      appBar: AppBar(title: const Text('Profile')),
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
                          Text(me.ratingCount == 0
                              ? ' No reviews yet'
                              : ' ${me.rating.toStringAsFixed(1)} · ${me.ratingCount} reviews'),
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
                title: const Text('About you'),
                subtitle: Text(me.bio.isEmpty ? 'Add a short bio' : me.bio),
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
                        Text('Payouts', style: theme.textTheme.titleMedium),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      me.payoutsEnabled
                          ? 'You can receive payments for your rides. Money is released 24 hours after each trip.'
                          : 'Add your bank details to get paid when you offer rides.',
                    ),
                    const SizedBox(height: 12),
                    if (!me.payoutsEnabled)
                      FilledButton(
                        onPressed: _busy ? null : _startOnboarding,
                        child: const Text('Set up payouts'),
                      )
                    else
                      OutlinedButton(
                        onPressed: _busy ? null : _startOnboarding,
                        child: const Text('Manage payout details'),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              icon: const Icon(Icons.logout),
              label: const Text('Log out'),
              onPressed: () => ref.read(authControllerProvider.notifier).logout(),
            ),
          ],
        ),
      ),
    );
  }
}
