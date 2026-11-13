#!/usr/bin/env python3
"""
Guided-exam steps for EchoScope (en / de / ar).

Merges the `steps` array of every view into app/src/main/assets/echo_protocol_full.json.
Caliper coordinates are [x1, y1, x2, y2] in the 600x420 space of the bundled SVG diagrams.

Usage: python3 scripts/guided_steps.py
"""

import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")


def t(en, de, ar):
    return {"en": en, "de": de, "ar": ar}


def step(title, instruction, normal=None, note=None, caliper=None):
    s = {"title": title, "instruction": instruction}
    if normal is not None:
        s["normal"] = normal
    if note is not None:
        s["note"] = note
    if caliper is not None:
        s["caliper"] = caliper
    return s


STEPS = {
    "subcostal_4c": [
        step(
            t("Probe position", "Schallkopfposition", "وضع المجس"),
            t("Patient supine with knees slightly flexed. Place the probe below the xiphoid, almost flat on the abdomen, notch to the patient's left, and aim the beam at the left shoulder.",
              "Patient in Rückenlage, Knie leicht angewinkelt. Schallkopf unterhalb des Xiphoids fast flach auf den Bauch legen, Markierung zur linken Patientenseite, Schallstrahl auf die linke Schulter richten.",
              "المريض مستلقٍ والركبتان مثنيتان قليلاً. ضع المجس تحت الناتئ الرهابي شبه مسطّح على البطن، والمؤشر نحو يسار المريض، ووجّه الحزمة نحو الكتف الأيسر."),
            note=t("Ask for a deep breath and breath-hold: the heart descends toward the probe and the image improves.",
                   "Tiefe Inspiration und Atemanhalten: Das Herz tritt tiefer und das Bild wird besser.",
                   "اطلب شهيقاً عميقاً وحبس النفس: ينزل القلب نحو المجس وتتحسن الصورة."),
        ),
        step(
            t("Identify the chambers", "Kammern identifizieren", "تعرّف على الحجرات"),
            t("The liver is at the top of the screen; below it the right heart, then the left heart. The atria lie on the left of the screen.",
              "Die Leber liegt oben im Bild, darunter das rechte, dann das linke Herz. Die Vorhöfe liegen links im Bild.",
              "الكبد في أعلى الشاشة، تحته القلب الأيمن ثم القلب الأيسر. الأذينان إلى يسار الشاشة."),
        ),
        step(
            t("Interatrial septum", "Vorhofseptum", "الحاجز بين الأذينين"),
            t("Here the beam is perpendicular to the atrial septum, which makes this the best view for ASD / PFO. Apply colour Doppler to the septum with a low Nyquist limit.",
              "Hier steht der Schallstrahl senkrecht auf dem Vorhofseptum – der beste Schnitt für ASD / PFO. Farbdoppler mit niedriger Nyquist-Grenze auf das Septum legen.",
              "هنا الحزمة عمودية على الحاجز الأذيني، فهو أفضل مقطع للبحث عن ASD/PFO. ضع الدوبلر الملون على الحاجز بسرعة Nyquist منخفضة."),
            normal=t("Intact septum · Nyquist ≈ 30–40 cm/s", "Intaktes Septum · Nyquist ≈ 30–40 cm/s", "حاجز سليم · Nyquist ≈ 30–40 سم/ث"),
            note=t("In apical views the septum can look falsely perforated (drop-out) because the beam runs parallel to it.",
                   "In apikalen Schnitten kann das Septum fälschlich perforiert wirken (Drop-out), weil der Schallstrahl parallel verläuft.",
                   "في المقطع القمّي قد يظهر الحاجز «مثقوباً» كاذباً (drop-out) لأن الحزمة موازية له."),
            caliper=[272, 244, 213, 244],
        ),
        step(
            t("Pericardium", "Perikard", "التامور"),
            t("Look for an echo-free space around the heart, especially between the liver and the right ventricle; measure it at end-diastole.",
              "Nach einem echofreien Saum um das Herz suchen, besonders zwischen Leber und rechtem Ventrikel; enddiastolisch messen.",
              "ابحث عن فراغ أسود حول القلب، خصوصاً بين الكبد والبطين الأيمن، وقِسه في نهاية الانبساط."),
            normal=t("No effusion · small < 10 mm", "Kein Erguss · klein < 10 mm", "لا انصباب · صغير < 10 مم"),
            caliper=[364, 164, 374, 150],
        ),
    ],
    "subcostal_ivc": [
        step(
            t("Rotate the probe", "Schallkopf drehen", "تدوير المجس"),
            t("From the four-chamber view centre the right atrium, then rotate 90° counter-clockwise until the notch points to the head. The IVC appears in long axis entering the right atrium.",
              "Aus dem Vierkammerblick den rechten Vorhof zentrieren, dann um 90° gegen den Uhrzeigersinn drehen, bis die Markierung nach kranial zeigt. Die VCI erscheint längs mit Einmündung in den rechten Vorhof.",
              "من مقطع الحجرات الأربع، ركّز على الأذين الأيمن ثم دوّر المجس 90° عكس عقارب الساعة حتى يصبح المؤشر نحو الرأس. يظهر الوريد الأجوف السفلي طولياً داخلاً إلى الأذين الأيمن."),
            note=t("To tell it from the aorta: the IVC runs through the liver, drains into the right atrium and receives the hepatic veins. The aorta is pulsatile and thick-walled.",
                   "Abgrenzung zur Aorta: Die VCI verläuft durch die Leber, mündet in den rechten Vorhof und nimmt die Lebervenen auf. Die Aorta pulsiert und ist dickwandig.",
                   "للتمييز عن الأبهر: الوريد الأجوف يمر داخل الكبد، يصب في الأذين الأيمن، وتصب فيه الأوردة الكبدية. الأبهر نابض وسميك الجدار."),
        ),
        step(
            t("Measure the IVC diameter", "VCI-Durchmesser messen", "قياس قطر IVC"),
            t("Freeze at end-expiration and measure perpendicular to the long axis, 1–2 cm from the right-atrial junction.",
              "Endexspiratorisch einfrieren und senkrecht zur Längsachse messen, 1–2 cm vor der Einmündung in den rechten Vorhof.",
              "ثبّت الصورة في نهاية الزفير وقِس القطر عمودياً على المحور الطويل، على بعد 1–2 سم من مدخل الأذين الأيمن."),
            normal=t("≤ 2.1 cm", "≤ 2,1 cm", "≤ 2.1 سم"),
            caliper=[377, 236, 383, 276],
        ),
        step(
            t("Collapsibility test", "Kollapsibilitätstest", "اختبار الانخماص"),
            t("Ask the patient to sniff quickly and watch the diameter fall (M-mode at the same site can help).",
              "Den Patienten kurz und kräftig schnüffeln lassen und die Abnahme des Durchmessers beobachten (M-Mode an gleicher Stelle möglich).",
              "اطلب من المريض أن «يشمّ» بسرعة وراقب نقص القطر (يمكن استخدام M-Mode في نفس الموضع)."),
            normal=t("Collapse > 50 % → RAP ≈ 3 mmHg (0–5)", "Kollaps > 50 % → RAP ≈ 3 mmHg (0–5)", "انخماص > 50٪ ← RAP ≈ 3 ملم زئبق (0–5)"),
            note=t("Diameter > 2.1 cm with < 50 % collapse fits RAP ≈ 15 mmHg. The rule is less reliable in children and in mechanically ventilated patients.",
                   "Durchmesser > 2,1 cm mit < 50 % Kollaps entspricht RAP ≈ 15 mmHg. Bei Kindern und beatmeten Patienten ist die Regel weniger verlässlich.",
                   "قطر > 2.1 سم مع انخماص < 50٪ يوافق RAP ≈ 15 ملم زئبق. عند الأطفال والمرضى على التهوية الآلية القاعدة أقل موثوقية."),
        ),
    ],
    "plax_standard": [
        step(
            t("Probe position", "Schallkopfposition", "وضع المجس"),
            t("Patient in left lateral decubitus. Place the probe in the 3rd or 4th intercostal space left of the sternum, notch toward the right shoulder.",
              "Patient in Linksseitenlage. Schallkopf im 3. oder 4. ICR links parasternal, Markierung zur rechten Schulter.",
              "المريض على جنبه الأيسر. ضع المجس في الورب الثالث أو الرابع يسار القص، والمؤشر نحو الكتف الأيمن."),
            note=t("If a rounded apex appears in the image you are too low: move up one intercostal space.",
                   "Erscheint eine abgerundete Spitze im Bild, liegt der Schallkopf zu tief: einen ICR höher gehen.",
                   "إذا ظهرت القمة مدوّرة داخل الصورة فأنت منخفض جداً: اصعد ورباً واحداً."),
        ),
        step(
            t("Optimise the view", "Schnitt optimieren", "ضبط المقطع"),
            t("Make the septum and posterior wall horizontal and parallel, with the mitral and aortic valves in the centre. The apex should not be visible.",
              "Septum und Hinterwand horizontal und parallel einstellen, Mitral- und Aortenklappe in der Bildmitte. Die Herzspitze sollte nicht sichtbar sein.",
              "اجعل الحاجز والجدار الخلفي أفقيين ومتوازيين، والصمامين التاجي والأبهري في منتصف الصورة. القمة يجب ألا تظهر."),
        ),
        step(
            t("Freeze end-diastole", "Enddiastole einfrieren", "تثبيت نهاية الانبساط"),
            t("Freeze and step frame by frame to the first frame after mitral valve closure (largest LV). All wall measurements are taken at the level of the mitral leaflet tips.",
              "Einfrieren und Bild für Bild bis zum ersten Bild nach Mitralklappenschluss gehen (größter LV). Alle Wandmessungen auf Höhe der Mitralsegelspitzen.",
              "أوقف المقطع وتحرك إطاراً إطاراً حتى أول إطار بعد انغلاق الصمام التاجي (أكبر حجم للبطين). كل قياسات الجدران تؤخذ عند مستوى أطراف وريقات الصمام التاجي."),
        ),
        step(
            t("Measure IVSd", "IVSd messen", "قياس IVSd"),
            t("Measure the interventricular septal thickness perpendicular to the LV long axis.",
              "Dicke des interventrikulären Septums senkrecht zur LV-Längsachse messen.",
              "قِس سُمك الحاجز بين البطينين عمودياً على محور البطين الأيسر."),
            normal=t("0.6–1.0 cm (M) · 0.6–0.9 cm (F)", "0,6–1,0 cm (M) · 0,6–0,9 cm (F)", "0.6–1.0 سم (ذكور) · 0.6–0.9 سم (إناث)"),
            note=t("Do not include the moderator band or other RV structures.",
                   "Moderatorband und andere RV-Strukturen nicht mitmessen.",
                   "لا تُدخل الحزمة المعدِّلة (moderator band) أو تراكيب البطين الأيمن في القياس."),
            caliper=[300, 150, 300, 171],
        ),
        step(
            t("Measure LVIDd", "LVIDd messen", "قياس LVIDd"),
            t("On the same line: from the inner edge of the septum to the inner edge of the posterior wall.",
              "Auf derselben Linie: von der Innenkante des Septums bis zur Innenkante der Hinterwand.",
              "على نفس الخط: من حافة الحاجز الداخلية إلى حافة الجدار الخلفي الداخلية."),
            normal=t("4.2–5.8 cm (M) · 3.8–5.2 cm (F)", "4,2–5,8 cm (M) · 3,8–5,2 cm (F)", "4.2–5.8 سم (ذكور) · 3.8–5.2 سم (إناث)"),
            caliper=[300, 172, 300, 263],
        ),
        step(
            t("Measure LVPWd", "LVPWd messen", "قياس LVPWd"),
            t("Continue on the same line: posterior wall thickness without chordae or pericardium.",
              "Auf derselben Linie weiter: Hinterwanddicke ohne Chordae und Perikard.",
              "أكمل على نفس الخط: سُمك الجدار الخلفي دون إدخال الحبال الوترية أو التامور."),
            normal=t("0.6–1.0 cm (M) · 0.6–0.9 cm (F)", "0,6–1,0 cm (M) · 0,6–0,9 cm (F)", "0.6–1.0 سم (ذكور) · 0.6–0.9 سم (إناث)"),
            caliper=[300, 264, 300, 288],
        ),
        step(
            t("Aortic root", "Aortenwurzel", "جذر الأبهر"),
            t("Measure at the sinuses of Valsalva at end-diastole, leading edge to leading edge.",
              "Auf Höhe der Sinus Valsalvae enddiastolisch messen, Leading Edge zu Leading Edge.",
              "قِس عند جيوب فالسالفا في نهاية الانبساط بطريقة الحافة الأمامية إلى الحافة الأمامية (leading edge)."),
            normal=t("2.8–4.0 cm (M) · 2.4–3.6 cm (F)", "2,8–4,0 cm (M) · 2,4–3,6 cm (F)", "2.8–4.0 سم (ذكور) · 2.4–3.6 سم (إناث)"),
            caliper=[440, 152, 440, 228],
        ),
        step(
            t("Left atrium", "Linker Vorhof", "الأذين الأيسر"),
            t("Advance to end-systole (just before mitral opening) and measure the anteroposterior diameter.",
              "Bis zur Endsystole (kurz vor Mitralklappenöffnung) weitergehen und den anteroposterioren Durchmesser messen.",
              "تقدّم إلى نهاية الانقباض (قبيل انفتاح الصمام التاجي) وقِس القطر الأمامي الخلفي."),
            normal=t("3.0–4.0 cm (M) · 2.7–3.8 cm (F)", "3,0–4,0 cm (M) · 2,7–3,8 cm (F)", "3.0–4.0 سم (ذكور) · 2.7–3.8 سم (إناث)"),
            note=t("The diameter alone underestimates enlargement; grade with LAVI from the apical views.",
                   "Der Durchmesser allein unterschätzt eine Dilatation; Graduierung über LAVI aus den apikalen Schnitten.",
                   "القطر وحده يقلل من تقدير التوسع؛ المعتمد هو LAVI من المقاطع القمّية."),
            caliper=[450, 254, 450, 320],
        ),
    ],
    "psax_av": [
        step(
            t("Rotate the probe", "Schallkopf drehen", "تدوير المجس"),
            t("From PLAX keep the aortic valve centred and rotate 90° clockwise until the notch points to the left shoulder.",
              "Aus der PLAX die Aortenklappe zentriert halten und um 90° im Uhrzeigersinn drehen, bis die Markierung zur linken Schulter zeigt.",
              "من PLAX، ثبّت الصمام الأبهري في المنتصف ثم دوّر 90° مع عقارب الساعة حتى يصبح المؤشر نحو الكتف الأيسر."),
        ),
        step(
            t("Aortic valve", "Aortenklappe", "الصمام الأبهري"),
            t("The valve sits in the centre with three cusps (Mercedes sign in diastole). Watch the systolic opening: tricuspid or bicuspid?",
              "Die Klappe liegt zentral mit drei Taschen (Mercedes-Stern in der Diastole). Systolische Öffnung beobachten: trikuspid oder bikuspid?",
              "يظهر الصمام في المنتصف بثلاث شُرف (علامة مرسيدس في الانبساط). راقب الانفتاح في الانقباض: ثلاثي أم ثنائي الشرف؟"),
            note=t("In children look here for the origins of the left (≈ 4 o'clock) and right (≈ 11 o'clock) coronary arteries.",
                   "Bei Kindern hier die Abgänge der linken (≈ 4 Uhr) und rechten (≈ 11 Uhr) Koronararterie suchen.",
                   "عند الأطفال: ابحث هنا عن منشأ الشريانين التاجيين الأيسر (نحو الساعة 4) والأيمن (نحو الساعة 11)."),
        ),
        step(
            t("Pulmonary artery diameter", "Durchmesser des Truncus pulmonalis", "قطر الشريان الرئوي"),
            t("Tilt slightly to show the RVOT, pulmonary valve, main trunk and bifurcation. Measure the trunk at its mid-point.",
              "Leicht kippen, um RVOT, Pulmonalklappe, Truncus und Bifurkation darzustellen. Den Truncus in der Mitte messen.",
              "أمل قليلاً لإظهار مخرج البطين الأيمن والصمام الرئوي والجذع وتفرّعه. قِس قطر الجذع في منتصفه."),
            normal=t("< 2.9 cm (children: Z-score)", "< 2,9 cm (Kinder: Z-Score)", "< 2.9 سم (عند الأطفال: Z-Score)"),
            caliper=[438, 200, 466, 180],
        ),
        step(
            t("Pulmonary valve Doppler", "Pulmonalklappendoppler", "دوبلر الصمام الرئوي"),
            t("Place a PW sample at the pulmonary valve with the beam parallel to flow.",
              "PW-Messvolumen an die Pulmonalklappe legen, Schallstrahl parallel zum Fluss.",
              "ضع عينة PW عند الصمام الرئوي والحزمة موازية للجريان."),
            normal=t("0.6–0.9 m/s", "0,6–0,9 m/s", "0.6–0.9 م/ث"),
            caliper=[428, 172, 440, 164],
        ),
        step(
            t("Look for a PDA", "Nach PDA suchen", "البحث عن PDA"),
            t("Apply colour Doppler at the bifurcation: continuous red flow entering the main PA near the left branch indicates a patent ductus arteriosus.",
              "Farbdoppler auf die Bifurkation: Ein kontinuierlicher roter Fluss in den Truncus nahe dem linken Ast spricht für einen persistierenden Ductus arteriosus.",
              "ضع الدوبلر الملون على التفرّع: جريان أحمر مستمر يدخل الجذع الرئوي قرب الفرع الأيسر يدل على قناة شريانية سالكة."),
        ),
    ],
    "psax_papillary": [
        step(
            t("Tilt the probe", "Schallkopf kippen", "إمالة المجس"),
            t("From the aortic valve level tilt the beam toward the apex: the plane passes the mitral valve (fish-mouth), then both papillary muscles appear.",
              "Von der Aortenklappenebene den Schallstrahl Richtung Apex kippen: Die Ebene passiert die Mitralklappe („Fischmaul“), dann erscheinen beide Papillarmuskeln.",
              "من مستوى الصمام الأبهري أمل الحزمة نحو القمة: يمر المقطع بالصمام التاجي (فم السمكة) ثم تظهر العضلتان الحليميتان."),
        ),
        step(
            t("LV roundness", "Rundheit des LV", "استدارة البطين الأيسر"),
            t("The cavity must be circular. If it looks oval the plane is oblique: correct the rotation.",
              "Das Kavum muss rund sein. Wirkt es oval, ist die Ebene schräg: Rotation korrigieren.",
              "يجب أن يكون التجويف دائرياً. إذا كان بيضوياً فالمقطع مائل: عدّل الدوران."),
        ),
        step(
            t("Fractional shortening (FS)", "Verkürzungsfraktion (FS)", "التقاصر الجزئي FS"),
            t("Place the M-mode line perpendicular through the centre of the LV. FS = (LVIDd − LVIDs) ÷ LVIDd.",
              "M-Mode-Linie senkrecht durch die LV-Mitte legen. FS = (LVIDd − LVIDs) ÷ LVIDd.",
              "ضع خط M-Mode عمودياً عبر مركز البطين. FS = (LVIDd − LVIDs) ÷ LVIDd."),
            normal=t("25–43 %", "25–43 %", "25–43٪"),
            caliper=[320, 158, 320, 306],
        ),
        step(
            t("Eccentricity index", "Exzentrizitätsindex", "مؤشر اللامركزية"),
            t("Measure the diameter parallel to the septum and divide it by the diameter perpendicular to it.",
              "Den Durchmesser parallel zum Septum messen und durch den senkrecht dazu stehenden Durchmesser teilen.",
              "قِس القطر الموازي للحاجز واقسمه على القطر العمودي عليه."),
            normal=t("≈ 1.0 (> 1.1 = D-shaped LV)", "≈ 1,0 (> 1,1 = D-förmiger LV)", "≈ 1.0 (> 1.1 = بطين بشكل D)"),
            note=t("Flattening in diastole only: RV volume overload. Flattening in systole and diastole: RV pressure overload.",
                   "Abflachung nur in der Diastole: RV-Volumenbelastung. Abflachung in Systole und Diastole: RV-Druckbelastung.",
                   "تسطّح في الانبساط فقط: حمل حجمي على البطين الأيمن. تسطّح في الانقباض والانبساط: حمل ضغطي."),
            caliper=[244, 232, 396, 232],
        ),
        step(
            t("Wall motion", "Wandbewegung", "حركية الجدران"),
            t("Assess thickening and motion of each segment: anterior, septal, inferior and lateral.",
              "Wanddickenzunahme und Bewegung jedes Segments beurteilen: anterior, septal, inferior und lateral.",
              "قيّم تسمّك وحركة كل قطعة: الأمامية، الحاجزية، السفلية، الوحشية."),
        ),
    ],
    "apical_4c": [
        step(
            t("Probe position", "Schallkopfposition", "وضع المجس"),
            t("Palpate the apex beat and place the probe on it, notch to the patient's left, beam toward the right shoulder.",
              "Herzspitzenstoß tasten und den Schallkopf daraufsetzen, Markierung zur linken Patientenseite, Schallstrahl Richtung rechte Schulter.",
              "جسّ صدمة القمة وضع المجس عليها، المؤشر نحو يسار المريض، ووجّه الحزمة نحو الكتف الأيمن."),
            note=t("The apex should be pointed and stationary at the top of the sector. A rounded, moving apex means foreshortening: go one space lower or more lateral.",
                   "Die Spitze sollte spitz und unbewegt oben im Sektor liegen. Eine runde, bewegte Spitze bedeutet Verkürzung: einen ICR tiefer oder weiter lateral gehen.",
                   "القمة يجب أن تكون مدببة وثابتة في أعلى القطاع. قمة مدوّرة ومتحركة = تقصير (foreshortening): انزل ورباً أو تحرك للوحشي."),
        ),
        step(
            t("Optimise the view", "Schnitt optimieren", "ضبط المقطع"),
            t("The interventricular septum runs vertically in the centre, and both mitral and tricuspid valves open clearly.",
              "Das interventrikuläre Septum verläuft senkrecht in der Bildmitte, Mitral- und Trikuspidalklappe öffnen sich deutlich.",
              "الحاجز بين البطينين عمودي في منتصف الشاشة، والصمامان التاجي ومثلث الشرف ينفتحان بوضوح."),
        ),
        step(
            t("LV ejection fraction", "LV-Ejektionsfraktion", "الكسر القذفي LVEF"),
            t("Trace the endocardium at end-diastole and end-systole (Simpson), then repeat in A2C for the biplane calculation.",
              "Endokard enddiastolisch und endsystolisch umfahren (Simpson), dann im 2K-Blick für die biplane Berechnung wiederholen.",
              "ارسم حدود الشغاف في نهاية الانبساط ثم نهاية الانقباض (طريقة Simpson)، وكرّر في A2C للحساب ثنائي المستوى."),
            normal=t("52–72 % (M) · 54–74 % (F)", "52–72 % (M) · 54–74 % (F)", "52–72٪ (ذكور) · 54–74٪ (إناث)"),
            note=t("Papillary muscles and trabeculae belong to the cavity, not to the wall.",
                   "Papillarmuskeln und Trabekel gehören zum Kavum, nicht zur Wand.",
                   "العضلات الحليمية والترابيق تُحسب ضمن التجويف، لا ضمن الجدار."),
            caliper=[316, 90, 358, 260],
        ),
        step(
            t("TAPSE", "TAPSE", "TAPSE"),
            t("Place the M-mode line on the lateral tricuspid annulus and measure its longitudinal excursion from diastole to systole.",
              "M-Mode-Linie auf den lateralen Trikuspidalanulus legen und dessen longitudinale Exkursion von Diastole zu Systole messen.",
              "ضع خط M-Mode على الحلقة الوحشية للصمام مثلث الشرف وقِس حركتها الطولية من الانبساط إلى الانقباض."),
            normal=t("≥ 1.7 cm", "≥ 1,7 cm", "≥ 1.7 سم"),
            caliper=[300, 22, 196, 262],
        ),
        step(
            t("RV basal diameter", "Basaler RV-Durchmesser", "قطر قاعدة البطين الأيمن"),
            t("In an RV-focused view measure the widest diameter in the basal third at end-diastole.",
              "Im RV-fokussierten Schnitt den größten Durchmesser im basalen Drittel enddiastolisch messen.",
              "في مقطع مركَّز على البطين الأيمن، قِس أعرض قطر في الثلث القاعدي في نهاية الانبساط."),
            normal=t("≤ 4.1 cm", "≤ 4,1 cm", "≤ 4.1 سم"),
            caliper=[205, 236, 295, 236],
        ),
        step(
            t("Mitral inflow", "Mitraleinstrom", "جريان الصمام التاجي"),
            t("Place a PW sample (1–3 mm) at the mitral leaflet tips and record the E and A waves.",
              "PW-Messvolumen (1–3 mm) an die Mitralsegelspitzen legen und E- und A-Welle ableiten.",
              "ضع عينة PW (1–3 مم) عند أطراف وريقات الصمام التاجي وسجّل موجتي E و A."),
            normal=t("E/A = 0.8–2.0 (age-dependent)", "E/A = 0,8–2,0 (altersabhängig)", "E/A = 0.8–2.0 (حسب العمر)"),
            caliper=[340, 224, 356, 220],
        ),
        step(
            t("Tissue Doppler e'", "Gewebedoppler e'", "دوبلر الأنسجة e'"),
            t("Activate TDI and place the sample on the septal, then the lateral mitral annulus. Calculate E ÷ average e'.",
              "TDI aktivieren und das Messvolumen auf den septalen, dann den lateralen Mitralanulus legen. E ÷ gemitteltes e' berechnen.",
              "فعّل TDI وضع العينة على الحلقة التاجية الحاجزية ثم الوحشية. احسب E ÷ متوسط e'."),
            normal=t("Average E/e' < 14", "Gemitteltes E/e' < 14", "E/e' المتوسط < 14"),
            caliper=[316, 262, 400, 262],
        ),
        step(
            t("Left atrial volume", "Volumen des linken Vorhofs", "حجم الأذين الأيسر"),
            t("At end-systole trace the left atrium (excluding pulmonary veins and appendage) and divide the volume by BSA.",
              "Endsystolisch den linken Vorhof umfahren (ohne Lungenvenen und Vorhofohr) und das Volumen durch die KOF teilen.",
              "في نهاية الانقباض ارسم حدود الأذين الأيسر (دون الأوردة الرئوية والزائدة) واقسم الحجم على BSA."),
            normal=t("LAVI ≤ 34 mL/m²", "LAVI ≤ 34 ml/m²", "LAVI ≤ 34 مل/م²"),
            caliper=[360, 278, 360, 366],
        ),
    ],
    "apical_5c": [
        step(
            t("Tilt the probe", "Schallkopf kippen", "إمالة المجس"),
            t("From A4C lower the probe tail slightly so the beam points anteriorly until the LVOT and aortic valve appear in the centre.",
              "Aus dem 4K-Blick das Schallkopfende leicht absenken, sodass der Strahl nach anterior zeigt, bis LVOT und Aortenklappe zentral erscheinen.",
              "من A4C اخفض ذيل المجس قليلاً لتتجه الحزمة للأمام حتى يظهر مخرج البطين الأيسر والصمام الأبهري في المنتصف."),
        ),
        step(
            t("LVOT VTI", "LVOT-VTI", "LVOT VTI"),
            t("Place a PW sample in the LVOT 0.5–1 cm below the aortic valve and trace the envelope.",
              "PW-Messvolumen im LVOT 0,5–1 cm unterhalb der Aortenklappe platzieren und die Hüllkurve umfahren.",
              "ضع عينة PW في مخرج البطين الأيسر 0.5–1 سم تحت الصمام الأبهري وارسم حدود الموجة."),
            normal=t("18–22 cm", "18–22 cm", "18–22 سم"),
            note=t("Stroke volume = LVOT area × VTI. The LVOT diameter is measured in PLAX.",
                   "Schlagvolumen = LVOT-Fläche × VTI. Der LVOT-Durchmesser wird in der PLAX gemessen.",
                   "الحجم الضربي = مساحة LVOT × VTI. قطر LVOT يُقاس في PLAX."),
            caliper=[288, 250, 320, 250],
        ),
        step(
            t("Aortic valve velocity", "Aortenklappengeschwindigkeit", "سرعة الصمام الأبهري"),
            t("Switch to CW and align the cursor through the valve as parallel to flow as possible.",
              "Auf CW umschalten und den Cursor möglichst flussparallel durch die Klappe legen.",
              "بدّل إلى CW ومرّر الخط عبر الصمام بمحاذاة الجريان قدر الإمكان."),
            normal=t("Vmax < 2.0 m/s · mean gradient < 10 mmHg", "Vmax < 2,0 m/s · mittlerer Gradient < 10 mmHg", "Vmax < 2.0 م/ث · المدروج الوسطي < 10 ملم زئبق"),
            note=t("Any angle error lowers the measured velocity; try other windows when stenosis is suspected.",
                   "Jeder Winkelfehler senkt die gemessene Geschwindigkeit; bei Stenoseverdacht weitere Fenster nutzen.",
                   "أي انحراف في الزاوية يقلل السرعة المقاسة؛ جرّب نوافذ أخرى عند الشك بتضيق."),
            caliper=[300, 22, 303, 400],
        ),
    ],
    "apical_2c_3c": [
        step(
            t("A2C view", "2K-Blick", "مقطع A2C"),
            t("From A4C rotate about 60° counter-clockwise until the right ventricle disappears completely. The inferior wall is on the left of the screen, the anterior wall on the right.",
              "Aus dem 4K-Blick etwa 60° gegen den Uhrzeigersinn drehen, bis der rechte Ventrikel ganz verschwindet. Die inferiore Wand liegt links, die Vorderwand rechts im Bild.",
              "من A4C دوّر المجس نحو 60° عكس عقارب الساعة حتى يختفي البطين الأيمن تماماً. يظهر الجدار السفلي (يسار الشاشة) والأمامي (يمينها)."),
        ),
        step(
            t("Simpson in A2C", "Simpson im 2K-Blick", "Simpson في A2C"),
            t("Trace the endocardium at end-diastole and end-systole to complete the biplane LVEF.",
              "Endokard enddiastolisch und endsystolisch umfahren, um die biplane LVEF zu vervollständigen.",
              "ارسم حدود الشغاف في نهاية الانبساط والانقباض لإكمال حساب LVEF ثنائي المستوى."),
            note=t("LV length in A4C and A2C should not differ by more than 10 %.",
                   "Die LV-Länge in 4K und 2K sollte um nicht mehr als 10 % abweichen.",
                   "فرق طول البطين بين A4C و A2C يجب ألا يتجاوز 10%."),
            caliper=[150, 84, 150, 266],
        ),
        step(
            t("A3C view", "3K-Blick", "مقطع A3C"),
            t("Keep rotating (about 120° from A4C) until the LVOT and aortic valve appear: an image like PLAX, seen from the apex.",
              "Weiter drehen (etwa 120° vom 4K-Blick), bis LVOT und Aortenklappe erscheinen: ein Bild wie die PLAX, von apikal gesehen.",
              "تابع التدوير (نحو 120° من A4C) حتى يظهر مخرج البطين الأيسر والصمام الأبهري: صورة تشبه PLAX لكن من القمة."),
        ),
        step(
            t("Global longitudinal strain", "Globaler longitudinaler Strain", "الإجهاد الطولي GLS"),
            t("Record all three views (A4C, A2C, A3C) at a high frame rate without foreshortening for speckle-tracking analysis.",
              "Alle drei Schnitte (4K, 2K, 3K) mit hoher Bildrate und ohne Verkürzung für die Speckle-Tracking-Analyse aufzeichnen.",
              "سجّل المقاطع الثلاثة (A4C, A2C, A3C) بمعدل إطارات عالٍ ودون تقصير القمة لتحليل speckle tracking."),
            normal=t("−18 % to −22 %", "−18 % bis −22 %", "−18٪ إلى −22٪"),
        ),
    ],
    "suprasternal_arch": [
        step(
            t("Probe position", "Schallkopfposition", "وضع المجس"),
            t("Put a pillow under the shoulders, extend the neck and turn the head slightly. Place the probe in the suprasternal notch pointing downward, notch toward 1 o'clock.",
              "Kissen unter die Schultern, Hals reklinieren und Kopf leicht drehen. Schallkopf ins Jugulum nach kaudal gerichtet, Markierung Richtung 1 Uhr.",
              "ضع وسادة تحت الكتفين وابسط الرقبة مع إدارة الرأس قليلاً. ضع المجس في الثلمة فوق القص موجهاً للأسفل، والمؤشر نحو الساعة 1."),
            note=t("Use light pressure; this view is uncomfortable, especially for children.",
                   "Nur leichten Druck ausüben; der Schnitt ist unangenehm, besonders für Kinder.",
                   "استخدم ضغطاً خفيفاً؛ المقطع مزعج للمريض خصوصاً الأطفال."),
        ),
        step(
            t("Arch branches", "Bogenabgänge", "تفرعات القوس"),
            t("Show the whole aortic arch (candy-cane shape) with its three branches: brachiocephalic trunk, left common carotid and left subclavian artery.",
              "Den gesamten Aortenbogen (Spazierstockform) mit seinen drei Abgängen darstellen: Truncus brachiocephalicus, A. carotis communis sinistra und A. subclavia sinistra.",
              "أظهر قوس الأبهر كاملاً (شكل العكاز) مع فروعه الثلاثة: الجذع العضدي الرأسي، السباتي الأيسر، تحت الترقوة الأيسر."),
        ),
        step(
            t("Transverse arch diameter", "Durchmesser des transversen Bogens", "قطر القوس المستعرض"),
            t("Measure between the brachiocephalic and left carotid origins, inner edge to inner edge.",
              "Zwischen den Abgängen von Truncus brachiocephalicus und linker A. carotis messen, Innenkante zu Innenkante.",
              "قِس القطر بين الجذع العضدي الرأسي والسباتي الأيسر، من الحافة الداخلية إلى الداخلية."),
            normal=t("2.2–3.6 cm (children: Z-score)", "2,2–3,6 cm (Kinder: Z-Score)", "2.2–3.6 سم (عند الأطفال: Z-Score)"),
            caliper=[268, 129, 268, 179],
        ),
        step(
            t("Aortic isthmus", "Aortenisthmus", "برزخ الأبهر"),
            t("Measure the diameter just beyond the left subclavian origin: the typical site of coarctation.",
              "Den Durchmesser unmittelbar distal des Abgangs der linken A. subclavia messen: typische Lokalisation der Isthmusstenose.",
              "قِس القطر مباشرة بعد منشأ الشريان تحت الترقوة الأيسر: الموضع النموذجي لتضيق برزخ الأبهر."),
            normal=t("Children: Z-score > −2", "Kinder: Z-Score > −2", "عند الأطفال: Z-Score > −2"),
            caliper=[375, 210, 415, 180],
        ),
        step(
            t("Descending aorta Doppler", "Doppler der Aorta descendens", "دوبلر الأبهر النازل"),
            t("Align a CW cursor through the descending aorta.",
              "CW-Cursor durch die Aorta descendens legen.",
              "مرّر خط CW عبر الأبهر النازل."),
            normal=t("1.0–1.5 m/s without diastolic tail", "1,0–1,5 m/s ohne diastolischen Ausläufer", "1.0–1.5 م/ث دون ذيل انبساطي"),
            note=t("A high velocity with flow continuing into diastole (diastolic tail) is the hallmark of coarctation.",
                   "Hohe Geschwindigkeit mit in die Diastole fortgesetztem Fluss (diastolischer Ausläufer) ist das Leitzeichen der Isthmusstenose.",
                   "سرعة مرتفعة مع استمرار الجريان في الانبساط (ذيل انبساطي) علامة مميزة لتضيق البرزخ."),
            caliper=[300, 22, 432, 392],
        ),
    ],
}


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    count = 0
    for window in db["windows"]:
        for view in window["views"]:
            steps = STEPS[view["id"]]
            view["steps"] = steps
            count += len(steps)
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"merged {count} guided steps into {os.path.relpath(DB, ROOT)}")


if __name__ == "__main__":
    main()
