#!/usr/bin/env bash
# Capture README screenshots from a connected Android device.
#
#   ./tools/capture-screenshots.sh            # all five, prompting between each
#   ./tools/capture-screenshots.sh walk data  # just these
#
# Requires: adb on PATH, one device attached and unlocked.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=docs/screenshots
mkdir -p "$OUT"

declare -a ORDER=(summary walk data music settings)
declare -A WHAT=(
  [summary]="Summary tab — week progress and the Start button"
  [walk]="Walk tab — MID-SESSION, ideally with the gauge in the yellow or orange band"
  [data]="Data tab — the 180° arc and the metric tiles"
  [music]="Music tab — search results and the queued track list"
  [settings]="Settings tab — the zone colour cards"
)

targets=("$@")
[ ${#targets[@]} -eq 0 ] && targets=("${ORDER[@]}")

if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Plug the phone in, unlock it, and check 'adb devices'." >&2
  exit 1
fi

for name in "${targets[@]}"; do
  echo
  echo ">>> ${WHAT[$name]:-$name}"
  read -rp "    Put that on screen, then press Enter (s to skip): " ans
  [ "$ans" = "s" ] && continue
  adb exec-out screencap -p > "$OUT/$name.png"
  bytes=$(wc -c < "$OUT/$name.png")
  if [ "$bytes" -lt 10000 ]; then
    echo "    !! only $bytes bytes — screencap failed, is the screen on?" >&2
    rm -f "$OUT/$name.png"
  else
    echo "    saved $OUT/$name.png ($((bytes/1024)) KB)"
  fi
done

echo
echo "Done. Shrink them so the repo stays small (optional, needs ImageMagick):"
echo "  for f in $OUT/*.png; do magick \"\$f\" -resize 40% -strip \"\$f\"; done"
