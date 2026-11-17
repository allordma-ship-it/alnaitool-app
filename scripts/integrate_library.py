#!/usr/bin/env python3
"""
EchoScope – (1) real spectral / M-mode traces for the valve measurement guide and
(2) the Echocardiographer.org Image Library as a pathology case list.

(1) Real recordings from Echocardiographer.org (assets/echoorg/, free reuse with credit) replace the
    drawn trace wherever one exists; "marks" put the measurement points on the real trace
    (normalised image coordinates, read on a 0.05 grid: debug_calipers/spec_grid.png).
(2) The Image Library (https://www.echocardiographer.org/image-library) consists of YouTube
    videos only. YouTube refuses downloads from the build server, so the cases are listed with
    their links (opened online); the library list is read from the site's page data
    (temp_real/echoorg/library.json).
Run after scripts/valve_content.py.
"""

import json
import os
import re

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
CREDIT = "Echocardiographer.org – Atif Qasim, MD MSCE"


def mark(label, x, y, x2=None, y2=None):
    m = {"label": label, "x": x, "y": y}
    if x2 is not None:
        m.update({"x2": x2, "y2": y2})
    return m


TRACES = {
    "av_cw": ("echoorg/apical5_05.jpg", [mark("Vmax", 0.40, 0.79), mark("VTI", 0.36, 0.50, 0.49, 0.50),
                                          mark("meanPG", 0.44, 0.66)]),
    "lvot_pw": ("echoorg/apical5_04.jpg", [mark("V LVOT", 0.115, 0.80), mark("LVOT VTI", 0.06, 0.50, 0.18, 0.50)]),
    "mv_pw": ("echoorg/apical4_07.jpg", [mark("E", 0.548, 0.617), mark("A", 0.621, 0.695),
                                         mark("DT", 0.548, 0.617, 0.607, 0.870)]),
    "tapse": ("echoorg/apical4_12.jpg", [mark("TAPSE", 0.385, 0.845, 0.385, 0.715)]),
}

# Schema tab = real medical drawings + labelled echo frames from Echocardiographer.org
# (replaces the generated cartoon schemas). (asset, caption en/de/ar)
D = ("Schema", "Schema", "مخطط المقطع")
LBL = ("Labelled echo frame", "Beschriftetes Echobild", "صورة إيكو موسومة")
SCHEMA = {
    "subcostal_4c": [("subcostal_03", LBL)],
    "subcostal_ivc": [("subcostal-greatvessels_01", ("IVC with M-mode", "VCI mit M-Mode", "الوريد الأجوف مع نمط M")),
                      ("subcostal-greatvessels_00", ("Probe position", "Schallkopfposition", "وضع المسبار"))],
    "plax_standard": [("plax_02", LBL), ("plax_04", ("AV and MV zoom", "Zoom AK und MK", "تكبير الأبهري والتاجي"))],
    "psax_av": [("psax_02", LBL), ("psax_03", ("Aortic valve zoom", "Aortenklappe gezoomt", "تكبير الصمام الأبهري")),
                ("psax_01", ("Short-axis levels", "Kurzachsen-Ebenen", "مستويات المحور القصير"))],
    "psax_papillary": [("psax_06", LBL), ("psax_01", ("Short-axis levels", "Kurzachsen-Ebenen", "مستويات المحور القصير"))],
    "apical_4c": [("apical4_03", LBL)],
    "apical_5c": [("apical5_03", LBL)],
    "apical_2c": [("apical2_03", LBL)],
    "apical_3c": [("apical-long-axis_03", LBL)],
    "suprasternal_arch": [("suprasternal_03", LBL)],
}
# Page 1 of every view: the dark-theme view drawing (scripts/harmonize_schema_dark_theme.py) that
# carries the step's calipers / Doppler cursors (scripts/schema_overlays.py).
DRAWING_SOURCE = {"subcostal_4c": "subcostal", "plax_standard": "plax", "psax_av": "psax", "psax_papillary": "psax",
                  "apical_4c": "apical4", "apical_5c": "apical5", "apical_2c": "apical2",
                  "apical_3c": "apical-long-axis", "suprasternal_arch": "suprasternal"}
ADAPTED = {"psax_av", "psax_papillary"}   # level drawing cut from psax_01 and set into a sector
SOURCES = {"subcostal": "tte-subcostal", "subcostal-greatvessels": "tte-subcostal-greatvessels", "plax": "tte-plax",
           "psax": "tte-psax", "apical4": "tte-apical4", "apical5": "tte-apical5", "apical2": "tte-apical2",
           "apical-long-axis": "tte-apical-long-axis", "suprasternal": "tte-suprasternal"}

CATEGORIES = [
    ("cardiomyopathy", ("Cardiomyopathies", "Kardiomyopathien", "اعتلالات العضلة القلبية"),
     r"cardiomyopathy|HCM|fibroelastosis|ballooning|diverticulum|LV assessment"),
    ("valves", ("Valves", "Klappen", "الصمامات"),
     r"valve|prolapse|endocarditis|mitral|bicuspid|unicuspid|quadracuspid|MitraClip|EROA|4 Valve"),
    ("ischemia", ("Ischaemia & infarction", "Ischämie & Infarkt", "الإقفار والاحتشاء"), r"infarct|ischemia|LAD|RCA"),
    ("aorta", ("Aortic disease", "Aortenerkrankungen", "أمراض الأبهر"), r"aort"),
    ("rv", ("RV & pulmonary", "RV & pulmonal", "البطين الأيمن والرئة"), r"pulmonale|RV|SVC"),
    ("pericardium", ("Pericardial disease", "Perikarderkrankungen", "أمراض التامور"), r"effusion|tamponade|pericardial"),
    ("masses", ("Masses & thrombi", "Raumforderungen & Thromben", "الكتل والخثرات"), r"thrombus|myxoma|tumor|carcinoma|cyst"),
    ("congenital", ("Congenital & shunts", "Angeboren & Shunts", "الخلقية والتحويلات"), r"ASD|VSD|PFO|shunt|saline|canal"),
    ("other", ("Other", "Sonstiges", "أخرى"), r".*"),
]


def drawing_page(view_id):
    """
    Schema page 1 (scripts/schema_raster.py): the original view drawing of every view (dark mapping /
    light original, lossless WebP, realistic transducer) with an end-systolic version for views
    with a left ventricle. Carries the step's calipers.
    """
    asset = f"schema/{view_id}_dark.webp"
    iw, ih = Image.open(os.path.join(ASSETS, asset)).size
    page = {"asset": asset, "aspect": round(iw / ih, 4), "caption": dict(zip(("en", "de", "ar"), D)), "calipers": True}
    es = f"schema/{view_id}_es_dark.webp"
    if os.path.exists(os.path.join(ASSETS, es)):
        page["systolicAsset"] = es
    if view_id in DRAWING_SOURCE:
        page["credit"] = CREDIT + (" (adapted)" if view_id in ADAPTED else "")
        page["source"] = "https://www.echocardiographer.org/" + SOURCES[DRAWING_SOURCE[view_id]]
    else:   # subcostal IVC: no drawing on the site, drawn for EchoScope in the same style
        page["credit"] = "EchoScope"
    return page


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    n = 0
    for valve in db["valves"]:
        for m in valve.get("measurements", []):
            if m["id"] in TRACES:
                asset, marks = TRACES[m["id"]]
                w, h = Image.open(os.path.join(ASSETS, asset)).size
                m["trace"] = {"asset": asset, "aspect": round(w / h, 4), "credit": CREDIT, "marks": marks}
                n += 1
            else:
                m.pop("trace", None)
    for w in db["windows"]:
        for view in w["views"]:
            pages = [drawing_page(view["id"])]
            for name, cap in SCHEMA[view["id"]]:
                asset = f"echoorg/{name}.jpg"
                iw, ih = Image.open(os.path.join(ASSETS, asset)).size
                pages.append({"asset": asset, "aspect": round(iw / ih, 4), "caption": dict(zip(("en", "de", "ar"), cap)),
                              "credit": CREDIT, "source": "https://www.echocardiographer.org/" + SOURCES[name.rsplit("_", 1)[0]]})
            view["schemaGallery"] = pages
            for key in ("schemaAsset", "schemaAspect", "schemaCrop"):
                view.pop(key, None)
    with open(os.path.join(ROOT, "temp_real", "echoorg", "library.json"), encoding="utf-8") as f:
        lib = json.load(f)
    groups = {cid: {"id": cid, "title": dict(zip(("en", "de", "ar"), t)), "cases": []} for cid, t, _ in CATEGORIES}
    seen = set()
    for case in lib:
        key = (case["title"].strip(), case["url"])
        if key in seen:
            continue
        seen.add(key)
        cid = next(c for c, _, rx in CATEGORIES if re.search(rx, case["title"], re.I))
        groups[cid]["cases"].append({"title": " ".join(case["title"].split()), "url": case["url"]})
    db["caseLibrary"] = {
        "source": "https://www.echocardiographer.org/image-library",
        "credit": CREDIT,
        "groups": [g for g in groups.values() if g["cases"]],
    }
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{n} real traces, {sum(len(g['cases']) for g in db['caseLibrary']['groups'])} library cases")


if __name__ == "__main__":
    main()
