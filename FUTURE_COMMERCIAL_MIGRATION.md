# EchoScope – future commercial migration

EchoScope currently ships media whose licences do **not** allow a commercial release. Nothing has
been removed or replaced: the app uses every original file and behaves exactly as before. This
document explains what has to change before a paid / commercial distribution and how to do it
without touching UI or playback code.

The per-file inventory is in [`ASSET_LICENSES.md`](ASSET_LICENSES.md) (generated) and, at runtime,
in `app/src/main/assets/media/media_registry.json`. This is a technical plan, not legal advice –
have the licence position confirmed before a commercial release.

## 1. What must be replaced or separately licensed

| Group | Files | Licence | Status | Action |
|---|---|---|---|---|
| The POCUS Atlas – view loops | `loops/psax_av.mp4`, `loops/apical_5c.mp4` | CC BY-NC 4.0 | NON_COMMERCIAL_ONLY | Replace, or obtain a commercial licence from the authors (Justin Bowra et al.; Hannah Kopinski, Lindsay Davis, Matthew Riscinti) |
| The POCUS Atlas – colour Doppler loops (also used in the Pathologie cases MR, TR, AR) | `loops/color/a4c_mv_color.mp4`, `a4c_tv_color.mp4`, `a5c_av_color.mp4`, `plax_color.mp4` | CC BY-NC 4.0 | NON_COMMERCIAL_ONLY | Replace, or licence |
| Echocardiographer.org images | all 52 files in `echoorg/` (probe photos, view drawings, labelled frames, spectral traces, examples) | "Feel free to use … for free, but please provide credit" – commercial use **not expressly granted** | PERMISSION_REQUIRED | Ask Dr. Atif Qasim for written commercial permission, else replace |
| Schema drawings derived from Echocardiographer.org | 30 files in `schema/` (all except `subcostal_ivc_*`) | as above (derived works) | PERMISSION_REQUIRED | Covered by the same permission, else redraw from a cleared source |
| Subcostal IVC atlas image | `diagrams/subcostal_ivc.png` | unknown ("supplied; original source and licence to be confirmed") | LICENSE_UNVERIFIED | Identify the source and licence, else replace |
| Launcher icon | `res/mipmap-*/ic_launcher_foreground.png`, `ic_launcher_monochrome.png` | artwork supplied by the project owner, AI-generated (generator watermark removed) | LICENSE_UNVERIFIED | Confirm the image generator's terms allow commercial use and trademark use |

## 2. What can stay (licence allows commercial use)

| Group | Licence | Duties |
|---|---|---|
| CardioNetworks ECHOpedia view loops (`loops/subcostal_4c`, `plax_standard`, `psax_papillary`, `apical_4c`, `apical_2c`, `apical_3c`, `suprasternal_arch`) | CC BY-SA 3.0 | Attribution; the converted files stay under CC BY-SA 3.0 |
| `loops/subcostal_ivc.mp4` (Tinss, Wikimedia Commons) | CC BY-SA 4.0 | Attribution, ShareAlike |
| Pathology loops and stills from ECHOpedia (`pathology/*.mp4`, `commons/*e00*.jpg`), TAPSE M-mode (Wolff-BI) | CC BY-SA 3.0 | Attribution, ShareAlike |
| ASD II still (Kjetil Lenes) | Public domain | none (credit kept as courtesy) |
| Anatomical atlas illustrations, Patrick J. Lynch & C. Carl Jaffe (`diagrams/*.png` except `subcostal_ivc.png`) | CC BY 2.5 | Attribution |
| Project-original artwork: orientation diagrams (`orientation/`), legacy schematics (`svg/`), IVC schema (`schema/subcostal_ivc_*`) | EchoScope | none |

ShareAlike means the media files themselves (not the app's code) must remain available under the
same CC BY-SA licence; keeping the credits in Settings › "Über EchoScope & Bildnachweise", the
`ATTRIBUTION.txt` files and `ASSET_LICENSES.md` covers attribution.

## 3. How the replacement mechanism works

Every video and image is loaded through `MediaRegistry` (`app/src/main/java/com/sonopulse/echoguide/MediaRegistry.kt`):

* `MediaRegistry.resolve(path)` returns the registered `replacement` of an asset, or the path itself;
* the cine-loop player (`EchoCinePlayer.rememberLoopState`) and every image loader
  (`ViewCockpit`, `ZoomableSvgDiagram`, `ValveAssessment`, `PathologyGallery`, `MainActivity`)
  call it, so a replacement takes effect everywhere at once;
* the registry is read together with the protocol (`EchoRepository.load`); without replacements
  the mapping is the identity, which is the state of the app today.

The protocol (`echo_protocol_full.json`), UI and ExoPlayer configuration never need to change to
swap a file.

## 4. How to replace a restricted video (no code changes)

1. Put the licensed file in the assets, preferably under `app/src/main/assets/media/commercial/`,
   e.g. `media/commercial/psax_av.mp4` (H.264 MP4, no audio, ≈ 720 px wide, same view and similar
   length so the ECG timing still fits – adjust `ecg` of the view in the protocol only if the beat
   timing differs).
2. Register it and map the original in `scripts/media_overrides.json`:

   ```json
   {
     "replacements": { "loops/psax_av.mp4": "media/commercial/psax_av.mp4" },
     "assets": [{
       "path": "media/commercial/psax_av.mp4", "type": "video",
       "source": "…", "sourceUrl": "…", "author": "…",
       "license": "…", "commercialUse": "COMMERCIAL_ALLOWED",
       "originalFilename": "…"
     }]
   }
   ```
3. Run `python3 scripts/build_media_registry.py` (regenerates the registry and `ASSET_LICENSES.md`).
4. Update the visible credit: the `videoSource` of the view (or the colour loop / case media entry)
   in `echo_protocol_full.json` and the matching `ATTRIBUTION.txt`, so Settings shows the new author.
5. Run the tests (`./gradlew testDebugUnitTest`) and the gate (section 6).

Images work the same way (e.g. `"echoorg/apical4_02.jpg": "media/commercial/apical4_drawing.webp"`).
Calipers drawn on a Schema drawing are positioned in normalised image coordinates; a replacement
drawing with different geometry needs its `schema` coordinates re-checked (`scripts/schema_overlays.py`,
`scripts/verify_doppler_and_calipers.py`).

## 5. Adding a commercially licensed asset

* Record the exact licence (or the written permission) before use; store the permission letter or
  invoice outside the APK (e.g. a private `licensing/` folder or document system).
* Only mark `"commercialUse": "COMMERCIAL_ALLOWED"` when that evidence exists – never for a file
  that is merely publicly accessible.
* For a permission granted by Echocardiographer.org, change the status of the `echoorg/` and derived
  `schema/` entries in `scripts/build_media_registry.py` (one place) and note the permission reference.

## 6. Verifying that no restricted asset remains in a commercial release

```
python3 scripts/check_commercial_assets.py                       # registry: every restricted file replaced?
./gradlew assembleRelease
python3 scripts/check_commercial_assets.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

The gate exits with 1 and lists every finding while any NON_COMMERCIAL_ONLY, PERMISSION_REQUIRED or
LICENSE_UNVERIFIED file is used **or packaged**. Today it reports 99 findings – expected.

Remapping alone keeps the original file inside the APK. To also leave restricted originals out of a
commercial build (required for the `--apk` check), add a `commercial` product flavor when the time
comes, for example:

```kotlin
flavorDimensions += "distribution"
productFlavors {
    create("education") { dimension = "distribution" }          // today's app, all media
    create("commercial") { dimension = "distribution"; applicationIdSuffix = ".pro" }
}
```

and move the restricted originals from `src/main/assets/` to `src/education/assets/` (same relative
paths, so references keep working) while the substitutes live in `src/main/assets/media/commercial/`.
This flavor split is deliberately **not** done yet: it changes the build and was out of scope for this
preparation step.

## 7. Open licensing questions

* Echocardiographer.org: does "use … for free" include commercial use? Needs written confirmation.
* `diagrams/subcostal_ivc.png`: original source and licence unknown.
* Launcher icon: rights in the AI-generated master artwork.
* The POCUS Atlas: commercial licensing terms are only available from the individual authors.
* CC BY-SA 3.0 / 4.0: confirm the attribution format satisfies the licences for an app store listing.
* Clinical content (texts, calculators) is project-authored; regulatory status (EU MDR) is a separate question.
