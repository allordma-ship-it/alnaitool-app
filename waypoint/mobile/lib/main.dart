import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import 'app_router.dart';
import 'core/l10n/l10n.dart';
import 'core/l10n/syria_dates.dart';
import 'core/theme/app_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeSyrianDateFormatting();
  runApp(const ProviderScope(child: WaneesApp()));
}

class WaneesApp extends ConsumerWidget {
  const WaneesApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final locale = ref.watch(localeProvider);
    Intl.defaultLocale = locale.languageCode;
    return MaterialApp.router(
      onGenerateTitle: (context) => context.l10n.appTitle,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      routerConfig: router,
      // Arabic first: the app is RTL by default; Flutter mirrors layouts automatically.
      locale: locale,
      supportedLocales: AppLocalizations.supportedLocales,
      // Syrian month names / digits must be applied after Material's own date data loads.
      localizationsDelegates: const [...AppLocalizations.localizationsDelegates, SyrianDatesDelegate()],
    );
  }
}
