#!/usr/bin/env bash
# Puts the previous screensaver and home screen back and uninstalls this app.
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
else
  echo "No saved settings for $DEVICE: going back to the TV's default screensaver." >&2
  adb shell settings delete secure screensaver_components >/dev/null
fi
home_ok=true
if [[ -f ".previous/$DEVICE.home" ]]; then
  if adb shell cmd package set-home-activity "$(cat ".previous/$DEVICE.home")" >/dev/null; then
    echo "Home screen: $(cat ".previous/$DEVICE.home")"
  else
    home_ok=false
    echo "Could not make $(cat ".previous/$DEVICE.home") the home screen again: pick one on the TV." >&2
  fi
fi
adb uninstall tv.mcclock.dream || true
# Forget the saved settings only once everything is back, so running this again still works.
if $home_ok; then
  rm -f ".previous/$DEVICE" ".previous/$DEVICE.home"
fi
echo "Screensaver: $(adb shell settings get secure screensaver_components | tr -d '\r')"
