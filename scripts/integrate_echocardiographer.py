#!/usr/bin/env python3
"""
EchoScope – add the reviewed Echocardiographer.org images to the app.

Source and licence: https://www.echocardiographer.org (Atif Qasim, MD MSCE). About page,
"Reuse of Materials": "Feel free to use any of the content here and on my youtube channel for
free, but please provide credit to this site and me or other authors."

Run scripts/fetch_echocardiographer.py first (downloads to temp_real/echoorg/), then this
script. It
  * converts the selected images (max 1000 px, JPEG q82) to assets/echoorg/<view>/,
  * adds per view "probeGallery" (probe photo, beam-cut drawing) and "atlasGallery"
    (labelled echo frame, view drawing, zooms), shown as extra pages of the Sonde / Atlas panel,
  * adds per guided step "examples" (real PW / CW / TDI / M-mode recordings),
  * replaces the low-resolution SSN atlas image (a copy of the site's labelled SSN frame,
    previously "source to be confirmed") with the original and credits it,
  * writes assets/echoorg/ATTRIBUTION.txt.

Every image below was looked at before it was added (debug_calipers/eo_*.png contact sheets).
Pipeline order: guided_steps.py → measurement_overlays.py → integrate_echocardiographer.py.
Usage: python3 scripts/integrate_echocardiographer.py
"""

from __future__ import annotations

import json
import os

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "temp_real", "echoorg")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "echoorg")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
SITE = "https://www.echocardiographer.org/"
CREDIT = "Echocardiographer.org – Atif Qasim, MD MSCE"


def t(en, de, ar):
    return {"en": en, "de": de, "ar": ar}


C = {
    "photo": t("Probe on the chest (photo)", "Schallkopf auf dem Thorax (Foto)", "المسبار على الصدر (صورة)"),
    "cut": t("How the beam cuts the heart", "So schneidet die Schallebene das Herz", "كيف تقطع الحزمة القلب"),
    "levels": t("Short-axis levels: AV, MV, papillary muscles, apex", "Kurzachsen-Ebenen: AK, MK, Papillarmuskeln, Apex",
                "مستويات المحور القصير: الأبهري، التاجي، العضلات الحليمية، القمة"),
    "view": t("Resulting view (drawing)", "Ergebnisbild (Zeichnung)", "المقطع الناتج (رسم)"),
    "label": t("Labelled echo frame", "Beschriftetes Echobild", "صورة إيكو موسومة"),
    "plax_zoom": t("Zoom on aortic and mitral valve", "Zoom auf Aorten- und Mitralklappe", "تكبير على الصمام الأبهري والتاجي"),
    "av_zoom": t("Aortic valve zoom: RCC, LCC, NCC", "Aortenklappe gezoomt: RCC, LCC, NCC", "تكبير الصمام الأبهري: الشرفات الثلاث"),
    "pa": t("Pulmonary artery and bifurcation", "Pulmonalarterie und Bifurkation", "الشريان الرئوي وتفرعه"),
    "mv_level": t("Mitral valve level", "Mitralklappen-Ebene", "مستوى الصمام التاجي"),
    "pm_level": t("Papillary-muscle level", "Papillarmuskel-Ebene", "مستوى العضلات الحليمية"),
    "apex_level": t("Apical level", "Apikale Ebene", "المستوى القمي"),
    "lv_zoom": t("LV zoom", "LV-Zoom", "تكبير البطين الأيسر"),
    "a3c": t("Apical long axis (A3C)", "Apikale lange Achse (3K)", "المحور الطويل القمي (ثلاثي الحجرات)"),
    "pw_mv": t("PW mitral inflow (E/A)", "PW-Mitraleinstrom (E/A)", "دوبلر نبضي للتدفق التاجي (E/A)"),
    "tdi_lat": t("TDI lateral annulus (e', a')", "TDI lateraler Anulus (e', a')", "دوبلر نسيجي الحلقة الوحشية"),
    "tdi_sep": t("TDI septal annulus (e', a')", "TDI septaler Anulus (e', a')", "دوبلر نسيجي الحلقة الحاجزية"),
    "tapse": t("TAPSE M-mode", "TAPSE im M-Mode", "TAPSE بنمط M"),
    "pw_lvot": t("PW LVOT (VTI)", "PW LVOT (VTI)", "دوبلر نبضي في LVOT"),
    "cw_av": t("CW aortic valve", "CW Aortenklappe", "دوبلر مستمر عبر الصمام الأبهري"),
    "ivc_m": t("IVC M-mode: expiration vs inspiration", "VCI im M-Mode: Exspiration vs. Inspiration",
               "الوريد الأجوف بنمط M: زفير مقابل شهيق"),
    "pw_asc": t("PW ascending aorta", "PW Aorta ascendens", "دوبلر نبضي في الأبهر الصاعد"),
    "pw_desc": t("PW descending aorta", "PW Aorta descendens", "دوبلر نبضي في الأبهر النازل"),
}

# view -> (probe pages, atlas pages); each page = (source file, caption key)
GALLERIES = {
    "subcostal_4c": ([("tte-subcostal/00.jpg", "photo"), ("tte-subcostal/01.jpg", "cut")],
                     [("tte-subcostal/03.jpg", "label"), ("tte-subcostal/02.jpg", "view")]),
    "subcostal_ivc": ([("tte-subcostal-greatvessels/00.jpg", "photo")], []),
    "plax_standard": ([("tte-plax/00.jpg", "photo"), ("tte-plax/01.jpg", "cut")],
                      [("tte-plax/02.jpg", "label"), ("tte-plax/03.jpg", "view"), ("tte-plax/04.jpg", "plax_zoom")]),
    "psax_av": ([("tte-psax/00.jpg", "photo"), ("tte-psax/01.jpg", "levels")],
                [("tte-psax/02.jpg", "label"), ("tte-psax/03.jpg", "av_zoom"), ("tte-psax/04.jpg", "pa")]),
    "psax_papillary": ([("tte-psax/00.jpg", "photo"), ("tte-psax/01.jpg", "levels")],
                       [("tte-psax/06.jpg", "pm_level"), ("tte-psax/05.jpg", "mv_level"), ("tte-psax/07.jpg", "apex_level")]),
    "apical_4c": ([("tte-apical4/00.jpg", "photo"), ("tte-apical4/01.jpg", "cut")],
                  [("tte-apical4/03.jpg", "label"), ("tte-apical4/02.jpg", "view"), ("tte-apical4/04.jpg", "lv_zoom")]),
    "apical_5c": ([("tte-apical5/00.jpg", "photo"), ("tte-apical5/01.jpg", "cut")],
                  [("tte-apical5/03.jpg", "label"), ("tte-apical5/02.jpg", "view")]),
    "apical_2c_3c": ([("tte-apical2/00.jpg", "photo"), ("tte-apical2/01.jpg", "cut"),
                      ("tte-apical-long-axis/00.jpg", "photo"), ("tte-apical-long-axis/01.jpg", "cut")],
                     [("tte-apical2/03.jpg", "label"), ("tte-apical2/02.jpg", "view"),
                      ("tte-apical-long-axis/03.jpg", "a3c"), ("tte-apical-long-axis/02.jpg", "view")]),
    "suprasternal_arch": ([("tte-suprasternal/00.jpg", "photo"), ("tte-suprasternal/01.jpg", "cut")],
                          [("tte-suprasternal/02.jpg", "view")]),
}

# (view, step index) -> example recordings shown from the step card
EXAMPLES = {
    ("plax_standard", 6): [("tte-plax/04.jpg", "plax_zoom")],
    ("psax_av", 1): [("tte-psax/03.jpg", "av_zoom")],
    ("psax_av", 2): [("tte-psax/04.jpg", "pa")],
    ("psax_papillary", 1): [("tte-psax/06.jpg", "pm_level")],
    ("apical_4c", 3): [("tte-apical4/12.jpg", "tapse")],
    ("apical_4c", 5): [("tte-apical4/07.jpg", "pw_mv")],
    ("apical_4c", 6): [("tte-apical4/09.jpg", "tdi_sep"), ("tte-apical4/08.jpg", "tdi_lat")],
    ("apical_5c", 1): [("tte-apical5/04.jpg", "pw_lvot")],
    ("apical_5c", 2): [("tte-apical5/05.jpg", "cw_av")],
    ("subcostal_ivc", 2): [("tte-subcostal-greatvessels/01.jpg", "ivc_m")],
    ("suprasternal_arch", 4): [("tte-suprasternal/05.jpg", "pw_desc"), ("tte-suprasternal/04.jpg", "pw_asc")],
}

# The previous SSN atlas was this site's labelled frame, scaled ×0.3825 and cropped 7 px left.
SSN_ATLAS = "tte-suprasternal/03.jpg"


def convert(rel: str, used: dict) -> dict:
    page = rel.split("/")[0]
    name = rel.replace("/", "_").replace("tte-", "")
    dest = os.path.join(OUT, name)
    if rel not in used:
        im = Image.open(os.path.join(SRC, rel)).convert("RGB")
        im.thumbnail((1000, 1000), Image.LANCZOS)
        im.save(dest, "JPEG", quality=82, optimize=True)  # baseline JPEG: decodes everywhere
        used[rel] = (os.path.relpath(dest, ASSETS), im.width / im.height, page)
    asset, aspect, _ = used[rel]
    return {"asset": asset, "aspect": round(aspect, 4), "credit": CREDIT, "source": SITE + page}


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    with open(os.path.join(SRC, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    original_names = {img["file"]: img["name"] or img["alt"] for p in manifest.values() for img in p["images"]}
    used: dict = {}
    pages = examples = 0
    for window in db["windows"]:
        for view in window["views"]:
            probe, atlas = GALLERIES.get(view["id"], ([], []))
            view["probeGallery"] = [{**convert(r, used), "caption": C[k]} for r, k in probe]
            view["atlasGallery"] = [{**convert(r, used), "caption": C[k]} for r, k in atlas]
            pages += len(probe) + len(atlas)
            for i, step in enumerate(view["steps"]):
                ex = EXAMPLES.get((view["id"], i), [])
                if ex:
                    step["examples"] = [{**convert(r, used), "caption": C[k]} for r, k in ex]
                    examples += len(ex)
                else:
                    step.pop("examples", None)
            if view["id"] == "suprasternal_arch":
                src = convert(SSN_ATLAS, used)
                view["diagramAsset"] = src["asset"]
                view["diagramAspect"] = src["aspect"]
                view["diagramCredit"] = t(
                    f"Labelled echo image: {CREDIT} (echocardiographer.org, free reuse with credit)",
                    f"Beschriftetes Echobild: {CREDIT} (echocardiographer.org, freie Nutzung mit Quellenangabe)",
                    f"صورة إيكو موسومة: {CREDIT} (echocardiographer.org، استخدام مجاني مع ذكر المصدر)")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    old = os.path.join(ASSETS, "diagrams", "suprasternal_arch.png")
    if os.path.exists(old):
        os.remove(old)

    lines = ["EchoScope – images from Echocardiographer.org", "=" * 46, "",
             "Author: Atif Qasim, MD MSCE – https://www.echocardiographer.org",
             "Licence (About page, \"Reuse of Materials\"): \"Feel free to use any of the content here and",
             "on my youtube channel for free, but please provide credit to this site and me or other authors.\"",
             "Images were resized (max 1000 px) and re-encoded as JPEG; otherwise unchanged.", ""]
    for rel, (asset, _, page) in sorted(used.items(), key=lambda kv: kv[1][0]):
        lines.append(f"{asset}  <-  {SITE}{page}  ({original_names.get(rel, '')})")
    with open(os.path.join(OUT, "ATTRIBUTION.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    size = sum(os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT))
    print(f"{len(used)} images ({size / 1e6:.1f} MB): {pages} gallery pages, {examples} step examples")


if __name__ == "__main__":
    main()
