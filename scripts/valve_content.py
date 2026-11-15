#!/usr/bin/env python3
"""
EchoScope – structured heart-valve assessment (aortic, mitral, tricuspid, pulmonary valve).

Writes "valves" into app/src/main/assets/echo_protocol_full.json. Per valve:
  * views      – where to image it (links into the view cockpits)
  * morphology / color / spectral – step-by-step checklists (2D, colour Doppler, PW / CW)
  * normals    – normal values
  * grading    – stenosis and regurgitation grading (mild / moderate / severe)
  * examples   – real recordings from Echocardiographer.org (licence: free reuse with credit)

Sources (adult TTE):
  Baumgartner H et al. Recommendations on the echocardiographic assessment of aortic valve
    stenosis: a focused update from the EACVI and ASE. J Am Soc Echocardiogr 2017;30:372-92.
  Zoghbi WA et al. Recommendations for noninvasive evaluation of native valvular
    regurgitation. ASE / SCMR. J Am Soc Echocardiogr 2017;30:303-71.
  Baumgartner H et al. Echocardiographic assessment of valve stenosis: EAE/ASE recommendations
    for clinical practice. J Am Soc Echocardiogr 2009;22:1-23.
  Rudski LG et al. Guidelines for the echocardiographic assessment of the right heart in
    adults. J Am Soc Echocardiogr 2010;23:685-713.
Educational summary – always apply the full guideline and integrate all parameters.

Pipeline: run after integrate_echocardiographer.py (uses its image conversion).
Usage: python3 scripts/valve_content.py
"""

from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from integrate_echocardiographer import convert  # noqa: E402  (same image pipeline + credit)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")


def t(en, de, ar):
    return {"en": en, "de": de, "ar": ar}


def n(text):
    """Numeric text: German gets decimal commas, Arabic keeps Western digits."""
    return {"en": text, "de": re.sub(r"(?<=\d)\.(?=\d)", ",", text), "ar": text}


def item(en, de, ar, view=None):
    out = {"text": t(en, de, ar)}
    if view:
        out["view"] = view
    return out


def cell(value):
    """Numbers become locale-formatted text; words must already be a t(en, de, ar) dict."""
    return value if isinstance(value, dict) else n(value)


def row(param, mild, moderate, severe):
    return {"parameter": param, "mild": cell(mild), "moderate": cell(moderate), "severe": cell(severe)}


def normal(param, value):
    return {"parameter": param, "value": cell(value)}


INTERMEDIATE = t("intermediate", "dazwischen", "بينهما")
S_DOM = t("systolic dominance", "systolisch dominant", "سيطرة انقباضية")
S_BLUNT = t("systolic blunting", "systolisch abgeflacht", "تسطّح انقباضي")
S_REV = t("systolic reversal", "systolische Umkehr", "انعكاس انقباضي")


VALVES = [
    # -----------------------------------------------------------------------------------------
    {
        "id": "aortic",
        "title": t("Aortic valve", "Aortenklappe", "الصمام الأبهري"),
        "short": "AV",
        "views": ["plax_standard", "psax_av", "apical_5c", "apical_3c", "suprasternal_arch"],
        "morphology": [
            item("PLAX: cusp thickening, calcification and opening; systolic doming suggests a bicuspid valve.",
                 "PLAX: Taschenverdickung, Verkalkung und Öffnung; systolisches Doming spricht für eine bikuspide Klappe.",
                 "PLAX: سماكة الشرفات وتكلّسها وانفتاحها؛ القبّة الانقباضية توحي بصمام ثنائي الشرف.", "plax_standard"),
            item("PLAX zoom: LVOT diameter in mid-systole, inner edge to inner edge, 0.5–1 cm below the annulus.",
                 "PLAX-Zoom: LVOT-Durchmesser mesosystolisch, Innenkante zu Innenkante, 0,5–1 cm unterhalb des Anulus.",
                 "PLAX مكبّر: قطر LVOT في منتصف الانقباض من الحافة الداخلية إلى الداخلية، 0.5–1 سم تحت الحلقة.", "plax_standard"),
            item("PSAX-AV: count the cusps (tricuspid 'Mercedes star' vs bicuspid 'fish mouth'), raphe, commissural fusion.",
                 "PSAX-AV: Taschen zählen (trikuspid 'Mercedesstern' vs. bikuspid 'Fischmaul'), Raphe, Kommissurenfusion.",
                 "PSAX-AV: عدّ الشرفات (ثلاثي 'نجمة مرسيدس' مقابل ثنائي 'فم السمكة')، الخط الليفي، التحام الصوارين.", "psax_av"),
            item("Aortic root: sinuses, sinotubular junction and ascending aorta (dilatation?).",
                 "Aortenwurzel: Sinus, sinotubulärer Übergang und Aorta ascendens (Dilatation?).",
                 "جذر الأبهر: الجيوب، الوصل الجيبي الأنبوبي والأبهر الصاعد (توسّع؟).", "plax_standard"),
            item("Consequences: LV wall thickness (hypertrophy), LV size and EF.",
                 "Folgen: LV-Wanddicke (Hypertrophie), LV-Größe und EF.",
                 "العواقب: ثخانة جدار البطين الأيسر (ضخامة)، حجمه والكسر القذفي.", "apical_4c"),
        ],
        "color": [
            item("PLAX / A5C: AR jet origin and direction (central vs eccentric).",
                 "PLAX / A5C: Ursprung und Richtung des AI-Jets (zentral vs. exzentrisch).",
                 "PLAX / A5C: منشأ واتجاه نفث القلس الأبهري (مركزي أم لا مركزي).", "plax_standard"),
            item("PLAX zoom: vena contracta (narrowest neck) and jet width / LVOT width.",
                 "PLAX-Zoom: Vena contracta (engste Stelle) und Jetbreite / LVOT-Breite.",
                 "PLAX مكبّر: الوريد المنقبض (أضيق عنق) ونسبة عرض النفث إلى عرض LVOT.", "plax_standard"),
            item("PSAX-AV: where the AR jet leaves the valve (commissure, central).",
                 "PSAX-AV: wo der AI-Jet die Klappe verlässt (Kommissur, zentral).",
                 "PSAX-AV: مكان خروج نفث القلس من الصمام (عند الصوار أو في المركز).", "psax_av"),
            item("Systolic flow acceleration / aliasing above the valve: think of stenosis.",
                 "Systolische Flussbeschleunigung / Aliasing oberhalb der Klappe: an Stenose denken.",
                 "تسارع الجريان الانقباضي / التعرّج فوق الصمام: فكّر بالتضيّق.", "apical_5c"),
        ],
        "spectral": [
            item("A5C CW through the valve: Vmax, mean gradient, VTI(AV). Also try right parasternal and suprasternal windows.",
                 "A5C CW durch die Klappe: Vmax, mittlerer Gradient, VTI(AK). Auch rechts parasternal und suprasternal versuchen.",
                 "A5C دوبلر مستمر عبر الصمام: Vmax، المتوسط، VTI. جرّب أيضاً النافذة جانب القص اليمنى وفوق القص.", "apical_5c"),
            item("A5C PW in the LVOT just proximal to the valve: VTI(LVOT) for the continuity equation.",
                 "A5C PW im LVOT direkt vor der Klappe: VTI(LVOT) für die Kontinuitätsgleichung.",
                 "A5C دوبلر نبضي في LVOT قبل الصمام مباشرة: VTI(LVOT) لمعادلة الاستمرارية.", "apical_5c"),
            item("AVA = π·(D/2)²·VTI(LVOT) / VTI(AV); velocity ratio = VTI(LVOT) / VTI(AV).",
                 "AÖF = π·(D/2)²·VTI(LVOT) / VTI(AK); Geschwindigkeitsquotient = VTI(LVOT) / VTI(AK).",
                 "مساحة الصمام = π·(D/2)²·VTI(LVOT) / VTI(AV)؛ نسبة السرعة = VTI(LVOT) / VTI(AV).", "apical_5c"),
            item("AR: CW pressure half-time of the regurgitant jet (A5C).",
                 "AI: CW-Druckhalbwertszeit des Regurgitationsjets (A5C).",
                 "القلس الأبهري: زمن نصف الضغط بالدوبلر المستمر (A5C).", "apical_5c"),
            item("AR: PW in the proximal descending aorta (suprasternal) – holodiastolic flow reversal?",
                 "AI: PW in der proximalen Aorta descendens (suprasternal) – holodiastolischer Rückfluss?",
                 "القلس الأبهري: دوبلر نبضي في الأبهر النازل (فوق القص) – جريان راجع طوال الانبساط؟", "suprasternal_arch"),
        ],
        "normals": [
            normal(t("Peak velocity (Vmax)", "Spitzengeschwindigkeit (Vmax)", "السرعة القصوى (Vmax)"), t("1.0–1.7 m/s (sclerosis ≤ 2.5)", "1,0–1,7 m/s (Sklerose ≤ 2,5)", "1.0–1.7 m/s (تصلّب ≤ 2.5)")),
            normal(t("Valve area (AVA)", "Klappenöffnungsfläche (AÖF)", "مساحة الصمام"), "3.0–4.0 cm²"),
            normal(t("LVOT VTI", "LVOT-VTI", "VTI في LVOT"), "18–22 cm"),
            normal(t("LVOT diameter", "LVOT-Durchmesser", "قطر LVOT"), "1.8–2.2 cm"),
        ],
        "grading": [
            {"title": t("Aortic stenosis (EACVI/ASE 2017)", "Aortenstenose (EACVI/ASE 2017)", "تضيّق الصمام الأبهري (EACVI/ASE 2017)"),
             "rows": [
                 row(t("Vmax", "Vmax", "Vmax"), "2.6–2.9 m/s", "3.0–4.0 m/s", "≥ 4.0 m/s"),
                 row(t("Mean gradient", "Mittlerer Gradient", "الممال المتوسط"), "< 20 mmHg", "20–40 mmHg", "≥ 40 mmHg"),
                 row(t("AVA", "AÖF", "مساحة الصمام"), "> 1.5 cm²", "1.0–1.5 cm²", "< 1.0 cm²"),
                 row(t("Indexed AVA", "AÖF indiziert", "المساحة المفهرسة"), "> 0.85 cm²/m²", "0.60–0.85 cm²/m²", "< 0.6 cm²/m²"),
                 row(t("Velocity ratio", "Geschwindigkeitsquotient", "نسبة السرعة"), "> 0.50", "0.25–0.50", "< 0.25"),
             ],
             "note": t("Discordant grading (AVA < 1.0 cm² but gradient < 40 mmHg): check LVOT measurement, flow (SVi < 35 mL/m²) and consider low-flow low-gradient AS.",
                       "Diskordanz (AÖF < 1,0 cm², Gradient < 40 mmHg): LVOT-Messung und Fluss (SVi < 35 ml/m²) prüfen, an Low-Flow-Low-Gradient-AS denken.",
                       "عدم التوافق (المساحة < 1.0 سم² والممال < 40 ملم زئبق): راجع قياس LVOT والجريان (SVi < 35 مل/م²) وفكّر بتضيّق منخفض الجريان والممال.")},
            {"title": t("Aortic regurgitation (ASE 2017)", "Aorteninsuffizienz (ASE 2017)", "القلس الأبهري (ASE 2017)"),
             "rows": [
                 row(t("Vena contracta", "Vena contracta", "الوريد المنقبض"), "< 0.3 cm", "0.3–0.6 cm", "> 0.6 cm"),
                 row(t("Jet width / LVOT", "Jetbreite / LVOT", "عرض النفث / LVOT"), "< 25 %", "25–64 %", "≥ 65 %"),
                 row(t("Pressure half-time", "Druckhalbwertszeit", "زمن نصف الضغط"), "> 500 ms", "200–500 ms", "< 200 ms"),
                 row(t("EROA", "EROA", "مساحة فتحة القلس الفعّالة"), "< 0.10 cm²", "0.10–0.29 cm²", "≥ 0.30 cm²"),
                 row(t("Regurgitant volume", "Regurgitationsvolumen", "حجم القلس"), "< 30 mL", "30–59 mL", "≥ 60 mL"),
                 row(t("Regurgitant fraction", "Regurgitationsfraktion", "كسر القلس"), "< 30 %", "30–49 %", "≥ 50 %"),
                 row(t("Descending aorta", "Aorta descendens", "الأبهر النازل"), t("brief early-diastolic reversal", "kurzer frühdiastolischer Rückfluss", "انعكاس قصير في بداية الانبساط"),
                     INTERMEDIATE, t("holodiastolic reversal (EDV > 20 cm/s)", "holodiastolischer Rückfluss (EDV > 20 cm/s)",
                                     "انعكاس طوال الانبساط (EDV > 20 سم/ث)")),
             ]},
        ],
        "examples": [("tte-apical5/05.jpg", "cw_av"), ("tte-apical5/04.jpg", "pw_lvot"), ("tte-psax/03.jpg", "av_zoom"),
                     ("tte-plax/04.jpg", "plax_zoom")],
    },
    # -----------------------------------------------------------------------------------------
    {
        "id": "mitral",
        "title": t("Mitral valve", "Mitralklappe", "الصمام التاجي"),
        "short": "MV",
        "views": ["plax_standard", "psax_papillary", "apical_4c", "apical_2c", "apical_3c"],
        "morphology": [
            item("PLAX: leaflet thickening, calcification, mobility; prolapse or flail beyond the annular line.",
                 "PLAX: Segelverdickung, Verkalkung, Beweglichkeit; Prolaps oder Flail über die Anulusebene.",
                 "PLAX: سماكة الوريقات وتكلّسها وحركتها؛ تدلٍّ أو سائبة خلف مستوى الحلقة.", "plax_standard"),
            item("Rheumatic signs: diastolic doming ('hockey stick' AML), commissural fusion, chordal shortening.",
                 "Rheumatische Zeichen: diastolisches Doming ('Hockeyschläger'-AML), Kommissurenfusion, Sehnenfadenverkürzung.",
                 "علامات رثوية: قبّة انبساطية (الوريقة الأمامية كعصا الهوكي)، التحام الصوارين، قِصَر الحبال.", "plax_standard"),
            item("PSAX at the leaflet tips: 'fish mouth' orifice, planimetry of the MVA, segment of prolapse (A1–P3).",
                 "PSAX auf Segelspitzen-Ebene: 'Fischmaul'-Öffnung, Planimetrie der MÖF, Prolaps-Segment (A1–P3).",
                 "PSAX عند رؤوس الوريقات: فتحة 'فم السمكة'، قياس مساحة الصمام بالتخطيط، تحديد قطعة التدلي (A1–P3).", "psax_papillary"),
            item("A4C / A2C / A3C: annular calcification, LA size (LA volume index), LV size and function.",
                 "4K / 2K / 3K: Anulusverkalkung, LA-Größe (LAVI), LV-Größe und -Funktion.",
                 "رباعي/ثنائي/ثلاثي الحجرات: تكلّس الحلقة، حجم الأذين الأيسر (LAVI)، حجم البطين الأيسر ووظيفته.", "apical_4c"),
        ],
        "color": [
            item("A4C / A2C / A3C: jet direction (central = often secondary, eccentric = often primary) and jet area / LA area.",
                 "4K / 2K / 3K: Jetrichtung (zentral = oft sekundär, exzentrisch = oft primär) und Jetfläche / LA-Fläche.",
                 "رباعي/ثنائي/ثلاثي الحجرات: اتجاه النفث (مركزي غالباً ثانوي، لا مركزي غالباً أولي) ونسبة مساحة النفث إلى الأذين.", "apical_4c"),
            item("PLAX or A3C zoom: vena contracta perpendicular to the commissural line.",
                 "PLAX- oder 3K-Zoom: Vena contracta senkrecht zur Kommissurenlinie.",
                 "PLAX أو ثلاثي الحجرات مكبّر: الوريد المنقبض عمودياً على خط الصوارين.", "apical_3c"),
            item("PISA: zoom, shift the colour baseline toward the jet (Nyquist ≈ 30–40 cm/s), measure the radius in mid-systole.",
                 "PISA: zoomen, Farb-Baseline in Jetrichtung verschieben (Nyquist ≈ 30–40 cm/s), Radius mesosystolisch messen.",
                 "PISA: كبّر، أزح خط الأساس اللوني باتجاه النفث (Nyquist ≈ 30–40 سم/ث)، قِس نصف القطر في منتصف الانقباض.", "apical_4c"),
            item("MS: flow convergence and turbulent jet in the LV during diastole.",
                 "MS: Flusskonvergenz und turbulenter Jet im LV während der Diastole.",
                 "التضيّق التاجي: تقارب الجريان ونفث مضطرب في البطين الأيسر أثناء الانبساط.", "apical_4c"),
        ],
        "spectral": [
            item("A4C PW at the leaflet tips: E, A, E/A, deceleration time (MR: E > 1.2 m/s supports severe).",
                 "4K PW an den Segelspitzen: E, A, E/A, Dezelerationszeit (MI: E > 1,2 m/s spricht für schwer).",
                 "رباعي الحجرات دوبلر نبضي عند رؤوس الوريقات: E، A، E/A، زمن التباطؤ (القلس: E > 1.2 م/ث يدعم الشديد).", "apical_4c"),
            item("MS: CW mean gradient (note heart rate) and pressure half-time → MVA = 220 / PHT.",
                 "MS: CW mittlerer Gradient (Herzfrequenz notieren) und Druckhalbwertszeit → MÖF = 220 / PHT.",
                 "التضيّق التاجي: الممال المتوسط بالدوبلر المستمر (سجّل النبض) وزمن نصف الضغط ← المساحة = 220 / PHT.", "apical_4c"),
            item("MR: CW jet density and shape (dense, triangular/early-peaking = severe).",
                 "MI: CW-Signaldichte und -form (dicht, dreieckig/früher Gipfel = schwer).",
                 "القلس التاجي: كثافة وشكل إشارة الدوبلر المستمر (كثيفة ومثلثية/ذروة مبكرة = شديد).", "apical_4c"),
            item("Pulmonary vein PW (A4C): systolic flow reversal = severe MR.",
                 "Pulmonalvenen-PW (4K): systolische Flussumkehr = schwere MI.",
                 "دوبلر نبضي في الأوردة الرئوية (رباعي الحجرات): انعكاس الجريان الانقباضي = قلس شديد.", "apical_4c"),
            item("TR Vmax → systolic PA pressure (consequence of MS / MR).",
                 "TI-Vmax → systolischer PA-Druck (Folge von MS / MI).",
                 "السرعة القصوى للقلس ثلاثي الشرف ← الضغط الرئوي الانقباضي (نتيجة التضيّق/القلس التاجي).", "apical_4c"),
        ],
        "normals": [
            normal(t("Mitral valve area", "Mitralöffnungsfläche", "مساحة الصمام التاجي"), "4–6 cm²"),
            normal(t("E velocity", "E-Welle", "سرعة الموجة E"), "0.6–1.3 m/s"),
            normal(t("E/A", "E/A", "E/A"), "0.8–2.0"),
            normal(t("Deceleration time", "Dezelerationszeit", "زمن التباطؤ"), "160–240 ms"),
            normal(t("Septal / lateral e'", "Septales / laterales e'", "e' الحاجزي / الوحشي"), "≥ 7 / ≥ 10 cm/s"),
        ],
        "grading": [
            {"title": t("Mitral stenosis (EAE/ASE 2009)", "Mitralstenose (EAE/ASE 2009)", "تضيّق الصمام التاجي (EAE/ASE 2009)"),
             "rows": [
                 row(t("MVA", "MÖF", "المساحة"), "> 1.5 cm²", "1.0–1.5 cm²", "< 1.0 cm²"),
                 row(t("Mean gradient", "Mittlerer Gradient", "الممال المتوسط"), "< 5 mmHg", "5–10 mmHg", "> 10 mmHg"),
                 row(t("PA systolic pressure", "Systolischer PA-Druck", "الضغط الرئوي الانقباضي"), "< 30 mmHg", "30–50 mmHg", "> 50 mmHg"),
             ],
             "note": t("MVA by PHT = 220 / PHT (ms); PHT ≥ 220 ms ≈ MVA ≤ 1.0 cm². AHA/ACC 2020 call MVA ≤ 1.5 cm² severe.",
                       "MÖF nach PHT = 220 / PHT (ms); PHT ≥ 220 ms ≈ MÖF ≤ 1,0 cm². AHA/ACC 2020 werten MÖF ≤ 1,5 cm² als schwer.",
                       "المساحة بزمن نصف الضغط = 220 / PHT (مللي ثانية)؛ PHT ≥ 220 ≈ مساحة ≤ 1.0 سم². توصيات AHA/ACC 2020 تعدّ ≤ 1.5 سم² شديداً.")},
            {"title": t("Mitral regurgitation (ASE 2017)", "Mitralinsuffizienz (ASE 2017)", "القلس التاجي (ASE 2017)"),
             "rows": [
                 row(t("Vena contracta", "Vena contracta", "الوريد المنقبض"), "< 0.3 cm", "0.3–0.69 cm", "≥ 0.7 cm"),
                 row(t("EROA (PISA)", "EROA (PISA)", "مساحة الفتحة الفعّالة (PISA)"), "< 0.20 cm²", "0.20–0.39 cm²", "≥ 0.40 cm²"),
                 row(t("Regurgitant volume", "Regurgitationsvolumen", "حجم القلس"), "< 30 mL", "30–59 mL", "≥ 60 mL"),
                 row(t("Regurgitant fraction", "Regurgitationsfraktion", "كسر القلس"), "< 30 %", "30–49 %", "≥ 50 %"),
                 row(t("Jet area / LA area", "Jetfläche / LA-Fläche", "مساحة النفث / الأذين"), t("< 20 %, central", "< 20 %, zentral", "< 20 %، مركزي"), INTERMEDIATE,
                     t("> 50 % or wall-hugging", "> 50 % oder wandständig", "> 50 % أو ملاصق للجدار")),
                 row(t("Pulmonary vein flow", "Pulmonalvenenfluss", "جريان الأوردة الرئوية"), S_DOM, S_BLUNT, S_REV),
             ],
             "note": t("Secondary MR: an elliptical orifice is underestimated by PISA – EROA ≥ 0.20 cm² / RVol ≥ 30 mL already signal significant MR (ESC).",
                       "Sekundäre MI: elliptische Öffnung wird per PISA unterschätzt – EROA ≥ 0,20 cm² / RVol ≥ 30 ml sprechen bereits für relevante MI (ESC).",
                       "القلس التاجي الثانوي: PISA يقلّل تقدير الفتحة الإهليلجية – EROA ≥ 0.20 سم² / حجم ≥ 30 مل يدلّان على قلس مهم (ESC).")},
        ],
        "examples": [("tte-apical4/07.jpg", "pw_mv"), ("tte-apical4/06.jpg", "mv_color"), ("tte-psax/05.jpg", "mv_level"),
                     ("tte-apical4/11.jpg", "pv_pw"), ("tte-apical4/09.jpg", "tdi_sep"), ("tte-apical4/08.jpg", "tdi_lat")],
    },
    # -----------------------------------------------------------------------------------------
    {
        "id": "tricuspid",
        "title": t("Tricuspid valve", "Trikuspidalklappe", "الصمام ثلاثي الشرف"),
        "short": "TV",
        "views": ["apical_4c", "psax_av", "subcostal_4c", "subcostal_ivc"],
        "morphology": [
            item("A4C / RV-focused: leaflet morphology, coaptation gap, tethering, prolapse; device leads.",
                 "4K / RV-fokussiert: Segelmorphologie, Koaptationsdefekt, Tethering, Prolaps; Schrittmachersonden.",
                 "رباعي الحجرات / مركّز على البطين الأيمن: شكل الوريقات، فجوة الالتقاء، الشدّ، التدلي؛ أسلاك الناظمة.", "apical_4c"),
            item("Annulus diameter in diastole (A4C): > 40 mm or > 21 mm/m² = dilated.",
                 "Anulusdurchmesser diastolisch (4K): > 40 mm oder > 21 mm/m² = dilatiert.",
                 "قطر الحلقة في الانبساط (رباعي الحجرات): > 40 ملم أو > 21 ملم/م² = متوسّعة.", "apical_4c"),
            item("PSAX-AV and subcostal: additional leaflets and jet views.",
                 "PSAX-AV und subkostal: weitere Segel- und Jetansichten.",
                 "PSAX-AV وتحت الضلع: مشاهد إضافية للوريقات والنفث.", "psax_av"),
            item("RA and RV size, RV function (TAPSE ≥ 17 mm, S' ≥ 9.5 cm/s).",
                 "RA- und RV-Größe, RV-Funktion (TAPSE ≥ 17 mm, S' ≥ 9,5 cm/s).",
                 "حجم الأذين والبطين الأيمنين، وظيفة البطين الأيمن (TAPSE ≥ 17 ملم، S' ≥ 9.5 سم/ث).", "apical_4c"),
            item("IVC diameter and collapse → RA pressure.",
                 "VCI-Durchmesser und Kollaps → RA-Druck.",
                 "قطر الوريد الأجوف السفلي وانخماصه ← ضغط الأذين الأيمن.", "subcostal_ivc"),
        ],
        "color": [
            item("A4C: vena contracta, jet area, PISA (baseline shift, Nyquist ≈ 28 cm/s).",
                 "4K: Vena contracta, Jetfläche, PISA (Baseline-Shift, Nyquist ≈ 28 cm/s).",
                 "رباعي الحجرات: الوريد المنقبض، مساحة النفث، PISA (إزاحة خط الأساس، Nyquist ≈ 28 سم/ث).", "apical_4c"),
            item("Subcostal: colour in the hepatic veins (systolic backflow).",
                 "Subkostal: Farbe in den Lebervenen (systolischer Rückfluss).",
                 "تحت الضلع: اللون في الأوردة الكبدية (جريان راجع انقباضي).", "subcostal_ivc"),
        ],
        "spectral": [
            item("CW TR: align carefully, Vmax → RVSP = 4·Vmax² + RA pressure.",
                 "CW TI: sorgfältig ausrichten, Vmax → RVSP = 4·Vmax² + RA-Druck.",
                 "دوبلر مستمر للقلس: محاذاة دقيقة، Vmax ← ضغط البطين الأيمن الانقباضي = 4·Vmax² + ضغط الأذين الأيمن.", "apical_4c"),
            item("TR signal: dense, triangular, early peak = severe (low Vmax with severe TR = 'laminar' free TR).",
                 "TI-Signal: dicht, dreieckig, früher Gipfel = schwer (niedrige Vmax bei schwerer TI = 'laminare' freie TI).",
                 "إشارة القلس: كثيفة، مثلثية، ذروة مبكرة = شديد (سرعة منخفضة مع قلس شديد = قلس حرّ 'صفيحي').", "apical_4c"),
            item("Hepatic vein PW: systolic flow reversal = severe TR.",
                 "Lebervenen-PW: systolische Flussumkehr = schwere TI.",
                 "دوبلر نبضي في الأوردة الكبدية: انعكاس الجريان الانقباضي = قلس شديد.", "subcostal_ivc"),
            item("TS: PW/CW inflow – mean gradient, VTI, PHT.",
                 "TS: PW/CW-Einstrom – mittlerer Gradient, VTI, PHT.",
                 "تضيّق ثلاثي الشرف: دوبلر الجريان الداخل – الممال المتوسط، VTI، PHT.", "apical_4c"),
            item("RA pressure from the IVC: ≤ 2.1 cm and > 50 % collapse → 3 mmHg; > 2.1 cm and < 50 % → 15 mmHg; otherwise 8 mmHg.",
                 "RA-Druck aus der VCI: ≤ 2,1 cm und > 50 % Kollaps → 3 mmHg; > 2,1 cm und < 50 % → 15 mmHg; sonst 8 mmHg.",
                 "ضغط الأذين الأيمن من الوريد الأجوف: ≤ 2.1 سم وانخماص > 50 % ← 3؛ > 2.1 سم و< 50 % ← 15؛ غير ذلك 8 ملم زئبق.", "subcostal_ivc"),
        ],
        "normals": [
            normal(t("TR Vmax", "TI-Vmax", "سرعة القلس القصوى"), "≤ 2.8 m/s"),
            normal(t("RVSP", "RVSP", "ضغط البطين الأيمن الانقباضي"), "< 35 mmHg"),
            normal(t("TAPSE", "TAPSE", "TAPSE"), "≥ 17 mm"),
            normal(t("Tricuspid annulus (diastole)", "Trikuspidalanulus (diastolisch)", "الحلقة ثلاثية الشرف (انبساط)"), "≤ 40 mm (≤ 21 mm/m²)"),
            normal(t("IVC", "VCI", "الوريد الأجوف السفلي"), t("≤ 2.1 cm, > 50 % collapse", "≤ 2,1 cm, > 50 % Kollaps", "≤ 2.1 cm، انخماص > 50 %")),
        ],
        "grading": [
            {"title": t("Tricuspid regurgitation (ASE 2017)", "Trikuspidalinsuffizienz (ASE 2017)", "القلس ثلاثي الشرف (ASE 2017)"),
             "rows": [
                 row(t("Vena contracta", "Vena contracta", "الوريد المنقبض"), "< 0.3 cm", "0.3–0.69 cm", "≥ 0.7 cm"),
                 row(t("PISA radius (Nyquist 28 cm/s)", "PISA-Radius (Nyquist 28 cm/s)", "نصف قطر PISA (Nyquist 28)"), "≤ 0.5 cm", "0.6–0.9 cm", "> 0.9 cm"),
                 row(t("EROA", "EROA", "مساحة الفتحة الفعّالة"), "< 0.20 cm²", "0.20–0.39 cm²", "≥ 0.40 cm²"),
                 row(t("Regurgitant volume", "Regurgitationsvolumen", "حجم القلس"), "< 30 mL", "30–44 mL", "≥ 45 mL"),
                 row(t("Hepatic vein flow", "Lebervenenfluss", "جريان الأوردة الكبدية"), S_DOM, S_BLUNT, S_REV),
             ]},
            {"title": t("Tricuspid stenosis (EAE/ASE 2009)", "Trikuspidalstenose (EAE/ASE 2009)", "تضيّق ثلاثي الشرف (EAE/ASE 2009)"),
             "rows": [
                 row(t("Mean gradient", "Mittlerer Gradient", "الممال المتوسط"), "–", "–", "≥ 5 mmHg"),
                 row(t("Inflow VTI", "Einstrom-VTI", "VTI الجريان الداخل"), "–", "–", "> 60 cm"),
                 row(t("Pressure half-time", "Druckhalbwertszeit", "زمن نصف الضغط"), "–", "–", "≥ 190 ms"),
                 row(t("Valve area (continuity)", "Öffnungsfläche (Kontinuität)", "المساحة (الاستمرارية)"), "–", "–", "≤ 1.0 cm²"),
             ],
             "note": t("Values indicate significant TS; the guideline gives no mild/moderate cut-offs.",
                       "Die Werte zeigen eine relevante TS an; die Leitlinie nennt keine Grenzen für leicht/mittel.",
                       "القيم تدل على تضيّق مهم؛ لا يحدد الدليل حدوداً للخفيف/المتوسط.")},
        ],
        "examples": [("tte-apical4/05.jpg", "tr_color"), ("tte-apical4/12.jpg", "tapse"),
                     ("tte-subcostal-greatvessels/02.jpg", "hv_pw"), ("tte-subcostal-greatvessels/01.jpg", "ivc_m")],
    },
    # -----------------------------------------------------------------------------------------
    {
        "id": "pulmonary",
        "title": t("Pulmonary valve", "Pulmonalklappe", "الصمام الرئوي"),
        "short": "PV",
        "views": ["psax_av", "subcostal_4c"],
        "morphology": [
            item("PSAX-AV (tilted to RVOT/PA): leaflet thickening, systolic doming, annulus diameter.",
                 "PSAX-AV (Richtung RVOT/PA gekippt): Segelverdickung, systolisches Doming, Anulusdurchmesser.",
                 "PSAX-AV (مائل نحو RVOT/الشريان الرئوي): سماكة الوريقات، القبّة الانقباضية، قطر الحلقة.", "psax_av"),
            item("Main PA and branches: post-stenotic dilatation, PA dilatation in pulmonary hypertension.",
                 "PA-Hauptstamm und Äste: poststenotische Dilatation, PA-Dilatation bei pulmonaler Hypertonie.",
                 "الجذع الرئوي وفروعه: توسّع بعد التضيّق، توسّع الشريان الرئوي في ارتفاع الضغط الرئوي.", "psax_av"),
            item("Consequences: RV hypertrophy and dilatation, septal shape (PSAX).",
                 "Folgen: RV-Hypertrophie und -Dilatation, Septumform (PSAX).",
                 "العواقب: ضخامة وتوسّع البطين الأيمن، شكل الحاجز (PSAX).", "psax_papillary"),
        ],
        "color": [
            item("PSAX-AV: PR jet width at its origin relative to the annulus; flow acceleration in PS.",
                 "PSAX-AV: PI-Jetbreite am Ursprung im Verhältnis zum Anulus; Flussbeschleunigung bei PS.",
                 "PSAX-AV: عرض نفث القلس الرئوي عند منشئه بالنسبة للحلقة؛ تسارع الجريان في التضيّق.", "psax_av"),
            item("Look for a PDA (continuous flow into the PA) in the same view.",
                 "Im selben Schnitt nach einem PDA suchen (kontinuierlicher Fluss in die PA).",
                 "ابحث عن قناة شريانية سالكة (جريان مستمر إلى الشريان الرئوي) في نفس المقطع.", "psax_av"),
        ],
        "spectral": [
            item("PW in the RVOT just below the valve: acceleration time (< 100 ms suggests pulmonary hypertension).",
                 "PW im RVOT direkt unter der Klappe: Akzelerationszeit (< 100 ms spricht für pulmonale Hypertonie).",
                 "دوبلر نبضي في RVOT تحت الصمام: زمن التسارع (< 100 مللي ثانية يوحي بارتفاع الضغط الرئوي).", "psax_av"),
            item("CW across the valve: Vmax and peak gradient (PS).",
                 "CW über die Klappe: Vmax und Spitzengradient (PS).",
                 "دوبلر مستمر عبر الصمام: السرعة القصوى والممال الأقصى (التضيّق).", "psax_av"),
            item("CW PR: end-diastolic velocity → PA diastolic pressure = 4·v² + RA pressure; steep deceleration = severe PR.",
                 "CW PI: enddiastolische Geschwindigkeit → PA-Diastolendruck = 4·v² + RA-Druck; steile Dezeleration = schwere PI.",
                 "دوبلر مستمر للقلس الرئوي: السرعة في نهاية الانبساط ← الضغط الرئوي الانبساطي = 4·v² + ضغط الأذين الأيمن؛ تباطؤ حاد = قلس شديد.", "psax_av"),
        ],
        "normals": [
            normal(t("Peak velocity", "Spitzengeschwindigkeit", "السرعة القصوى"), "0.6–0.9 m/s"),
            normal(t("RVOT acceleration time", "RVOT-Akzelerationszeit", "زمن التسارع في RVOT"), "> 130 ms"),
        ],
        "grading": [
            {"title": t("Pulmonary stenosis (EAE/ASE 2009)", "Pulmonalstenose (EAE/ASE 2009)", "تضيّق الصمام الرئوي (EAE/ASE 2009)"),
             "rows": [
                 row(t("Vmax", "Vmax", "Vmax"), "< 3 m/s", "3–4 m/s", "> 4 m/s"),
                 row(t("Peak gradient", "Spitzengradient", "الممال الأقصى"), "< 36 mmHg", "36–64 mmHg", "> 64 mmHg"),
             ]},
            {"title": t("Pulmonary regurgitation (ASE 2017)", "Pulmonalinsuffizienz (ASE 2017)", "القلس الرئوي (ASE 2017)"),
             "rows": [
                 row(t("Jet width / annulus", "Jetbreite / Anulus", "عرض النفث / الحلقة"), t("small, narrow origin", "schmal, enger Ursprung", "رفيع، منشأ ضيق"), INTERMEDIATE, "≥ 0.7"),
                 row(t("CW signal", "CW-Signal", "إشارة الدوبلر المستمر"), t("faint, slow deceleration", "schwach, langsame Dezeleration", "ضعيفة، تباطؤ بطيء"),
                     t("dense, variable", "dicht, variabel", "كثيفة، متغيرة"),
                     t("dense, early termination", "dicht, früh endend", "كثيفة، تنتهي مبكراً")),
                 row(t("Pressure half-time", "Druckhalbwertszeit", "زمن نصف الضغط"), "–", "–", "< 100 ms"),
                 row(t("PR index", "PI-Index", "مؤشر القلس الرئوي"), "–", "–", "< 0.77"),
             ]},
        ],
        "examples": [("tte-psax/04.jpg", "pa"), ("tte-psax/02.jpg", "label")],
    },
]

EXTRA_CAPTIONS = {
    "mv_color": t("Colour Doppler: mitral valve (A4C)", "Farbdoppler: Mitralklappe (4K)", "الدوبلر الملوّن: الصمام التاجي"),
    "pv_pw": t("PW pulmonary vein (S, D, A)", "PW Pulmonalvene (S, D, A)", "دوبلر نبضي في الوريد الرئوي (S، D، A)"),
    "tr_color": t("Colour Doppler: TR jet (A4C)", "Farbdoppler: TI-Jet (4K)", "الدوبلر الملوّن: نفث القلس ثلاثي الشرف"),
    "hv_pw": t("PW hepatic vein (S, D, A)", "PW Lebervene (S, D, A)", "دوبلر نبضي في الوريد الكبدي (S، D، A)"),
}


def main():
    from integrate_echocardiographer import C  # captions shared with the view galleries

    captions = {**C, **EXTRA_CAPTIONS}
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    ids = {v["id"] for w in db["windows"] for v in w["views"]}
    used: dict = {}
    out = []
    for valve in VALVES:
        v = dict(valve)
        assert all(x in ids for x in v["views"]), v["id"]
        for section in ("morphology", "color", "spectral"):
            assert all(i.get("view") in ids for i in v[section] if i.get("view")), (v["id"], section)
        v["examples"] = [{**convert(src, used), "caption": captions[key]} for src, key in valve["examples"]]
        out.append(v)
    db["valves"] = out
    # Credit the newly bundled example images next to the gallery ones.
    attribution = os.path.join(ROOT, "app", "src", "main", "assets", "echoorg", "ATTRIBUTION.txt")
    with open(attribution, encoding="utf-8") as f:
        text = f.read()
    new = [f"{asset}  <-  https://www.echocardiographer.org/{page}  (valve assessment example)"
           for asset, _, page in used.values() if asset not in text]
    if new:
        with open(attribution, "a", encoding="utf-8") as f:
            f.write("\n".join(new) + "\n")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    items = sum(len(v[s]) for v in out for s in ("morphology", "color", "spectral"))
    rows = sum(len(g["rows"]) for v in out for g in v["grading"])
    print(f"{len(out)} valves, {items} checklist items, {rows} grading rows, {sum(len(v['examples']) for v in out)} examples")


if __name__ == "__main__":
    main()
