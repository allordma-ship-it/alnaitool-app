import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config/server_url.dart';
import '../l10n/l10n.dart';

/// Lets the user point the app at a different server and test the connection.
Future<void> showServerSettings(BuildContext context) {
  return showDialog<void>(context: context, builder: (_) => const _ServerSettingsDialog());
}

class _ServerSettingsDialog extends ConsumerStatefulWidget {
  const _ServerSettingsDialog();

  @override
  ConsumerState<_ServerSettingsDialog> createState() => _ServerSettingsDialogState();
}

class _ServerSettingsDialogState extends ConsumerState<_ServerSettingsDialog> {
  late final TextEditingController _url = TextEditingController(text: ref.read(serverUrlProvider));
  bool _testing = false;
  bool? _ok;

  @override
  void dispose() {
    _url.dispose();
    super.dispose();
  }

  Future<void> _test() async {
    setState(() {
      _testing = true;
      _ok = null;
    });
    final ok = await checkServer(_url.text);
    if (mounted) {
      setState(() {
        _testing = false;
        _ok = ok;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(l.serverAddress),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _url,
            keyboardType: TextInputType.url,
            textDirection: TextDirection.ltr,
            autocorrect: false,
            decoration: InputDecoration(hintText: l.serverAddressHint),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: _testing ? null : _test,
            icon: _testing
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.wifi_tethering),
            label: Text(l.testConnection),
          ),
          if (_ok != null) ...[
            const SizedBox(height: 8),
            Text(
              _ok! ? l.connectionOk : l.connectionFailed,
              style: TextStyle(color: _ok! ? scheme.secondary : scheme.error),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l.cancel)),
        FilledButton(
          onPressed: () async {
            await ref.read(serverUrlProvider.notifier).set(_url.text);
            if (context.mounted) Navigator.pop(context);
          },
          child: Text(l.save),
        ),
      ],
    );
  }
}
