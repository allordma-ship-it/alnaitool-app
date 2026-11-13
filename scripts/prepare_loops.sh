#!/usr/bin/env bash
# EchoScope – cine-loop preparation pipeline.
#
# Converts raw TTE clips (exported from the echo machine / PACS) into small,
# anonymised, silent H.264 loops that ship offline inside the APK.
#
#   * crop=in_w:in_h-60:0:60   -> removes the top 60 px header (hospital name,
#                                 patient name, ID, date) so no PHI is bundled
#   * scale=720:-2             -> 720 px wide, height kept even for libx264
#   * -an                      -> strips any audio track
#   * libx264 -crf 26          -> good visual quality at a small file size
#   * -movflags +faststart     -> moov atom first for instant ExoPlayer start
#
# Usage:
#   scripts/prepare_loops.sh [RAW_DIR]
#
# RAW_DIR defaults to ./raw_loops. Every input file must be named after a view
# id from echo_protocol_full.json, e.g.:
#   subcostal_4c.avi  subcostal_ivc.mov  plax_standard.mp4  psax_av.wmv
#   psax_papillary.*  apical_4c.*  apical_5c.*  apical_2c_3c.*  suprasternal_arch.*
#
# Output: app/src/main/assets/loops/<view_id>.mp4

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RAW_DIR="${1:-${PROJECT_ROOT}/raw_loops}"
OUT_DIR="${PROJECT_ROOT}/app/src/main/assets/loops"

VIEW_IDS=(
  subcostal_4c
  subcostal_ivc
  plax_standard
  psax_av
  psax_papillary
  apical_4c
  apical_5c
  apical_2c_3c
  suprasternal_arch
)

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg is not installed or not on PATH." >&2
  exit 1
fi

if [[ ! -d "${RAW_DIR}" ]]; then
  echo "ERROR: raw clip directory not found: ${RAW_DIR}" >&2
  echo "Create it and add one clip per view id (any container ffmpeg can read)." >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

converted=0
missing=()

for view_id in "${VIEW_IDS[@]}"; do
  input=""
  for candidate in "${RAW_DIR}/${view_id}".*; do
    if [[ -f "${candidate}" ]]; then
      input="${candidate}"
      break
    fi
  done

  if [[ -z "${input}" ]]; then
    missing+=("${view_id}")
    continue
  fi

  output="${OUT_DIR}/${view_id}.mp4"
  echo ">> ${view_id}: $(basename "${input}") -> ${output#"${PROJECT_ROOT}/"}"

  ffmpeg -hide_banner -loglevel error -y \
    -i "${input}" \
    -vf "crop=in_w:in_h-60:0:60,scale=720:-2" \
    -an \
    -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p \
    -movflags +faststart \
    -map_metadata -1 \
    "${output}"

  converted=$((converted + 1))
done

echo
echo "Converted ${converted}/${#VIEW_IDS[@]} loops into ${OUT_DIR#"${PROJECT_ROOT}/"}"
if (( ${#missing[@]} > 0 )); then
  echo "Missing raw clips (app will show a fallback message): ${missing[*]}"
fi
