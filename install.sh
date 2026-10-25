#!/usr/bin/env bash
# Builds and installs the app, and makes it the screensaver and the home screen.
#   ./install.sh               the TV (192.168.178.23)
#   ./install.sh --now         ...and start it right away to have a look
#   DEVICE=emulator-5554 ./install.sh --now
set -euo pipefail
# Relative paths given by the caller are relative to where it was run from.
for v in BACKGROUND_FILE VIDEO MUSIC_DIR; do
  if [[ -n "${!v:-}" && "${!v}" != /* ]]; then export "$v=$PWD/${!v}"; fi
done
cd "$(dirname "$0")"

DEVICE=${DEVICE:-192.168.178.23}
[[ "$DEVICE" == emulator-* || "$DEVICE" == *:* ]] || DEVICE=$DEVICE:5555
ADB=${ADB:-$HOME/platform-tools/adb}
COMPONENT=tv.mcclock.dream/.ClockDream
HOME_ACTIVITY=tv.mcclock.dream/.HomeActivity
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
# The same goes for the home screen's idle mode, which holds the screen on: a key that does
# nothing else (it just brings the home screen back) counts as activity.
if grep -qE 'ResumedActivity.*tv\.mcclock\.dream/\.HomeActivity' <<<"$(adb shell dumpsys activity activities)"; then
  adb shell input keyevent KEYCODE_SHIFT_LEFT
fi
home=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | tr -d '\r' | tail -1)
if [[ "$home" == */* && "$home" != "$HOME_ACTIVITY" && "$home" != */*ResolverActivity ]]; then
  echo "$home" > ".previous/$DEVICE.home"
fi

adb install -r "$APK"
adb shell settings put secure screensaver_components "$COMPONENT"
adb shell settings put secure screensaver_enabled 1
adb shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null
echo "Screensaver: $(setting screensaver_components)"
echo "Home screen: $HOME_ACTIVITY (was: $(cat ".previous/$DEVICE.home" 2>/dev/null || echo unknown))"

if [[ "${1:-}" == --now ]]; then
  if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
    adb shell cmd dreams start-dreaming
  else
    adb shell am start -n com.android.systemui/.Somnambulator >/dev/null
  fi
  echo "Started. Press any button on the remote to leave it."
fi
