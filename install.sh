#!/usr/bin/env bash
# Builds and installs the screensaver and makes it the active one.
#   ./install.sh               the TV (192.168.178.23)
#   ./install.sh --now         ...and start it right away to have a look
#   DEVICE=emulator-5554 ./install.sh --now
set -euo pipefail
cd "$(dirname "$0")"

DEVICE=${DEVICE:-192.168.178.23}
[[ "$DEVICE" == emulator-* || "$DEVICE" == *:* ]] || DEVICE=$DEVICE:5555
ADB=${ADB:-$HOME/platform-tools/adb}
COMPONENT=tv.mcclock.dream/.ClockDream
APK=MinecraftClock.apk
adb() { "$ADB" -s "$DEVICE" "$@"; }
setting() { adb shell settings get secure "$1" | tr -d '\r'; }

./build.sh
[[ "$DEVICE" == *:* ]] && "$ADB" connect "$DEVICE"
if [[ "$(adb get-state 2>/dev/null)" != device ]]; then
  echo "Can't reach $DEVICE. If the TV shows a USB debugging prompt, accept it and run this again." >&2
  exit 1
fi

# Remember the screensaver settings as they were, so undo.sh can put them back.
mkdir -p .previous
if [[ "$(setting screensaver_components)" != "$COMPONENT" ]]; then
  printf '%s\n%s\n' "$(setting screensaver_components)" "$(setting screensaver_enabled)" \
    > ".previous/$DEVICE"
fi

# Replacing the app ends a running screensaver, and with no remote press for a while the TV
# would take that as a timeout and go to standby. Waking it first counts as activity.
if grep -qE 'mCurrentDream(Name)?=.*tv\.mcclock\.dream' <<<"$(adb shell dumpsys dreams)"; then
  adb shell input keyevent KEYCODE_WAKEUP
fi
adb install -r "$APK"
adb shell settings put secure screensaver_components "$COMPONENT"
adb shell settings put secure screensaver_enabled 1
echo "Screensaver: $(setting screensaver_components)"

if [[ "${1:-}" == --now ]]; then
  if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
    adb shell cmd dreams start-dreaming
  else
    adb shell am start -n com.android.systemui/.Somnambulator >/dev/null
  fi
  echo "Started. Press any button on the remote to leave it."
fi
