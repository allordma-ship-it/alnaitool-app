import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:intl/date_symbol_data_custom.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/date_symbols.dart';
import 'package:intl/date_time_patterns.dart';
import 'package:intl/intl.dart';

/// Levantine (Syrian) month names. CLDR's generic Arabic uses the Egyptian/Gulf
/// names (يناير، فبراير …), which read as foreign in Syria.
const syrianMonths = [
  'كانون الثاني', 'شباط', 'آذار', 'نيسان', 'أيار', 'حزيران',
  'تموز', 'آب', 'أيلول', 'تشرين الأول', 'تشرين الثاني', 'كانون الأول',
];

/// Loads all date symbols, then applies the Syrian conventions.
Future<void> initializeSyrianDateFormatting() async {
  await initializeDateFormatting();
  applySyrianDateConventions();
}

/// Applies Syrian conventions to the Arabic date symbols:
/// * Levantine month names (also used by the Material date picker),
/// * Western digits (0-9), matching prices and phone numbers.
///
/// Must run after Flutter's Material localizations, which re-initialize the
/// default Arabic symbols when they load — see [SyrianDatesDelegate].
void applySyrianDateConventions() {
  final base = dateTimeSymbolMap()['ar'] as DateSymbols;
  final map = Map<String, dynamic>.from(base.serializeToMap());
  for (final key in ['MONTHS', 'STANDALONEMONTHS', 'SHORTMONTHS', 'STANDALONESHORTMONTHS']) {
    map[key] = syrianMonths;
  }
  initializeDateFormattingCustom(
    locale: 'ar',
    symbols: DateSymbols.deserializeFromMap(map),
    patterns: dateTimePatternMap()['ar']!,
  );
  DateFormat.useNativeDigitsByDefaultFor('ar', false);
}

/// Re-applies the Syrian conventions whenever Arabic localizations load.
/// List it *after* the Material/Cupertino delegates.
class SyrianDatesDelegate extends LocalizationsDelegate<SyrianDates> {
  const SyrianDatesDelegate();

  @override
  bool isSupported(Locale locale) => locale.languageCode == 'ar';

  @override
  Future<SyrianDates> load(Locale locale) {
    applySyrianDateConventions();
    return SynchronousFuture(const SyrianDates());
  }

  @override
  bool shouldReload(SyrianDatesDelegate old) => false;
}

/// Marker type for [SyrianDatesDelegate].
class SyrianDates {
  const SyrianDates();
}
