#!/usr/bin/env bash
# Builds MinecraftClock.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   ./build.sh                          uses ~/screensaver.webm
#   VIDEO=/path/to/loop.webm ./build.sh another video
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0
MIN_SDK=24          # the target TV runs Android 9 (API 28)
TARGET_SDK=34
VIDEO=${VIDEO:-$HOME/screensaver.webm}
APK=MinecraftClock.apk

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$ANDROID_HOME/build-tools/35.0.0
PLATFORM=$ANDROID_HOME/platforms/android-35/android.jar
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(mise where java@21.0.2 2>/dev/null || true)
fi
[[ -x "${JAVA_HOME:-}/bin/javac" ]] || { echo "JDK not found: set JAVA_HOME" >&2; exit 1; }
[[ -f "$PLATFORM" && -x "$BT/aapt2" ]] || { echo "Android SDK not found at $ANDROID_HOME" >&2; exit 1; }
[[ -f "$VIDEO" ]] || { echo "Video not found: $VIDEO" >&2; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

echo "[1/6] resources"
[[ -d assets ]] && cp -r assets/. "$OUT/assets/"
cp "$VIDEO" "$OUT/assets/screensaver.webm"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
# The video stays uncompressed so it can be read straight out of the APK.
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest AndroidManifest.xml -A "$OUT/assets" \
  -0 webm --java "$OUT/gen" --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME "$OUT/res.zip"

echo "[2/6] javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/6] d8"
"$BT/d8" --release --min-api $MIN_SDK --lib "$PLATFORM" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[4/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -P 16 -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/6] sign"
KS=keystore/minecraft-clock.jks
KS_PASS_FILE=keystore/password.txt
if [[ ! -f "$KS" ]]; then
  # One key for every future build, so updates install over the old version. Keep it.
  mkdir -p keystore
  head -c 18 /dev/urandom | base64 | tr -d '/+=' > "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias minecraft-clock -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$KS_PASS_FILE")" -keypass "$(cat "$KS_PASS_FILE")" \
    -dname "CN=Minecraft Clock personal build" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias minecraft-clock --ks-pass "file:$KS_PASS_FILE" \
  --out "$APK" "$OUT/aligned.apk"

echo "[6/6] verify"
"$BT/apksigner" verify --min-sdk-version $MIN_SDK "$APK"
unzip -v "$APK" | grep -E 'screensaver.webm'
ls -lh "$APK"
