import 'package:intl/intl.dart';

import '../l10n/l10n.dart';

/// Formats integer minor units (cents, piastres) as a localized currency string.
///
/// The Syrian pound is written "900 ل.س" in Arabic and "900 SYP" in English;
/// intl's default symbol for SYP ("£") would be confused with sterling.
String formatMoney(int cents, String currency) {
  if (currency == 'SYP') {
    final amount = NumberFormat.decimalPattern().format((cents / 100).round());
    return Intl.getCurrentLocale().startsWith('ar') ? '$amount ل.س' : '$amount SYP';
  }
  return NumberFormat.simpleCurrency(name: currency).format(cents / 100);
}

/// "2 h 35 min" / "2 س 35 د" style duration.
String formatDuration(AppLocalizations l, int seconds) {
  final d = Duration(seconds: seconds);
  final h = d.inHours;
  final m = d.inMinutes.remainder(60);
  if (h == 0) return l.durationM(m);
  if (m == 0) return l.durationH(h);
  return l.durationHM(h, m.toString().padLeft(2, '0'));
}

/// "231 km" / "850 m" in the current language.
String formatDistance(AppLocalizations l, num meters) {
  if (meters < 1000) return l.distanceM(meters.round());
  if (meters < 10000) return l.distanceKm((meters / 1000).toStringAsFixed(1));
  return l.distanceKm('${(meters / 1000).round()}');
}

String formatTime(DateTime t) => DateFormat.Hm().format(t);

/// "الاثنين، 9 تشرين الثاني" / "Mon, Nov 9" (locale-aware order and comma).
String formatDay(DateTime t) => DateFormat.MMMEd().format(t);

String formatDateTime(DateTime t) => '${DateFormat.MMMEd().format(t)} · ${DateFormat.Hm().format(t)}';

/// Always ISO (Latin digits, fixed locale) — used for API parameters.
String formatIsoDate(DateTime t) => DateFormat('yyyy-MM-dd', 'en').format(t);
