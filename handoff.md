# Handoff: Minecraft Clock TV

Read this first when picking the project up in a fresh session. Keep it current: update it
after every meaningful step (what changed, what was verified, what's next).

_Last updated: 2026-10-25 19:45_

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
  HW2..HW5 are HDMI 1, 2, 3/ARC, 4 and all report INPUT_STATE_CONNECTED (0) whatever is plugged
  in; no HDMI-CEC devices are registered. (AOSP: a port without hotplug reports STANDBY, which
  means "unknown", so the menu calls that "Kein Signal", not "standby".)
- Sony settings pages that open directly (all side panels, exported): network
  (ACTION_WIFI_SETTINGS), apps (ACTION_MANAGE_APPLICATIONS_SETTINGS), date (ACTION_DATE_SETTINGS),
  `com.sony.dtv.settings.power.EcoActivity`, `...externalinputs.ExternalInputsActivity` (rename or
  hide inputs), `...about.AboutActivity`. ACTION_SOUND_SETTINGS only has "Systemtöne"; the
  speaker choice (TV or audio system) is `com.sony.dtv.settings.intent.action.SPEAKERS_SETTINGS`.
  ACTION_DEVICE_INFO_SETTINGS and ACTION_APPLICATION_SETTINGS open the "open with" chooser.
- `screencap -p` on the TV captures the video too (but not Sony's TV player: protected).
- Opening an HDMI input starts Sony's `com.sony.dtv.tvx/.MainActivity`; BACK stays there.

## Home screen plan (in progress)

Same APK and process as the dream, so both can share one decoder.

- `HomeActivity` (HOME launcher, singleTask): SurfaceView video, black cover, a bottom
  gradient, `PixelClockView`, and an apps panel: two rows of app banners (home_apps.txt order:
  Netflix, YouTube, Disney+, Prime Video, Spotify / NBA, TCL Browser, ProtonVPN, HiAnime,
  123Movies) and a row of Minecraft-style buttons (Play Store, Quelle, Einstellungen). HDMI
  opening logic is ported from tv-detox (MIT).
- Quelle and Einstellungen open `McMenu`, an overlay in the same window drawn like Minecraft
  1.21.11's multiplayer server list (numbers measured from the game; see the comments).
  `HomeActivity.arrange()` places clock, apps, shade and menu for (home | idle) x (menu open).
  Menus close on BACK/Zurück, HOME, idle mode and the screensaver hand-over; opening an entry
  leaves the menu open (BACK from a settings side panel returns to it).
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

## Current state (03:30)

Requirement 4 is done: package `tv.mcclock.dream.background` (public `Background` + `Backgrounds`;
`VideoBackground`, `ImageBackground`, `LoopingVideo`, `Vsync`). The background is picked by the file
`build.sh` bundles: `BACKGROUND_FILE=... ./build.sh` (not `BACKGROUND`: that name is set in the
user's shell to a colour). Contract: `show(host)` (last caller gets the picture; always answered by
`onBackgroundShown`), `hide(host)`, `suspend()/resume()`. Video and UI are separate layers: the video
is a SurfaceView on the TV's hardware video plane; slides only move views in the window layer.

Verified on the TV (this branch is installed there as home screen + screensaver):
- Home screen: banners in apps.txt order, Play Store, HDMI 1-4, Einstellungen; 4K VP9 on the MTK
  decoder locked to 60 Hz.
- HOME during the screensaver over an app: seamless hand-over, no black frame (recorded), dream gone
  ~1 s after HOME, then the slide; 60.0 fps / 0 late in the next stats minute.
  How: Android 9 puts activities under a screensaver to sleep (resume, pause ~15 ms later, stop
  "sleeping"); `KeptSurfaceView` overrides the hidden `SurfaceView.windowStopped()` as a no-op so
  the home keeps its video surface; the video moves there under the dream; then
  `ClockDream.handOver()` hides the dream window, waits 3 frames and finishes (taking the window down
  while visible flashed black: its SurfaceView's black backdrop layer).
- The TV has animator_duration_scale=0 (also transition/window = 0): ViewPropertyAnimator jumps.
  All animations use `Anim` (Choreographer-driven) instead. Idle -> ambient -> key slides animate.
- Settings button: Sony settings is a translucent side panel; the video keeps playing behind it
  (no suspend for it) and BACK continues.
- Idle mode holds its own SCREEN_BRIGHT wake lock (FLAG_KEEP_SCREEN_ON's lock pokes user activity
  when released) and honours `sleep_timeout` (the TV has -1 = never). Tested on API 28 emulator.
- TV screenrecord: only `--size 960x540 --bit-rate 4000000` gives decodable (~10 fps) video.
- Test helpers (outside the repo): ~/.cache/mcclock-tv-test/{tv-handover.sh,handover.sh}.
  Android 9 TV emulator AVD `mcclock_tv28` (ANDROID_AVD_HOME=~/.config/.android/avd; stock
  launcher disabled there). Hyprland rule puts emulator windows on workspace 5.

## Menus and two rows (04:45)

Done on branch home-screen (uncommitted until the user says so): two rows of apps, Quelle and
Einstellungen menus, McButton in the 1.21 colours (white label, no yellow on focus), strings in
res/values + res/values-de, focus stops at the ends of each row (Android would jump diagonally
from the last button onto a banner). HomeActivity's configChanges now includes layoutDirection:
without it a language change relaunched the activity (Android changes both together), so
onConfigurationChanged relabels in place; not run on a device (the emulator only has English).
Reviewed by a two-lens review workflow with verifiers; all confirmed findings fixed.
Verified on the TV: both rows fit with the clock and buttons; Quelle lists HDMI 1-4 ("Bereit");
HDMI 4 opens Sony's TV app and HOME comes back with the menu closed and the video restarted;
Ton opens Sony's speaker choice as a side panel over the menu and BACK returns to it; RIGHT on
the last button stays; with a menu open the video stays at 60.0 fps sent and shown, 0 late.
Verified on the API 28 emulator: empty input
list, scrolling, BACK, idle mode from a menu. Another session (hianime-tv) asked for HiAnime and
123Movies on the home screen; 123Movies currently ships HiAnime's banner (their side).

## Next steps

1. A final review (3 lenses + verifiers) found 7 small issues; all fixed (re-armed hand-over timer
   once ready, stopped home lets go of the decoder, MediaPlayer fallback retries, stale first-frame
   check, dead decode thread detected, exact sleep time, undo.sh activity poke). Regression on the
   TV passed (2x HOME hand-over, key in the screensaver).
2. Open question for the user: the TV's animation scales are 0 (tv-detox?); the app no longer
   depends on them.
3. `soundtrack/` (2026-10-25): 24 MP3s of C418's Minecraft Volume Alpha, numbered 01-24
   (~91 MB), downloaded with yt-dlp from playlist PLefKpFQ8Pvy5aCLAGHD8Zmzsdljos-t2l (items
   1-25, minus a duplicate "Dry Hands"). Copyrighted: in .gitignore, never commit or push it.
   build.sh bundles it uncompressed as assets/music/01.mp3.. (`MUSIC_DIR`, empty = none).
   `Music` (process singleton) plays it in random order with 1-30 s gaps (the game's menu
   music) while ClockDream or HomeActivity holds it: hand-over keeps the same track; starting
   an app/input fades it out; a key in the screensaver stops it; only plays if
   !isMusicActive() and audio focus is granted; keeps the focus between tracks (waits out
   transient losses such as voice search). Verified on the TV (19:15-19:40): screensaver over an
   app + key (music started 3.4 s in, stopped, focus released); + HOME (same track carried on
   through the hand-over); home + Sony side panel + screensaver + key (carried on); app from
   the home button (stopped), HOME (new track). build.sh strips a background video's sound.
4. Black home screen found while testing (1 in 4 runs): after the home slept under the
   screensaver and was woken by a key, an app + HOME left it black. KeptSurfaceView ignored
   every windowStopped, so a normal stop relied on the window-visibility cue, which sometimes
   never came: the SurfaceView kept a dead surface, MediaCodec.configure failed twice
   (nativeWindowConnect -19), the MediaPlayer fallback threw and nothing retried. Fixed:
   Background.Host.keepSurfaceWhileStopped() (home: Handover.dreamShowing()); any other stop
   hides the view (surface released at once); a decoder that fails before its first frame gets
   a new surface; a failed fallback retries after 5 s. Verified on the TV: A-D sequence twice
   with 0 failures, hand-over still seamless. Known side effect: a screensaver that starts over
   the home itself (side panel open) comes before Handover knows of it, so the home lets its
   surface go; HOME in that screensaver then fades in from black instead of sliding seamlessly.
   TV logging: the main buffer rolls over within ~90 s, stream `logcat -s McClock` to a file.
   In Claude's bash, `grep` is a wrapper that can return nothing: use `command grep`.
