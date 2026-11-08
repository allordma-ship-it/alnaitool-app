// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Arabic (`ar`).
class AppLocalizationsAr extends AppLocalizations {
  AppLocalizationsAr([String locale = 'ar']) : super(locale);

  @override
  String get appTitle => 'ونيس';

  @override
  String get tagline => 'رفيقك على الطريق — شارك الرحلة وتقاسم التكلفة.';

  @override
  String get serverAddress => 'عنوان الخادم';

  @override
  String get serverAddressHint => 'مثال: http://192.168.1.10:8080';

  @override
  String get testConnection => 'اختبار الاتصال';

  @override
  String get connectionOk => 'تم الاتصال بالخادم بنجاح.';

  @override
  String get connectionFailed =>
      'تعذّر الوصول إلى الخادم. تأكد من العنوان ومن أن الهاتف والخادم على نفس الشبكة.';

  @override
  String driverRoute(String route) {
    return 'رحلة السائق: $route';
  }

  @override
  String get phoneHint => '+963 9XX XXX XXX';

  @override
  String get hintCash =>
      'تدفع للسائق نقداً أثناء الرحلة — لا حاجة لبطاقة مصرفية.';

  @override
  String get hintCancelCash =>
      'الإلغاء مجاني — يُرجى الإلغاء مبكراً إذا تغيّرت خططك.';

  @override
  String get feeNoticeCash => 'تدفع المبلغ للسائق نقداً أثناء الرحلة.';

  @override
  String payCashDue(String amount) {
    return 'ادفع $amount نقداً للسائق.';
  }

  @override
  String payCashPaid(String amount) {
    return 'تم دفع $amount نقداً.';
  }

  @override
  String get payCashReleased => 'أُلغي الحجز.';

  @override
  String get cancelFreeBodyCash =>
      'يمكنك الإلغاء مجاناً. يُرجى الإلغاء مبكراً ليتمكن غيرك من حجز المقعد.';

  @override
  String get tripConfirmedThanks => 'شكراً لتأكيدك الرحلة!';

  @override
  String get completeRideCash => 'أنهِ الرحلة';

  @override
  String get cashPaymentTitle => 'الدفع نقداً';

  @override
  String get cashPaymentBody =>
      'يدفع لك الركاب نقداً أثناء الرحلة. لا حاجة لحساب مصرفي.';

  @override
  String get retry => 'إعادة المحاولة';

  @override
  String get cancel => 'إلغاء';

  @override
  String get back => 'رجوع';

  @override
  String get save => 'حفظ';

  @override
  String get submit => 'إرسال';

  @override
  String get later => 'لاحقاً';

  @override
  String get required => 'مطلوب';

  @override
  String get language => 'اللغة';

  @override
  String get firstName => 'الاسم الأول';

  @override
  String get lastName => 'اسم العائلة';

  @override
  String get phoneOptional => 'الهاتف (اختياري)';

  @override
  String get email => 'البريد الإلكتروني';

  @override
  String get password => 'كلمة المرور';

  @override
  String get invalidEmail => 'أدخل بريداً إلكترونياً صحيحاً';

  @override
  String get passwordTooShort => '8 أحرف على الأقل';

  @override
  String get createAccount => 'إنشاء حساب';

  @override
  String get logIn => 'تسجيل الدخول';

  @override
  String get haveAccount => 'لديك حساب؟ سجّل الدخول';

  @override
  String get noAccount => 'جديد هنا؟ أنشئ حساباً';

  @override
  String get navSearch => 'بحث';

  @override
  String get navPublish => 'نشر رحلة';

  @override
  String get navTrips => 'رحلاتي';

  @override
  String get navProfile => 'حسابي';

  @override
  String get searchTitle => 'إلى أين تذهب؟';

  @override
  String get leavingFrom => 'الانطلاق من';

  @override
  String get goingTo => 'الوجهة';

  @override
  String get swap => 'تبديل';

  @override
  String get date => 'التاريخ';

  @override
  String get search => 'ابحث';

  @override
  String get howItWorks => 'كيف يعمل التطبيق';

  @override
  String get hintMatching =>
      'نطابقك مع سائقين يمر طريقهم بالقرب من نقطة انطلاقك ووجهتك، حتى لو كانوا مسافرين لمسافة أبعد.';

  @override
  String get hintEscrow =>
      'يُحجز مبلغك بأمان ولا يُحوَّل إلى السائق إلا بعد انتهاء الرحلة.';

  @override
  String get hintCancel => 'إلغاء مجاني حتى 24 ساعة قبل موعد الانطلاق.';

  @override
  String get placeSearchHint => 'مدينة أو عنوان أو محطة';

  @override
  String routeFromTo(String from, String to) {
    return 'من $from إلى $to';
  }

  @override
  String passengersCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count راكب',
      many: '$count راكباً',
      few: '$count ركاب',
      two: 'راكبان',
      one: 'راكب واحد',
    );
    return '$_temp0';
  }

  @override
  String get sortEarliest => 'الأبكر';

  @override
  String get sortCheapest => 'الأرخص';

  @override
  String get sortClosest => 'الأقرب';

  @override
  String get noRidesTitle => 'لا توجد رحلات في هذا اليوم بعد';

  @override
  String get noRidesSubtitle => 'جرّب تاريخاً آخر أو مدينة قريبة.';

  @override
  String get searchNextDay => 'ابحث في اليوم التالي';

  @override
  String toPickup(String distance) {
    return '$distance إلى نقطة الالتقاء';
  }

  @override
  String fromDropoff(String distance) {
    return '$distance من نقطة النزول';
  }

  @override
  String seatsLeft(int count) {
    return 'متبقٍ: $count';
  }

  @override
  String get rideDetails => 'تفاصيل الرحلة';

  @override
  String driverTravels(
      String origin, String destination, String pickup, String dropoff) {
    return 'يسافر السائق من $origin إلى $destination. نقطة الالتقاء على بُعد $pickup من نقطة انطلاقك، ونقطة النزول على بُعد $dropoff من وجهتك.';
  }

  @override
  String get pickup => 'نقطة الالتقاء';

  @override
  String get dropoff => 'نقطة النزول';

  @override
  String get instantBooking => 'حجز فوري';

  @override
  String get prefApproval => 'السائق يوافق على الطلبات';

  @override
  String get prefMaxTwoBack => 'راكبان كحد أقصى في الخلف';

  @override
  String get prefThreeBack => '3 ركاب في الخلف';

  @override
  String get prefPets => 'الحيوانات الأليفة مرحّب بها';

  @override
  String get prefNoPets => 'ممنوع الحيوانات الأليفة';

  @override
  String get prefSmoking => 'التدخين مسموح';

  @override
  String get prefNoSmoking => 'ممنوع التدخين';

  @override
  String get ownRide => 'هذه رحلتك';

  @override
  String get rideWasCancelled => 'أُلغيت هذه الرحلة';

  @override
  String get rideFull => 'هذه الرحلة ممتلئة';

  @override
  String get seats => 'المقاعد';

  @override
  String seatsTimesPrice(int seats, String price) {
    return '$seats × $price';
  }

  @override
  String get feeNotice =>
      'تُضاف رسوم خدمة عند الدفع. لا يُخصم المبلغ من بطاقتك إلا بعد الرحلة.';

  @override
  String get messageToDriver => 'رسالة إلى السائق (اختياري)';

  @override
  String get book => 'احجز';

  @override
  String get requestToBook => 'اطلب الحجز';

  @override
  String get offerRide => 'اعرض رحلة';

  @override
  String get departure => 'الانطلاق';

  @override
  String get destination => 'الوجهة';

  @override
  String distanceAbout(String distance, String duration) {
    return '$distance · حوالي $duration';
  }

  @override
  String get when => 'متى؟';

  @override
  String get departureTooSoon =>
      'يجب أن يكون موعد الانطلاق بعد 30 دقيقة على الأقل من الآن.';

  @override
  String get seatsOffered => 'المقاعد المعروضة';

  @override
  String get pricePerSeat => 'السعر لكل مقعد';

  @override
  String get recommendedPrice =>
      'السعر المقترح — تقاسم عادل للتكلفة لهذه المسافة';

  @override
  String recommendedPriceIs(String price) {
    return 'السعر المقترح: $price';
  }

  @override
  String get proportionalPriceNote =>
      'الركاب على جزء من طريقك يدفعون سعراً بنسبة المسافة التي يقطعونها.';

  @override
  String get preferences => 'التفضيلات';

  @override
  String get instantBookingSubtitle => 'يحجز الركاب دون انتظار موافقتك';

  @override
  String get petsAllowed => 'يُسمح بالحيوانات الأليفة';

  @override
  String get smokingAllowed => 'يُسمح بالتدخين';

  @override
  String get carLabel => 'السيارة (مثلاً: فولكس فاغن غولف زرقاء)';

  @override
  String get notesLabel => 'هل هناك ما يجب أن يعرفه الركاب؟';

  @override
  String get publishRide => 'انشر الرحلة';

  @override
  String get ridePublished => 'تم نشر رحلتك!';

  @override
  String get payoutsFirstTitle => 'أعِدّ استلام الأموال أولاً';

  @override
  String get payoutsFirstBody =>
      'لاستلام المال من الركاب، أضف بيانات الاستلام الخاصة بك. لا يستغرق ذلك سوى بضع دقائق.';

  @override
  String get setUpPayouts => 'إعداد استلام الأموال';

  @override
  String get yourTrips => 'رحلاتي';

  @override
  String get asPassenger => 'كراكب';

  @override
  String get asDriver => 'كسائق';

  @override
  String get noBookings => 'لا توجد حجوزات بعد';

  @override
  String get noBookingsSub => 'ابحث عن رحلة وسافروا معاً.';

  @override
  String get findRide => 'ابحث عن رحلة';

  @override
  String get upcoming => 'القادمة';

  @override
  String get history => 'السابقة';

  @override
  String get noRidesOffered => 'لم تعرض أي رحلة بعد';

  @override
  String get noRidesOfferedSub =>
      'مسافر بسيارتك؟ شارك مقاعدك الفارغة وتقاسم التكاليف.';

  @override
  String withDriver(String name) {
    return 'مع $name';
  }

  @override
  String get rideScheduled => 'مجدولة';

  @override
  String get rideOnTheRoad => 'على الطريق';

  @override
  String get rideCompleted => 'مكتملة';

  @override
  String get rideCancelled => 'ملغاة';

  @override
  String seatsBookedFree(int booked, int free) {
    return '$booked محجوز · $free متاح';
  }

  @override
  String requestsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count طلب',
      many: '$count طلباً',
      few: '$count طلبات',
      two: 'طلبان',
      one: 'طلب واحد',
    );
    return '$_temp0';
  }

  @override
  String get bsPaymentPending => 'بانتظار الدفع';

  @override
  String get bsRequested => 'بانتظار موافقة السائق';

  @override
  String get bsConfirmed => 'مؤكد';

  @override
  String get bsRejected => 'مرفوض';

  @override
  String get bsCancelled => 'ملغى';

  @override
  String get bsCancelledByDriver => 'ألغاه السائق';

  @override
  String get bsExpired => 'منتهي الصلاحية';

  @override
  String get bsCompleted => 'مكتمل';

  @override
  String get yourBooking => 'حجزك';

  @override
  String seatsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count مقعد',
      many: '$count مقعداً',
      few: '$count مقاعد',
      two: 'مقعدان',
      one: 'مقعد واحد',
    );
    return '$_temp0';
  }

  @override
  String get serviceFee => 'رسوم الخدمة';

  @override
  String get total => 'المجموع';

  @override
  String get payPending => 'بانتظار تفويضك بالدفع.';

  @override
  String payAuthorized(String amount) {
    return 'تم حجز $amount على بطاقتك، ويُحوَّل إلى السائق بعد الرحلة.';
  }

  @override
  String payCaptured(String amount) {
    return 'تم خصم $amount.';
  }

  @override
  String get payVoided => 'أُلغي الحجز على بطاقتك — لم يُخصم منك أي مبلغ.';

  @override
  String payRefunded(String amount) {
    return 'تم استرداد $amount.';
  }

  @override
  String get payFailed => 'رُفضت بطاقتك. يُرجى تجربة وسيلة دفع أخرى.';

  @override
  String get completePayment => 'أكمل الدفع';

  @override
  String get paymentAuthorized => 'تم تفويض الدفع';

  @override
  String get refreshPaymentStatus => 'تحديث حالة الدفع';

  @override
  String get confirmTrip => 'أكّد أن الرحلة تمّت';

  @override
  String get driverPaid => 'شكراً! تم الدفع للسائق.';

  @override
  String rateName(String name) {
    return 'قيّم $name';
  }

  @override
  String get cancelBooking => 'إلغاء الحجز';

  @override
  String get cancelBookingTitle => 'هل تريد إلغاء هذا الحجز؟';

  @override
  String get cancelLateBody =>
      'موعد انطلاقك بعد أقل من 24 ساعة. سيحصل السائق على جزء من سعر المقعد كتعويض، ويُحرَّر باقي المبلغ المحجوز.';

  @override
  String get cancelFreeBody => 'سيُحرَّر المبلغ المحجوز على بطاقتك بالكامل.';

  @override
  String get bookingCancelled => 'تم إلغاء الحجز';

  @override
  String get thanksReview => 'شكراً على تقييمك!';

  @override
  String get commentOptional => 'تعليق (اختياري)';

  @override
  String get yourRide => 'رحلتك';

  @override
  String get passengers => 'الركاب';

  @override
  String get noPassengers => 'لا يوجد ركاب بعد.';

  @override
  String pricePerSeatInfo(String price, int free, int total) {
    return '$price للمقعد · $free/$total مقاعد متاحة';
  }

  @override
  String pickupYouReceive(String time, String amount) {
    return 'الالتقاء $time · تحصل على $amount';
  }

  @override
  String get decline => 'رفض';

  @override
  String get accept => 'قبول';

  @override
  String get requestDeclined => 'تم رفض الطلب';

  @override
  String get passengerConfirmed => 'تم تأكيد الراكب';

  @override
  String removePassengerTitle(String name) {
    return 'إزالة $name؟';
  }

  @override
  String get removePassengerBody =>
      'سيُلغى حجزه ويُحرَّر المبلغ المحجوز على بطاقته.';

  @override
  String get remove => 'إزالة';

  @override
  String get removePassenger => 'إزالة الراكب';

  @override
  String get passengerRemoved => 'تمت إزالة الراكب';

  @override
  String get reviewSaved => 'تم حفظ التقييم';

  @override
  String get startRide => 'ابدأ الرحلة';

  @override
  String get safeTrip => 'رحلة آمنة!';

  @override
  String get completeRide => 'أنهِ الرحلة واستلم أجرك';

  @override
  String get rideCompletedMsg => 'اكتملت الرحلة';

  @override
  String get cancelRide => 'إلغاء الرحلة';

  @override
  String get cancelRideTitle => 'هل تريد إلغاء هذه الرحلة؟';

  @override
  String get cancelRideBody =>
      'سيتم إبلاغ جميع الركاب وتحرير المبالغ المحجوزة على بطاقاتهم.';

  @override
  String get rideCancelledMsg => 'تم إلغاء الرحلة';

  @override
  String get profile => 'حسابي';

  @override
  String get noReviewsYet => 'لا توجد تقييمات بعد';

  @override
  String get newMember => 'عضو جديد';

  @override
  String ratingSummary(String rating, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count تقييم',
      many: '$count تقييماً',
      few: '$count تقييمات',
      two: 'تقييمان',
      one: 'تقييم واحد',
    );
    return '$rating · $_temp0';
  }

  @override
  String get aboutYou => 'نبذة عنك';

  @override
  String get addBio => 'أضف نبذة قصيرة عنك';

  @override
  String get bioHint => 'تحب الحديث؟ تحب الموسيقى؟ عرّف المسافرين بنفسك.';

  @override
  String get payouts => 'استلام الأموال';

  @override
  String get payoutsActiveBody =>
      'يمكنك استلام المدفوعات مقابل رحلاتك. يُحوَّل المال بعد 24 ساعة من كل رحلة.';

  @override
  String get payoutsSetupBody =>
      'أضف بياناتك البنكية لتستلم أجرك عندما تعرض رحلات.';

  @override
  String get managePayouts => 'إدارة بيانات الاستلام';

  @override
  String get payoutsActive => 'استلام الأموال مُفعّل';

  @override
  String get logOut => 'تسجيل الخروج';

  @override
  String durationHM(int h, String m) {
    return '$h س $m د';
  }

  @override
  String durationH(int h) {
    return '$h ساعة';
  }

  @override
  String durationM(int m) {
    return '$m دقيقة';
  }

  @override
  String distanceKm(String value) {
    return '$value كم';
  }

  @override
  String distanceM(int value) {
    return '$value م';
  }

  @override
  String get errOffline => 'لا يوجد اتصال بالخادم. تحقق من اتصالك بالإنترنت.';

  @override
  String get errTimeout => 'استغرق الخادم وقتاً طويلاً للرد. حاول مرة أخرى.';

  @override
  String get errUnauthorized => 'البريد الإلكتروني أو كلمة المرور غير صحيحة.';

  @override
  String get errNoSeats => 'لا توجد مقاعد كافية على هذا الجزء من الطريق.';

  @override
  String get errAlreadyBooked => 'لديك حجز نشط على هذه الرحلة بالفعل.';

  @override
  String get errAlreadyReviewed => 'لقد قيّمت هذه الرحلة من قبل.';

  @override
  String get errConflict => 'هذا الإجراء يتعارض مع بيانات موجودة.';

  @override
  String get errInvalidState => 'لم يعد هذا الإجراء ممكناً.';

  @override
  String get errPaymentRequired => 'يلزم إعداد الدفع أولاً.';

  @override
  String get errUpstream => 'إحدى الخدمات غير متاحة مؤقتاً. حاول مرة أخرى.';

  @override
  String get errNotFound => 'غير موجود.';

  @override
  String get errForbidden => 'غير مسموح لك بهذا الإجراء.';

  @override
  String get errRateLimited => 'طلبات كثيرة جداً. انتظر قليلاً.';

  @override
  String get errEmailTaken => 'هذا البريد الإلكتروني مسجّل بالفعل.';

  @override
  String get errNoRoute => 'لم يُعثر على طريق للسيارات بين هذين المكانين.';

  @override
  String get errOwnRide => 'لا يمكنك حجز رحلتك الخاصة.';

  @override
  String get errRidePassed => 'لقد تجاوزت هذه الرحلة نقطة الالتقاء الخاصة بك.';

  @override
  String get errTooFar => 'هذا المكان بعيد جداً عن طريق السائق.';

  @override
  String get errPrice => 'السعر خارج النطاق المسموح به لهذه المسافة.';

  @override
  String get errDeparture => 'يُرجى اختيار موعد انطلاق صالح.';

  @override
  String errValidation(String field) {
    return 'يُرجى التحقق من: $field';
  }

  @override
  String get errGeneric => 'حدث خطأ ما. حاول مرة أخرى.';
}
