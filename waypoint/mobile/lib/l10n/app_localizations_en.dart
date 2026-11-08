// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Wanees';

  @override
  String get tagline =>
      'Your companion on the road — share the ride, split the cost.';

  @override
  String get phoneHint => '+963 9XX XXX XXX';

  @override
  String get hintCash =>
      'Pay the driver in cash during the trip — no card needed.';

  @override
  String get hintCancelCash =>
      'Free cancellation — please cancel early if your plans change.';

  @override
  String get feeNoticeCash => 'You pay the driver in cash during the trip.';

  @override
  String payCashDue(String amount) {
    return 'Pay $amount in cash to the driver.';
  }

  @override
  String payCashPaid(String amount) {
    return 'Paid $amount in cash.';
  }

  @override
  String get payCashReleased => 'Reservation released.';

  @override
  String get cancelFreeBodyCash =>
      'You can cancel for free. Please cancel early so someone else can take the seat.';

  @override
  String get tripConfirmedThanks => 'Thanks for confirming the trip!';

  @override
  String get completeRideCash => 'Complete ride';

  @override
  String get cashPaymentTitle => 'Cash payment';

  @override
  String get cashPaymentBody =>
      'Passengers pay you in cash during the trip. No bank account needed.';

  @override
  String get retry => 'Retry';

  @override
  String get cancel => 'Cancel';

  @override
  String get back => 'Back';

  @override
  String get save => 'Save';

  @override
  String get submit => 'Submit';

  @override
  String get later => 'Later';

  @override
  String get required => 'Required';

  @override
  String get language => 'Language';

  @override
  String get firstName => 'First name';

  @override
  String get lastName => 'Last name';

  @override
  String get phoneOptional => 'Phone (optional)';

  @override
  String get email => 'Email';

  @override
  String get password => 'Password';

  @override
  String get invalidEmail => 'Enter a valid email';

  @override
  String get passwordTooShort => 'At least 8 characters';

  @override
  String get createAccount => 'Create account';

  @override
  String get logIn => 'Log in';

  @override
  String get haveAccount => 'Already a member? Log in';

  @override
  String get noAccount => 'New here? Create an account';

  @override
  String get navSearch => 'Search';

  @override
  String get navPublish => 'Publish';

  @override
  String get navTrips => 'Trips';

  @override
  String get navProfile => 'Profile';

  @override
  String get searchTitle => 'Where are you going?';

  @override
  String get leavingFrom => 'Leaving from';

  @override
  String get goingTo => 'Going to';

  @override
  String get swap => 'Swap';

  @override
  String get date => 'Date';

  @override
  String get search => 'Search';

  @override
  String get howItWorks => 'How it works';

  @override
  String get hintMatching =>
      'We match you with drivers whose route passes near your start and destination — even if they travel further.';

  @override
  String get hintEscrow =>
      'Your payment is held securely and only released to the driver after the trip.';

  @override
  String get hintCancel => 'Free cancellation until 24 hours before pickup.';

  @override
  String get placeSearchHint => 'City, address or station';

  @override
  String routeFromTo(String from, String to) {
    return '$from → $to';
  }

  @override
  String passengersCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count passengers',
      one: '1 passenger',
    );
    return '$_temp0';
  }

  @override
  String get sortEarliest => 'Earliest';

  @override
  String get sortCheapest => 'Cheapest';

  @override
  String get sortClosest => 'Closest';

  @override
  String get noRidesTitle => 'No rides yet for this day';

  @override
  String get noRidesSubtitle => 'Try another date or a nearby city.';

  @override
  String get searchNextDay => 'Search the next day';

  @override
  String toPickup(String distance) {
    return '$distance to pickup';
  }

  @override
  String fromDropoff(String distance) {
    return '$distance from drop-off';
  }

  @override
  String seatsLeft(int count) {
    return '$count left';
  }

  @override
  String get rideDetails => 'Ride details';

  @override
  String driverTravels(
      String origin, String destination, String pickup, String dropoff) {
    return 'Driver travels $origin → $destination. Meeting point $pickup from your start, drop-off $dropoff from your destination.';
  }

  @override
  String get pickup => 'Pickup';

  @override
  String get dropoff => 'Drop-off';

  @override
  String get instantBooking => 'Instant booking';

  @override
  String get prefApproval => 'Driver approves requests';

  @override
  String get prefMaxTwoBack => 'Max. 2 in the back';

  @override
  String get prefThreeBack => '3 in the back';

  @override
  String get prefPets => 'Pets welcome';

  @override
  String get prefNoPets => 'No pets';

  @override
  String get prefSmoking => 'Smoking allowed';

  @override
  String get prefNoSmoking => 'No smoking';

  @override
  String get ownRide => 'This is your ride';

  @override
  String get rideWasCancelled => 'This ride was cancelled';

  @override
  String get rideFull => 'This ride is full';

  @override
  String get seats => 'Seats';

  @override
  String seatsTimesPrice(int seats, String price) {
    return '$seats × $price';
  }

  @override
  String get feeNotice =>
      'A service fee is added at checkout. Your card is only charged after the trip.';

  @override
  String get messageToDriver => 'Message to the driver (optional)';

  @override
  String get book => 'Book';

  @override
  String get requestToBook => 'Request to book';

  @override
  String get offerRide => 'Offer a ride';

  @override
  String get departure => 'Departure';

  @override
  String get destination => 'Destination';

  @override
  String distanceAbout(String distance, String duration) {
    return '$distance · about $duration';
  }

  @override
  String get when => 'When?';

  @override
  String get departureTooSoon =>
      'Departure must be at least 30 minutes from now.';

  @override
  String get seatsOffered => 'Seats offered';

  @override
  String get pricePerSeat => 'Price per seat';

  @override
  String get recommendedPrice =>
      'Recommended price — fair cost sharing for this distance';

  @override
  String recommendedPriceIs(String price) {
    return 'Recommended: $price';
  }

  @override
  String get proportionalPriceNote =>
      'Passengers on part of your route pay a proportional price.';

  @override
  String get preferences => 'Preferences';

  @override
  String get instantBookingSubtitle =>
      'Passengers book without waiting for your approval';

  @override
  String get petsAllowed => 'Pets allowed';

  @override
  String get smokingAllowed => 'Smoking allowed';

  @override
  String get carLabel => 'Car (e.g. blue VW Golf)';

  @override
  String get notesLabel => 'Anything passengers should know?';

  @override
  String get publishRide => 'Publish ride';

  @override
  String get ridePublished => 'Your ride is published!';

  @override
  String get payoutsFirstTitle => 'Set up payouts first';

  @override
  String get payoutsFirstBody =>
      'To receive money from passengers, add your payout details. It only takes a few minutes.';

  @override
  String get setUpPayouts => 'Set up payouts';

  @override
  String get yourTrips => 'Your trips';

  @override
  String get asPassenger => 'As passenger';

  @override
  String get asDriver => 'As driver';

  @override
  String get noBookings => 'No bookings yet';

  @override
  String get noBookingsSub => 'Find a ride and travel together.';

  @override
  String get findRide => 'Find a ride';

  @override
  String get upcoming => 'Upcoming';

  @override
  String get history => 'History';

  @override
  String get noRidesOffered => 'No rides offered';

  @override
  String get noRidesOfferedSub =>
      'Driving somewhere? Share your empty seats and your costs.';

  @override
  String withDriver(String name) {
    return 'with $name';
  }

  @override
  String get rideScheduled => 'Scheduled';

  @override
  String get rideOnTheRoad => 'On the road';

  @override
  String get rideCompleted => 'Completed';

  @override
  String get rideCancelled => 'Cancelled';

  @override
  String seatsBookedFree(int booked, int free) {
    return '$booked booked · $free free';
  }

  @override
  String requestsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count requests',
      one: '1 request',
    );
    return '$_temp0';
  }

  @override
  String get bsPaymentPending => 'Awaiting payment';

  @override
  String get bsRequested => 'Awaiting driver';

  @override
  String get bsConfirmed => 'Confirmed';

  @override
  String get bsRejected => 'Declined';

  @override
  String get bsCancelled => 'Cancelled';

  @override
  String get bsCancelledByDriver => 'Cancelled by driver';

  @override
  String get bsExpired => 'Expired';

  @override
  String get bsCompleted => 'Completed';

  @override
  String get yourBooking => 'Your booking';

  @override
  String seatsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count seats',
      one: '1 seat',
    );
    return '$_temp0';
  }

  @override
  String get serviceFee => 'Service fee';

  @override
  String get total => 'Total';

  @override
  String get payPending => 'Waiting for your payment authorization.';

  @override
  String payAuthorized(String amount) {
    return '$amount is reserved on your card and released to the driver after the trip.';
  }

  @override
  String payCaptured(String amount) {
    return 'Charged $amount.';
  }

  @override
  String get payVoided =>
      'The hold on your card was released — you were not charged.';

  @override
  String payRefunded(String amount) {
    return 'Refunded $amount.';
  }

  @override
  String get payFailed =>
      'Your card was declined. Please try another payment method.';

  @override
  String get completePayment => 'Complete payment';

  @override
  String get paymentAuthorized => 'Payment authorized';

  @override
  String get refreshPaymentStatus => 'Refresh payment status';

  @override
  String get confirmTrip => 'Confirm the trip took place';

  @override
  String get driverPaid => 'Thanks! The driver has been paid.';

  @override
  String rateName(String name) {
    return 'Rate $name';
  }

  @override
  String get cancelBooking => 'Cancel booking';

  @override
  String get cancelBookingTitle => 'Cancel this booking?';

  @override
  String get cancelLateBody =>
      'Your pickup is less than 24 hours away. The driver will receive part of the seat price as compensation; the rest of the hold is released.';

  @override
  String get cancelFreeBody => 'Your payment hold will be released in full.';

  @override
  String get bookingCancelled => 'Booking cancelled';

  @override
  String get thanksReview => 'Thanks for your review!';

  @override
  String get commentOptional => 'Comment (optional)';

  @override
  String get yourRide => 'Your ride';

  @override
  String get passengers => 'Passengers';

  @override
  String get noPassengers => 'No passengers yet.';

  @override
  String pricePerSeatInfo(String price, int free, int total) {
    return '$price per seat · $free/$total seats free';
  }

  @override
  String pickupYouReceive(String time, String amount) {
    return 'Pickup $time · you receive $amount';
  }

  @override
  String get decline => 'Decline';

  @override
  String get accept => 'Accept';

  @override
  String get requestDeclined => 'Request declined';

  @override
  String get passengerConfirmed => 'Passenger confirmed';

  @override
  String removePassengerTitle(String name) {
    return 'Remove $name?';
  }

  @override
  String get removePassengerBody =>
      'Their booking will be cancelled and their payment hold released.';

  @override
  String get remove => 'Remove';

  @override
  String get removePassenger => 'Remove passenger';

  @override
  String get passengerRemoved => 'Passenger removed';

  @override
  String get reviewSaved => 'Review saved';

  @override
  String get startRide => 'Start ride';

  @override
  String get safeTrip => 'Have a safe trip!';

  @override
  String get completeRide => 'Complete ride & get paid';

  @override
  String get rideCompletedMsg => 'Ride completed';

  @override
  String get cancelRide => 'Cancel ride';

  @override
  String get cancelRideTitle => 'Cancel this ride?';

  @override
  String get cancelRideBody =>
      'All passengers will be notified and their payment holds released.';

  @override
  String get rideCancelledMsg => 'Ride cancelled';

  @override
  String get profile => 'Profile';

  @override
  String get noReviewsYet => 'No reviews yet';

  @override
  String get newMember => 'New member';

  @override
  String ratingSummary(String rating, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count reviews',
      one: '1 review',
    );
    return '$rating · $_temp0';
  }

  @override
  String get aboutYou => 'About you';

  @override
  String get addBio => 'Add a short bio';

  @override
  String get bioHint => 'Chatty? Music lover? Tell travellers about yourself.';

  @override
  String get payouts => 'Payouts';

  @override
  String get payoutsActiveBody =>
      'You can receive payments for your rides. Money is released 24 hours after each trip.';

  @override
  String get payoutsSetupBody =>
      'Add your bank details to get paid when you offer rides.';

  @override
  String get managePayouts => 'Manage payout details';

  @override
  String get payoutsActive => 'Payouts are active';

  @override
  String get logOut => 'Log out';

  @override
  String durationHM(int h, String m) {
    return '$h h $m min';
  }

  @override
  String durationH(int h) {
    return '$h h';
  }

  @override
  String durationM(int m) {
    return '$m min';
  }

  @override
  String distanceKm(String value) {
    return '$value km';
  }

  @override
  String distanceM(int value) {
    return '$value m';
  }

  @override
  String get errOffline =>
      'No connection to the server. Check your internet connection.';

  @override
  String get errTimeout =>
      'The server took too long to respond. Please try again.';

  @override
  String get errUnauthorized => 'Invalid email or password.';

  @override
  String get errNoSeats =>
      'Not enough seats available on this part of the route.';

  @override
  String get errAlreadyBooked =>
      'You already have an active booking on this ride.';

  @override
  String get errAlreadyReviewed => 'You already reviewed this trip.';

  @override
  String get errConflict => 'This action conflicts with existing data.';

  @override
  String get errInvalidState => 'This action is no longer possible.';

  @override
  String get errPaymentRequired => 'Payment setup is required first.';

  @override
  String get errUpstream =>
      'A service is temporarily unavailable. Please try again.';

  @override
  String get errNotFound => 'Not found.';

  @override
  String get errForbidden => 'You are not allowed to do this.';

  @override
  String get errRateLimited => 'Too many requests. Please wait a moment.';

  @override
  String get errEmailTaken => 'This email is already registered.';

  @override
  String get errNoRoute => 'No drivable route was found between these places.';

  @override
  String get errOwnRide => 'You cannot book your own ride.';

  @override
  String get errRidePassed => 'This ride has already passed your pickup point.';

  @override
  String get errTooFar => 'This place is too far from the driver\'s route.';

  @override
  String get errPrice =>
      'The price is outside the allowed range for this distance.';

  @override
  String get errDeparture => 'Please choose a valid departure time.';

  @override
  String errValidation(String field) {
    return 'Please check: $field';
  }

  @override
  String get errGeneric => 'Something went wrong. Please try again.';
}
