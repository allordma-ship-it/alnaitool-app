import 'package:intl/intl.dart';

import '../l10n/l10n.dart';

/// Formats integer minor units (cents) as a localized currency string.
String formatMoney(int cents, String currency) {
  final f = NumberFormat.simpleCurrency(name: currency);
  return f.format(cents / 100);
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

String formatDay(DateTime t) => DateFormat('EEE, d MMM').format(t);

String formatDateTime(DateTime t) => DateFormat('EEE, d MMM · HH:mm').format(t);

/// Always ISO (Latin digits, fixed locale) — used for API parameters.
String formatIsoDate(DateTime t) => DateFormat('yyyy-MM-dd', 'en').format(t);
