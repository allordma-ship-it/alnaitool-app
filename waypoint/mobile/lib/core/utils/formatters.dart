import 'package:intl/intl.dart';

/// Formats integer minor units (cents) as a localized currency string.
String formatMoney(int cents, String currency) {
  final f = NumberFormat.simpleCurrency(name: currency);
  return f.format(cents / 100);
}

/// "2 h 35 min" style duration.
String formatDuration(int seconds) {
  final d = Duration(seconds: seconds);
  final h = d.inHours;
  final m = d.inMinutes.remainder(60);
  if (h == 0) return '$m min';
  if (m == 0) return '$h h';
  return '$h h ${m.toString().padLeft(2, '0')} min';
}

/// "231 km" / "850 m".
String formatDistance(num meters) {
  if (meters < 1000) return '${meters.round()} m';
  if (meters < 10000) return '${(meters / 1000).toStringAsFixed(1)} km';
  return '${(meters / 1000).round()} km';
}

String formatTime(DateTime t) => DateFormat.Hm().format(t);

String formatDay(DateTime t) => DateFormat('EEE, d MMM').format(t);

String formatDateTime(DateTime t) => DateFormat('EEE, d MMM · HH:mm').format(t);

String formatIsoDate(DateTime t) => DateFormat('yyyy-MM-dd').format(t);
