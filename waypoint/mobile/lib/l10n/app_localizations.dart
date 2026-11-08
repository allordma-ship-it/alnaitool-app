import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_ar.dart';
import 'app_localizations_en.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
      : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
    delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
  ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('ar'),
    Locale('en')
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Wanees'**
  String get appTitle;

  /// No description provided for @tagline.
  ///
  /// In en, this message translates to:
  /// **'Your companion on the road — share the ride, split the cost.'**
  String get tagline;

  /// No description provided for @serverAddress.
  ///
  /// In en, this message translates to:
  /// **'Server address'**
  String get serverAddress;

  /// No description provided for @serverAddressHint.
  ///
  /// In en, this message translates to:
  /// **'e.g. http://192.168.1.10:8080'**
  String get serverAddressHint;

  /// No description provided for @testConnection.
  ///
  /// In en, this message translates to:
  /// **'Test connection'**
  String get testConnection;

  /// No description provided for @connectionOk.
  ///
  /// In en, this message translates to:
  /// **'Connected to the server.'**
  String get connectionOk;

  /// No description provided for @connectionFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not reach the server. Check the address and that phone and server are on the same network.'**
  String get connectionFailed;

  /// No description provided for @driverRoute.
  ///
  /// In en, this message translates to:
  /// **'Driver\'s trip: {route}'**
  String driverRoute(String route);

  /// No description provided for @phoneHint.
  ///
  /// In en, this message translates to:
  /// **'+963 9XX XXX XXX'**
  String get phoneHint;

  /// No description provided for @hintCash.
  ///
  /// In en, this message translates to:
  /// **'Pay the driver in cash during the trip — no card needed.'**
  String get hintCash;

  /// No description provided for @hintCancelCash.
  ///
  /// In en, this message translates to:
  /// **'Free cancellation — please cancel early if your plans change.'**
  String get hintCancelCash;

  /// No description provided for @feeNoticeCash.
  ///
  /// In en, this message translates to:
  /// **'You pay the driver in cash during the trip.'**
  String get feeNoticeCash;

  /// No description provided for @payCashDue.
  ///
  /// In en, this message translates to:
  /// **'Pay {amount} in cash to the driver.'**
  String payCashDue(String amount);

  /// No description provided for @payCashPaid.
  ///
  /// In en, this message translates to:
  /// **'Paid {amount} in cash.'**
  String payCashPaid(String amount);

  /// No description provided for @payCashReleased.
  ///
  /// In en, this message translates to:
  /// **'Reservation released.'**
  String get payCashReleased;

  /// No description provided for @cancelFreeBodyCash.
  ///
  /// In en, this message translates to:
  /// **'You can cancel for free. Please cancel early so someone else can take the seat.'**
  String get cancelFreeBodyCash;

  /// No description provided for @tripConfirmedThanks.
  ///
  /// In en, this message translates to:
  /// **'Thanks for confirming the trip!'**
  String get tripConfirmedThanks;

  /// No description provided for @completeRideCash.
  ///
  /// In en, this message translates to:
  /// **'Complete ride'**
  String get completeRideCash;

  /// No description provided for @cashPaymentTitle.
  ///
  /// In en, this message translates to:
  /// **'Cash payment'**
  String get cashPaymentTitle;

  /// No description provided for @cashPaymentBody.
  ///
  /// In en, this message translates to:
  /// **'Passengers pay you in cash during the trip. No bank account needed.'**
  String get cashPaymentBody;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @back.
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get back;

  /// No description provided for @save.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// No description provided for @submit.
  ///
  /// In en, this message translates to:
  /// **'Submit'**
  String get submit;

  /// No description provided for @later.
  ///
  /// In en, this message translates to:
  /// **'Later'**
  String get later;

  /// No description provided for @required.
  ///
  /// In en, this message translates to:
  /// **'Required'**
  String get required;

  /// No description provided for @language.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// No description provided for @firstName.
  ///
  /// In en, this message translates to:
  /// **'First name'**
  String get firstName;

  /// No description provided for @lastName.
  ///
  /// In en, this message translates to:
  /// **'Last name'**
  String get lastName;

  /// No description provided for @phoneOptional.
  ///
  /// In en, this message translates to:
  /// **'Phone (optional)'**
  String get phoneOptional;

  /// No description provided for @email.
  ///
  /// In en, this message translates to:
  /// **'Email'**
  String get email;

  /// No description provided for @password.
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get password;

  /// No description provided for @invalidEmail.
  ///
  /// In en, this message translates to:
  /// **'Enter a valid email'**
  String get invalidEmail;

  /// No description provided for @passwordTooShort.
  ///
  /// In en, this message translates to:
  /// **'At least 8 characters'**
  String get passwordTooShort;

  /// No description provided for @createAccount.
  ///
  /// In en, this message translates to:
  /// **'Create account'**
  String get createAccount;

  /// No description provided for @logIn.
  ///
  /// In en, this message translates to:
  /// **'Log in'**
  String get logIn;

  /// No description provided for @haveAccount.
  ///
  /// In en, this message translates to:
  /// **'Already a member? Log in'**
  String get haveAccount;

  /// No description provided for @noAccount.
  ///
  /// In en, this message translates to:
  /// **'New here? Create an account'**
  String get noAccount;

  /// No description provided for @navSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get navSearch;

  /// No description provided for @navPublish.
  ///
  /// In en, this message translates to:
  /// **'Publish'**
  String get navPublish;

  /// No description provided for @navTrips.
  ///
  /// In en, this message translates to:
  /// **'Trips'**
  String get navTrips;

  /// No description provided for @navProfile.
  ///
  /// In en, this message translates to:
  /// **'Profile'**
  String get navProfile;

  /// No description provided for @searchTitle.
  ///
  /// In en, this message translates to:
  /// **'Where are you going?'**
  String get searchTitle;

  /// No description provided for @leavingFrom.
  ///
  /// In en, this message translates to:
  /// **'Leaving from'**
  String get leavingFrom;

  /// No description provided for @goingTo.
  ///
  /// In en, this message translates to:
  /// **'Going to'**
  String get goingTo;

  /// No description provided for @swap.
  ///
  /// In en, this message translates to:
  /// **'Swap'**
  String get swap;

  /// No description provided for @date.
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get date;

  /// No description provided for @search.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get search;

  /// No description provided for @howItWorks.
  ///
  /// In en, this message translates to:
  /// **'How it works'**
  String get howItWorks;

  /// No description provided for @hintMatching.
  ///
  /// In en, this message translates to:
  /// **'We match you with drivers whose route passes near your start and destination — even if they travel further.'**
  String get hintMatching;

  /// No description provided for @hintEscrow.
  ///
  /// In en, this message translates to:
  /// **'Your payment is held securely and only released to the driver after the trip.'**
  String get hintEscrow;

  /// No description provided for @hintCancel.
  ///
  /// In en, this message translates to:
  /// **'Free cancellation until 24 hours before pickup.'**
  String get hintCancel;

  /// No description provided for @placeSearchHint.
  ///
  /// In en, this message translates to:
  /// **'City, address or station'**
  String get placeSearchHint;

  /// No description provided for @routeFromTo.
  ///
  /// In en, this message translates to:
  /// **'{from} – {to}'**
  String routeFromTo(String from, String to);

  /// No description provided for @passengersCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 passenger} other{{count} passengers}}'**
  String passengersCount(int count);

  /// No description provided for @sortEarliest.
  ///
  /// In en, this message translates to:
  /// **'Earliest'**
  String get sortEarliest;

  /// No description provided for @sortCheapest.
  ///
  /// In en, this message translates to:
  /// **'Cheapest'**
  String get sortCheapest;

  /// No description provided for @sortClosest.
  ///
  /// In en, this message translates to:
  /// **'Closest'**
  String get sortClosest;

  /// No description provided for @noRidesTitle.
  ///
  /// In en, this message translates to:
  /// **'No rides yet for this day'**
  String get noRidesTitle;

  /// No description provided for @noRidesSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Try another date or a nearby city.'**
  String get noRidesSubtitle;

  /// No description provided for @searchNextDay.
  ///
  /// In en, this message translates to:
  /// **'Search the next day'**
  String get searchNextDay;

  /// No description provided for @toPickup.
  ///
  /// In en, this message translates to:
  /// **'{distance} to pickup'**
  String toPickup(String distance);

  /// No description provided for @fromDropoff.
  ///
  /// In en, this message translates to:
  /// **'{distance} from drop-off'**
  String fromDropoff(String distance);

  /// No description provided for @seatsLeft.
  ///
  /// In en, this message translates to:
  /// **'{count} left'**
  String seatsLeft(int count);

  /// No description provided for @rideDetails.
  ///
  /// In en, this message translates to:
  /// **'Ride details'**
  String get rideDetails;

  /// No description provided for @driverTravels.
  ///
  /// In en, this message translates to:
  /// **'Driver travels from {origin} to {destination}. Meeting point {pickup} from your start, drop-off {dropoff} from your destination.'**
  String driverTravels(
      String origin, String destination, String pickup, String dropoff);

  /// No description provided for @pickup.
  ///
  /// In en, this message translates to:
  /// **'Pickup'**
  String get pickup;

  /// No description provided for @dropoff.
  ///
  /// In en, this message translates to:
  /// **'Drop-off'**
  String get dropoff;

  /// No description provided for @instantBooking.
  ///
  /// In en, this message translates to:
  /// **'Instant booking'**
  String get instantBooking;

  /// No description provided for @prefApproval.
  ///
  /// In en, this message translates to:
  /// **'Driver approves requests'**
  String get prefApproval;

  /// No description provided for @prefMaxTwoBack.
  ///
  /// In en, this message translates to:
  /// **'Max. 2 in the back'**
  String get prefMaxTwoBack;

  /// No description provided for @prefThreeBack.
  ///
  /// In en, this message translates to:
  /// **'3 in the back'**
  String get prefThreeBack;

  /// No description provided for @prefPets.
  ///
  /// In en, this message translates to:
  /// **'Pets welcome'**
  String get prefPets;

  /// No description provided for @prefNoPets.
  ///
  /// In en, this message translates to:
  /// **'No pets'**
  String get prefNoPets;

  /// No description provided for @prefSmoking.
  ///
  /// In en, this message translates to:
  /// **'Smoking allowed'**
  String get prefSmoking;

  /// No description provided for @prefNoSmoking.
  ///
  /// In en, this message translates to:
  /// **'No smoking'**
  String get prefNoSmoking;

  /// No description provided for @ownRide.
  ///
  /// In en, this message translates to:
  /// **'This is your ride'**
  String get ownRide;

  /// No description provided for @rideWasCancelled.
  ///
  /// In en, this message translates to:
  /// **'This ride was cancelled'**
  String get rideWasCancelled;

  /// No description provided for @rideFull.
  ///
  /// In en, this message translates to:
  /// **'This ride is full'**
  String get rideFull;

  /// No description provided for @seats.
  ///
  /// In en, this message translates to:
  /// **'Seats'**
  String get seats;

  /// No description provided for @seatsTimesPrice.
  ///
  /// In en, this message translates to:
  /// **'{seats} × {price}'**
  String seatsTimesPrice(int seats, String price);

  /// No description provided for @feeNotice.
  ///
  /// In en, this message translates to:
  /// **'A service fee is added at checkout. Your card is only charged after the trip.'**
  String get feeNotice;

  /// No description provided for @messageToDriver.
  ///
  /// In en, this message translates to:
  /// **'Message to the driver (optional)'**
  String get messageToDriver;

  /// No description provided for @book.
  ///
  /// In en, this message translates to:
  /// **'Book'**
  String get book;

  /// No description provided for @requestToBook.
  ///
  /// In en, this message translates to:
  /// **'Request to book'**
  String get requestToBook;

  /// No description provided for @offerRide.
  ///
  /// In en, this message translates to:
  /// **'Offer a ride'**
  String get offerRide;

  /// No description provided for @departure.
  ///
  /// In en, this message translates to:
  /// **'Departure'**
  String get departure;

  /// No description provided for @destination.
  ///
  /// In en, this message translates to:
  /// **'Destination'**
  String get destination;

  /// No description provided for @distanceAbout.
  ///
  /// In en, this message translates to:
  /// **'{distance} · about {duration}'**
  String distanceAbout(String distance, String duration);

  /// No description provided for @when.
  ///
  /// In en, this message translates to:
  /// **'When?'**
  String get when;

  /// No description provided for @departureTooSoon.
  ///
  /// In en, this message translates to:
  /// **'Departure must be at least 30 minutes from now.'**
  String get departureTooSoon;

  /// No description provided for @seatsOffered.
  ///
  /// In en, this message translates to:
  /// **'Seats offered'**
  String get seatsOffered;

  /// No description provided for @pricePerSeat.
  ///
  /// In en, this message translates to:
  /// **'Price per seat'**
  String get pricePerSeat;

  /// No description provided for @recommendedPrice.
  ///
  /// In en, this message translates to:
  /// **'Recommended price — fair cost sharing for this distance'**
  String get recommendedPrice;

  /// No description provided for @recommendedPriceIs.
  ///
  /// In en, this message translates to:
  /// **'Recommended: {price}'**
  String recommendedPriceIs(String price);

  /// No description provided for @proportionalPriceNote.
  ///
  /// In en, this message translates to:
  /// **'Passengers on part of your route pay a proportional price.'**
  String get proportionalPriceNote;

  /// No description provided for @preferences.
  ///
  /// In en, this message translates to:
  /// **'Preferences'**
  String get preferences;

  /// No description provided for @instantBookingSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Passengers book without waiting for your approval'**
  String get instantBookingSubtitle;

  /// No description provided for @petsAllowed.
  ///
  /// In en, this message translates to:
  /// **'Pets allowed'**
  String get petsAllowed;

  /// No description provided for @smokingAllowed.
  ///
  /// In en, this message translates to:
  /// **'Smoking allowed'**
  String get smokingAllowed;

  /// No description provided for @carLabel.
  ///
  /// In en, this message translates to:
  /// **'Car (e.g. blue VW Golf)'**
  String get carLabel;

  /// No description provided for @notesLabel.
  ///
  /// In en, this message translates to:
  /// **'Anything passengers should know?'**
  String get notesLabel;

  /// No description provided for @publishRide.
  ///
  /// In en, this message translates to:
  /// **'Publish ride'**
  String get publishRide;

  /// No description provided for @ridePublished.
  ///
  /// In en, this message translates to:
  /// **'Your ride is published!'**
  String get ridePublished;

  /// No description provided for @payoutsFirstTitle.
  ///
  /// In en, this message translates to:
  /// **'Set up payouts first'**
  String get payoutsFirstTitle;

  /// No description provided for @payoutsFirstBody.
  ///
  /// In en, this message translates to:
  /// **'To receive money from passengers, add your payout details. It only takes a few minutes.'**
  String get payoutsFirstBody;

  /// No description provided for @setUpPayouts.
  ///
  /// In en, this message translates to:
  /// **'Set up payouts'**
  String get setUpPayouts;

  /// No description provided for @yourTrips.
  ///
  /// In en, this message translates to:
  /// **'Your trips'**
  String get yourTrips;

  /// No description provided for @asPassenger.
  ///
  /// In en, this message translates to:
  /// **'As passenger'**
  String get asPassenger;

  /// No description provided for @asDriver.
  ///
  /// In en, this message translates to:
  /// **'As driver'**
  String get asDriver;

  /// No description provided for @noBookings.
  ///
  /// In en, this message translates to:
  /// **'No bookings yet'**
  String get noBookings;

  /// No description provided for @noBookingsSub.
  ///
  /// In en, this message translates to:
  /// **'Find a ride and travel together.'**
  String get noBookingsSub;

  /// No description provided for @findRide.
  ///
  /// In en, this message translates to:
  /// **'Find a ride'**
  String get findRide;

  /// No description provided for @upcoming.
  ///
  /// In en, this message translates to:
  /// **'Upcoming'**
  String get upcoming;

  /// No description provided for @history.
  ///
  /// In en, this message translates to:
  /// **'History'**
  String get history;

  /// No description provided for @noRidesOffered.
  ///
  /// In en, this message translates to:
  /// **'No rides offered'**
  String get noRidesOffered;

  /// No description provided for @noRidesOfferedSub.
  ///
  /// In en, this message translates to:
  /// **'Driving somewhere? Share your empty seats and your costs.'**
  String get noRidesOfferedSub;

  /// No description provided for @withDriver.
  ///
  /// In en, this message translates to:
  /// **'with {name}'**
  String withDriver(String name);

  /// No description provided for @rideScheduled.
  ///
  /// In en, this message translates to:
  /// **'Scheduled'**
  String get rideScheduled;

  /// No description provided for @rideOnTheRoad.
  ///
  /// In en, this message translates to:
  /// **'On the road'**
  String get rideOnTheRoad;

  /// No description provided for @rideCompleted.
  ///
  /// In en, this message translates to:
  /// **'Completed'**
  String get rideCompleted;

  /// No description provided for @rideCancelled.
  ///
  /// In en, this message translates to:
  /// **'Cancelled'**
  String get rideCancelled;

  /// No description provided for @seatsBookedFree.
  ///
  /// In en, this message translates to:
  /// **'{booked} booked · {free} free'**
  String seatsBookedFree(int booked, int free);

  /// No description provided for @requestsCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 request} other{{count} requests}}'**
  String requestsCount(int count);

  /// No description provided for @bsPaymentPending.
  ///
  /// In en, this message translates to:
  /// **'Awaiting payment'**
  String get bsPaymentPending;

  /// No description provided for @bsRequested.
  ///
  /// In en, this message translates to:
  /// **'Awaiting driver'**
  String get bsRequested;

  /// No description provided for @bsConfirmed.
  ///
  /// In en, this message translates to:
  /// **'Confirmed'**
  String get bsConfirmed;

  /// No description provided for @bsRejected.
  ///
  /// In en, this message translates to:
  /// **'Declined'**
  String get bsRejected;

  /// No description provided for @bsCancelled.
  ///
  /// In en, this message translates to:
  /// **'Cancelled'**
  String get bsCancelled;

  /// No description provided for @bsCancelledByDriver.
  ///
  /// In en, this message translates to:
  /// **'Cancelled by driver'**
  String get bsCancelledByDriver;

  /// No description provided for @bsExpired.
  ///
  /// In en, this message translates to:
  /// **'Expired'**
  String get bsExpired;

  /// No description provided for @bsCompleted.
  ///
  /// In en, this message translates to:
  /// **'Completed'**
  String get bsCompleted;

  /// No description provided for @yourBooking.
  ///
  /// In en, this message translates to:
  /// **'Your booking'**
  String get yourBooking;

  /// No description provided for @seatsCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 seat} other{{count} seats}}'**
  String seatsCount(int count);

  /// No description provided for @serviceFee.
  ///
  /// In en, this message translates to:
  /// **'Service fee'**
  String get serviceFee;

  /// No description provided for @total.
  ///
  /// In en, this message translates to:
  /// **'Total'**
  String get total;

  /// No description provided for @payPending.
  ///
  /// In en, this message translates to:
  /// **'Waiting for your payment authorization.'**
  String get payPending;

  /// No description provided for @payAuthorized.
  ///
  /// In en, this message translates to:
  /// **'{amount} is reserved on your card and released to the driver after the trip.'**
  String payAuthorized(String amount);

  /// No description provided for @payCaptured.
  ///
  /// In en, this message translates to:
  /// **'Charged {amount}.'**
  String payCaptured(String amount);

  /// No description provided for @payVoided.
  ///
  /// In en, this message translates to:
  /// **'The hold on your card was released — you were not charged.'**
  String get payVoided;

  /// No description provided for @payRefunded.
  ///
  /// In en, this message translates to:
  /// **'Refunded {amount}.'**
  String payRefunded(String amount);

  /// No description provided for @payFailed.
  ///
  /// In en, this message translates to:
  /// **'Your card was declined. Please try another payment method.'**
  String get payFailed;

  /// No description provided for @completePayment.
  ///
  /// In en, this message translates to:
  /// **'Complete payment'**
  String get completePayment;

  /// No description provided for @paymentAuthorized.
  ///
  /// In en, this message translates to:
  /// **'Payment authorized'**
  String get paymentAuthorized;

  /// No description provided for @refreshPaymentStatus.
  ///
  /// In en, this message translates to:
  /// **'Refresh payment status'**
  String get refreshPaymentStatus;

  /// No description provided for @confirmTrip.
  ///
  /// In en, this message translates to:
  /// **'Confirm the trip took place'**
  String get confirmTrip;

  /// No description provided for @driverPaid.
  ///
  /// In en, this message translates to:
  /// **'Thanks! The driver has been paid.'**
  String get driverPaid;

  /// No description provided for @rateName.
  ///
  /// In en, this message translates to:
  /// **'Rate {name}'**
  String rateName(String name);

  /// No description provided for @cancelBooking.
  ///
  /// In en, this message translates to:
  /// **'Cancel booking'**
  String get cancelBooking;

  /// No description provided for @cancelBookingTitle.
  ///
  /// In en, this message translates to:
  /// **'Cancel this booking?'**
  String get cancelBookingTitle;

  /// No description provided for @cancelLateBody.
  ///
  /// In en, this message translates to:
  /// **'Your pickup is less than 24 hours away. The driver will receive part of the seat price as compensation; the rest of the hold is released.'**
  String get cancelLateBody;

  /// No description provided for @cancelFreeBody.
  ///
  /// In en, this message translates to:
  /// **'Your payment hold will be released in full.'**
  String get cancelFreeBody;

  /// No description provided for @bookingCancelled.
  ///
  /// In en, this message translates to:
  /// **'Booking cancelled'**
  String get bookingCancelled;

  /// No description provided for @thanksReview.
  ///
  /// In en, this message translates to:
  /// **'Thanks for your review!'**
  String get thanksReview;

  /// No description provided for @commentOptional.
  ///
  /// In en, this message translates to:
  /// **'Comment (optional)'**
  String get commentOptional;

  /// No description provided for @yourRide.
  ///
  /// In en, this message translates to:
  /// **'Your ride'**
  String get yourRide;

  /// No description provided for @passengers.
  ///
  /// In en, this message translates to:
  /// **'Passengers'**
  String get passengers;

  /// No description provided for @noPassengers.
  ///
  /// In en, this message translates to:
  /// **'No passengers yet.'**
  String get noPassengers;

  /// No description provided for @pricePerSeatInfo.
  ///
  /// In en, this message translates to:
  /// **'{price} per seat · {free}/{total} seats free'**
  String pricePerSeatInfo(String price, int free, int total);

  /// No description provided for @pickupYouReceive.
  ///
  /// In en, this message translates to:
  /// **'Pickup {time} · you receive {amount}'**
  String pickupYouReceive(String time, String amount);

  /// No description provided for @decline.
  ///
  /// In en, this message translates to:
  /// **'Decline'**
  String get decline;

  /// No description provided for @accept.
  ///
  /// In en, this message translates to:
  /// **'Accept'**
  String get accept;

  /// No description provided for @requestDeclined.
  ///
  /// In en, this message translates to:
  /// **'Request declined'**
  String get requestDeclined;

  /// No description provided for @passengerConfirmed.
  ///
  /// In en, this message translates to:
  /// **'Passenger confirmed'**
  String get passengerConfirmed;

  /// No description provided for @removePassengerTitle.
  ///
  /// In en, this message translates to:
  /// **'Remove {name}?'**
  String removePassengerTitle(String name);

  /// No description provided for @removePassengerBody.
  ///
  /// In en, this message translates to:
  /// **'Their booking will be cancelled and their payment hold released.'**
  String get removePassengerBody;

  /// No description provided for @remove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get remove;

  /// No description provided for @removePassenger.
  ///
  /// In en, this message translates to:
  /// **'Remove passenger'**
  String get removePassenger;

  /// No description provided for @passengerRemoved.
  ///
  /// In en, this message translates to:
  /// **'Passenger removed'**
  String get passengerRemoved;

  /// No description provided for @reviewSaved.
  ///
  /// In en, this message translates to:
  /// **'Review saved'**
  String get reviewSaved;

  /// No description provided for @startRide.
  ///
  /// In en, this message translates to:
  /// **'Start ride'**
  String get startRide;

  /// No description provided for @safeTrip.
  ///
  /// In en, this message translates to:
  /// **'Have a safe trip!'**
  String get safeTrip;

  /// No description provided for @completeRide.
  ///
  /// In en, this message translates to:
  /// **'Complete ride & get paid'**
  String get completeRide;

  /// No description provided for @rideCompletedMsg.
  ///
  /// In en, this message translates to:
  /// **'Ride completed'**
  String get rideCompletedMsg;

  /// No description provided for @cancelRide.
  ///
  /// In en, this message translates to:
  /// **'Cancel ride'**
  String get cancelRide;

  /// No description provided for @cancelRideTitle.
  ///
  /// In en, this message translates to:
  /// **'Cancel this ride?'**
  String get cancelRideTitle;

  /// No description provided for @cancelRideBody.
  ///
  /// In en, this message translates to:
  /// **'All passengers will be notified and their payment holds released.'**
  String get cancelRideBody;

  /// No description provided for @rideCancelledMsg.
  ///
  /// In en, this message translates to:
  /// **'Ride cancelled'**
  String get rideCancelledMsg;

  /// No description provided for @profile.
  ///
  /// In en, this message translates to:
  /// **'Profile'**
  String get profile;

  /// No description provided for @noReviewsYet.
  ///
  /// In en, this message translates to:
  /// **'No reviews yet'**
  String get noReviewsYet;

  /// No description provided for @newMember.
  ///
  /// In en, this message translates to:
  /// **'New member'**
  String get newMember;

  /// No description provided for @ratingSummary.
  ///
  /// In en, this message translates to:
  /// **'{rating} · {count, plural, =1{1 review} other{{count} reviews}}'**
  String ratingSummary(String rating, int count);

  /// No description provided for @aboutYou.
  ///
  /// In en, this message translates to:
  /// **'About you'**
  String get aboutYou;

  /// No description provided for @addBio.
  ///
  /// In en, this message translates to:
  /// **'Add a short bio'**
  String get addBio;

  /// No description provided for @bioHint.
  ///
  /// In en, this message translates to:
  /// **'Chatty? Music lover? Tell travellers about yourself.'**
  String get bioHint;

  /// No description provided for @payouts.
  ///
  /// In en, this message translates to:
  /// **'Payouts'**
  String get payouts;

  /// No description provided for @payoutsActiveBody.
  ///
  /// In en, this message translates to:
  /// **'You can receive payments for your rides. Money is released 24 hours after each trip.'**
  String get payoutsActiveBody;

  /// No description provided for @payoutsSetupBody.
  ///
  /// In en, this message translates to:
  /// **'Add your bank details to get paid when you offer rides.'**
  String get payoutsSetupBody;

  /// No description provided for @managePayouts.
  ///
  /// In en, this message translates to:
  /// **'Manage payout details'**
  String get managePayouts;

  /// No description provided for @payoutsActive.
  ///
  /// In en, this message translates to:
  /// **'Payouts are active'**
  String get payoutsActive;

  /// No description provided for @logOut.
  ///
  /// In en, this message translates to:
  /// **'Log out'**
  String get logOut;

  /// No description provided for @durationHM.
  ///
  /// In en, this message translates to:
  /// **'{h} h {m} min'**
  String durationHM(int h, String m);

  /// No description provided for @durationH.
  ///
  /// In en, this message translates to:
  /// **'{h} h'**
  String durationH(int h);

  /// No description provided for @durationM.
  ///
  /// In en, this message translates to:
  /// **'{m} min'**
  String durationM(int m);

  /// No description provided for @distanceKm.
  ///
  /// In en, this message translates to:
  /// **'{value} km'**
  String distanceKm(String value);

  /// No description provided for @distanceM.
  ///
  /// In en, this message translates to:
  /// **'{value} m'**
  String distanceM(int value);

  /// No description provided for @errOffline.
  ///
  /// In en, this message translates to:
  /// **'No connection to the server. Check your internet connection.'**
  String get errOffline;

  /// No description provided for @errTimeout.
  ///
  /// In en, this message translates to:
  /// **'The server took too long to respond. Please try again.'**
  String get errTimeout;

  /// No description provided for @errUnauthorized.
  ///
  /// In en, this message translates to:
  /// **'Invalid email or password.'**
  String get errUnauthorized;

  /// No description provided for @errNoSeats.
  ///
  /// In en, this message translates to:
  /// **'Not enough seats available on this part of the route.'**
  String get errNoSeats;

  /// No description provided for @errAlreadyBooked.
  ///
  /// In en, this message translates to:
  /// **'You already have an active booking on this ride.'**
  String get errAlreadyBooked;

  /// No description provided for @errAlreadyReviewed.
  ///
  /// In en, this message translates to:
  /// **'You already reviewed this trip.'**
  String get errAlreadyReviewed;

  /// No description provided for @errConflict.
  ///
  /// In en, this message translates to:
  /// **'This action conflicts with existing data.'**
  String get errConflict;

  /// No description provided for @errInvalidState.
  ///
  /// In en, this message translates to:
  /// **'This action is no longer possible.'**
  String get errInvalidState;

  /// No description provided for @errPaymentRequired.
  ///
  /// In en, this message translates to:
  /// **'Payment setup is required first.'**
  String get errPaymentRequired;

  /// No description provided for @errUpstream.
  ///
  /// In en, this message translates to:
  /// **'A service is temporarily unavailable. Please try again.'**
  String get errUpstream;

  /// No description provided for @errNotFound.
  ///
  /// In en, this message translates to:
  /// **'Not found.'**
  String get errNotFound;

  /// No description provided for @errForbidden.
  ///
  /// In en, this message translates to:
  /// **'You are not allowed to do this.'**
  String get errForbidden;

  /// No description provided for @errRateLimited.
  ///
  /// In en, this message translates to:
  /// **'Too many requests. Please wait a moment.'**
  String get errRateLimited;

  /// No description provided for @errEmailTaken.
  ///
  /// In en, this message translates to:
  /// **'This email is already registered.'**
  String get errEmailTaken;

  /// No description provided for @errNoRoute.
  ///
  /// In en, this message translates to:
  /// **'No drivable route was found between these places.'**
  String get errNoRoute;

  /// No description provided for @errOwnRide.
  ///
  /// In en, this message translates to:
  /// **'You cannot book your own ride.'**
  String get errOwnRide;

  /// No description provided for @errRidePassed.
  ///
  /// In en, this message translates to:
  /// **'This ride has already passed your pickup point.'**
  String get errRidePassed;

  /// No description provided for @errTooFar.
  ///
  /// In en, this message translates to:
  /// **'This place is too far from the driver\'s route.'**
  String get errTooFar;

  /// No description provided for @errPrice.
  ///
  /// In en, this message translates to:
  /// **'The price is outside the allowed range for this distance.'**
  String get errPrice;

  /// No description provided for @errDeparture.
  ///
  /// In en, this message translates to:
  /// **'Please choose a valid departure time.'**
  String get errDeparture;

  /// No description provided for @errValidation.
  ///
  /// In en, this message translates to:
  /// **'Please check: {field}'**
  String errValidation(String field);

  /// No description provided for @errGeneric.
  ///
  /// In en, this message translates to:
  /// **'Something went wrong. Please try again.'**
  String get errGeneric;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['ar', 'en'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'ar':
      return AppLocalizationsAr();
    case 'en':
      return AppLocalizationsEn();
  }

  throw FlutterError(
      'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
      'an issue with the localizations generation tool. Please file an issue '
      'on GitHub with a reproducible sample app and the gen-l10n configuration '
      'that was used.');
}
