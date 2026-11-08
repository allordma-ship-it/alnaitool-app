import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/l10n/l10n.dart';
import 'core/models/models.dart';
import 'features/auth/presentation/auth_controller.dart';
import 'features/auth/presentation/login_screen.dart';
import 'features/bookings/presentation/booking_detail_screen.dart';
import 'features/bookings/presentation/driver_ride_screen.dart';
import 'features/bookings/presentation/trips_screen.dart';
import 'features/profile/presentation/profile_screen.dart';
import 'features/publish/presentation/publish_screen.dart';
import 'features/search/domain/search_entities.dart';
import 'features/search/presentation/ride_detail_screen.dart';
import 'features/search/presentation/search_results_screen.dart';
import 'features/search/presentation/search_screen.dart';

final _rootKey = GlobalKey<NavigatorState>(debugLabel: 'root');

/// App navigation with an auth guard and a bottom-tab shell.
final routerProvider = Provider<GoRouter>((ref) {
  final auth = ValueNotifier<AsyncValue<Me?>>(const AsyncLoading());
  ref.listen<AsyncValue<Me?>>(authControllerProvider, (_, next) => auth.value = next, fireImmediately: true);
  ref.onDispose(auth.dispose);

  return GoRouter(
    navigatorKey: _rootKey,
    initialLocation: '/search',
    refreshListenable: auth,
    redirect: (context, state) {
      final value = auth.value;
      final atSplash = state.matchedLocation == '/splash';
      final atLogin = state.matchedLocation == '/login';
      if (value.isLoading && !value.hasValue) return atSplash ? null : '/splash';
      final signedIn = value.valueOrNull != null;
      if (!signedIn) return atLogin ? null : '/login';
      if (atLogin || atSplash) return '/search';
      return null;
    },
    routes: [
      GoRoute(path: '/splash', builder: (_, __) => const _SplashScreen()),
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => _HomeShell(shell: shell),
        branches: [
          StatefulShellBranch(routes: [GoRoute(path: '/search', builder: (_, __) => const SearchScreen())]),
          StatefulShellBranch(routes: [GoRoute(path: '/publish', builder: (_, __) => const PublishScreen())]),
          StatefulShellBranch(routes: [GoRoute(path: '/trips', builder: (_, __) => const TripsScreen())]),
          StatefulShellBranch(routes: [GoRoute(path: '/profile', builder: (_, __) => const ProfileScreen())]),
        ],
      ),
      GoRoute(
        path: '/search/results',
        parentNavigatorKey: _rootKey,
        redirect: (_, state) => state.extra is SearchQuery ? null : '/search',
        builder: (_, state) => SearchResultsScreen(query: state.extra! as SearchQuery),
      ),
      GoRoute(
        path: '/rides/:id',
        parentNavigatorKey: _rootKey,
        builder: (_, state) {
          final extra = state.extra;
          return RideDetailScreen(
            args: extra is RideDetailArgs ? extra : RideDetailArgs(rideId: state.pathParameters['id']!),
          );
        },
      ),
      GoRoute(
        path: '/bookings/:id',
        parentNavigatorKey: _rootKey,
        builder: (_, state) => BookingDetailScreen(bookingId: state.pathParameters['id']!),
      ),
      GoRoute(
        path: '/driver/rides/:id',
        parentNavigatorKey: _rootKey,
        builder: (_, state) => DriverRideScreen(rideId: state.pathParameters['id']!),
      ),
    ],
  );
});

class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) => const Scaffold(body: Center(child: CircularProgressIndicator()));
}

class _HomeShell extends StatelessWidget {
  const _HomeShell({required this.shell});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.search), label: context.l10n.navSearch),
          NavigationDestination(
            icon: const Icon(Icons.add_circle_outline),
            selectedIcon: const Icon(Icons.add_circle),
            label: context.l10n.navPublish,
          ),
          NavigationDestination(
            icon: const Icon(Icons.commute_outlined),
            selectedIcon: const Icon(Icons.commute),
            label: context.l10n.navTrips,
          ),
          NavigationDestination(
            icon: const Icon(Icons.person_outline),
            selectedIcon: const Icon(Icons.person),
            label: context.l10n.navProfile,
          ),
        ],
      ),
    );
  }
}
