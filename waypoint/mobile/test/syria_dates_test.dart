import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:wanees/core/l10n/l10n.dart';
import 'package:wanees/core/l10n/syria_dates.dart';
import 'package:wanees/core/utils/formatters.dart';

void main() {
  setUpAll(initializeSyrianDateFormatting);

  test('Syrian month names and Western digits in Arabic', () {
    final t = DateTime(2026, 11, 9, 9, 56);
    Intl.withLocale('ar', () {
      expect(formatDay(t), 'الاثنين، 9 تشرين الثاني');
      expect(formatTime(t), '09:56');
      expect(DateFormat.yMMMMd().format(DateTime(2026, 2, 1)), contains('شباط'));
    });
  });

  testWidgets('Material date picker shows Syrian months inside the real app setup', (tester) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('ar'),
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: const [...AppLocalizations.localizationsDelegates, SyrianDatesDelegate()],
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showDatePicker(
            context: context,
            initialDate: DateTime(2026, 11, 9),
            firstDate: DateTime(2026, 1, 1),
            lastDate: DateTime(2027, 1, 1),
          ),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.textContaining('تشرين الثاني'), findsWidgets);
    expect(find.textContaining('نوفمبر'), findsNothing);
  });

  test('English is unaffected', () {
    Intl.withLocale('en', () => expect(formatDay(DateTime(2026, 11, 9)), 'Mon, Nov 9'));
  });
}
