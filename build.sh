#!/usr/bin/env bash
# Builds MinecraftClock.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   ./build.sh                                    background: ~/screensaver.webm
#   BACKGROUND_FILE=/path/to/loop.webm ./build.sh   another video (webm, mp4, mkv)
#   BACKGROUND_FILE=/path/to/picture.png ./build.sh a still image (png, jpg, webp)
# VIDEO=... works too, as another name for BACKGROUND_FILE.
# The music played on the screensaver and home screen comes from ./soundtrack (mp3, ogg, m4a);
#   MUSIC_DIR=/other/folder ./build.sh   from elsewhere,  MUSIC_DIR= ./build.sh   none.
set -euo pipefail
# Relative paths given by the caller are relative to where it was run from.
for v in BACKGROUND_FILE VIDEO MUSIC_DIR; do
  if [[ -n "${!v:-}" && "${!v}" != /* ]]; then export "$v=$PWD/${!v}"; fi
done
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0
MIN_SDK=24          # the target TV runs Android 9 (API 28)
TARGET_SDK=34
BACKGROUND_FILE=${BACKGROUND_FILE:-${VIDEO:-$HOME/screensaver.webm}}
MUSIC_DIR=${MUSIC_DIR-soundtrack}
APK=MinecraftClock.apk

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$ANDROID_HOME/build-tools/35.0.0
PLATFORM=$ANDROID_HOME/platforms/android-35/android.jar
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(mise where java@21.0.2 2>/dev/null || true)
fi
[[ -x "${JAVA_HOME:-}/bin/javac" ]] || { echo "JDK not found: set JAVA_HOME" >&2; exit 1; }
[[ -f "$PLATFORM" && -x "$BT/aapt2" ]] || { echo "Android SDK not found at $ANDROID_HOME" >&2; exit 1; }
[[ -f "$BACKGROUND_FILE" ]] || { echo "Background not found: $BACKGROUND_FILE" >&2; exit 1; }
if [[ -n "$MUSIC_DIR" && ! -d "$MUSIC_DIR" ]]; then
  if [[ "$MUSIC_DIR" == soundtrack ]]; then
    echo "No soundtrack/ folder: building without music" >&2
    MUSIC_DIR=
  else
    echo "Music folder not found: $MUSIC_DIR (MUSIC_DIR= builds without music)" >&2
    exit 1
  fi
fi
# The file's type picks what draws it (src/tv/mcclock/dream/background/Backgrounds.java).
EXT=$(tr '[:upper:]' '[:lower:]' <<<"${BACKGROUND_FILE##*.}")
case "$EXT" in
  webm|mp4|mkv|png|jpg|jpeg|webp) ;;
  *) echo "Unsupported background type .$EXT: use webm, mp4, mkv, png, jpg or webp" >&2; exit 1 ;;
esac
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

echo "[1/6] resources"
[[ -d assets ]] && cp -r assets/. "$OUT/assets/"
rm -f "$OUT"/assets/background.*
cp "$BACKGROUND_FILE" "$OUT/assets/background.$EXT"
# A video's own sound would only be muted, and it would keep the music from starting (Music
# doesn't play over other audio): take it out.
if [[ "$EXT" == webm || "$EXT" == mp4 || "$EXT" == mkv ]] && command -v ffprobe >/dev/null \
    && [[ -n "$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$BACKGROUND_FILE")" ]]; then
  if command -v ffmpeg >/dev/null; then
    ffmpeg -v error -y -i "$BACKGROUND_FILE" -map 0:v -c copy "$OUT/assets/background.$EXT"
    echo "      background: its sound track left out"
  else
    echo "warning: the background video has sound, which keeps the music from playing; install ffmpeg to leave it out" >&2
  fi
fi
# Tracks go in as assets/music/01.mp3, 02.mp3, ... (in file name order).
rm -rf "$OUT/assets/music"
if [[ -n "$MUSIC_DIR" && -d "$MUSIC_DIR" ]]; then
  mkdir -p "$OUT/assets/music"
  n=0
  while IFS= read -r -d '' f; do
    n=$((n + 1))
    ext=$(tr '[:upper:]' '[:lower:]' <<<"${f##*.}")
    cp "$f" "$OUT/assets/music/$(printf '%02d' "$n").$ext"
  done < <(find "$MUSIC_DIR" -maxdepth 1 -type f \( -iname '*.mp3' -o -iname '*.ogg' -o -iname '*.m4a' \) -print0 | sort -z)
  echo "      $n music tracks from $MUSIC_DIR"
fi
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
# Videos and music stay uncompressed so they can be read straight out of the APK.
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest AndroidManifest.xml -A "$OUT/assets" \
  -0 webm -0 mkv -0 mp4 -0 mp3 -0 ogg -0 m4a --java "$OUT/gen" --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
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
unzip -v "$APK" | grep -E 'assets/background\.'
echo "music tracks: $(unzip -l "$APK" | grep -c 'assets/music/')"
ls -lh "$APK"
