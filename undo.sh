#!/usr/bin/env bash
# Puts the previous screensaver settings back and uninstalls this one.
#   ./undo.sh                  the TV (192.168.178.23)
#   DEVICE=emulator-5554 ./undo.sh
set -euo pipefail
cd "$(dirname "$0")"

DEVICE=${DEVICE:-192.168.178.23}
[[ "$DEVICE" == emulator-* || "$DEVICE" == *:* ]] || DEVICE=$DEVICE:5555
ADB=${ADB:-$HOME/platform-tools/adb}
adb() { "$ADB" -s "$DEVICE" "$@"; }
restore() {  # restore <setting> <saved value>
  if [[ -z "$2" || "$2" == null ]]; then
    adb shell settings delete secure "$1" >/dev/null
  else
    adb shell settings put secure "$1" "$2"
  fi
}

[[ "$DEVICE" == *:* ]] && "$ADB" connect "$DEVICE"
if [[ -f ".previous/$DEVICE" ]]; then
  { read -r components; read -r enabled; } < ".previous/$DEVICE" || true
  restore screensaver_components "${components:-}"
  [[ -n "${enabled:-}" ]] && restore screensaver_enabled "$enabled"
  rm ".previous/$DEVICE"
else
  echo "No saved settings for $DEVICE: going back to the TV's default screensaver." >&2
  adb shell settings delete secure screensaver_components >/dev/null
fi
adb uninstall tv.mcclock.dream || true
echo "Screensaver: $(adb shell settings get secure screensaver_components | tr -d '\r')"
