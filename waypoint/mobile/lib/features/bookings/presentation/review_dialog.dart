import 'package:flutter/material.dart';

/// Result of the review dialog.
class ReviewInput {
  const ReviewInput(this.rating, this.comment);

  final int rating;
  final String comment;
}

/// Asks for a 1–5 star rating and optional comment.
Future<ReviewInput?> showReviewDialog(BuildContext context, {required String name}) {
  return showDialog<ReviewInput>(context: context, builder: (_) => _ReviewDialog(name: name));
}

class _ReviewDialog extends StatefulWidget {
  const _ReviewDialog({required this.name});

  final String name;

  @override
  State<_ReviewDialog> createState() => _ReviewDialogState();
}

class _ReviewDialogState extends State<_ReviewDialog> {
  int _rating = 5;
  final _comment = TextEditingController();

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.tertiary;
    return AlertDialog(
      title: Text('Rate ${widget.name}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              for (var i = 1; i <= 5; i++)
                IconButton(
                  iconSize: 36,
                  onPressed: () => setState(() => _rating = i),
                  icon: Icon(i <= _rating ? Icons.star_rounded : Icons.star_outline_rounded, color: color),
                ),
            ],
          ),
          TextField(
            controller: _comment,
            maxLength: 1000,
            maxLines: 3,
            decoration: const InputDecoration(labelText: 'Comment (optional)'),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: () => Navigator.pop(context, ReviewInput(_rating, _comment.text.trim())),
          child: const Text('Submit'),
        ),
      ],
    );
  }
}

/// Generic confirmation dialog.
Future<bool> confirmAction(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
  bool destructive = false,
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Back')),
        FilledButton(
          style: destructive ? FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error) : null,
          onPressed: () => Navigator.pop(ctx, true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return result ?? false;
}
