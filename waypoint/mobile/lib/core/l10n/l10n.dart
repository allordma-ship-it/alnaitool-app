import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../l10n/app_localizations.dart';

export '../../l10n/app_localizations.dart';

extension L10nContext on BuildContext {
  AppLocalizations get l10n => AppLocalizations.of(this);

  bool get isRtl => Directionality.of(this) == TextDirection.rtl;
}

/// Languages offered in the app. Arabic is the primary language.
const appLocales = [Locale('ar'), Locale('en')];

/// Human-readable names, written in their own language.
String localeDisplayName(Locale locale) => switch (locale.languageCode) {
      'ar' => 'العربية',
      'en' => 'English',
      _ => locale.languageCode,
    };

/// The selected UI language, persisted across launches. Defaults to Arabic.
class LocaleController extends Notifier<Locale> {
  static const _key = 'wp_locale';

  @override
  Locale build() {
    _restore();
    return appLocales.first;
  }

  Future<void> _restore() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final code = prefs.getString(_key);
      if (code != null && appLocales.any((l) => l.languageCode == code)) {
        state = Locale(code);
      }
    } catch (_) {
      // Preferences unavailable (e.g. tests): keep the default.
    }
  }

  Future<void> setLocale(Locale locale) async {
    state = locale;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, locale.languageCode);
  }
}

final localeProvider = NotifierProvider<LocaleController, Locale>(LocaleController.new);
