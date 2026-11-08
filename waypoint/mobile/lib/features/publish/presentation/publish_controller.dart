import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/models.dart';
import '../data/publish_repository_impl.dart';
import '../domain/publish_entities.dart';

/// Form state for publishing a ride.
class PublishState {
  const PublishState({
    this.origin,
    this.destination,
    this.preview,
    this.previewLoading = false,
    this.previewError,
    required this.date,
    required this.time,
    this.seats = 3,
    this.priceCents,
    this.preferences = const RidePreferences(),
    this.vehicle = '',
    this.notes = '',
    this.submitting = false,
  });

  final Place? origin;
  final Place? destination;
  final RoutePreview? preview;
  final bool previewLoading;
  final Object? previewError;
  final DateTime date;
  final TimeOfDay time;
  final int seats;
  final int? priceCents;
  final RidePreferences preferences;
  final String vehicle;
  final String notes;
  final bool submitting;

  DateTime get departureAt => DateTime(date.year, date.month, date.day, time.hour, time.minute);

  bool get canSubmit =>
      origin != null &&
      destination != null &&
      preview != null &&
      priceCents != null &&
      !submitting &&
      departureAt.isAfter(DateTime.now().add(const Duration(minutes: 30)));

  PublishState copyWith({
    Place? origin,
    Place? destination,
    RoutePreview? preview,
    bool clearPreview = false,
    bool? previewLoading,
    Object? previewError,
    bool clearPreviewError = false,
    DateTime? date,
    TimeOfDay? time,
    int? seats,
    int? priceCents,
    RidePreferences? preferences,
    String? vehicle,
    String? notes,
    bool? submitting,
  }) {
    return PublishState(
      origin: origin ?? this.origin,
      destination: destination ?? this.destination,
      preview: clearPreview ? null : (preview ?? this.preview),
      previewLoading: previewLoading ?? this.previewLoading,
      previewError: clearPreviewError ? null : (previewError ?? this.previewError),
      date: date ?? this.date,
      time: time ?? this.time,
      seats: seats ?? this.seats,
      priceCents: priceCents ?? this.priceCents,
      preferences: preferences ?? this.preferences,
      vehicle: vehicle ?? this.vehicle,
      notes: notes ?? this.notes,
      submitting: submitting ?? this.submitting,
    );
  }
}

class PublishController extends AutoDisposeNotifier<PublishState> {
  int _previewSeq = 0;

  @override
  PublishState build() {
    final tomorrow = DateTime.now().add(const Duration(days: 1));
    return PublishState(date: tomorrow, time: const TimeOfDay(hour: 9, minute: 0));
  }

  void setOrigin(Place p) {
    state = state.copyWith(origin: p);
    _loadPreview();
  }

  void setDestination(Place p) {
    state = state.copyWith(destination: p);
    _loadPreview();
  }

  void setDate(DateTime d) => state = state.copyWith(date: d);
  void setTime(TimeOfDay t) => state = state.copyWith(time: t);
  void setSeats(int s) => state = state.copyWith(seats: s.clamp(1, 8));
  void setPreferences(RidePreferences p) => state = state.copyWith(preferences: p);
  void setVehicle(String v) => state = state.copyWith(vehicle: v);
  void setNotes(String v) => state = state.copyWith(notes: v);

  /// Adjusts the price in 50-cent steps within the allowed range.
  void adjustPrice(int deltaCents) {
    final p = state.preview;
    final current = state.priceCents;
    if (p == null || current == null) return;
    final next = (current + deltaCents).clamp(p.minPriceCents, p.maxPriceCents);
    state = state.copyWith(priceCents: next);
  }

  Future<void> retryPreview() => _loadPreview();

  Future<void> _loadPreview() async {
    final o = state.origin, d = state.destination;
    if (o == null || d == null) return;
    final seq = ++_previewSeq;
    state = state.copyWith(previewLoading: true, clearPreview: true, clearPreviewError: true);
    try {
      final preview = await ref.read(publishRepositoryProvider).preview(o.location, d.location);
      if (seq != _previewSeq) return;
      state = state.copyWith(preview: preview, previewLoading: false, priceCents: preview.suggestedPriceCents);
    } catch (e) {
      if (seq != _previewSeq) return;
      state = state.copyWith(previewLoading: false, previewError: e);
    }
  }

  Future<RideDetail> submit() async {
    final s = state;
    if (!s.canSubmit) throw StateError('Form incomplete');
    state = s.copyWith(submitting: true);
    try {
      return await ref.read(publishRepositoryProvider).publish(
            PublishRideInput(
              origin: s.origin!,
              destination: s.destination!,
              departureAt: s.departureAt,
              seatsTotal: s.seats,
              pricePerSeatCents: s.priceCents!,
              preferences: s.preferences,
              vehicleDescription: s.vehicle.trim(),
              notes: s.notes.trim(),
            ),
          );
    } finally {
      state = state.copyWith(submitting: false);
    }
  }
}

final publishControllerProvider =
    AutoDisposeNotifierProvider<PublishController, PublishState>(PublishController.new);

/// The driver's rides (upcoming or past).
final myRidesProvider = FutureProvider.autoDispose.family<List<RideDetail>, bool>(
  (ref, upcoming) => ref.watch(publishRepositoryProvider).myRides(upcoming: upcoming),
);
