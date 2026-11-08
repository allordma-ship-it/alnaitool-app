import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waypoint/core/l10n/error_text.dart';
import 'package:waypoint/core/l10n/l10n.dart';
import 'package:waypoint/core/network/api_exception.dart';
import 'package:waypoint/features/search/presentation/search_screen.dart';

Widget _app(Locale locale, Widget home) => ProviderScope(
      child: MaterialApp(
        locale: locale,
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        home: home,
      ),
    );

void main() {
  test('Arabic is the primary locale', () {
    expect(appLocales.first, const Locale('ar'));
    expect(AppLocalizations.supportedLocales, containsAll(appLocales));
  });

  testWidgets('search screen renders in Arabic, right-to-left', (tester) async {
    await tester.pumpWidget(_app(const Locale('ar'), const SearchScreen()));
    await tester.pumpAndSettle();

    expect(find.text('إلى أين تذهب؟'), findsWidgets);
    expect(find.text('الانطلاق من'), findsOneWidget);
    expect(find.text('ابحث'), findsOneWidget);
    final dir = Directionality.of(tester.element(find.byType(SearchScreen)));
    expect(dir, TextDirection.rtl);
  });

  testWidgets('search screen renders in English, left-to-right', (tester) async {
    await tester.pumpWidget(_app(const Locale('en'), const SearchScreen()));
    await tester.pumpAndSettle();

    expect(find.text('Where are you going?'), findsWidgets);
    final dir = Directionality.of(tester.element(find.byType(SearchScreen)));
    expect(dir, TextDirection.ltr);
  });

  test('server errors are translated by code and field', () {
    final ar = lookupAppLocalizations(const Locale('ar'));
    expect(
      localizedError(ar, const ApiException(code: 'no_seats', message: 'x', statusCode: 409)),
      'لا توجد مقاعد كافية على هذا الجزء من الطريق.',
    );
    expect(
      localizedError(
        ar,
        const ApiException(code: 'validation_failed', message: 'Email is already registered', field: 'email'),
      ),
      'هذا البريد الإلكتروني مسجّل بالفعل.',
    );
    expect(
      localizedError(ar, const ApiException(code: 'offline', message: 'x')),
      'لا يوجد اتصال بالخادم. تحقق من اتصالك بالإنترنت.',
    );
    expect(localizedError(ar, StateError('boom')), 'حدث خطأ ما. حاول مرة أخرى.');
  });
}
