EchoScope – Cine Loop Assets
============================

This folder holds the offline echocardiography cine loops played by
EchoCinePlayer (AndroidX Media3 / ExoPlayer) via asset:///loops/<id>.mp4.

Expected files (one per standard TTE view, names must match exactly):

  loops/subcostal_4c.mp4        Subcostal 4-Chamber
  loops/subcostal_ivc.mp4       Subcostal IVC & Bicaval
  loops/plax_standard.mp4       Parasternal Long Axis
  loops/psax_av.mp4             PSAX Aortic Valve & Great Vessels
  loops/psax_papillary.mp4      PSAX Papillary Muscle Level
  loops/apical_4c.mp4           Apical 4-Chamber
  loops/apical_5c.mp4           Apical 5-Chamber
  loops/apical_2c_3c.mp4        Apical 2-Chamber & 3-Chamber
  loops/suprasternal_arch.mp4   Suprasternal Aortic Arch (Long & Crab)

Bundled loops: real + simulated
-------------------------------
Real, openly licensed NORMAL echocardiography clips from Wikimedia Commons are used for
7 views: apical_4c, apical_2c_3c, psax_papillary, suprasternal_arch, plax_standard,
subcostal_4c (CardioNetworks ECHOpedia, CC BY-SA 3.0) and subcostal_ivc
("Inferior vena cava ultrasound.gif", CC BY-SA 4.0; no ECG, nominal timing).
psax_av and apical_5c keep clearly labelled simulated loops (scripts/simulate_loops.py):
no normal clip of these views was found under a redistributable licence on Commons,
Zenodo, Figshare or The POCUS Atlas (scripts/global_echo_hunter.py). Replace them with
anonymised normal clips when available (convert_real_loops.py, MAPPING).
Credits for every clip: ATTRIBUTION.txt. Each view's "videoSource" in
echo_protocol_full.json says which kind it is; the app shows the credit or a
"simulated" note under the player.

Rebuild the real loops:
  python3 scripts/download_real_echo.py --match '<titles>'   (Wikimedia Commons)
  python3 scripts/convert_real_loops.py                      (map, convert, ECG timing)

How to replace them with real clips
-----------------------------------
1. Export the raw clips from the echo machine or PACS (AVI/MOV/MP4/WMV).
2. Put them in <project>/raw_loops/ named <view_id>.<ext>
   (e.g. raw_loops/apical_4c.avi).
3. Run:  scripts/prepare_loops.sh
   The script crops the top 60 px (hospital / patient header), scales to
   720 px width, strips audio and encodes H.264 (CRF 26, +faststart).

Data protection
---------------
Only bundle fully anonymised clips. Verify every output frame visually:
burned-in patient data outside the top 60 px header (e.g. side panels or
measurement overlays with names) is NOT removed by the crop and must be
handled before export. Obtain institutional permission before distributing
clinical images.

Missing files
-------------
The app works without these files: the player shows a fallback card that
names the missing path (assets/loops/<id>.mp4). All other features
(SVG anatomy, measurements, checklists, Z-score calculator) stay available.

Recommended clip properties: 2–4 cardiac cycles, 30 fps, < 1.5 MB each.
