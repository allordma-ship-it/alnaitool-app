#!/usr/bin/env python3
"""Extract review-only MP4 candidates from the EV9V frame-sequence dataset.

This script NEVER writes into app/src/main/assets/loops. Every generated clip
lands in raw_loops/ev9v_candidates and must be visually reviewed before use.

Expected EV9V layout:
  DATASET_ROOT/
    Images/<video_name>/<frame files>.jpg
    train_labeled.txt
    validation_labeled.txt
    test_labeled.txt

Each label file contains: <video_name> <CLASS_CODE>
Requires ffmpeg on PATH. Uses only Python's standard library.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

# These are candidate mappings, not clinical equivalence claims.
# PASA is labelled only "Parasternal Short Axis" in EV9V; it is NOT
# specifically confirmed as the semilunar/aortic-valve level.
CANDIDATES = {
    "PLHLA": ("plax_standard", "Parasternal long axis"),
    "A5C": ("apical_5c", "Apical five-chamber"),
    "SC4C": ("subcostal_4c", "Subcostal four-chamber"),
    "PASA": ("psax_av", "Generic PSAX; AV level NOT confirmed"),
}
MIN_FRAMES = 45


def natural_key(path: Path):
    return [int(part) if part.isdigit() else part.lower()
            for part in re.split(r"(\d+)", path.name)]


def load_labels(root: Path):
    rows = []
    for split in ("train_labeled.txt", "validation_labeled.txt", "test_labeled.txt"):
        file = root / split
        if not file.exists():
            continue
        for line in file.read_text(encoding="utf-8").splitlines():
            parts = line.strip().split()
            if len(parts) >= 2:
                rows.append((parts[0], parts[-1], split))
    if not rows:
        raise SystemExit("No label files found. Expected train/validation/test_labeled.txt.")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_root", type=Path)
    parser.add_argument("--out", type=Path, default=Path("raw_loops/ev9v_candidates"))
    parser.add_argument("--max-per-view", type=int, default=3)
    args = parser.parse_args()

    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise SystemExit("ffmpeg is required and must be on PATH.")
    root = args.dataset_root.resolve()
    images_root = root / "Images"
    if not images_root.is_dir():
        raise SystemExit(f"Missing frame directory: {images_root}")

    labels = load_labels(root)
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {
        "dataset": "EV9V",
        "dataset_license": "CC BY 4.0 (verify current dataset card before redistribution)",
        "source": "https://huggingface.co/datasets/bgx666/EV9V",
        "note": "Review-only candidates. No file is installed into app assets.",
        "candidates": [],
        "not_covered": {
            "subcostal_ivc": "EV9V has SC4C but no dedicated IVC long-axis class.",
            "psax_av": "PASA is generic PSAX; aortic/semilunar valve level is not guaranteed.",
            "psax_papillary": "PPMLSA exists in EV9V, but current app already has a real clip."
        }
    }

    for class_code, (view_id, description) in CANDIDATES.items():
        candidates = []
        for video_name, label, split in labels:
            if label != class_code:
                continue
            frame_dir = images_root / video_name
            if not frame_dir.is_dir():
                continue
            frames = sorted(
                [p for p in frame_dir.iterdir()
                 if p.suffix.lower() in {".jpg", ".jpeg", ".png"}],
                key=natural_key,
            )
            if len(frames) >= MIN_FRAMES:
                candidates.append((len(frames), video_name, split, frames))
        candidates.sort(key=lambda row: (-row[0], row[1]))

        for rank, (count, video_name, split, frames) in enumerate(
            candidates[:args.max_per_view], start=1
        ):
            output = args.out / f"{view_id}_candidate_{rank}.mp4"
            # concat demuxer avoids assuming that frame names are zero-padded.
            concat_file = args.out / f".{view_id}_{rank}_frames.txt"
            try:
                lines = []
                for frame in frames:
                    escaped = str(frame.resolve()).replace("'", "'\\''")
                    lines.append(f"file '{escaped}'")
                concat_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
                subprocess.run(
                    [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                     "-f", "concat", "-safe", "0", "-r", "30",
                     "-i", str(concat_file), "-an",
                     "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                     "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                     "-map_metadata", "-1", str(output)],
                    check=True,
                )
            finally:
                concat_file.unlink(missing_ok=True)
            manifest["candidates"].append({
                "view_id": view_id,
                "class_code": class_code,
                "description": description,
                "file": output.name,
                "source_video": video_name,
                "split": split,
                "frame_count": count,
                "fps": 30,
                "review_required": True,
                "clinical_view_warning": (
                    "Generic PSAX only; verify semilunar/aortic-valve level."
                    if class_code == "PASA" else None
                ),
            })
            print(f"Created review candidate: {output} ({count} frames)")

    manifest_path = args.out / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nManifest: {manifest_path}")
    print("No app asset was overwritten. Review anatomy, cycles, overlays and attribution before use.")


if __name__ == "__main__":
    main()
