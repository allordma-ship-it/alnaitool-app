import { settings } from './settings';

const dict = {
  appName: { ar: 'Alnai Notes', de: 'Alnai Notes', en: 'Alnai Notes' },
  library: { ar: 'المكتبة', de: 'Bibliothek', en: 'Library' },
  newNotebook: { ar: 'دفتر جديد', de: 'Neues Notizbuch', en: 'New notebook' },
  importPdf: { ar: 'استيراد PDF', de: 'PDF importieren', en: 'Import PDF' },
  search: { ar: 'بحث…', de: 'Suchen…', en: 'Search…' },
  empty: {
    ar: 'لا توجد مستندات بعد. أنشئ دفترًا أو استورد ملف PDF.',
    de: 'Noch keine Dokumente. Erstelle ein Notizbuch oder importiere ein PDF.',
    en: 'No documents yet. Create a notebook or import a PDF.',
  },
  untitled: { ar: 'دفتر بدون عنوان', de: 'Unbenanntes Notizbuch', en: 'Untitled notebook' },
  rename: { ar: 'إعادة تسمية', de: 'Umbenennen', en: 'Rename' },
  delete: { ar: 'حذف', de: 'Löschen', en: 'Delete' },
  confirmDelete: { ar: 'حذف هذا المستند نهائيًا؟', de: 'Dieses Dokument endgültig löschen?', en: 'Delete this document permanently?' },
  pages: { ar: 'صفحات', de: 'Seiten', en: 'pages' },
  template: { ar: 'القالب', de: 'Vorlage', en: 'Template' },
  paper: { ar: 'الورق', de: 'Papier', en: 'Paper' },
  blank: { ar: 'فارغ', de: 'Leer', en: 'Blank' },
  lined: { ar: 'مسطّر', de: 'Liniert', en: 'Lined' },
  grid: { ar: 'مربعات', de: 'Kariert', en: 'Grid' },
  dots: { ar: 'نقاط', de: 'Punktraster', en: 'Dots' },
  cornell: { ar: 'كورنيل', de: 'Cornell', en: 'Cornell' },
  white: { ar: 'أبيض', de: 'Weiß', en: 'White' },
  cream: { ar: 'كريمي', de: 'Creme', en: 'Cream' },
  dark: { ar: 'داكن', de: 'Dunkel', en: 'Dark' },
  create: { ar: 'إنشاء', de: 'Erstellen', en: 'Create' },
  cancel: { ar: 'إلغاء', de: 'Abbrechen', en: 'Cancel' },
  title: { ar: 'العنوان', de: 'Titel', en: 'Title' },
  pen: { ar: 'قلم', de: 'Stift', en: 'Pen' },
  highlighter: { ar: 'تظليل', de: 'Textmarker', en: 'Highlighter' },
  eraser: { ar: 'ممحاة', de: 'Radierer', en: 'Eraser' },
  lasso: { ar: 'تحديد', de: 'Lasso', en: 'Lasso' },
  undo: { ar: 'تراجع', de: 'Rückgängig', en: 'Undo' },
  redo: { ar: 'إعادة', de: 'Wiederholen', en: 'Redo' },
  addPage: { ar: 'إضافة صفحة', de: 'Seite hinzufügen', en: 'Add page' },
  deletePage: { ar: 'حذف الصفحة', de: 'Seite löschen', en: 'Delete page' },
  confirmDeletePage: { ar: 'حذف الصفحة الحالية؟', de: 'Aktuelle Seite löschen?', en: 'Delete current page?' },
  exportPdf: { ar: 'تصدير PDF', de: 'Als PDF exportieren', en: 'Export PDF' },
  exporting: { ar: 'جارٍ التصدير…', de: 'Exportiere…', en: 'Exporting…' },
  settings: { ar: 'الإعدادات', de: 'Einstellungen', en: 'Settings' },
  back: { ar: 'رجوع', de: 'Zurück', en: 'Back' },
  strokeEraser: { ar: 'مسح الخط كاملًا', de: 'Ganze Striche', en: 'Whole strokes' },
  preciseEraser: { ar: 'مسح دقيق', de: 'Präzise', en: 'Precise' },
  size: { ar: 'الحجم', de: 'Größe', en: 'Size' },
  color: { ar: 'اللون', de: 'Farbe', en: 'Color' },
  duplicate: { ar: 'نسخ', de: 'Duplizieren', en: 'Duplicate' },
  stylus: { ar: 'القلم الإلكتروني', de: 'Eingabestift', en: 'Stylus' },
  penOnly: { ar: 'الرسم بالقلم فقط (رفض راحة اليد)', de: 'Nur mit Stift zeichnen (Handballenerkennung)', en: 'Draw with pen only (palm rejection)' },
  auto: { ar: 'تلقائي', de: 'Automatisch', en: 'Automatic' },
  on: { ar: 'تشغيل', de: 'An', en: 'On' },
  off: { ar: 'إيقاف', de: 'Aus', en: 'Off' },
  barrelButton: { ar: 'الزر الجانبي الأول', de: 'Erste Stifttaste', en: 'First pen button' },
  secondButton: { ar: 'الزر الجانبي الثاني', de: 'Zweite Stifttaste', en: 'Second pen button' },
  none: { ar: 'لا شيء', de: 'Nichts', en: 'Nothing' },
  twoFingerUndo: { ar: 'نقرة بإصبعين = تراجع، بثلاثة = إعادة', de: 'Zwei-Finger-Tipp = Rückgängig, drei = Wiederholen', en: 'Two-finger tap = undo, three = redo' },
  pressure: { ar: 'حساسية الضغط', de: 'Druckempfindlichkeit', en: 'Pressure sensitivity' },
  language: { ar: 'اللغة', de: 'Sprache', en: 'Language' },
  penTest: { ar: 'اختبار القلم', de: 'Stifttest', en: 'Pen test' },
  penTestHint: {
    ar: 'اكتب هنا واضغط على أزرار القلم لترى ما يصل للتطبيق',
    de: 'Schreibe hier und drücke die Stifttasten, um zu sehen, was ankommt',
    en: 'Write here and press the pen buttons to see what the app receives',
  },
  install: { ar: 'تثبيت التطبيق', de: 'App installieren', en: 'Install app' },
  storageWarn: {
    ar: 'تُحفظ بياناتك على هذا الجهاز فقط. صدّر ملاحظاتك المهمة كـ PDF بشكل دوري.',
    de: 'Deine Daten liegen nur auf diesem Gerät. Exportiere wichtige Notizen regelmäßig als PDF.',
    en: 'Your data is stored on this device only. Export important notes as PDF regularly.',
  },
  page: { ar: 'صفحة', de: 'Seite', en: 'Page' },
  loading: { ar: 'جارٍ التحميل…', de: 'Lädt…', en: 'Loading…' },
  pdfError: { ar: 'تعذّر فتح ملف PDF', de: 'PDF konnte nicht geöffnet werden', en: 'Could not open PDF' },
  fitWidth: { ar: 'ملاءمة العرض', de: 'Breite anpassen', en: 'Fit width' },
} as const;

export type Key = keyof typeof dict;

export function t(k: Key): string {
  return dict[k][settings.lang];
}

export function applyLang() {
  document.documentElement.lang = settings.lang;
  document.documentElement.dir = settings.lang === 'ar' ? 'rtl' : 'ltr';
}
