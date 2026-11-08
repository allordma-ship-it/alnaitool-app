import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../geo/geocoding_repository.dart';
import '../l10n/error_text.dart';
import '../l10n/l10n.dart';
import '../models/models.dart';

/// Full-screen place autocomplete. Pops with the selected [Place].
class PlaceSearchScreen extends ConsumerStatefulWidget {
  const PlaceSearchScreen({super.key, required this.title, this.initial});

  final String title;
  final Place? initial;

  static Future<Place?> open(BuildContext context, {required String title, Place? initial}) {
    return Navigator.of(context).push<Place>(
      MaterialPageRoute(builder: (_) => PlaceSearchScreen(title: title, initial: initial), fullscreenDialog: true),
    );
  }

  @override
  ConsumerState<PlaceSearchScreen> createState() => _PlaceSearchScreenState();
}

class _PlaceSearchScreenState extends ConsumerState<PlaceSearchScreen> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial?.name ?? '');
  Timer? _debounce;
  List<PlaceSuggestion> _results = const [];
  bool _loading = false;
  String? _error;
  int _requestSeq = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () => _search(value));
  }

  Future<void> _search(String query) async {
    final seq = ++_requestSeq;
    if (query.trim().length < 2) {
      setState(() {
        _results = const [];
        _loading = false;
        _error = null;
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final lang = Localizations.localeOf(context).languageCode;
      final results = await ref.read(geocodingRepositoryProvider).search(query, lang: lang);
      if (!mounted || seq != _requestSeq) return;
      setState(() {
        _results = results;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || seq != _requestSeq) return;
      setState(() {
        _error = localizedError(context.l10n, e);
        _loading = false;
      });
    }
  }

  IconData _iconFor(String kind) => switch (kind) {
        'city' || 'town' || 'village' || 'district' => Icons.location_city,
        'station' || 'railway' || 'bus_station' => Icons.train,
        'aerodrome' || 'airport' => Icons.flight,
        'street' || 'house' => Icons.signpost_outlined,
        _ => Icons.place_outlined,
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              controller: _controller,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onChanged: _onChanged,
              onSubmitted: _search,
              decoration: InputDecoration(
                hintText: context.l10n.placeSearchHint,
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _controller.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _controller.clear();
                          _onChanged('');
                        },
                      ),
              ),
            ),
          ),
          if (_loading) const LinearProgressIndicator(minHeight: 2),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          Expanded(
            child: ListView.separated(
              itemCount: _results.length,
              separatorBuilder: (_, __) => const Divider(height: 1, indent: 72),
              itemBuilder: (context, i) {
                final s = _results[i];
                return ListTile(
                  leading: CircleAvatar(child: Icon(_iconFor(s.kind))),
                  title: Text(s.name),
                  subtitle: Text(s.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                  onTap: () => Navigator.of(context).pop(s.toPlace()),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// A tappable field that opens [PlaceSearchScreen].
class PlaceField extends StatelessWidget {
  const PlaceField({
    super.key,
    required this.label,
    required this.icon,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final IconData icon;
  final Place? value;
  final ValueChanged<Place> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () async {
        final place = await PlaceSearchScreen.open(context, title: label, initial: value);
        if (place != null) onChanged(place);
      },
      child: InputDecorator(
        decoration: InputDecoration(prefixIcon: Icon(icon), labelText: label),
        isEmpty: value == null,
        child: Text(
          value?.name ?? '',
          style: theme.textTheme.bodyLarge,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}
