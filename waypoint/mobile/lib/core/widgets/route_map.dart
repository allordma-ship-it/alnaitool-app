import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../config/env.dart';

/// A labelled map pin.
class MapPin {
  const MapPin({required this.point, required this.icon, required this.color, this.tooltip});

  final LatLng point;
  final IconData icon;
  final Color color;
  final String? tooltip;
}

/// OpenStreetMap view showing a driving route, an optional highlighted
/// passenger segment, and pins. The camera fits all content.
class RouteMap extends StatelessWidget {
  const RouteMap({
    super.key,
    required this.route,
    this.highlight = const [],
    this.pins = const [],
    this.height = 240,
    this.interactive = true,
  });

  final List<LatLng> route;
  final List<LatLng> highlight;
  final List<MapPin> pins;
  final double height;
  final bool interactive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final allPoints = [...route, ...pins.map((p) => p.point)];
    if (allPoints.isEmpty) {
      return SizedBox(height: height, child: const Center(child: Icon(Icons.map_outlined, size: 48)));
    }
    final camera = allPoints.length == 1
        ? null
        : CameraFit.bounds(bounds: LatLngBounds.fromPoints(allPoints), padding: const EdgeInsets.all(36));

    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: SizedBox(
        height: height,
        child: FlutterMap(
          options: MapOptions(
            initialCenter: allPoints.first,
            initialZoom: 11,
            initialCameraFit: camera,
            interactionOptions: InteractionOptions(
              flags: interactive ? InteractiveFlag.all & ~InteractiveFlag.rotate : InteractiveFlag.none,
            ),
          ),
          children: [
            TileLayer(
              urlTemplate: Env.mapTileUrl,
              userAgentPackageName: Env.userAgentPackage,
              maxNativeZoom: 19,
            ),
            if (route.length > 1)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: route,
                    strokeWidth: 5,
                    color: highlight.isEmpty ? scheme.primary : scheme.primary.withValues(alpha: 0.35),
                  ),
                  if (highlight.length > 1) Polyline(points: highlight, strokeWidth: 6, color: scheme.primary),
                ],
              ),
            MarkerLayer(
              markers: [
                for (final pin in pins)
                  Marker(
                    point: pin.point,
                    width: 40,
                    height: 40,
                    alignment: Alignment.topCenter,
                    child: Tooltip(
                      message: pin.tooltip ?? '',
                      child: Icon(pin.icon, color: pin.color, size: 36),
                    ),
                  ),
              ],
            ),
            RichAttributionWidget(
              attributions: [
                TextSourceAttribution(
                  'OpenStreetMap contributors',
                  onTap: () => launchUrl(Uri.parse('https://openstreetmap.org/copyright')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Extracts the part of [route] between two route fractions (0..1 by vertex length).
List<LatLng> sliceRoute(List<LatLng> route, double from, double to) {
  if (route.length < 2) return const [];
  const distance = Distance();
  final cumulative = <double>[0];
  for (var i = 1; i < route.length; i++) {
    cumulative.add(cumulative.last + distance(route[i - 1], route[i]));
  }
  final total = cumulative.last;
  if (total == 0) return const [];
  final start = from.clamp(0, 1) * total;
  final end = to.clamp(0, 1) * total;
  final out = <LatLng>[];
  for (var i = 0; i < route.length; i++) {
    if (cumulative[i] >= start && cumulative[i] <= end) out.add(route[i]);
  }
  return out;
}
