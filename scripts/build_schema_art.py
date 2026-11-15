#!/usr/bin/env python3
"""
EchoScope – hand-authored Schema art in the exact style of the reference
scripts/schema_art/subcostal_4c.svg ("subcostal_4ch_ohne_labels.svg", supplied by the user).

Every view is drawn on the reference's 1000 x 900 canvas with its frame (sector apex 500/34,
radius 826, teal #12a08c walls and dashed arc, probe bar with index dot), its defs (feTurbulence
speckle, warm-taupe myocardium gradient, navy / burgundy blood gradients, Gaussian glow, depth
attenuation) and its drawing language: one smooth myocardial body with an ivory glowing
epicardium, chambers carved out as blood pools, tapered ivory leaflets, chordae, papillary
muscles. subcostal_4c IS the reference file.

Output (rendered offline in Chromium, identical on every phone):
  assets/diagrams/schema/<view>_schema.png         1080 px
  assets/diagrams/schema/<view>_schema_labels.png  transparent label layer, same size
and "schema" caliper coordinates (normalised to this canvas) for every step overlay.
Usage: python3 scripts/build_schema_art.py
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ART = os.path.join(ROOT, "scripts", "schema_art")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "diagrams", "schema")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
W, H = 1000, 900
PX = 1080

SECTOR = "M500,34 L2.9,693.7 A826,826 0 0 0 997.1,693.7 Z"
IVORY, LEAF, CHORD_C = "#f4eee2", "#f7f1e6", "#f4eee2"

_n = [0]


def uid(p):
    _n[0] += 1
    return f"{p}{_n[0]}"


def tissue(d: str, glow: bool = True) -> str:
    """Myocardial body exactly as in the reference: glow, gradient, ivory edge, clipped speckle."""
    c = uid("epi")
    return (f'<clipPath id="{c}"><path d="{d}"/></clipPath>'
            + (f'<path d="{d}" fill="none" stroke="{IVORY}" stroke-width="16" opacity=".22" filter="url(#glow)"/>' if glow else "")
            + f'<path d="{d}" fill="url(#myoA)" stroke="{IVORY}" stroke-width="3.5" stroke-linejoin="round"/>'
            f'<g clip-path="url(#{c})"><rect width="1000" height="900" filter="url(#spk)" opacity=".38"/>'
            f'<rect width="1000" height="900" filter="url(#spkD)" opacity=".42"/></g>')


def blood(d: str, side: str) -> str:
    return f'<path d="{d}" fill="url(#blood{side})"/>'


def leaflets(*ds: str) -> str:
    return (f'<g fill="{LEAF}" stroke="{LEAF}" stroke-width="1" stroke-linejoin="round">'
            + "".join(f'<path d="{d}"/>' for d in ds) + "</g>")


def chordae(*ds: str) -> str:
    return (f'<g stroke="{CHORD_C}" stroke-width="1.4" opacity=".55" fill="none">'
            + "".join(f'<path d="{d}"/>' for d in ds) + "</g>")


def tube(d: str, wall: float, lumen: float, side: str) -> str:
    """Vessel: glowing ivory outline, speckled taupe wall (masked noise), blood lumen."""
    m = uid("tm")
    return (f'<mask id="{m}"><path d="{d}" fill="none" stroke="#fff" stroke-width="{wall}" stroke-linecap="round"/></mask>'
            f'<path d="{d}" fill="none" stroke="{IVORY}" stroke-width="{wall + 14}" stroke-linecap="round" opacity=".22" filter="url(#glow)"/>'
            f'<path d="{d}" fill="none" stroke="{IVORY}" stroke-width="{wall + 5}" stroke-linecap="round"/>'
            f'<path d="{d}" fill="none" stroke="#b8927f" stroke-width="{wall}" stroke-linecap="round"/>'
            f'<g mask="url(#{m})"><rect width="1000" height="900" filter="url(#spk)" opacity=".38"/>'
            f'<rect width="1000" height="900" filter="url(#spkD)" opacity=".42"/></g>'
            f'<path d="{d}" fill="none" stroke="{"#10253a" if side == "R" else "#24111a"}" stroke-width="{lumen}" stroke-linecap="round"/>')


def ring(cx, cy, r, wall, side) -> str:
    return (tube(f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0", wall, 0, side)
            + f'<circle cx="{cx}" cy="{cy}" r="{r - wall / 2 + 1}" fill="url(#blood{side})"/>')


def pericardium(d: str) -> str:
    return (f'<path d="{d}" fill="none" stroke="#f1e9d8" stroke-width="12" opacity=".35" filter="url(#glow)"/>'
            f'<path d="{d}" fill="none" stroke="{IVORY}" stroke-width="5" stroke-linecap="round" opacity=".9"/>')


def liver(d: str, veins: list[tuple[str, float]]) -> str:
    c = uid("liv")
    return (f'<clipPath id="{c}"><path d="{d}"/></clipPath><g clip-path="url(#{c})">'
            f'<rect width="1000" height="900" fill="url(#liverG)"/>'
            f'<rect width="1000" height="900" filter="url(#spk)" opacity=".30"/>'
            f'<rect width="1000" height="900" filter="url(#spkD)" opacity=".45"/>'
            f'<g fill="none" stroke="#141c22" stroke-linecap="round" filter="url(#soft)">'
            + "".join(f'<path d="{v}" stroke-width="{w}"/>' for v, w in veins)
            + '</g><path d="M500,34 L420,140 L580,140 Z" fill="#fff" opacity=".10" filter="url(#glow)"/></g>')


# ---------------------------------------------------------------------------------------------
# Views (final canvas coordinates; the sector half-angle is 37°, apex 500/34)
# ---------------------------------------------------------------------------------------------

def plax() -> str:
    epi = ("M60,400 C200,352 380,340 540,342 C720,344 880,368 960,410 C1000,500 995,630 920,690 "
           "C790,745 540,755 340,740 C200,728 100,690 60,625 C40,570 42,470 60,400 Z")
    return (tissue(epi)
            + blood("M100,425 C260,382 600,372 900,408 C910,425 905,440 895,450 C700,430 400,432 130,474 "
                    "C112,460 100,442 100,425 Z", "R")
            # LV, LVOT and aortic root with the sinuses of Valsalva
            + blood("M60,520 C160,495 340,490 520,500 L600,500 C640,470 668,458 700,452 C740,440 792,440 832,450 "
                    "C880,455 930,456 990,460 L990,556 C930,560 880,564 832,568 C792,580 742,578 700,566 "
                    "C668,562 640,560 612,566 C622,590 618,612 604,628 C570,654 480,668 340,676 "
                    "C220,682 120,668 60,640 Z", "L")
            + blood("M650,606 C730,594 880,594 975,602 C995,640 980,690 918,708 C820,728 722,722 678,698 "
                    "C652,680 642,636 650,606 Z", "L")
            + blood("M590,610 C620,612 645,625 664,646 L670,700 C626,692 590,672 566,652 Z", "L")
            + '<path d="M300,676 C318,644 352,634 372,644 C386,652 384,664 398,666 C368,676 330,680 300,676 Z" fill="url(#myoA)"/>'
            + chordae("M352,648 L572,640", "M366,656 L594,662")
            + leaflets("M652,588 C626,600 598,616 568,642 C600,628 628,614 654,604 Z",
                       "M670,702 C644,690 618,676 592,660 C616,684 644,700 664,716 Z",
                       "M700,456 C708,476 714,494 716,510 C708,494 700,478 694,462 Z",
                       "M700,562 C708,544 714,526 716,512 C706,528 698,544 692,558 Z")
            + '<ellipse cx="650" cy="592" rx="8" ry="12" fill="#efe2d2"/>'
            + pericardium("M60,650 C150,735 420,775 640,772 C790,770 900,735 975,665")
            + ring(690, 800, 30, 10, "L"))


def psax_av() -> str:
    epi = ("M160,600 C160,470 300,395 500,392 C700,390 840,470 862,600 C878,730 735,822 500,822 "
           "C290,822 160,740 160,600 Z")
    return (tissue(epi)
            + blood("M200,565 C218,455 340,412 500,410 C650,408 756,452 812,524 L752,560 C706,494 616,474 500,474 "
                    "C386,474 306,516 288,588 Z", "R")
            + blood("M776,526 C820,566 842,628 838,716 L770,716 C774,650 760,600 730,566 Z", "R")
            + blood("M184,646 C186,602 238,584 290,600 C340,622 350,690 318,730 C286,764 222,760 200,724 "
                    "C186,702 180,672 184,646 Z", "R")
            + blood("M372,712 C404,682 600,680 640,712 C672,746 646,796 566,808 C482,820 394,802 366,770 "
                    "C352,750 358,728 372,712 Z", "L")
            + '<circle cx="505" cy="594" r="70" fill="url(#bloodL)"/>'
            + leaflets("M502,594 L501,526 L509,526 L508,594 Z",
                       "M503,592 L445,626 L449,633 L507,598 Z",
                       "M507,592 L565,626 L561,633 L503,598 Z")
            + leaflets("M760,530 C772,518 786,514 798,518 C786,526 774,534 764,542 Z",
                       "M780,566 C796,554 812,552 822,558 C808,566 796,572 784,578 Z")
            + leaflets("M262,590 C272,574 286,566 300,566 C290,578 280,590 270,602 Z",
                       "M296,606 C310,596 326,594 338,600 C324,608 312,614 300,620 Z"))


def psax_pap() -> str:
    epi = ("M215,580 C232,450 352,382 565,377 C700,377 790,482 790,612 C790,742 700,846 565,846 "
           "C420,846 300,782 242,700 C218,662 208,618 215,580 Z")
    return (tissue(epi)
            + blood("M240,580 C258,488 330,436 410,424 C360,484 344,548 348,616 C352,684 372,734 410,784 "
                    "C332,772 272,732 250,672 C238,640 234,608 240,580 Z", "R")
            + '<circle cx="568" cy="612" r="170" fill="url(#bloodL)"/>'
            # papillary muscles at 4–5 o'clock (posteromedial) and 7–8 o'clock (anterolateral)
            + '<ellipse cx="655" cy="698" rx="42" ry="32" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
            + '<ellipse cx="481" cy="698" rx="42" ry="32" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
            + pericardium("M250,820 C390,880 720,884 840,800"))


APICAL_EPI = ("M500,168 C622,190 768,380 806,560 C834,690 792,806 652,834 C560,850 440,850 348,834 "
              "C208,806 166,690 194,560 C232,380 378,190 500,168 Z")


def apical_4c(five: bool = False) -> str:
    out = tissue(APICAL_EPI)
    if five:
        out += blood("M480,205 C420,240 300,380 240,500 C222,545 222,580 232,606 C300,592 390,592 448,602 C452,500 470,340 490,215 Z", "R")
        out += blood("M512,200 C580,230 700,360 752,480 C772,535 774,575 766,606 C690,590 560,590 470,608 C476,500 492,340 508,212 Z", "L")
        out += blood("M470,622 C474,700 476,780 476,860 L560,860 C556,780 552,700 548,622 Z", "L")
        out += blood("M236,628 C222,700 250,790 330,812 C400,824 446,790 450,730 L448,630 C380,616 300,616 236,628 Z", "R")
        out += blood("M574,628 C576,740 600,812 670,812 C740,810 772,740 764,628 C700,616 630,616 574,628 Z", "L")
        out += leaflets("M474,630 C486,642 496,650 508,656 C494,658 482,652 470,642 Z",
                        "M544,630 C532,642 522,650 510,656 C524,658 536,652 548,642 Z")
        mv = ("M578,612 C590,592 604,574 618,560 C610,584 600,602 592,620 Z",
              "M762,612 C752,592 738,574 722,560 C732,584 742,602 748,620 Z")
        tv = ("M240,600 C252,578 264,560 278,548 C270,572 262,590 254,606 Z",
              "M444,600 C434,580 424,562 412,550 C420,572 428,590 432,606 Z")
        out += '<path d="M262,476 L446,504" stroke="#c9a48f" stroke-width="7" stroke-linecap="round" fill="none"/>'
        out += '<ellipse cx="726" cy="470" rx="22" ry="52" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
        out += chordae("M722,520 L620,562", "M728,520 L724,562")
    else:
        out += blood("M480,205 C420,240 300,380 240,500 C222,545 222,580 232,606 C300,592 400,592 470,602 C468,500 476,340 492,215 Z", "R")
        out += blood("M512,200 C580,230 700,360 752,480 C772,535 774,575 766,606 C700,590 580,590 506,602 C500,500 500,340 508,212 Z", "L")
        out += blood("M236,628 C222,700 250,790 330,812 C410,826 466,790 474,730 L470,630 C400,616 300,616 236,628 Z", "R")
        out += blood("M508,628 C510,740 540,812 640,812 C730,810 772,740 764,628 C700,616 580,616 508,628 Z", "L")
        mv = ("M510,612 C522,592 536,574 550,560 C542,584 532,602 524,620 Z",
              "M762,612 C752,592 738,574 722,560 C732,584 742,602 748,620 Z")
        tv = ("M240,600 C252,578 264,560 278,548 C270,572 262,590 254,606 Z",
              "M466,598 C456,578 446,560 434,548 C442,570 450,588 454,604 Z")
        out += '<path d="M262,476 L468,506" stroke="#c9a48f" stroke-width="7" stroke-linecap="round" fill="none"/>'
        out += '<ellipse cx="726" cy="470" rx="22" ry="52" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
        out += chordae("M722,520 L552,562", "M728,520 L724,562")
    out += leaflets(*mv, *tv)
    return out


APICAL_LV = ("M500,200 C580,224 720,360 762,480 C780,540 780,580 770,608 C640,588 360,588 230,608 C220,580 220,540 238,480 "
             "C280,360 420,224 500,200 Z")


def apical_2c() -> str:
    return (tissue(APICAL_EPI)
            + blood(APICAL_LV, "L")
            + blood("M250,630 C240,740 320,814 500,818 C680,814 760,740 750,630 C620,612 380,612 250,630 Z", "L")
            + '<ellipse cx="292" cy="478" rx="22" ry="52" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
            + '<ellipse cx="708" cy="478" rx="22" ry="52" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
            + chordae("M294,528 L282,566", "M298,528 L330,570", "M706,528 L718,566", "M702,528 L670,570")
            + leaflets("M244,614 C258,592 272,574 286,560 C280,584 270,604 258,620 Z",
                       "M756,614 C742,592 728,574 714,560 C720,584 730,604 742,620 Z"))


def apical_3c() -> str:
    epi = ("M500,168 C622,190 768,380 806,560 C834,690 812,820 742,850 C640,866 420,862 330,840 "
           "C208,806 166,690 194,560 C232,380 378,190 500,168 Z")
    return (tissue(epi)
            + blood(APICAL_LV, "L")
            + blood("M238,632 C228,740 290,812 400,816 C490,818 532,770 534,700 L530,630 C440,616 320,618 238,632 Z", "L")
            + blood("M570,612 C590,700 610,780 626,870 L740,866 C718,780 700,690 690,612 Z", "L")
            + '<ellipse cx="300" cy="478" rx="22" ry="52" fill="url(#myoA)" stroke="#efe2d2" stroke-width="1"/>'
            + chordae("M302,528 L290,566", "M306,528 L500,572")
            + leaflets("M244,614 C258,592 272,574 286,560 C280,584 270,604 258,620 Z",
                       "M548,616 C534,600 516,584 496,568 C508,590 522,608 536,626 Z",
                       "M576,630 C590,642 604,650 620,654 C604,658 588,650 574,642 Z",
                       "M686,628 C672,642 658,650 642,654 C658,658 674,650 688,640 Z")
            + '<ellipse cx="550" cy="620" rx="9" ry="13" fill="#efe2d2"/>')


def suprasternal() -> str:
    arch = "M320,880 C318,690 330,560 410,500 C490,445 610,450 670,520 C722,580 718,720 700,880"
    branches = ["M430,494 C428,430 424,370 420,300", "M496,470 C496,410 496,350 498,290",
                "M560,474 C566,414 574,360 584,300"]
    out = "".join(tube(b, 58, 30, "L") for b in branches)
    out += tube(arch, 150, 112, "L")
    out += ring(510, 640, 52, 14, "R")
    return out


def subcostal_ivc() -> str:
    liv = "M-60,-10 L-60,640 C200,600 420,610 600,640 C760,668 880,690 1060,700 L1060,-10 Z"
    veins = [("M560,280 C590,380 620,470 650,560", 18), ("M600,400 C660,380 720,350 760,300", 10),
             ("M300,260 C330,330 360,380 390,420", 9)]
    ivc = "M60,560 C200,560 360,580 520,600 C620,612 700,624 760,640"
    out = liver(liv, veins)
    out += tissue("M690,600 C730,540 880,530 925,610 C960,690 920,800 820,806 C730,812 680,740 690,660 Z")
    out += tube(ivc, 88, 66, "R")
    out += blood("M716,612 C750,566 864,560 900,622 C928,690 896,776 820,782 C748,786 708,728 712,664 Z", "R")
    out += pericardium("M40,708 C260,700 520,712 700,716")
    return out


def frame(body: str, title: str) -> str:
    defs = open(os.path.join(ART, "_defs.svgpart"), encoding="utf-8").read()
    # myoA: the reference's myocardium gradient in object space (works for every shape)
    defs = defs.replace("</defs>", '<linearGradient id="myoA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" '
                        'stop-color="#c9a48f"/><stop offset="1" stop-color="#a47f6e"/></linearGradient></defs>')
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 900" width="1000" height="900">
<title>{title}</title>
{defs}
<g clip-path="url(#sec)">
 <rect width="1000" height="900" fill="url(#bg)"/>
 <rect width="1000" height="900" filter="url(#spk)" opacity=".10"/>
 {body}
 <rect width="1000" height="900" fill="url(#depth)"/>
</g>
<path d="M500,34 L2.9,693.7" stroke="#12a08c" stroke-width="3.5" fill="none"/>
<path d="M500,34 L997.1,693.7" stroke="#12a08c" stroke-width="3.5" fill="none"/>
<path d="M2.9,693.7 A826,826 0 0 0 997.1,693.7" stroke="#12a08c" stroke-width="3.5" stroke-dasharray="20 14" fill="none"/>
<rect x="438" y="8" width="124" height="30" rx="15" fill="#cdd5de"/>
<circle cx="422" cy="23" r="11" fill="#38b6f5"/>
</svg>
'''


VIEWS = {
    "plax_standard": plax,
    "psax_av": psax_av,
    "psax_papillary": psax_pap,
    "apical_4c": lambda: apical_4c(False),
    "apical_5c": lambda: apical_4c(True),
    "apical_2c": apical_2c,
    "apical_3c": apical_3c,
    "suprasternal_arch": suprasternal,
    "subcostal_ivc": subcostal_ivc,
}


# ---------------------------------------------------------------------------------------------
# Labels (AN) – dark pills with white text and leader lines, same canvas
# ---------------------------------------------------------------------------------------------

BIG, SMALL = 34, 26
LABELS = {
    "subcostal_4c": [("Liver", (500, 190), BIG, None), ("RA", (260, 560), BIG, None), ("RV", (560, 470), BIG, None),
                     ("LA", (300, 720), BIG, None), ("LV", (620, 650), BIG, None),
                     ("IAS", (120, 600), SMALL, (230, 625)), ("IVS", (850, 560), SMALL, (700, 585))],
    "subcostal_ivc": [("Liver", (430, 250), BIG, None), ("IVC", (250, 560), SMALL, None), ("RA", (810, 690), BIG, None),
                      ("HV", (700, 300), SMALL, (620, 470)), ("Diaphragm", (300, 770), SMALL, (300, 708))],
    "plax_standard": [("RV", (500, 415), BIG, None), ("LV", (260, 580), BIG, None), ("Ao", (870, 505), BIG, None),
                      ("LA", (830, 665), BIG, None), ("IVS", (180, 330), SMALL, (300, 470)), ("MV", (560, 760), SMALL, (600, 655)),
                      ("DAo", (850, 800), SMALL, (720, 800))],
    "psax_av": [("RVOT", (500, 445), BIG, None), ("AV", (505, 680), SMALL, None), ("RA", (250, 670), BIG, None),
                ("LA", (505, 760), BIG, None), ("TV", (200, 500), SMALL, (275, 585)), ("PV", (860, 470), SMALL, (780, 530)),
                ("MPA", (900, 660), SMALL, (808, 650))],
    "psax_papillary": [("RV", (295, 610), BIG, None), ("LV", (568, 560), BIG, None), ("IVS", (300, 400), SMALL, (380, 480)),
                       ("PM", (800, 740), SMALL, (655, 698)), ("PM", (330, 790), SMALL, (481, 698))],
    "apical_4c": [("RV", (360, 420), BIG, None), ("LV", (620, 400), BIG, None), ("RA", (350, 720), BIG, None),
                  ("LA", (640, 720), BIG, None), ("TV", (150, 560), SMALL, (250, 590)), ("MV", (860, 560), SMALL, (750, 590)),
                  ("IVS", (490, 120), SMALL, (488, 300))],
    "apical_5c": [("RV", (340, 420), BIG, None), ("LV", (630, 400), BIG, None), ("RA", (340, 720), BIG, None),
                  ("LA", (670, 720), BIG, None), ("Ao", (512, 760), SMALL, None), ("AV", (150, 600), SMALL, (488, 646)),
                  ("LVOT", (860, 520), SMALL, (520, 590))],
    "apical_2c": [("LV", (500, 420), BIG, None), ("LA", (500, 720), BIG, None), ("INF", (150, 420), SMALL, (215, 470)),
                  ("ANT", (860, 420), SMALL, (785, 470)), ("MV", (880, 600), SMALL, (750, 600))],
    "apical_3c": [("LV", (500, 420), BIG, None), ("LA", (380, 720), BIG, None), ("Ao", (660, 760), SMALL, None),
                  ("LVOT", (870, 520), SMALL, (640, 590)), ("AV", (880, 620), SMALL, (660, 650)),
                  ("MV", (130, 600), SMALL, (260, 600)), ("IL", (150, 400), SMALL, (215, 450)), ("AS", (860, 400), SMALL, (790, 450))],
    "suprasternal_arch": [("Arch", (560, 470), BIG, None), ("Asc Ao", (240, 700), SMALL, (320, 700)),
                          ("Desc Ao", (840, 720), SMALL, (708, 720)), ("RPA", (510, 640), SMALL, None),
                          ("BCA", (300, 330), SMALL, (424, 360)), ("LCCA", (500, 240), SMALL, (497, 300)),
                          ("LSA", (720, 330), SMALL, (578, 350))],
}


def label_svg(view_id: str) -> str:
    parts = []
    for text, (x, y), size, target in LABELS[view_id]:
        w, h = len(text) * size * 0.6 + size * 0.9, size * 1.45
        if target:
            parts.append(f'<path d="M{x},{y} L{target[0]},{target[1]}" stroke="#f1f5f9" stroke-opacity=".85" stroke-width="2.5"/>'
                         f'<circle cx="{target[0]}" cy="{target[1]}" r="5" fill="#f1f5f9"/>')
        parts.append(f'<rect x="{x - w / 2:.1f}" y="{y - h / 2:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{h / 2:.1f}" '
                     f'fill="#0f1419" fill-opacity=".82"/><text x="{x}" y="{y + size * 0.36:.1f}" font-family="sans-serif" '
                     f'font-weight="700" font-size="{size}" fill="#f1f5f9" text-anchor="middle">{text}</text>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 900" width="1000" height="900">{"".join(parts)}</svg>'


# ---------------------------------------------------------------------------------------------
# Step calipers on these drawings (canvas px). CW cursors put the focus diamond (82 %) on the valve.
# ---------------------------------------------------------------------------------------------

APEX = (500, 34)


def cw_through(px, py, focus=0.82):
    return [APEX[0], APEX[1], APEX[0] + (px - APEX[0]) / focus, APEX[1] + (py - APEX[1]) / focus]


def ref_pt(x, y):
    """Reference subcostal heart local coords -> canvas (translate(188,612) rotate(-13) scale(1.1))."""
    import math
    a = math.radians(-13)
    xs, ys = 1.1 * x, 1.1 * y
    return 188 + xs * math.cos(a) - ys * math.sin(a), 612 + xs * math.sin(a) + ys * math.cos(a)


def _ias_box():
    pts = [ref_pt(x, y) for x, y in ((50, -55), (205, -55), (50, 55), (205, 55))]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return [min(xs), min(ys), max(xs), max(ys)]


SCHEMA_CALIPERS = {
    ("subcostal_4c", "Color · IAS"): _ias_box(),
    ("subcostal_4c", "Pericardium"): [*ref_pt(330, -190), *ref_pt(330, -150)],
    ("subcostal_ivc", "IVC"): [620, 578, 614, 646],
    ("subcostal_ivc", "M-mode · IVC"): [500, 34, 612, 720],
    ("plax_standard", "IVSd"): [450, 434, 450, 496],
    ("plax_standard", "LVIDd"): [450, 498, 450, 670],
    ("plax_standard", "LVPWd"): [450, 672, 450, 744],
    ("plax_standard", "Ao root"): [760, 444, 760, 576],
    ("plax_standard", "LA"): [820, 600, 820, 720],
    ("psax_av", "MPA"): [774, 640, 836, 640],
    ("psax_av", "PW · PV"): [500, 34, 744, 506],
    ("psax_av", "Color · PDA"): [730, 600, 870, 720],
    ("psax_av", "CW · TR"): cw_through(282, 592),
    ("psax_papillary", "LVIDd (FS)"): [568, 444, 568, 780],
    ("psax_papillary", "D1", "ED"): [560, 444, 560, 780],
    ("psax_papillary", "D2", "ED"): [400, 612, 736, 612],
    ("psax_papillary", "D1", "ES"): [560, 474, 560, 750],
    ("psax_papillary", "D2", "ES"): [428, 612, 708, 612],
    ("apical_4c", "LV length (EDV)"): [512, 206, 636, 598],
    ("apical_4c", "M-mode · TAPSE"): [500, 34, 226, 650],
    ("apical_4c", "RVD1"): [230, 560, 466, 560],
    ("apical_4c", "PW · E/A"): [500, 34, 636, 578],
    ("apical_4c", "e' septal"): [500, 34, 510, 604],
    ("apical_4c", "e' lateral"): [500, 34, 762, 604],
    ("apical_4c", "CW · TR"): cw_through(352, 590),
    ("apical_4c", "LA length"): [636, 622, 640, 810],
    ("apical_5c", "PW · LVOT"): [500, 34, 510, 590],
    ("apical_5c", "CW · AV"): cw_through(510, 650),
    ("apical_2c", "LV length (A2C)"): [500, 206, 500, 596],
    ("apical_2c", "LA length (A2C)"): [500, 624, 500, 816],
    ("apical_3c", "Color · AV/MV"): [430, 520, 720, 700],
    ("apical_3c", "PW · LVOT"): [500, 34, 628, 590],
    ("apical_3c", "CW · AV"): cw_through(630, 642),
    ("suprasternal_arch", "Arch"): [470, 408, 470, 516],
    ("suprasternal_arch", "Isthmus"): [656, 600, 768, 600],
    ("suprasternal_arch", "CW · DAo"): [500, 34, 708, 870],
}

JS = r"""
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const jobs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 972 } });
  for (const j of jobs) {
    const svg = fs.readFileSync(j.svg, 'utf8');
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ',
      '<svg style="display:block;width:1080px;height:972px" ')}</body></html>`);
    await page.screenshot({ path: j.png, omitBackground: true });
  }
  await browser.close();
})();
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    tmp = tempfile.mkdtemp()
    jobs = []
    all_views = ["subcostal_4c", *VIEWS]
    for vid in all_views:
        art = (open(os.path.join(ART, "subcostal_4c.svg"), encoding="utf-8").read() if vid == "subcostal_4c"
               else frame(VIEWS[vid](), f"{vid} schema"))
        with open(os.path.join(ART, f"{vid}.svg"), "w", encoding="utf-8") as f:
            f.write(art)
        for name, svg in ((f"{vid}_schema", art), (f"{vid}_schema_labels", label_svg(vid))):
            path = os.path.join(tmp, name + ".svg")
            with open(path, "w", encoding="utf-8") as f:
                f.write(svg)
            jobs.append({"svg": path, "png": os.path.join(OUT, name + ".png")})
    with open(os.path.join(tmp, "jobs.json"), "w") as f:
        json.dump(jobs, f)
    with open(os.path.join(tmp, "r.js"), "w") as f:
        f.write(JS)
    env = dict(os.environ, NODE_PATH=subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    subprocess.run(["node", os.path.join(tmp, "r.js"), os.path.join(tmp, "jobs.json")], check=True, env=env)
    from PIL import Image
    for j in jobs:
        im = Image.open(j["png"])
        if j["png"].endswith("_schema.png"):
            bg = Image.new("RGBA", im.size, (15, 20, 25, 255))   # panel colour #0F1419 outside the sector
            bg.alpha_composite(im.convert("RGBA"))
            im = bg.convert("RGB").quantize(colors=220, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)
        im.save(j["png"], optimize=True)

    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    missing = []
    for window in db["windows"]:
        for view in window["views"]:
            vid = view["id"]
            view["schemaAsset"] = f"diagrams/schema/{vid}_schema.png"
            view["schemaAspect"] = round(W / H, 4)
            view.pop("schemaCrop", None)
            for step in view["steps"]:
                for ov in ([step["caliperOverlay"]] if step.get("caliperOverlay") else []) + step.get("extraOverlays", []):
                    c = SCHEMA_CALIPERS.get((vid, ov["label"], step.get("requiredPhase"))) or \
                        SCHEMA_CALIPERS.get((vid, ov["label"]))
                    if c is None:
                        missing.append((vid, ov["label"]))
                        ov.pop("schema", None)
                    else:
                        ov["schema"] = [round(c[0] / W, 4), round(c[1] / H, 4), round(c[2] / W, 4), round(c[3] / H, 4)]
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    size = sum(os.path.getsize(j["png"]) for j in jobs)
    print(f"{len(jobs)} PNGs ({size / 1e6:.1f} MB); schema calipers missing: {missing or 'none'}")


if __name__ == "__main__":
    main()
