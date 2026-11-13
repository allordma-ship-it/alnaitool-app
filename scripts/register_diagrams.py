#!/usr/bin/env python3
"""
Register the anatomical reference diagrams (app/src/main/assets/diagrams/<view_id>.png)
in echo_protocol_full.json: asset path, aspect ratio and credit for every view.

Atlas illustrations: Patrick J. Lynch (medical illustrator) & C. Carl Jaffe, MD (cardiologist),
Yale University, CC BY 2.5 – outer transparent background flattened onto #E2E8F0 so the
dark labels are readable. Two views use labelled ultrasound reference screenshots supplied
by the app owner whose original source/licence still has to be confirmed.

Usage: python3 scripts/register_diagrams.py
"""

import json
import os

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DB = os.path.join(ASSETS, "echo_protocol_full.json")

LYNCH = {
    "en": "Illustration: Patrick J. Lynch & C. Carl Jaffe, MD (Yale) · CC BY 2.5 · adapted (light background)",
    "de": "Illustration: Patrick J. Lynch & C. Carl Jaffe, MD (Yale) · CC BY 2.5 · bearbeitet (heller Hintergrund)",
    "ar": "الرسم: Patrick J. Lynch و C. Carl Jaffe, MD (Yale) · CC BY 2.5 · معدَّل (خلفية فاتحة)",
}
SUPPLIED = {
    "en": "Labelled ultrasound reference image (supplied; original source and licence to be confirmed)",
    "de": "Beschriftetes Ultraschall-Referenzbild (bereitgestellt; Originalquelle und Lizenz noch zu klären)",
    "ar": "صورة إيكو مرجعية موسومة (مقدَّمة من المستخدم؛ المصدر الأصلي والترخيص بحاجة إلى تأكيد)",
}
SOURCE = {
    "subcostal_4c": LYNCH, "plax_standard": LYNCH, "psax_av": LYNCH, "psax_papillary": LYNCH,
    "apical_4c": LYNCH, "apical_5c": LYNCH, "apical_2c_3c": LYNCH,
    "subcostal_ivc": SUPPLIED, "suprasternal_arch": SUPPLIED,
}


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    for window in db["windows"]:
        for view in window["views"]:
            rel = f"diagrams/{view['id']}.png"
            path = os.path.join(ASSETS, rel)
            if not os.path.exists(path):
                raise SystemExit(f"missing {rel}")
            w, h = Image.open(path).size
            view["diagramAsset"] = rel
            view["diagramAspect"] = round(w / h, 4)
            view["diagramCredit"] = SOURCE[view["id"]]
            print(f"{view['id']:18} {rel:32} {w}x{h}")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
