#!/usr/bin/env python3
"""
ECG timing, ED/ES measurement phases and ultrasound-console guidance for EchoScope (en / de / ar).

Merges into app/src/main/assets/echo_protocol_full.json:
  * top-level  "consoleControls": function of every console key/knob
  * per view   "ecg":             cardiac-cycle timing of the bundled loop (ms)
  * per view   "timingNote":      which ECG phase to freeze for which measurement
  * per view   "machineControls": ordered console steps for this view
  * per adult measurement "phases": ["ED"] / ["ES"] / ["ED", "ES"]

The bundled simulated loops (scripts/simulate_loops.py) run at 72 bpm with the R-wave
on frame 0: beat = 25 frames @ 30 fps = 833 ms, T-wave end ≈ 0.39 of the beat = 325 ms.
If a loop is replaced by a real clip, update that view's "ecg" values to the clip.

Usage: python3 scripts/console_timing_content.py
"""

import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")

SIMULATED_ECG = {"beatMs": 833, "edMs": 0, "esMs": 325}


def t(en, de, ar):
    return {"en": en, "de": de, "ar": ar}


CONSOLE_CONTROLS = {
    "GAIN": t("Overall 2D gain: brightness of all echoes. Aim for black blood pools and mid-grey myocardium.",
              "Gesamtverstärkung (2D): Helligkeit aller Echos. Ziel: schwarzes Blut, mittelgraues Myokard.",
              "الكسب الكلي (2D): سطوع كل الأصداء. الهدف: دم أسود وعضلة قلبية رمادية متوسطة."),
    "DEPTH": t("Image depth. The heart should fill about two thirds of the sector with a small margin below the structure of interest.",
               "Bildtiefe. Das Herz sollte etwa zwei Drittel des Sektors füllen, mit etwas Rand unter der Zielstruktur.",
               "عمق الصورة. يجب أن يملأ القلب نحو ثلثي القطاع مع هامش صغير تحت البنية المستهدفة."),
    "FOCUS": t("Focal zone: place it at the structure of interest for the best lateral resolution.",
               "Fokuszone: auf Höhe der Zielstruktur legen für die beste laterale Auflösung.",
               "منطقة التركيز: ضعها عند البنية المستهدفة لأفضل دقة جانبية."),
    "SCALE": t("Velocity scale (Nyquist limit) for Color, PW and CW. Raise it to avoid aliasing, lower it for slow flow.",
               "Geschwindigkeitsskala (Nyquist-Grenze) für Farbe, PW und CW. Erhöhen gegen Aliasing, senken für langsame Flüsse.",
               "مقياس السرعة (حد Nyquist) للدوبلر الملون وPW وCW. ارفعه لتجنب التشوّه (aliasing) واخفضه للجريان البطيء."),
    "2D": t("B-mode imaging: the base mode for every view and every Doppler acquisition.",
            "B-Bild: Grundmodus für jeden Schnitt und jede Doppler-Ableitung.",
            "التصوير ثنائي الأبعاد (B-mode): الوضع الأساسي لكل مقطع وكل قياس دوبلر."),
    "M-MODE": t("One scan line over time with very high temporal resolution (TAPSE, FS, IVC collapse).",
                "Eine Schalllinie über die Zeit mit sehr hoher zeitlicher Auflösung (TAPSE, FS, VCI-Kollaps).",
                "خط مسح واحد عبر الزمن بدقة زمنية عالية جداً (TAPSE، FS، انخماص الأجوف)."),
    "COLOR": t("Colour flow Doppler box: direction and turbulence of flow (red toward, blue away from the probe).",
               "Farbdoppler-Box: Richtung und Turbulenz des Flusses (rot auf den Schallkopf zu, blau weg).",
               "صندوق الدوبلر الملون: اتجاه الجريان واضطرابه (الأحمر نحو المجس، الأزرق بعيداً عنه)."),
    "PW": t("Pulsed-wave Doppler: velocity at one sample gate (LVOT, mitral inflow, pulmonary valve).",
            "Gepulster Doppler: Geschwindigkeit an einem Messvolumen (LVOT, Mitraleinstrom, Pulmonalklappe).",
            "الدوبلر النبضي: السرعة عند بوابة عينة واحدة (مخرج البطين الأيسر، التدفق التاجي، الصمام الرئوي)."),
    "CW": t("Continuous-wave Doppler: peak velocity anywhere along the cursor line (AS, TR, coarctation).",
            "Continuous-Wave-Doppler: Spitzengeschwindigkeit entlang der ganzen Cursorlinie (AS, TI, Isthmusstenose).",
            "الدوبلر المستمر: السرعة القصوى على طول خط المؤشر (تضيق أبهري، قلس ثلاثي الشرف، تضيق البرزخ)."),
    "TDI": t("Tissue Doppler imaging: myocardial velocities such as mitral e' and tricuspid S'.",
             "Gewebedoppler: Myokardgeschwindigkeiten wie mitrales e' und trikuspidales S'.",
             "دوبلر الأنسجة: سرعات العضلة القلبية مثل e' التاجي وS' ثلاثي الشرف."),
    "ZOOM": t("Magnifies a region of interest for precise measurements (LVOT, valves, aortic root).",
              "Vergrößert eine Region für präzise Messungen (LVOT, Klappen, Aortenwurzel).",
              "تكبير منطقة محددة لقياسات دقيقة (مخرج البطين الأيسر، الصمامات، جذر الأبهر)."),
    "TRACKBALL": t("Moves the cursor, Doppler gate, colour box and caliper points; scrolls cine frames when frozen.",
                   "Bewegt Cursor, Messvolumen, Farbbox und Caliper-Punkte; blättert im eingefrorenen Cine-Loop.",
                   "يحرّك المؤشر وبوابة الدوبلر وصندوق الألوان ونقاط القياس؛ ويتنقل بين إطارات المقطع المجمّد."),
    "FREEZE": t("Freezes the image. Scroll to the ED frame (R-wave) or ES frame (T-wave end) before measuring.",
                "Friert das Bild ein. Vor dem Messen zum ED-Bild (R-Zacke) oder ES-Bild (T-Ende) blättern.",
                "يجمّد الصورة. انتقل إلى إطار نهاية الانبساط (موجة R) أو نهاية الانقباض (نهاية T) قبل القياس."),
    "CALIPER": t("Starts a distance, area or trace measurement on the frozen frame.",
                 "Startet eine Strecken-, Flächen- oder Tracing-Messung im eingefrorenen Bild.",
                 "يبدأ قياس مسافة أو مساحة أو تتبّع على الإطار المجمّد."),
    "STORE": t("Saves the loop or image to the study.",
               "Speichert Loop oder Bild in der Untersuchung.",
               "يحفظ المقطع أو الصورة في الدراسة."),
}


def s(control, en, de, ar):
    return {"control": control, "instruction": t(en, de, ar)}


VIEWS = {
    "subcostal_4c": {
        "timingNote": t("Effusion and RV wall at ED (R-wave); colour over the atrial septum through the whole cycle.",
                        "Erguss und RV-Wand in der ED (R-Zacke); Farbdoppler über dem Vorhofseptum über den ganzen Zyklus.",
                        "الانصباب وجدار البطين الأيمن عند نهاية الانبساط (موجة R)؛ الدوبلر الملون على الحاجز الأذيني طوال الدورة."),
        "machineControls": [
            s("2D", "Start in 2D with the probe flat below the xiphoid.", "Im 2D-Modus beginnen, Schallkopf flach unter dem Xiphoid.", "ابدأ بوضع 2D والمجس مسطّح تحت الناتئ الرهابي."),
            s("DEPTH", "Set 16–20 cm so liver and the whole heart are visible.", "16–20 cm einstellen, sodass Leber und ganzes Herz sichtbar sind.", "اضبط العمق 16–20 سم ليظهر الكبد والقلب كاملاً."),
            s("FOCUS", "Move the focus to the atrial septum.", "Fokus auf das Vorhofseptum legen.", "حرّك التركيز إلى الحاجز الأذيني."),
            s("COLOR", "Place the colour box over the atrial septum.", "Farbbox über das Vorhofseptum legen.", "ضع صندوق الألوان فوق الحاجز الأذيني."),
            s("SCALE", "Lower the Nyquist limit to about 30–40 cm/s for low-velocity shunts.", "Nyquist-Grenze auf etwa 30–40 cm/s senken für langsame Shunts.", "اخفض حد Nyquist إلى نحو 30–40 سم/ث لكشف التحويلات بطيئة الجريان."),
            s("FREEZE", "Freeze and scroll to ED (R-wave) to measure an effusion or the RV wall.", "Einfrieren und zur ED (R-Zacke) blättern, um Erguss oder RV-Wand zu messen.", "جمّد الصورة وانتقل إلى نهاية الانبساط (موجة R) لقياس الانصباب أو جدار البطين الأيمن."),
            s("STORE", "Store the loop.", "Loop speichern.", "احفظ المقطع."),
        ],
    },
    "subcostal_ivc": {
        "timingNote": t("The IVC follows respiration: maximum diameter at end-expiration, minimum after a sniff (ECG timing is secondary).",
                        "Die VCI folgt der Atmung: maximaler Durchmesser endexspiratorisch, minimaler nach dem Schnüffeln (EKG-Timing nachrangig).",
                        "الأجوف السفلي يتبع التنفس: أقصى قطر في نهاية الزفير وأدناه بعد الشهيق السريع (توقيت ECG ثانوي)."),
        "machineControls": [
            s("2D", "Rotate to the sagittal IVC view in 2D.", "Im 2D-Modus in den sagittalen VCI-Schnitt drehen.", "دوّر إلى المقطع السهمي للأجوف السفلي بوضع 2D."),
            s("DEPTH", "Set 12–16 cm so the IVC–RA junction is centred.", "12–16 cm einstellen, Einmündung VCI–RA zentriert.", "اضبط العمق 12–16 سم ليتوسط ملتقى الأجوف بالأذين الأيمن."),
            s("M-MODE", "Place the M-mode line perpendicular through the IVC 1–2 cm from the RA.", "M-Mode-Linie senkrecht durch die VCI 1–2 cm vor dem RA legen.", "ضع خط M-Mode عمودياً عبر الأجوف على بعد 1–2 سم من الأذين الأيمن."),
            s("FREEZE", "Freeze after a sniff manoeuvre.", "Nach dem Schnüffelmanöver einfrieren.", "جمّد الصورة بعد مناورة الشهيق السريع."),
            s("CALIPER", "Measure the maximum and minimum diameter; calculate collapse in %.", "Maximalen und minimalen Durchmesser messen; Kollaps in % berechnen.", "قِس القطر الأقصى والأدنى واحسب نسبة الانخماص."),
            s("STORE", "Store the M-mode trace.", "M-Mode-Kurve speichern.", "احفظ منحنى M-Mode."),
        ],
    },
    "plax_standard": {
        "timingNote": t("IVSd, LVIDd, LVPWd and aortic root at ED (R-wave); LA diameter at ES (T-end); LVOT in mid-systole.",
                        "IVSd, LVIDd, LVPWd und Aortenwurzel in der ED (R-Zacke); LA-Durchmesser in der ES (T-Ende); LVOT mittsystolisch.",
                        "IVSd وLVIDd وLVPWd وجذر الأبهر عند نهاية الانبساط (موجة R)؛ قطر الأذين الأيسر عند نهاية الانقباض (نهاية T)؛ مخرج البطين الأيسر في منتصف الانقباض."),
        "machineControls": [
            s("2D", "Acquire PLAX in 2D with septum and posterior wall horizontal.", "PLAX im 2D-Modus einstellen, Septum und Hinterwand horizontal.", "اضبط PLAX بوضع 2D مع الحاجز والجدار الخلفي أفقيين."),
            s("DEPTH", "Set about 16 cm so the descending aorta is just visible.", "Etwa 16 cm einstellen, sodass die Aorta descendens gerade sichtbar ist.", "اضبط نحو 16 سم ليظهر الأبهر النازل بالكاد."),
            s("FOCUS", "Place the focus at the mitral leaflet tips.", "Fokus auf Höhe der Mitralsegelspitzen legen.", "ضع التركيز عند أطراف وريقات الصمام التاجي."),
            s("FREEZE", "Freeze and scroll with the trackball to ED (R-wave).", "Einfrieren und mit dem Trackball zur ED (R-Zacke) blättern.", "جمّد الصورة وانتقل بكرة التتبع إلى نهاية الانبساط (موجة R)."),
            s("CALIPER", "Measure IVSd, LVIDd and LVPWd at the leaflet tips; then go to ES (T-end) for the LA.", "IVSd, LVIDd und LVPWd an den Segelspitzen messen; dann zur ES (T-Ende) für den LA.", "قِس IVSd وLVIDd وLVPWd عند أطراف الوريقات؛ ثم انتقل إلى نهاية الانقباض (نهاية T) لقياس الأذين الأيسر."),
            s("ZOOM", "Zoom the LVOT and aortic root for precise diameters.", "LVOT und Aortenwurzel für präzise Durchmesser zoomen.", "كبّر مخرج البطين الأيسر وجذر الأبهر لقياس دقيق."),
            s("COLOR", "Check mitral and aortic valves with colour Doppler.", "Mitral- und Aortenklappe mit Farbdoppler prüfen.", "افحص الصمامين التاجي والأبهري بالدوبلر الملون."),
            s("STORE", "Store loops and measurements.", "Loops und Messungen speichern.", "احفظ المقاطع والقياسات."),
        ],
    },
    "psax_av": {
        "timingNote": t("RVOT and PA diameters at ED; valve morphology in systole (opening) and diastole (closure); pediatric aortic dimensions in systole.",
                        "RVOT- und PA-Durchmesser in der ED; Klappenmorphologie in Systole (Öffnung) und Diastole (Schluss); pädiatrische Aortenmaße in der Systole.",
                        "أقطار مخرج البطين الأيمن والشريان الرئوي عند نهاية الانبساط؛ شكل الصمام في الانقباض (الانفتاح) والانبساط (الإغلاق)؛ أبعاد الأبهر عند الأطفال في الانقباض."),
        "machineControls": [
            s("2D", "Rotate to PSAX at the aortic valve level in 2D.", "Im 2D-Modus in die PSAX auf Aortenklappenebene drehen.", "دوّر إلى المحور القصير عند مستوى الصمام الأبهري بوضع 2D."),
            s("ZOOM", "Zoom the aortic valve to judge the number of cusps.", "Aortenklappe zoomen, um die Taschenzahl zu beurteilen.", "كبّر الصمام الأبهري لتقييم عدد الشرفات."),
            s("COLOR", "Place the colour box over RVOT, pulmonary valve and PA bifurcation.", "Farbbox über RVOT, Pulmonalklappe und PA-Bifurkation legen.", "ضع صندوق الألوان فوق مخرج البطين الأيمن والصمام الرئوي وتفرع الشريان الرئوي."),
            s("TRACKBALL", "Move the PW gate to the pulmonary valve with the trackball.", "PW-Messvolumen mit dem Trackball an die Pulmonalklappe bewegen.", "حرّك بوابة PW إلى الصمام الرئوي بكرة التتبع."),
            s("PW", "Record PW at the pulmonary valve and measure the acceleration time.", "PW an der Pulmonalklappe ableiten und Akzelerationszeit messen.", "سجّل PW عند الصمام الرئوي وقِس زمن التسارع."),
            s("SCALE", "Adjust the scale so the spectrum fills two thirds of the display.", "Skala anpassen, sodass das Spektrum zwei Drittel der Anzeige füllt.", "اضبط المقياس ليملأ الطيف ثلثي الشاشة."),
            s("STORE", "Store loops and Doppler traces.", "Loops und Dopplerkurven speichern.", "احفظ المقاطع ومنحنيات الدوبلر."),
        ],
    },
    "psax_papillary": {
        "timingNote": t("LVIDd at ED (R-wave) and LVIDs at ES (T-end) for FS; septal shape in both phases.",
                        "LVIDd in der ED (R-Zacke) und LVIDs in der ES (T-Ende) für die FS; Septumform in beiden Phasen.",
                        "LVIDd عند نهاية الانبساط (موجة R) وLVIDs عند نهاية الانقباض (نهاية T) لحساب FS؛ شكل الحاجز في الطورين."),
        "machineControls": [
            s("2D", "Tilt to the papillary muscle level in 2D; the LV must be round.", "Im 2D-Modus auf Papillarmuskelebene kippen; der LV muss rund sein.", "أمِل إلى مستوى العضلات الحليمية بوضع 2D؛ يجب أن يكون البطين دائرياً."),
            s("GAIN", "Adjust gain until the endocardium is clearly defined.", "Verstärkung anpassen, bis das Endokard klar abgegrenzt ist.", "اضبط الكسب حتى تتضح حدود الشغاف."),
            s("M-MODE", "Place the M-mode line through the centre of the LV between the papillary muscles.", "M-Mode-Linie durch die LV-Mitte zwischen den Papillarmuskeln legen.", "ضع خط M-Mode عبر مركز البطين بين العضلتين الحليميتين."),
            s("FREEZE", "Freeze a trace with at least two cardiac cycles.", "Eine Kurve mit mindestens zwei Herzzyklen einfrieren.", "جمّد منحنى يحتوي دورتين قلبيتين على الأقل."),
            s("CALIPER", "Measure LVIDd at the R-wave and LVIDs at the T-wave end; FS is calculated.", "LVIDd an der R-Zacke und LVIDs am T-Ende messen; die FS wird berechnet.", "قِس LVIDd عند موجة R وLVIDs عند نهاية T؛ يُحسب FS."),
            s("STORE", "Store the loop and the M-mode trace.", "Loop und M-Mode-Kurve speichern.", "احفظ المقطع ومنحنى M-Mode."),
        ],
    },
    "apical_4c": {
        "timingNote": t("Simpson: trace at ED (R-wave) and ES (T-end); LAVI and RA area at ES; RV basal diameter at ED.",
                        "Simpson: Tracing in der ED (R-Zacke) und ES (T-Ende); LAVI und RA-Fläche in der ES; basaler RV-Durchmesser in der ED.",
                        "سيمبسون: التتبّع عند نهاية الانبساط (موجة R) ونهاية الانقباض (نهاية T)؛ LAVI ومساحة الأذين الأيمن عند نهاية الانقباض؛ القطر القاعدي للبطين الأيمن عند نهاية الانبساط."),
        "machineControls": [
            s("2D", "Acquire A4C in 2D without foreshortening.", "4K-Blick im 2D-Modus ohne Verkürzung einstellen.", "اضبط المقطع القمي رباعي الحجرات بوضع 2D دون تقصير."),
            s("DEPTH", "Set the depth so both atria are completely visible.", "Tiefe so einstellen, dass beide Vorhöfe vollständig sichtbar sind.", "اضبط العمق ليظهر الأذينان كاملين."),
            s("FREEZE", "Freeze and select the ED (R-wave) and ES (T-end) frames.", "Einfrieren und die Bilder für ED (R-Zacke) und ES (T-Ende) auswählen.", "جمّد الصورة واختر إطاري نهاية الانبساط (موجة R) ونهاية الانقباض (نهاية T)."),
            s("CALIPER", "Trace the LV endocardium (Simpson) and the LA at ES.", "LV-Endokard (Simpson) und den LA in der ES umfahren.", "تتبّع شغاف البطين الأيسر (سيمبسون) والأذين الأيسر عند نهاية الانقباض."),
            s("M-MODE", "Place M-mode through the lateral tricuspid annulus for TAPSE.", "M-Mode durch den lateralen Trikuspidalanulus für die TAPSE legen.", "ضع M-Mode عبر الحلقة الجانبية لثلاثي الشرف لقياس TAPSE."),
            s("PW", "Place the PW gate at the mitral leaflet tips for E and A.", "PW-Messvolumen an die Mitralsegelspitzen für E und A legen.", "ضع بوابة PW عند أطراف الوريقات التاجية لقياس E وA."),
            s("TDI", "Switch to TDI and sample the septal and lateral mitral annulus (e').", "Auf TDI umschalten und septalen und lateralen Mitralanulus ableiten (e').", "بدّل إلى TDI وسجّل الحلقة التاجية الحاجزية والجانبية (e')."),
            s("CW", "Align CW through the tricuspid regurgitation jet for the TR velocity.", "CW durch den TI-Jet für die TI-Geschwindigkeit legen.", "مرّر CW عبر نفث قلس ثلاثي الشرف لقياس سرعته."),
            s("STORE", "Store loops and all traces.", "Loops und alle Kurven speichern.", "احفظ المقاطع وكل المنحنيات."),
        ],
    },
    "apical_5c": {
        "timingNote": t("Doppler over the ejection period: LVOT VTI and aortic CW from just after the QRS to the T-wave end.",
                        "Doppler über die Austreibungsphase: LVOT-VTI und Aorten-CW von kurz nach dem QRS bis zum T-Ende.",
                        "الدوبلر خلال فترة القذف: VTI مخرج البطين الأيسر وCW الأبهري من بعد QRS مباشرة حتى نهاية T."),
        "machineControls": [
            s("2D", "Tilt from A4C to A5C in 2D.", "Im 2D-Modus vom 4K- in den 5K-Blick kippen.", "أمِل من المقطع الرباعي إلى الخماسي بوضع 2D."),
            s("COLOR", "Check the LVOT and aortic valve for regurgitation or turbulence.", "LVOT und Aortenklappe auf Insuffizienz oder Turbulenz prüfen.", "افحص مخرج البطين الأيسر والصمام الأبهري بحثاً عن قلس أو اضطراب."),
            s("TRACKBALL", "Move the PW gate 0.5–1 cm below the aortic valve.", "PW-Messvolumen 0,5–1 cm unterhalb der Aortenklappe platzieren.", "حرّك بوابة PW إلى 0.5–1 سم تحت الصمام الأبهري."),
            s("PW", "Record the LVOT spectrum and trace the VTI.", "LVOT-Spektrum ableiten und VTI umfahren.", "سجّل طيف مخرج البطين الأيسر وتتبّع VTI."),
            s("CW", "Align CW through the aortic valve for Vmax and mean gradient.", "CW durch die Aortenklappe für Vmax und mittleren Gradienten legen.", "مرّر CW عبر الصمام الأبهري لقياس Vmax والمدروج الوسطي."),
            s("SCALE", "Shift the baseline and scale so the full envelope is shown.", "Nulllinie und Skala so verschieben, dass die ganze Hüllkurve sichtbar ist.", "حرّك خط الأساس والمقياس ليظهر الغلاف كاملاً."),
            s("STORE", "Store the Doppler traces.", "Dopplerkurven speichern.", "احفظ منحنيات الدوبلر."),
        ],
    },
    "apical_2c_3c": {
        "timingNote": t("Simpson in A2C at ED (R-wave) and ES (T-end); GLS uses QRS onset as the end-diastolic reference.",
                        "Simpson im 2K-Blick in ED (R-Zacke) und ES (T-Ende); GLS nutzt den QRS-Beginn als enddiastolische Referenz.",
                        "سيمبسون في ثنائي الحجرات عند نهاية الانبساط (موجة R) ونهاية الانقباض (نهاية T)؛ GLS يعتمد بداية QRS مرجعاً لنهاية الانبساط."),
        "machineControls": [
            s("2D", "Rotate counter-clockwise from A4C to A2C, then to A3C.", "Vom 4K-Blick gegen den Uhrzeigersinn in den 2K-, dann den 3K-Blick drehen.", "دوّر عكس عقارب الساعة من الرباعي إلى الثنائي ثم الثلاثي."),
            s("FREEZE", "Freeze A2C and select the ED and ES frames.", "2K-Blick einfrieren und ED- und ES-Bild auswählen.", "جمّد المقطع الثنائي واختر إطاري نهاية الانبساط والانقباض."),
            s("CALIPER", "Trace the endocardium for the biplane EF.", "Endokard für die biplane EF umfahren.", "تتبّع الشغاف لحساب الكسر القذفي ثنائي المستوى."),
            s("COLOR", "In A3C check the mitral and aortic valves with colour.", "Im 3K-Blick Mitral- und Aortenklappe mit Farbe prüfen.", "في المقطع الثلاثي افحص الصمامين التاجي والأبهري بالدوبلر الملون."),
            s("STORE", "Store A2C and A3C loops at a high frame rate for strain.", "2K- und 3K-Loops mit hoher Bildrate für Strain speichern.", "احفظ مقطعي الثنائي والثلاثي بمعدل إطارات عالٍ لتحليل الإجهاد."),
        ],
    },
    "suprasternal_arch": {
        "timingNote": t("Arch and isthmus diameters at ED in adults (systole in children); check for a diastolic tail after the T-wave end.",
                        "Bogen- und Isthmusdurchmesser bei Erwachsenen in der ED (bei Kindern in der Systole); nach dem T-Ende auf einen diastolischen Ausläufer achten.",
                        "أقطار القوس والبرزخ عند نهاية الانبساط عند البالغين (وفي الانقباض عند الأطفال)؛ ابحث عن الذيل الانبساطي بعد نهاية T."),
        "machineControls": [
            s("2D", "Acquire the arch long axis in 2D with the neck extended.", "Lange Achse des Bogens im 2D-Modus bei rekliniertem Hals einstellen.", "اضبط المحور الطويل للقوس بوضع 2D مع بسط الرقبة."),
            s("DEPTH", "Set the depth to show the arch and the descending aorta.", "Tiefe so wählen, dass Bogen und Aorta descendens sichtbar sind.", "اضبط العمق ليظهر القوس والأبهر النازل."),
            s("COLOR", "Place the colour box over the isthmus and descending aorta.", "Farbbox über Isthmus und Aorta descendens legen.", "ضع صندوق الألوان فوق البرزخ والأبهر النازل."),
            s("CW", "Align CW through the descending aorta.", "CW durch die Aorta descendens legen.", "مرّر CW عبر الأبهر النازل."),
            s("SCALE", "Adjust scale and baseline to see the whole diastolic flow.", "Skala und Nulllinie anpassen, um den gesamten diastolischen Fluss zu sehen.", "اضبط المقياس وخط الأساس لرؤية الجريان الانبساطي كاملاً."),
            s("STORE", "Store the loop and the Doppler trace.", "Loop und Dopplerkurve speichern.", "احفظ المقطع ومنحنى الدوبلر."),
        ],
    },
}

# ED / ES phase for adult measurements (matched on the English parameter name)
PHASES = {
    "RV free wall thickness (end-diastole)": ["ED"],
    "Pericardial effusion (end-diastole)": ["ED"],
    "IVSd": ["ED"],
    "LVIDd": ["ED"],
    "LVPWd": ["ED"],
    "Relative wall thickness": ["ED"],
    "Aortic root (sinuses of Valsalva)": ["ED"],
    "LA AP diameter": ["ES"],
    "RVOT proximal (PSAX)": ["ED"],
    "RVOT distal (pre-pulmonic)": ["ED"],
    "Main pulmonary artery": ["ED"],
    "Fractional shortening": ["ED", "ES"],
    "LV geometry": ["ED", "ES"],
    "Eccentricity index": ["ED", "ES"],
    "LVEF (biplane Simpson)": ["ED", "ES"],
    "RV basal diameter": ["ED"],
    "LA volume index (LAVI)": ["ES"],
    "RA area": ["ES"],
    "LVEF (biplane, with A4C)": ["ED", "ES"],
    "LA volume (A2C plane)": ["ES"],
    "Transverse aortic arch diameter": ["ED"],
}


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    db["schemaVersion"] = 4
    db["consoleControls"] = CONSOLE_CONTROLS
    tagged = 0
    for window in db["windows"]:
        for view in window["views"]:
            content = VIEWS[view["id"]]
            if view.get("videoSource", {}).get("type", "simulated") != "real":
                view["ecg"] = dict(SIMULATED_ECG)   # real clips keep their measured timing
            view["timingNote"] = content["timingNote"]
            view["machineControls"] = content["machineControls"]
            for control in content["machineControls"]:
                assert control["control"] in CONSOLE_CONTROLS, control["control"]
            for m in view["referenceMeasurements"]["adult"]:
                phases = PHASES.get(m["parameter"]["en"])
                if phases:
                    m["phases"] = phases
                    tagged += 1
                else:
                    m.pop("phases", None)
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    steps = sum(len(v["machineControls"]) for v in VIEWS.values())
    print(f"merged ECG timing + timing notes for {len(VIEWS)} views, {steps} console steps, "
          f"{len(CONSOLE_CONTROLS)} console controls, {tagged} ED/ES-tagged measurements")


if __name__ == "__main__":
    main()
