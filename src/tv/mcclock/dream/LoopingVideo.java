package tv.mcclock.dream;

import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a video file to a Surface forever without a gap at the loop point.
 *
 * MediaPlayer's looping seeks and flushes the decoder at the end of the file, which shows up as
 * a short freeze. Here the decoder never sees the end of the stream: when the extractor runs
 * out, it jumps back to the first frame (a key frame) and the timestamps keep counting up, so
 * the decoder just sees one endless video.
 *
 * Each frame is aimed at one display refresh. When the video's frame rate matches the display
 * (60 fps on 60 Hz), it is locked to it: one frame per refresh, so no frame is ever dropped or
 * shown twice, however long it runs.
 */
final class LoopingVideo implements Runnable {

    interface Listener {
        /**
         * Called on the main thread once the first frame has been sent to the screen, and again
         * after each {@link #setSurface} once the first frame has gone to the new surface.
         */
        void onFirstFrame(LoopingVideo source);

        /** Called on the main thread if decoding fails; the player has stopped. */
        void onVideoError(LoopingVideo source, Exception error);
    }

    private static final String TAG = ClockDream.TAG;
    private static final long TIMEOUT_US = 10_000;
    /** Frames go to the compositor this long before the refresh they are meant for. */
    private static final long RELEASE_AHEAD_NS = 30_000_000;
    /** Further behind than this (the system stalled), restart the clock instead of rushing. */
    private static final long RESYNC_NS = 100_000_000;
    /** Frames to watch before deciding whether the frame rate matches the display. */
    private static final int MEASURE_FRAMES = 30;
    /** No frame from the decoder for this long counts as a failure. */
    private static final long STALL_NS = 3_000_000_000L;
    private static final long STATS_EVERY_MS = 60_000;

    private final AssetFileDescriptor source;
    private volatile Surface surface;      // where frames go now
    private volatile Surface switchTo;     // set by setSurface(), applied by the decode thread
    private volatile CountDownLatch switched;
    private volatile boolean switchOk;
    private final Listener listener;
    private final Vsync vsync;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger rendered = new AtomicInteger();
    private volatile boolean running;
    private Thread thread;

    LoopingVideo(AssetFileDescriptor source, Surface surface, float refreshRate, Listener listener) {
        this.source = source;
        this.surface = surface;
        this.listener = listener;
        this.vsync = new Vsync(refreshRate);
    }

    /** Main thread. */
    void start() {
        running = true;
        vsync.start();
        thread = new Thread(this, "McClock-video");
        thread.start();
    }

    Surface surface() {
        return surface;
    }

    /**
     * Main thread. Moves the picture to another surface without restarting, so it carries on
     * from the same frame. Returns false if the decoder couldn't switch in time; it is then
     * still drawing to the old surface.
     */
    boolean setSurface(Surface target, long timeoutMs) {
        if (target == surface) return true;
        CountDownLatch done = new CountDownLatch(1);
        switched = done;
        switchTo = target;
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                switchTo = null;
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return switchOk;
    }

    /** Main thread. Stops decoding and waits until the decoder has let go of the surface. */
    void stop() {
        running = false;
        vsync.stop();
        main.removeCallbacksAndMessages(null);
        Thread t = thread;
        thread = null;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        // Like ExoPlayer's playback thread: a late wake-up here is a visible hitch.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot raise video thread priority", e);
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Exception failure = null;
        try {
            try {
                extractor.setDataSource(source.getFileDescriptor(), source.getStartOffset(),
                        source.getLength());
            } finally {
                source.close();
            }
            MediaFormat format = selectVideoTrack(extractor);
            codec = createDecoder(format);
            codec.setOnFrameRenderedListener((c, mediaUs, systemNs) -> rendered.incrementAndGet(),
                    main);
            codec.configure(format, surface, null, 0);
            codec.start();
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            decode(extractor, codec);
        } catch (InterruptedException e) {
            // stop() was called
        } catch (Exception e) {
            failure = e;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
        // Only now that the decoder has let go of the surface can something else use it.
        if (failure != null && running) {
            running = false;
            Exception error = failure;
            main.post(() -> listener.onVideoError(this, error));
        }
    }

    private static MediaFormat selectVideoTrack(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                extractor.selectTrack(i);
                if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    // Same worst case ExoPlayer assumes for VP9: 4:1 compression of a 4:2:0 frame.
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 8);
                }
                return format;
            }
        }
        throw new IOException("no video track");
    }

    private static MediaCodec createDecoder(MediaFormat format) throws IOException {
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
        MediaCodec codec = name != null
                ? MediaCodec.createByCodecName(name)
                : MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        Log.i(TAG, "decoder " + codec.getName() + " for " + format.getString(MediaFormat.KEY_MIME)
                + " " + format.getInteger(MediaFormat.KEY_WIDTH)
                + "x" + format.getInteger(MediaFormat.KEY_HEIGHT));
        return codec;
    }

    private void decode(MediaExtractor extractor, MediaCodec codec)
            throws IOException, InterruptedException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long periodNs = vsync.periodNs;

        // Reading: timestamps keep rising across loops.
        long firstFileUs = -1;  // timestamp of the file's first frame
        long lastFileUs = 0;    // latest timestamp read in this pass through the file
        int samples = 0;        // frames read in this pass
        long loopOffsetUs = 0;  // added to file timestamps on each pass
        int loops = 0;

        // Showing: which refresh each frame goes on.
        long baseNs = -1;       // refresh the base frame went on
        long basePtsUs = 0;     // and its timestamp
        int sinceBase = 0;      // frames shown since the base frame
        long cadenceNs = 0;     // once locked to the display: time between frames, else 0
        long lastShowNs = 0;
        long lastOutputNs = System.nanoTime();
        boolean first = true;

        // Stats, logged every minute.
        int late = 0, resyncs = 0, shown = 0;
        long minHeadroomNs = Long.MAX_VALUE;
        long statsSinceMs = SystemClock.elapsedRealtime();

        while (running) {
            Surface target = switchTo;
            if (target != null) {
                switchTo = null;
                try {
                    codec.setOutputSurface(target);
                    codec.setVideoScalingMode(
                            MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    surface = target;
                    switchOk = true;
                    first = true;  // announce the first frame on the new surface
                } catch (RuntimeException e) {
                    Log.w(TAG, "cannot move the video to another surface", e);
                    switchOk = false;
                }
                switched.countDown();
            }

            // Keep the decoder fed with everything it will take.
            int in;
            while ((in = codec.dequeueInputBuffer(0)) >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(in);
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    if (samples < 2) throw new IOException("video too short to loop");
                    long frameUs = (lastFileUs - firstFileUs) / (samples - 1);
                    loopOffsetUs += lastFileUs - firstFileUs + frameUs;
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    samples = 0;
                    loops++;
                    size = extractor.readSampleData(buffer, 0);
                    if (size < 0) throw new IOException("cannot rewind video");
                }
                long fileUs = extractor.getSampleTime();
                if (firstFileUs < 0) firstFileUs = fileUs;
                lastFileUs = samples == 0 ? fileUs : Math.max(lastFileUs, fileUs);
                samples++;
                codec.queueInputBuffer(in, 0, size, fileUs - firstFileUs + loopOffsetUs, 0);
                extractor.advance();
            }

            int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (out < 0) {  // try again later, or a format change we don't need
                if (System.nanoTime() - lastOutputNs > STALL_NS) {
                    throw new IOException("decoder stalled");
                }
                continue;
            }

            long ptsUs = info.presentationTimeUs;
            long now = System.nanoTime();
            lastOutputNs = now;
            long showNs;
            if (baseNs < 0) {
                baseNs = showNs = vsync.closest(now + RELEASE_AHEAD_NS + periodNs);
                basePtsUs = ptsUs;
                sinceBase = 0;
            } else if (cadenceNs > 0) {
                // Locked: the next refresh slot after the previous frame's. Snapping each step
                // to the real refreshes means the error never builds up.
                showNs = vsync.closest(lastShowNs + cadenceNs);
            } else {
                // Timestamp-based, snapped to a refresh. The file's timestamps are rounded to
                // whole milliseconds (16 or 17 ms apart at 60 fps), so unsnapped, two frames
                // would sometimes share a refresh and one would be dropped.
                showNs = vsync.closest(baseNs + (ptsUs - basePtsUs) * 1000);
                if (sinceBase == MEASURE_FRAMES && vsync.known()) {
                    long frameNs = (ptsUs - basePtsUs) * 1000 / sinceBase;
                    long refreshes = Math.round((double) frameNs / periodNs);
                    if (refreshes >= 1
                            && Math.abs(frameNs - refreshes * periodNs) < refreshes * periodNs / 100) {
                        cadenceNs = refreshes * periodNs;
                        Log.i(TAG, String.format(Locale.ROOT,
                                "%.2f fps video locked to %.2f Hz display, %d refresh(es) a frame",
                                1e9 / frameNs, 1e9 / periodNs, refreshes));
                    }
                }
            }

            long waitNs = showNs - RELEASE_AHEAD_NS - now;
            if (waitNs < minHeadroomNs) minHeadroomNs = waitNs;
            if (waitNs > 0 && waitNs < RESYNC_NS) {
                Thread.sleep(waitNs / 1_000_000, (int) (waitNs % 1_000_000));
            } else if (Math.abs(waitNs) > RESYNC_NS) {
                // Far behind (the system stalled) or far ahead (a timestamp jump): start the
                // clock again from this frame rather than rushing or waiting.
                baseNs = showNs = vsync.closest(now + RELEASE_AHEAD_NS + periodNs);
                basePtsUs = ptsUs;
                sinceBase = 0;
                resyncs++;
            } else if (now > showNs - periodNs) {
                late++;  // under a refresh to spare: it may miss its slot
            }
            // The compositor shows a frame on the first refresh at or after its timestamp.
            // Stamping it half a refresh early leaves the most room for timing jitter.
            codec.releaseOutputBuffer(out, showNs - periodNs / 2);
            lastShowNs = showNs;
            sinceBase++;
            shown++;
            if (first) {
                first = false;
                main.post(() -> listener.onFirstFrame(this));
            }

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - statsSinceMs >= STATS_EVERY_MS) {
                float seconds = (nowMs - statsSinceMs) / 1000f;
                Log.i(TAG, String.format(Locale.ROOT,
                        "sent %.1f fps, shown %.1f fps, late %d, resyncs %d, min headroom %.1f ms,"
                                + " loops %d",
                        shown / seconds, rendered.getAndSet(0) / seconds, late, resyncs,
                        minHeadroomNs / 1e6, loops));
                statsSinceMs = nowMs;
                shown = late = resyncs = 0;
                minHeadroomNs = Long.MAX_VALUE;
            }
        }
    }
}
