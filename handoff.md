# Handoff: Minecraft Clock TV

Read this first when picking the project up in a fresh session. Keep it current: update it
after every meaningful step (what changed, what was verified, what's next).

_Last updated: 2026-10-25 01:40_

## Goal

A Sony BRAVIA Android TV (192.168.178.23) shows `~/screensaver.webm` (a Minecraft scene)
looping seamlessly, with the time in Minecraft's font on top.

1. **Screensaver (done, shipped):** video loop + clock centred.
2. **Home screen (in progress):** same video; the clock a bit higher and the apps below.
   - Home -> screensaver: the clock slides down to the centre, the apps slide down off screen.
   - Screensaver -> home (any key, or HOME): the clock slides up, the apps slide up from below.
   - If the screensaver started from the home screen, any key goes back to the home screen.
3. All code goes to GitHub: `JonathanLauener/TV_Screensaver` (empty at first push).
4. **Requirement (from the user): the UI must be decoupled from the background renderer.**
   The clock, home screen, app rows, idle mode and all transitions must not depend on what
   draws the background. The background has to be swappable: another video, a still image,
   or a different renderer later, without touching the UI code. Design it as a background
   interface (e.g. `Background` with attach(surface or view)/detach, first-frame callback),
   with the current looping video (`VideoEngine` + `LoopingVideo`) as one implementation,
   picked by a setting or a build option. The screensaver -> home hand-over works by
   handing the *background* over, not "the video".

## Devices and access

- TV: `~/platform-tools/adb -s 192.168.178.23:5555` (network ADB, already authorised).
  Sony BRAVIA 2017 (`BRAVIA_ATV2`), Android 9 / API 28, MediaTek MT5891, 32-bit ARM,
  UI 1920x1080 @ density 320, panel 4K, 60 Hz. German system language, 24-hour time.
  Hardware decoder `OMX.MTK.VIDEO.DECODER.VP9` handles the 4K60 VP9 file.
- Current home screen before this project: tv-detox's `com.ansub.tvhome/.MainActivity`
  (from ~/tv-detox; the stock launcher is disabled). Its app order: ~/tv-detox/apps.txt.
- Emulator: `emulator-5554` (Android TV, API 36, x86_64, no root). It can't decode 4K VP9 at
  speed; use it only for functional checks. `cmd dreams` needs root there, so trigger the
  dream by temporarily setting `stay_on_while_plugged_in 0`, `screensaver_activate_on_sleep 1`,
  `screen_off_timeout 15000`, then restore 1 / 0 / 2147483647. Press HOME first:
  the HiAnime app (`tv.hianime.wrapper`) in the foreground wakes dreams.
- GitHub: deploy key `~/.ssh/github_minecraft_clock_tv`, SSH alias `github-mcclock`
  (in ~/.ssh/config). Remote: `git@github-mcclock:JonathanLauener/TV_Screensaver.git`.
  Commit as the global git identity; end messages with the Co-Authored-By line.

## Project layout (`~/minecraft-clock-tv`)

- `build.sh`: Gradle-free build (aapt2, javac, d8, zipalign, apksigner) with
  ~/Android/Sdk build-tools 35 and `mise` java 21. Bundles `$VIDEO` (default
  ~/screensaver.webm) uncompressed into the APK.
- `install.sh [--now]`: always rebuilds, saves the old screensaver settings to
  `.previous/<device>`, installs, sets `screensaver_components`. Wakes the TV out of a
  running dream first (otherwise replacing the APK kills the dream and the TV goes to
  standby because the idle timeout has passed).
- `undo.sh`: restores the saved settings and uninstalls.
- `src/tv/mcclock/dream/`
  - `ClockDream`: the DreamService (SurfaceView video + black cover until first frame +
    clock). MediaCodec player, restarted once on error, then MediaPlayer fallback.
  - `LoopingVideo`: MediaCodec loop that never sends end of stream (seeks to 0 and keeps
    timestamps rising). Frame pacing locked to the display: after 30 frames, if fps matches
    the refresh rate, each frame goes on the next refresh (`closest(prev + period)`) and is
    stamped half a refresh early. Thread at URGENT_DISPLAY priority, 3 s stall watchdog.
    Logs a stats line every minute (`adb logcat -s McClock`).
  - `Vsync`: Choreographer-sampled refresh grid, smoothed (1/8 step per 100 ms sample).
  - `PixelClockView`: Minecraft 5x7 digits + 1-px colon (shapes from Monocraft, OFL) drawn
    as k x k squares, k = 18 px at 1080p, #3F3F3F shadow one block right and down, Latin
    digits always, 24 h per system setting, burn-in drift of half a block per minute.
- Not in git: `keystore/` (signing key, keep it or updates won't install over the old
  version), `.previous/`, `build/`, APKs, and the video.

## Verified facts (don't re-derive)

- Steady state on the TV: 60.0 fps sent and shown, 0 late, 0 resyncs; SurfaceFlinger
  `--latency` shows 0 skipped refreshes. The first minute after an install is noisy
  (Play Protect scan + dex2oat), so judge from the second stats minute.
- Raw webm timestamps are whole milliseconds (16/17 ms), which caused ~18% judder before the
  display lock. KEY_OPERATING_RATE/PRIORITY made no measurable difference (tested).
- The video layer is HWC "Device" composited (hardware video plane, GRALLOC_USAGE_PATH_VDP0).
  `onFrameRendered` render times are fake on this TV; only the callback count is usable.
- The Craftron "Minecraft.ttf" is NOT on a uniform pixel grid (half-pixel features), which
  is why the glyphs are drawn as bitmaps now.
- Android 9 `DreamService.onWakeUp()` is public and gives up to 5 s for an exit transition
  before `finish()`. HOME during a dream: PhoneWindowManager calls awakenDreams()
  (onWakeUp via the system, not via a key event) and then starts the home activity.
- `pm set-home-activity <component>` exists on the TV (Android 9).
- TV HDMI inputs: Sony `com.sony.dtv.tvinput.external/.ExternalTvInputService/HW2..HW8`.

## Home screen plan (in progress)

Same APK and process as the dream, so both can share one decoder.

- `HomeActivity` (HOME launcher, singleTask): SurfaceView video, black cover, a bottom
  gradient, `PixelClockView`, and an apps panel: a row of app banners (apps.txt order:
  Netflix, YouTube, Disney+, Prime Video, Spotify, NBA, TCL Browser, ProtonVPN) and a row of
  Minecraft-style buttons (Play Store, HDMI inputs, Settings). HDMI opening logic is ported
  from tv-detox (MIT).
- Ambient mode lives inside HomeActivity: while it's in front it holds KEEP_SCREEN_ON (so
  the system dream never starts over it) and after `screen_off_timeout` without a key it
  animates to ambient. Any key (volume excepted) or HOME animates back. Only when our dream
  is the selected screensaver; otherwise it leaves dreams to the system.
- `VideoEngine` (process singleton) owns one LoopingVideo and switches its output with
  `MediaCodec.setOutputSurface`, so the dream can hand the running video to HomeActivity.
- Dream over another app + HOME: HomeActivity starts in ambient layout under the dream,
  takes the video, and on its first frame tells the dream to `finish()` (no exit
  animation), then slides into the home layout. A key press in the dream (not HOME)
  finishes it at once, back to the app.
- Make it the default home: `cmd package set-home-activity tv.mcclock.dream/.HomeActivity`.
  The undo restores `com.ansub.tvhome/.MainActivity`.

## Current state (01:35)

Written and compiling (`./build.sh` OK) but NOT tested; committed on branch `home-screen`
(`main` = the shipped screensaver):
`Glyphs.java` (151 glyphs generated from Monocraft), `PixelText.java`, `VideoEngine.java`,
`Handover.java`, `HomeActivity.java`, `AppTile.java`, `McButton.java`, the new
`ClockDream.java` (uses VideoEngine, onWakeUp hand-over, no exit animation), surface
switching in `LoopingVideo.setSurface()`, manifest (HOME activity + `<queries>`),
`res/values/styles.xml` (no-animation theme), `assets/home_apps.txt`, and install/undo
setting/restoring the home activity (`.previous/<device>.home`).
The TV still runs the shipped screensaver-only build (commit 7dc3eb2); its home is still
tv-detox. The emulator stopped responding during the first install ("waiting for device").

## Next steps

1. Refactor to the background interface (requirement 4) before going further: VideoEngine
   becomes the video `Background`; HomeActivity/ClockDream only talk to the interface.
2. Test on the emulator (restart it if needed), then the TV: home layout, idle -> ambient
   after `screen_off_timeout`, any key -> home, HOME from the screensaver over another app
   (record with `adb shell screenrecord` to check the hand-over has no black or app flash).
   Check that `MediaCodec.setOutputSurface` works on the MTK decoder.
3. Review workflow, fix, commit and push, update this file.
