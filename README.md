# Minecraft Clock screensaver and home screen

An Android TV screensaver (a `DreamService`) and home screen for the Sony BRAVIA.
It plays `~/screensaver.webm` on a seamless loop, with the time centred on top in
Minecraft's font and Minecraft's text shadow. The home screen shows the same picture with the
clock lifted and the apps below.

```sh
./install.sh          # build, install on the TV, make it the screensaver and the home screen
./install.sh --now    # ...and start the screensaver straight away
./undo.sh             # put the previous screensaver and home screen back, and uninstall
BACKGROUND_FILE=~/other.webm ./install.sh     # another video (webm, mp4, mkv)
BACKGROUND_FILE=~/picture.png ./install.sh    # a still image (png, jpg, webp)
DEVICE=emulator-5554 ./install.sh  # the local Android TV emulator instead
```

The TV starts the screensaver after 5 minutes without a remote press, or from
Settings > Device Preferences > Screen saver > Start now. Any button leaves it; HOME goes to
the home screen. On the home screen the same delay without a key slides the apps away and the
clock to the middle (its own screensaver); any button brings them back. The apps on the home
screen are listed in `assets/home_apps.txt`.

## Design rule: the background is swappable

The clock, the home screen and all the transitions are kept separate from whatever draws the
background. They only use the `Background` interface (`src/tv/mcclock/dream/background/`):
each window gets a view from it, asks for the picture with `show()`, and is told when it's on
screen. The looping video (`VideoBackground`) and a still image (`ImageBackground`) are two
implementations, picked by the type of the file `build.sh` bundles; another renderer is one more
class there. The screensaver hands its running background over to the home screen, so the
switch between them has no visible cut.

Android 9 puts every activity under a screensaver to sleep, which normally takes a SurfaceView's
surface away. The video's SurfaceView keeps it (`KeptSurfaceView`), so the home screen can take
the picture while the screensaver is still on top.

## How it works

- **Video** (`LoopingVideo`): decoded with MediaCodec straight into a SurfaceView, so the
  TV's hardware VP9 decoder and video plane do the work (4K60 decodes in real time). At
  the end of the file the extractor jumps back to the first frame without ever telling the
  decoder the stream ended, so the loop has no gap. Each frame is aimed at one display
  refresh (`Vsync`), and once 60 fps content is seen on a 60 Hz screen the two are locked:
  one frame per refresh, nothing dropped or repeated. If MediaCodec fails twice it falls
  back to MediaPlayer's own looping.
- **Clock** (`PixelClockView`): Minecraft's 5 x 7 digits and 1-pixel colon drawn as
  square blocks (18 px each at 1080p), in white with the #3F3F3F shadow one block right and
  down, as in the game. It follows the TV's 24-hour setting. Each minute it moves by half a
  block (9 px) through a small pattern, so the same pixels aren't lit all night (OLED care).
- `adb logcat -s McClock` prints a line a minute: frames sent and shown per second, late
  frames, and loops; and each step of a screensaver-to-home hand-over.

The glyph shapes follow [Monocraft](https://github.com/IdreesInc/Monocraft) (SIL OFL 1.1),
a recreation of Minecraft's font. Keep `keystore/`: updates must be signed with the same key.
