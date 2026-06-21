package com.artguard.gateway.camera;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A camera backed by a looping local video file (real surveillance footage of
 * people walking). Decodes with FFmpeg (JavaCV), re-encodes each frame as JPEG,
 * and emits at a fixed FPS — so the feed actually moves and YOLOv8 detects
 * people in motion. Loops the clip forever.
 */
public class VideoFileFrameSource implements FrameSource {

    private static final Logger log = LoggerFactory.getLogger(VideoFileFrameSource.class);
    private static final int FPS = 8;

    private final String cameraId;
    private final String cameraName;
    private final String path;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public VideoFileFrameSource(String cameraId, String cameraName, String path) {
        this.cameraId = cameraId;
        this.cameraName = cameraName;
        this.path = path;
    }

    @Override public String cameraId()   { return cameraId; }
    @Override public String cameraName() { return cameraName; }

    @Override
    public void start(Consumer<Frame> sink) {
        if (!running.compareAndSet(false, true)) return;
        thread = Thread.ofVirtual().name("video-" + cameraId).start(() -> runLoop(sink));
        log.info("Video camera {} playing {} (looping) at {} fps", cameraId, path, FPS);
    }

    private void runLoop(Consumer<Frame> sink) {
        var converter = new Java2DFrameConverter();
        long frameId = 0;
        final long periodMs = 1000L / FPS;
        while (running.get()) {
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path)) {
                grabber.start();
                org.bytedeco.javacv.Frame img;
                long next = System.currentTimeMillis();
                while (running.get() && (img = grabber.grabImage()) != null) {
                    var buffered = converter.getBufferedImage(img);
                    if (buffered == null) continue;
                    var baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "jpg", baos);
                    sink.accept(new Frame(cameraId, frameId++, System.currentTimeMillis(), baos.toByteArray()));
                    // pace playback to FPS
                    next += periodMs;
                    long sleep = next - System.currentTimeMillis();
                    if (sleep > 0) Thread.sleep(sleep);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running.get()) break;
                log.warn("Video {} error ({}); restarting clip", cameraId, e.getMessage());
                sleep(1000);
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }
}
