# EV9V review-candidate extraction

This workflow prepares **review-only** echo cine candidates from the EV9V dataset.
It does not replace or overwrite any bundled app video.

## Dataset facts

EV9V is a clinical TTE dataset from the First Affiliated Hospital of Chengdu
Medical College. Its dataset card declares **CC BY 4.0**, describes anonymized
JPEG frame sequences at 320x240 and 30 fps, and provides three label files.
Read the current dataset card before redistribution:
https://huggingface.co/datasets/bgx666/EV9V

The dataset has nine classes:

| Code | Label |
|---|---|
| PLHLA | Parasternal long axis |
| PASA | Parasternal short axis (generic; exact valve level not guaranteed) |
| PMPALA | Pulmonary main pulmonary artery long axis |
| PMVLSA | PSAX mitral valve level |
| PPMLSA | PSAX papillary muscle level |
| PMASA | PSAX apical level |
| A4C | Apical four chamber |
| A5C | Apical five chamber |
| SC4C | Subcostal four chamber |

**Important coverage gaps:** EV9V does not list a dedicated subcostal IVC long-axis
class. Its PASA class is generic PSAX and must not automatically be treated as a
confirmed aortic/semilunar-valve-level view. It does not provide a suprasternal
aortic arch class. It has no dedicated A2C/A3C class in the nine labels.

## Download and extract

1. Download the dataset files from Hugging Face:
   https://huggingface.co/datasets/bgx666/EV9V
2. Extract the archive locally. The dataset root must contain `Images/` and
   `train_labeled.txt`, `validation_labeled.txt`, `test_labeled.txt`.
3. Install FFmpeg and Python 3.10+.
4. Run from the repository root:

```bash
python3 scripts/prepare_ev9v_candidates.py /path/to/EV9V \
  --out raw_loops/ev9v_candidates --max-per-view 3
```

The script creates candidate MP4s and a manifest under
`raw_loops/ev9v_candidates/`. It never writes to
`app/src/main/assets/loops/`.

## Required human review before app integration

For every candidate:
- Confirm the acoustic window and exact anatomical level frame by frame.
- Check that the clip shows 2–4 useful cardiac cycles and has no abrupt
  acquisition transition.
- Inspect all frames for burned-in patient identifiers, measurements or labels.
- Confirm image quality and sector integrity at phone display size.
- Confirm CC BY 4.0 attribution is included in app credits and distribution notes.
- Check that ED/ES ECG timing is measured for that exact clip; do not reuse
  estimated timing from another video.

Do not use a generic PASA candidate as PSAX AV without an expert confirming the
semilunar valve level. Do not substitute SC4C for IVC long-axis. Do not bundle
unreviewed clips or treat this dataset as diagnostic evidence.

## Other candidate sources

The Indian Academy of Echocardiography adult TTE standards list 29 separately
provided videos, including PLAX, PSAX semilunar-valve level, papillary muscle,
A4C, subcostal IVC long-axis and suprasternal aortic arch:
https://www.iaecho.in/downloads/Comprehensive-TTE-in-adults-2015.pdf

The guideline PDF does not itself contain those separate video files or grant
redistribution rights. Obtain the files and written permission from IAE before
including them in EchoScope.
