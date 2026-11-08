/// Compile-time configuration, supplied with `--dart-define`.
class Env {
  const Env._();

  /// Base URL of the Wanees API (without the `/v1` suffix).
  /// Android emulators reach the host machine at 10.0.2.2.
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );

  /// Raster tile template for OpenStreetMap-based maps. Use your own tile
  /// server or a commercial provider in production (OSM tile usage policy).
  static const String mapTileUrl = String.fromEnvironment(
    'MAP_TILE_URL',
    defaultValue: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  );

  /// Package identifier sent to tile servers as User-Agent.
  static const String userAgentPackage = 'sy.wanees.app';

  /// Stripe merchant display name shown in the payment sheet.
  static const String merchantName = 'Wanees';

  /// Return URL scheme used for Stripe redirects (3DS) and Connect onboarding.
  static const String urlScheme = 'wanees';
}
