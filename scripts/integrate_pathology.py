#!/usr/bin/env python3
"""
EchoScope – pathology comparison cases and Commons measurement images.

Sources (all real recordings, each item credited with its own licence):
  * Wikimedia Commons, mainly the CardioNetworks ECHOpedia collection (AMC Echolab, Amsterdam;
    {{Cardionetworks permission}} = CC BY-SA 3.0), fetched by scripts/fetch_commons.py into
    temp_real/commons/files with licence metadata in temp_real/commons/meta.json,
  * the colour Doppler loops already bundled from The POCUS Atlas (CC BY-NC 4.0, approved),
  * Echocardiographer.org Image Library videos as an online link (YouTube only).

What it writes:
  * assets/commons/<slug>.jpg   stills (max 1280 px, JPEG q88)
  * assets/pathology/<slug>.mp4 Commons loops: H.264 yuv420p, ≤ 720p, no audio, faststart; the
                                loop restarts at frame 0 (ExoPlayer REPEAT_MODE_ONE)
  * echo_protocol_full.json     "pathology" cases; Commons TAPSE M-mode and ASD images added to
                                the matching guided steps and to the tricuspid valve examples
  * assets/commons/ATTRIBUTION.txt
Every case only lists media that exist, so the script can be re-run as more files arrive.
"""

import json
import os
import re
import subprocess
import urllib.parse

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
SRC = os.path.join(ROOT, "temp_real", "commons", "files")
META = os.path.join(ROOT, "temp_real", "commons", "meta.json")
OUT_IMG = os.path.join(ASSETS, "commons")
OUT_LOOP = os.path.join(ASSETS, "pathology")
ECHOPEDIA = "CardioNetworks ECHOpedia (AMC Echolab, Amsterdam), via Wikimedia Commons"


def L(en, de, ar):
    return {"en": en, "de": de, "ar": ar}


def slug(name):
    base = os.path.splitext(name)[0].replace("(CardioNetworks ECHOpedia)", "")
    return re.sub(r"[^a-z0-9]+", "_", base.lower()).strip("_")


def commons_page(name):
    return "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(name.replace(" ", "_"))


# ---------------------------------------------------------------------------------------------
# Cases: (id, group, title, findings, views, valves, media, online-library title)
# media entries: ("commons", file name, caption) or ("color", colour loop id, caption)
# ---------------------------------------------------------------------------------------------
LOOP_2D = L("2D loop", "2D-Loop", "مقطع ثنائي الأبعاد")
CASES = [
    ("as_severe", "aortic",
     L("Severe aortic stenosis", "Hochgradige Aortenklappenstenose", "تضيق أبهري شديد"),
     L("Calcified, thickened cusps with restricted opening (normal: thin cusps opening to the aortic walls). CW: Vmax ≥ 4 m/s, mean gradient ≥ 40 mmHg, AVA < 1.0 cm² (continuity equation – see Rechner).",
       "Verkalkte, verdickte Taschen mit eingeschränkter Öffnung (normal: zarte Taschen, die bis an die Aortenwand öffnen). CW: Vmax ≥ 4 m/s, mittlerer Gradient ≥ 40 mmHg, KÖF < 1,0 cm² (Kontinuitätsgleichung – siehe Rechner).",
       "وريقات متكلسة سميكة محدودة الانفتاح (الطبيعي: وريقات رقيقة تنفتح حتى جدار الأبهر). CW: سرعة قصوى ≥ 4 م/ث، مدروج وسطي ≥ 40 ملم زئبق، مساحة < 1.0 سم² (معادلة الاستمرارية – انظر الحاسبات)."),
     ["plax_standard", "psax_av", "apical_5c", "apical_3c"], ["aortic"],
     [("commons", "Severe aortic valve stenosis E00259 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Severe aortic valve stenosis E00261 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Severe aortic valve stenosis E00273 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Severe aortic valve stenosis E00264 (CardioNetworks ECHOpedia).jpg",
       L("CW: Vmax 6.8 m/s, mean PG 119 mmHg", "CW: Vmax 6,8 m/s, mittlerer PG 119 mmHg", "CW: سرعة 6.8 م/ث، مدروج وسطي 119"))],
     None),
    ("ar", "aortic",
     L("Aortic regurgitation", "Aortenklappeninsuffizienz", "قلس الصمام الأبهري"),
     L("Diastolic colour jet from the aortic valve into the LVOT. Severe: vena contracta > 6 mm, jet width ≥ 65 % of LVOT, PHT < 200 ms, holodiastolic flow reversal in the descending aorta, dilated LV.",
       "Diastolischer Farbjet von der Aortenklappe in den LVOT. Schwer: Vena contracta > 6 mm, Jetbreite ≥ 65 % des LVOT, PHT < 200 ms, holodiastolischer Rückfluss in der Aorta descendens, dilatierter LV.",
       "نفث ملوّن انبساطي من الصمام الأبهري إلى LVOT. شديد: Vena contracta > 6 ملم، عرض النفث ≥ 65% من LVOT، PHT < 200 ms، جريان عكسي شامل في الأبهر النازل، توسّع البطين الأيسر."),
     ["plax_standard", "apical_5c", "apical_3c", "suprasternal_arch"], ["aortic"],
     [("color", "plax_color", L("Colour: moderate AR (PLAX)", "Farbe: mittelgradige AI (PLAX)", "ملوّن: قلس متوسط (PLAX)")),
      ("color", "a5c_av_color", L("Colour: mild AR (A5C)", "Farbe: leichte AI (5K)", "ملوّن: قلس خفيف (A5C)")),
      ("commons", "Severe aortic regurgitation E00179 (CardioNetworks ECHOpedia).webm", L("Severe AR", "Schwere AI", "قلس شديد")),
      ("commons", "Severe aortic regurgitation E00180 (CardioNetworks ECHOpedia).webm", L("Severe AR", "Schwere AI", "قلس شديد")),
      ("commons", "Severe aortic regurgitation E00231 (CardioNetworks ECHOpedia).webm", L("Severe AR", "Schwere AI", "قلس شديد")),
      ("commons", "Severe aortic regurgitation E00181 (CardioNetworks ECHOpedia).jpg",
       L("M-mode: severe AR", "M-Mode: schwere AI", "نمط M: قلس شديد"))],
     None),
    ("ms_severe", "mitral",
     L("Severe mitral stenosis", "Hochgradige Mitralklappenstenose", "تضيق تاجي شديد"),
     L("Rheumatic: thickened leaflets, commissural fusion, diastolic 'hockey-stick' doming of the anterior leaflet, enlarged LA. MVA < 1.0 cm² (PHT > 220 ms; MVA = 220 / PHT – see Rechner).",
       "Rheumatisch: verdickte Segel, Kommissurenfusion, diastolisches „Hockeyschläger“-Doming des vorderen Segels, großer LA. MÖF < 1,0 cm² (PHT > 220 ms; MÖF = 220 / PHT – siehe Rechner).",
       "روماتيزمي: وريقات سميكة، التحام الصوارين، انتفاخ الوريقة الأمامية بشكل «عصا الهوكي» في الانبساط، أذين أيسر متوسّع. المساحة < 1.0 سم² (PHT > 220 ms؛ المساحة = 220 / PHT – انظر الحاسبات)."),
     ["plax_standard", "psax_papillary", "apical_4c"], ["mitral"],
     [("commons", "Severe mitral valve stenosis E00608 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Severe mitral valve stenosis E00610 (CardioNetworks ECHOpedia).webm", LOOP_2D)],
     None),
    ("mr", "mitral",
     L("Mitral regurgitation", "Mitralklappeninsuffizienz", "قلس الصمام التاجي"),
     L("Systolic colour jet into the LA. Severe: vena contracta ≥ 7 mm, EROA ≥ 0.4 cm², jet > 50 % of the LA, systolic flow reversal in the pulmonary veins. Look for the mechanism: flail / prolapse (primary) or tenting in a dilated LV (secondary).",
       "Systolischer Farbjet in den LA. Schwer: Vena contracta ≥ 7 mm, EROA ≥ 0,4 cm², Jet > 50 % des LA, systolische Flussumkehr in den Pulmonalvenen. Mechanismus suchen: Flail / Prolaps (primär) oder Tenting bei dilatiertem LV (sekundär).",
       "نفث ملوّن انقباضي إلى الأذين الأيسر. شديد: Vena contracta ≥ 7 ملم، EROA ≥ 0.4 سم²، النفث > 50% من الأذين، انعكاس انقباضي في الأوردة الرئوية. ابحث عن الآلية: سائبة/هبوط (أولي) أو شدّ الوريقات مع توسّع البطين (ثانوي)."),
     ["apical_4c", "apical_2c", "apical_3c", "plax_standard"], ["mitral"],
     [("color", "a4c_mv_color", L("Colour: moderate MR (A4C)", "Farbe: mittelgradige MI (4K)", "ملوّن: قلس تاجي متوسط")),
      ("commons", "Dilated cardiomyopathy with severe mitral valve regurgitation E00134 (CardioNetworks ECHOpedia).webm",
       L("Secondary MR in DCM", "Sekundäre MI bei DCM", "قلس ثانوي في DCM")),
      ("commons", "Dilated cardiomyopathy with severe mitral valve regurgitation E00136 (CardioNetworks ECHOpedia).webm",
       L("Secondary MR in DCM", "Sekundäre MI bei DCM", "قلس ثانوي في DCM")),
      ("commons", "Flail of mitral valve E00462 (CardioNetworks ECHOpedia).webm", L("Flail leaflet", "Flail-Segel", "وريقة سائبة")),
      ("commons", "Dilated cardiomyopathy with severe mitral valve regurgitation E00139 (CardioNetworks ECHOpedia).jpg",
       L("Severe MR (still)", "Schwere MI (Standbild)", "قلس شديد (صورة)")),
      ("commons", "Flail of mitral valve E00467 (CardioNetworks ECHOpedia).jpg", L("Flail leaflet (still)", "Flail-Segel (Standbild)", "وريقة سائبة (صورة)"))],
     None),
    ("tr_ph", "tricuspid",
     L("Tricuspid regurgitation / cor pulmonale", "Trikuspidalinsuffizienz / Cor pulmonale", "قلس ثلاثي الشرف / القلب الرئوي"),
     L("Colour jet into the RA; dilated RV and RA, D-shaped LV in pressure overload, plethoric IVC. TR Vmax > 3.4 m/s → high probability of pulmonary hypertension (sPAP = 4·V² + RAP – see Rechner). TAPSE < 17 mm = RV dysfunction.",
       "Farbjet in den RA; dilatierter RV und RA, D-förmiger LV bei Drucklast, gestaute VCI. TR-Vmax > 3,4 m/s → hohe PH-Wahrscheinlichkeit (sPAP = 4·V² + ZVD – siehe Rechner). TAPSE < 17 mm = RV-Dysfunktion.",
       "نفث ملوّن إلى الأذين الأيمن؛ توسّع البطين والأذين الأيمنين، بطين أيسر على شكل D عند زيادة الضغط، وريد أجوف ممتلئ. سرعة TR > 3.4 م/ث → احتمال مرتفع لارتفاع الضغط الرئوي (sPAP = 4·V² + RAP – انظر الحاسبات). TAPSE < 17 ملم = خلل البطين الأيمن."),
     ["apical_4c", "psax_av", "psax_papillary", "subcostal_4c", "subcostal_ivc"], ["tricuspid", "pulmonary"],
     [("color", "a4c_tv_color", L("Colour: mod.–severe TR (A4C)", "Farbe: mittel- bis hochgradige TI (4K)", "ملوّن: قلس متوسط–شديد")),
      ("commons", "Severe tricuspid regurgitation E00566 (CardioNetworks ECHOpedia).webm", L("Severe TR", "Schwere TI", "قلس شديد")),
      ("commons", "Severe tricuspid regurgitation E00567 (CardioNetworks ECHOpedia).webm", L("Severe TR", "Schwere TI", "قلس شديد")),
      ("commons", "Severe tricuspid regurgitation E00568 (CardioNetworks ECHOpedia).webm", L("Severe TR", "Schwere TI", "قلس شديد")),
      ("commons", "Echokardiographie M-Mode 4KB TAPSE.jpg", L("TAPSE M-mode (normal ≥ 17 mm)", "TAPSE im M-Mode (normal ≥ 17 mm)", "TAPSE بنمط M (طبيعي ≥ 17 ملم)"))],
     "Cor Pulmonale"),
    ("dcm", "cardiomyopathy",
     L("Dilated cardiomyopathy (DCM)", "Dilatative Kardiomyopathie (DCM)", "اعتلال عضلة القلب التوسعي"),
     L("Globally dilated, spherical LV with thin walls and severely reduced EF (normal LVIDd ≤ 58 mm ♂ / 52 mm ♀, EF ≥ 52 %); often secondary MR with tenting, enlarged LA, restrictive filling.",
       "Global dilatierter, kugeliger LV mit dünnen Wänden und hochgradig reduzierter EF (normal LVIDd ≤ 58 mm ♂ / 52 mm ♀, EF ≥ 52 %); oft sekundäre MI mit Tenting, großer LA, restriktive Füllung.",
       "بطين أيسر متوسّع كروي بجدران رقيقة وكسر قذف منخفض بشدة (الطبيعي LVIDd ≤ 58 ملم للرجال / 52 للنساء، EF ≥ 52%)؛ غالباً قلس تاجي ثانوي وأذين أيسر كبير وامتلاء تقييدي."),
     ["plax_standard", "psax_papillary", "apical_4c", "apical_2c"], [],
     [("commons", "Dilated cardiomyopathy E00473 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Dilated cardiomyopathy E00475 (CardioNetworks ECHOpedia).webm", LOOP_2D)],
     "Dilated Cardiomyopathy"),
    ("apical_hcm", "cardiomyopathy",
     L("Apical hypertrophic cardiomyopathy", "Apikale hypertrophe Kardiomyopathie", "اعتلال عضلة القلب الضخامي القمي"),
     L("Apical wall thickness ≥ 15 mm with an 'ace-of-spades' LV cavity in diastole and apical obliteration in systole (normal: apex thinner than the base). Use contrast if the apex is not seen; CW may show a mid-cavity gradient.",
       "Apikale Wanddicke ≥ 15 mm mit „Pik-As“-förmigem LV-Cavum in der Diastole und apikaler Obliteration in der Systole (normal: Apex dünner als die Basis). Bei schlecht sichtbarem Apex Kontrastmittel; CW zeigt evtl. einen mittventrikulären Gradienten.",
       "سماكة الجدار القمي ≥ 15 ملم مع تجويف على شكل «بستوني» في الانبساط وانطماس القمة في الانقباض (الطبيعي: القمة أرقّ من القاعدة). استخدم التباين إذا لم تظهر القمة؛ قد يُظهر CW مدروجاً في منتصف التجويف."),
     ["apical_4c", "apical_2c", "apical_3c"], [],
     [("commons", "Apical HCM E00291 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Apical HCM E00293 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Apical HCM E00296 (CardioNetworks ECHOpedia).jpg",
       L("CW: mid-cavity gradient 3.2 m/s", "CW: mittventrikulärer Gradient 3,2 m/s", "CW: مدروج منتصف التجويف 3.2 م/ث"))],
     "Apical HCM"),
    ("pericardial_effusion", "pericardium",
     L("Pericardial effusion / tamponade", "Perikarderguss / Tamponade", "انصباب التامور / الدكاك"),
     L("Echo-free space around the heart (measure in diastole: small < 10 mm, moderate 10–20 mm, large > 20 mm). Tamponade: RA systolic and RV diastolic collapse, plethoric IVC without collapse, respiratory variation of mitral E > 25 % / tricuspid E > 40 %.",
       "Echofreier Saum um das Herz (in der Diastole messen: klein < 10 mm, mittel 10–20 mm, groß > 20 mm). Tamponade: systolischer RA- und diastolischer RV-Kollaps, gestaute VCI ohne Kollaps, atemabhängige Variation von Mitral-E > 25 % / Trikuspidal-E > 40 %.",
       "فراغ خالٍ من الصدى حول القلب (يُقاس في الانبساط: صغير < 10 ملم، متوسط 10–20، كبير > 20). الدكاك: انخماص الأذين الأيمن انقباضياً والبطين الأيمن انبساطياً، وريد أجوف ممتلئ لا ينخمص، تغيّر تنفسي لموجة E التاجية > 25% / ثلاثية الشرف > 40%."),
     ["subcostal_4c", "subcostal_ivc", "plax_standard", "psax_papillary", "apical_4c"], [],
     [("commons", "Pericardial effusion E00672 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Pericardial effusion, \"swinging heart\" E00667 (CardioNetworks ECHOpedia).webm", L("Swinging heart", "Swinging heart", "القلب المتأرجح")),
      ("commons", "Tamponade E00167 (CardioNetworks ECHOpedia).webm", L("Tamponade", "Tamponade", "الدكاك")),
      ("commons", "Pericardial effusion E00676 (CardioNetworks ECHOpedia).jpg",
       L("Respiratory variation of inflow", "Atemvariation des Einstroms", "التغيّر التنفسي للتدفق"))],
     "Large Effusion/Tamponade"),
    ("aortic_aneurysm", "aorta",
     L("Ascending aortic aneurysm with AR", "Aneurysma der Aorta ascendens mit AI", "أم دم الأبهر الصاعد مع قلس أبهري"),
     L("Dilated sinuses / ascending aorta (normal ≤ 40 mm; aneurysm ≥ 45–50 mm, index to BSA – see Z-Score) with central AR from annular dilatation. Measure leading-edge to leading-edge in end-diastole, perpendicular to the axis (PLAX, higher intercostal space).",
       "Dilatierte Sinus / Aorta ascendens (normal ≤ 40 mm; Aneurysma ≥ 45–50 mm, auf KOF beziehen – siehe Z-Score) mit zentraler AI durch Anulusdilatation. Enddiastolisch, leading edge zu leading edge, senkrecht zur Achse messen (PLAX, einen ICR höher).",
       "توسّع الجيوب / الأبهر الصاعد (الطبيعي ≤ 40 ملم؛ أم الدم ≥ 45–50 ملم، يُنسب لمساحة الجسم – انظر Z-Score) مع قلس مركزي بسبب توسّع الحلقة. يُقاس نهاية الانبساط من الحافة الأمامية للأمامية عمودياً على المحور (PLAX، مسافة وربية أعلى)."),
     ["plax_standard", "suprasternal_arch"], ["aortic"],
     [("commons", "Dilated ascending aorta, severe aortic regurgitation E00442 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Dilated ascending aorta, severe aortic regurgitation E00444 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "Dilated ascending aorta, severe aortic regurgitation E00448 (CardioNetworks ECHOpedia).jpg",
       L("Still", "Standbild", "صورة"))],
     "Ascending Aortic Aneurysm with AR"),
    ("asd_secundum", "congenital",
     L("Atrial septal defect (secundum, ASD II)", "Vorhofseptumdefekt (Sekundum, ASD II)", "عيب الحاجز الأذيني (الثانوي)"),
     L("Gap in the mid (fossa ovalis) portion of the interatrial septum, best seen subcostally (beam perpendicular to the septum; apical views cause 'drop-out'). Colour: left-to-right shunt across the defect; volume-loaded RV and RA, Qp:Qs > 1.5.",
       "Lücke im mittleren Septumanteil (Fossa ovalis), am besten subkostal (Schallstrahl senkrecht zum Septum; apikal entstehen „Drop-outs“). Farbe: Links-rechts-Shunt über den Defekt; volumenbelasteter RV und RA, Qp:Qs > 1,5.",
       "فجوة في الجزء الأوسط من الحاجز الأذيني (الحفرة البيضوية)، تُرى أفضل تحت الضلع (الحزمة عمودية على الحاجز؛ في المقاطع القمية يحدث «انقطاع» كاذب). الملوّن: تحويلة من اليسار لليمين عبر العيب؛ تحميل حجمي للبطين والأذين الأيمنين، Qp:Qs > 1.5."),
     ["subcostal_4c", "apical_4c"], [],
     [("commons", "Echokardiogram von Atriumseptumdefekt (Ostium secundum).jpg",
       L("ASD II (subcostal)", "ASD II (subkostal)", "ASD II (تحت الضلع)")),
      ("commons", "ASD type II E00308 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "ASD type II E00310 (CardioNetworks ECHOpedia).webm", LOOP_2D),
      ("commons", "ASD type II E00312 (CardioNetworks ECHOpedia).webm", LOOP_2D)],
     None),
]

# Commons stills added to guided steps: (view, step title (en) prefix, file, caption) and valve examples.
STEP_EXAMPLES = [
    ("apical_4c", "TAPSE", "Echokardiographie M-Mode 4KB TAPSE.jpg",
     L("TAPSE 31 mm (M-mode, normal ≥ 17 mm)", "TAPSE 31 mm (M-Mode, normal ≥ 17 mm)", "TAPSE ‏31 ملم (نمط M، طبيعي ≥ 17 ملم)")),
    ("subcostal_4c", "Interatrial septum", "Echokardiogram von Atriumseptumdefekt (Ostium secundum).jpg",
     L("Pathology: ASD II (secundum)", "Pathologie: ASD II (Sekundum)", "حالة مرضية: ASD II")),
]
VALVE_EXAMPLES = [("tricuspid", "Echokardiographie M-Mode 4KB TAPSE.jpg",
                   L("RV function: TAPSE M-mode (normal ≥ 17 mm)", "RV-Funktion: TAPSE im M-Mode (normal ≥ 17 mm)", "وظيفة البطين الأيمن: TAPSE (طبيعي ≥ 17 ملم)"))]


def convert_loop(src, dst):
    """WebM / OGV → MP4 (H.264, yuv420p, ≤ 720 px high, no audio, constant 25 fps)."""
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", src,
        "-vf", "scale='min(1280,iw)':'min(720,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1,format=yuv420p",
        "-r", "25", "-c:v", "libx264", "-preset", "slow", "-crf", "21", "-pix_fmt", "yuv420p", "-an",
        "-movflags", "+faststart", "-map_metadata", "-1", dst], check=True)


def size_of(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    w, h = (int(x) for x in out.split(",")[:2])
    return w, h


def main():
    meta = json.load(open(META, encoding="utf-8")) if os.path.exists(META) else {}
    os.makedirs(OUT_IMG, exist_ok=True)
    os.makedirs(OUT_LOOP, exist_ok=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    used = {}

    def commons_media(name, caption):
        src = os.path.join(SRC, name)
        if not os.path.exists(src):
            return None
        info = meta.get(name, {})
        if not info.get("license"):
            return None
        if name.lower().endswith((".webm", ".ogv")):
            asset = f"pathology/{slug(name)}.mp4"
            dst = os.path.join(ASSETS, asset)
            if not os.path.exists(dst):
                convert_loop(src, dst)
            w, h = size_of(dst)
        else:
            asset = f"commons/{slug(name)}.jpg"
            dst = os.path.join(ASSETS, asset)
            im = Image.open(src).convert("RGB")
            im.thumbnail((1280, 1280))
            im.save(dst, quality=88, optimize=True)
            w, h = im.size
        author = info["author"] if "ECHOpedia" not in name else ECHOPEDIA
        used[asset] = (name, author, info["license"], info["page"])
        return {"asset": asset, "aspect": round(w / h, 4), "caption": caption, "credit": author,
                "license": info["license"], "source": info["page"]}

    library = {c["title"]: c["url"] for g in db.get("caseLibrary", {}).get("groups", []) for c in g["cases"]}
    cases = []
    for cid, group, title, findings, views, valves, media, online in CASES:
        items = []
        for kind, ref, caption in media:
            if kind == "color":
                c = db["colorLoops"][ref]
                w, h = size_of(os.path.join(ASSETS, c["asset"]))
                items.append({"asset": c["asset"], "aspect": round(w / h, 4), "caption": caption, "credit": c["author"],
                              "license": c["license"], "source": c["sourceUrl"]})
            else:
                m = commons_media(ref, caption)
                if m:
                    items.append(m)
        items.sort(key=lambda m: not m["asset"].endswith(".mp4"))   # loops first
        if not items:
            continue
        case = {"id": cid, "group": group, "title": title, "findings": findings, "views": views, "valves": valves, "media": items}
        if online and online in library:
            case["onlineUrl"] = library[online]
        cases.append(case)
    db["pathology"] = cases

    views = {v["id"]: v for w in db["windows"] for v in w["views"]}
    for view_id, prefix, name, caption in STEP_EXAMPLES:
        m = commons_media(name, caption)
        if not m:
            continue
        step = next(s for s in views[view_id]["steps"] if s["title"]["en"].startswith(prefix))
        ex = [e for e in step.get("examples", []) if e["asset"] != m["asset"]]
        step["examples"] = [{"asset": m["asset"], "aspect": m["aspect"], "caption": caption,
                             "credit": f'{m["credit"]} ({m["license"]})', "source": m["source"]}] + ex
    for valve_id, name, caption in VALVE_EXAMPLES:
        m = commons_media(name, caption)
        valve = next(v for v in db["valves"] if v["id"] == valve_id)
        if m:
            ex = [e for e in valve.get("examples", []) if e["asset"] != m["asset"]]
            valve["examples"] = ex + [{"asset": m["asset"], "aspect": m["aspect"], "caption": caption,
                                       "credit": f'{m["credit"]} ({m["license"]})', "source": m["source"]}]

    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    with open(os.path.join(OUT_IMG, "ATTRIBUTION.txt"), "w", encoding="utf-8") as f:
        f.write("Wikimedia Commons media used by EchoScope (scripts/integrate_pathology.py)\n\n"
                "Loops were converted to MP4 (H.264, no audio, max. 720p) and stills scaled to max. 1280 px.\n"
                "CC BY-SA content remains under CC BY-SA; CardioNetworks ECHOpedia files carry\n"
                "{{Cardionetworks permission}} = CC BY-SA 3.0 (Wikimedia OTRS ticket 2011102310008874).\n\n")
        for asset, (name, author, lic, page) in sorted(used.items()):
            f.write(f"{asset}\n  File: {name}\n  Author: {author}\n  Licence: {lic}\n  Source: {page}\n\n")
    print(f"{len(cases)} cases, {sum(len(c['media']) for c in cases)} media items, {len(used)} Commons files")
    for c in cases:
        print(f"  {c['id']:22} {len(c['media'])} media: " + ", ".join(os.path.basename(m['asset']) for m in c['media']))


if __name__ == "__main__":
    main()
