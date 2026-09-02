package wtf.nanoka.airplay.player.gstreamer;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class GstPlayer implements AirPlayConsumer, AutoCloseable {

    private static final long VIDEO_BACKPRESSURE_WARNING_NANOS = TimeUnit.SECONDS.toNanos(5);

    static {
        GstPlayerUtils.initialize();
    }

    protected final Pipeline h264Pipeline;
    protected final Pipeline hevcPipeline;

    private final AppSrc h264Src;
    private final AppSrc hevcSrc;
    private final GstAudioPlayer audioPlayer;

    private Pipeline hlsPipeline;

    private final LinkedBlockingDeque<TimedMedia> videoQueue;
    private final Object hlsLock = new Object();
    private final int videoQueueDepth;
    private final boolean aggressiveFrameDropping;
    private final long configuredVideoFrameDurationNanos;
    private volatile Pipeline activeVideoPipeline;
    private volatile AppSrc activeVideoSrc;
    private volatile VideoStreamInfo.Codec activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
    private final VideoAccessUnitGate videoAccessUnitGate = new VideoAccessUnitGate();
    private volatile long videoGeneration;
    protected final Object videoTransitionLock = new Object();
    private int videoPushesInFlight;
    private volatile long lastVideoBackpressureWarningNanos;
    private volatile boolean closed;
    private final Thread videoFeeder;

    public GstPlayer() {
        this(60, 3, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth) {
        this(fps, videoQueueDepth, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription) {
        this(fps, videoQueueDepth, h264PipelineDescription, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription,
                        String hevcPipelineDescription) {
        this(fps, videoQueueDepth, h264PipelineDescription, hevcPipelineDescription, false);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription,
                        String hevcPipelineDescription, boolean aggressiveFrameDropping) {
        this.videoQueueDepth = Math.max(1, videoQueueDepth);
        this.aggressiveFrameDropping = aggressiveFrameDropping;
        videoQueue = new LinkedBlockingDeque<>(this.videoQueueDepth);
        configuredVideoFrameDurationNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, fps);

        Pipeline createdH264Pipeline = null;
        Pipeline createdHevcPipeline = null;
        AppSrc createdH264Src = null;
        AppSrc createdHevcSrc = null;
        GstAudioPlayer createdAudioPlayer = null;
        Thread createdVideoFeeder = null;
        try {
            createdH264Pipeline = h264PipelineDescription == null
                    ? createH264Pipeline()
                    : (Pipeline) Gst.parseLaunch(h264PipelineDescription);
            createdH264Pipeline = Objects.requireNonNull(
                    createdH264Pipeline, "H.264 pipeline");
            createdH264Src = Objects.requireNonNull(
                    (AppSrc) createdH264Pipeline.getElementByName("h264-src"), "h264-src");
            configureVideoSource(createdH264Src,
                    "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au");

            createdHevcPipeline = hevcPipelineDescription == null
                    ? null
                    : (Pipeline) Gst.parseLaunch(hevcPipelineDescription);
            createdHevcSrc = createdHevcPipeline == null ? null : Objects.requireNonNull(
                    (AppSrc) createdHevcPipeline.getElementByName("hevc-src"), "hevc-src");
            if (createdHevcSrc != null) {
                configureVideoSource(createdHevcSrc,
                        "video/x-h265,stream-format=(string)byte-stream,alignment=(string)au");
            }

            createdAudioPlayer = new GstAudioPlayer();
            createdVideoFeeder = Thread.ofPlatform()
                    .name("gstreamer-video-feeder")
                    .daemon(true)
                    .unstarted(this::feedVideo);
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(
                    createdVideoFeeder, createdAudioPlayer,
                    createdHevcPipeline, createdH264Pipeline, failure);
            throw failure;
        }

        h264Pipeline = createdH264Pipeline;
        hevcPipeline = createdHevcPipeline;
        h264Src = createdH264Src;
        hevcSrc = createdHevcSrc;
        audioPlayer = createdAudioPlayer;
        videoFeeder = createdVideoFeeder;
        try {
            videoFeeder.start();
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(
                    videoFeeder, audioPlayer, hevcPipeline, h264Pipeline, failure);
            throw failure;
        }
        if (aggressiveFrameDropping) {
            log.warn("Experimental aggressive encoded-frame dropping is enabled; predicted frames may corrupt");
        }
    }

    protected abstract Pipeline createH264Pipeline();

    protected abstract Pipeline createHevcPipeline();

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        if (videoStreamInfo.getCodec() != VideoStreamInfo.Codec.UNKNOWN) {
            activateVideoPipeline(videoStreamInfo.getCodec());
        } else {
            deactivateVideoPipeline();
        }
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        if (videoStreamInfo.getCodec() != VideoStreamInfo.Codec.UNKNOWN) {
            activateVideoPipeline(videoStreamInfo.getCodec());
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        onVideo(bytes, -1);
    }

    @Override
    public void onVideo(byte[] bytes, long timestamp) {
        TimedMedia media;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (activeVideoSrc == null) {
                log.debug("Ignoring a video access unit before codec detection");
                return;
            }
            byte[] accessUnit = videoAccessUnitGate.accept(bytes);
            if (accessUnit == null) {
                return;
            }
            media = new TimedMedia(accessUnit, timestamp, activeVideoSrc, videoGeneration);
        }
        try {
            if (aggressiveFrameDropping) {
                offerLatest(videoQueue, media);
            } else {
                // Preserve every encoded reference frame. TCP backpressure is safer than corrupting the decoder DPB.
                if (!videoQueue.offerLast(media, 500, TimeUnit.MILLISECONDS)) {
                    logVideoBackpressure();
                    videoQueue.putLast(media);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (closed || media.generation() != videoGeneration || media.source() != activeVideoSrc) {
                videoQueue.remove(media);
            }
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        deactivateVideoPipeline();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioPlayer.onAudioFormat(audioStreamInfo);
    }

    @Override
    public void onAudio(byte[] bytes) {
        audioPlayer.onAudio(bytes);
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        audioPlayer.onAudio(bytes, timestamp, sequenceNumber);
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioPlayer.onAudioSrcDisconnect();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        Pipeline next = (Pipeline) Gst.parseLaunch("playbin3");
        next.set("uri", playlistUri);

        synchronized (hlsLock) {
            if (closed) {
                stopAndClose(next);
                return;
            }
            Pipeline previous = hlsPipeline;
            hlsPipeline = next;
            if (previous != null) {
                stopAndClose(previous);
            }
            try {
                next.play();
            } catch (RuntimeException | Error failure) {
                if (hlsPipeline == next) {
                    hlsPipeline = null;
                }
                stopAndClose(next);
                throw failure;
            }
        }
    }

    @Override
    public void onMediaPlaylistRemove() {
        synchronized (hlsLock) {
            if (hlsPipeline != null) {
                stopAndClose(hlsPipeline);
                hlsPipeline = null;
            }
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        synchronized (hlsLock) {
            if (hlsPipeline != null && hlsPipeline.isPlaying()) {
                hlsPipeline.pause();
            }
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        synchronized (hlsLock) {
            if (hlsPipeline != null && !hlsPipeline.isPlaying()) {
                hlsPipeline.play();
            }
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        synchronized (hlsLock) {
            if (hlsPipeline != null) {
                return new PlaybackInfo(
                        hlsPipeline.queryDuration(TimeUnit.SECONDS),
                        hlsPipeline.queryPosition(TimeUnit.SECONDS));
            }
        }
        return AirPlayConsumer.super.playbackInfo();
    }

    private void feedVideo() {
        long feederGeneration = Long.MIN_VALUE;
        long firstTimestamp = Long.MIN_VALUE;
        long syntheticPresentationTime = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedMedia media = videoQueue.takeFirst();
                if (media.generation() != videoGeneration) {
                    continue;
                }
                if (media.generation() != feederGeneration) {
                    feederGeneration = media.generation();
                    firstTimestamp = Long.MIN_VALUE;
                    syntheticPresentationTime = 0;
                }
                if (firstTimestamp == Long.MIN_VALUE && media.timestamp() >= 0) {
                    firstTimestamp = media.timestamp();
                }
                long presentationTime = media.timestamp() >= 0 && firstTimestamp != Long.MIN_VALUE
                        ? Math.max(0, fixedPointDeltaNanos(firstTimestamp, media.timestamp()))
                        : syntheticPresentationTime;
                syntheticPresentationTime = presentationTime + configuredVideoFrameDurationNanos;
                pushVideoBufferIfCurrent(media, presentationTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Unable to feed GStreamer video: {}", e.getMessage());
            }
        }
    }

    private void pushBuffer(AppSrc source, byte[] bytes, long presentationTime, long duration, String streamName) {
        Buffer buffer = new Buffer(bytes.length);
        var mapped = buffer.map(true);
        try {
            mapped.put(bytes);
        } finally {
            buffer.unmap();
        }
        buffer.setPresentationTimestamp(presentationTime);
        buffer.setDuration(duration);
        FlowReturn result = source.pushBuffer(buffer);
        if (result != FlowReturn.OK && result != FlowReturn.FLUSHING) {
            log.warn("GStreamer rejected {} buffer: {}", streamName, result);
        }
    }

    private void pushVideoBufferIfCurrent(TimedMedia media, long presentationTime) {
        if (!beginVideoPush(media)) {
            return;
        }
        try {
            pushBuffer(media.source(), media.bytes(), presentationTime,
                    configuredVideoFrameDurationNanos, "video");
        } finally {
            endVideoPush();
        }
    }

    private synchronized boolean beginVideoPush(TimedMedia media) {
        if (closed || media.generation() != videoGeneration || media.source() != activeVideoSrc) {
            return false;
        }
        videoPushesInFlight++;
        return true;
    }

    private synchronized void endVideoPush() {
        videoPushesInFlight--;
        notifyAll();
    }

    private <T> void offerLatest(LinkedBlockingDeque<T> queue, T value) {
        while (!queue.offerLast(value)) {
            queue.pollFirst();
        }
    }

    private void logVideoBackpressure() {
        long now = System.nanoTime();
        if (now - lastVideoBackpressureWarningNanos >= VIDEO_BACKPRESSURE_WARNING_NANOS) {
            lastVideoBackpressureWarningNanos = now;
            log.warn("GStreamer video input is backpressured; preserving encoded frames and waiting for capacity");
        }
    }

    private void configureVideoSource(AppSrc source, String caps) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.setCaps(Caps.fromString(caps));
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("block", !aggressiveFrameDropping);
        source.set("max-buffers", (long) videoQueueDepth);
        source.set("leaky-type", aggressiveFrameDropping ? 2 : 0);
        source.setMaxBytes(4 * 1024 * 1024);
    }

    private void activateVideoPipeline(VideoStreamInfo.Codec codec) {
        Pipeline pipeline;
        AppSrc source;
        if (codec == VideoStreamInfo.Codec.H264) {
            pipeline = h264Pipeline;
            source = h264Src;
        } else if (codec == VideoStreamInfo.Codec.HEVC && hevcPipeline != null && hevcSrc != null) {
            pipeline = hevcPipeline;
            source = hevcSrc;
        } else if (codec == VideoStreamInfo.Codec.HEVC) {
            throw new IllegalStateException("The sender selected HEVC, but HEVC reception is disabled");
        } else {
            throw new IllegalArgumentException("Unsupported video codec: " + codec);
        }
        synchronized (videoTransitionLock) {
            Pipeline previous;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (activeVideoPipeline == pipeline) {
                    return;
                }
                previous = invalidateVideoPipeline();
            }
            stopAndDrainVideoPipeline(previous);
            synchronized (this) {
                if (closed) {
                    return;
                }
                videoAccessUnitGate.activate(codec);
                videoGeneration++;
                videoQueue.clear();
                activeVideoPipeline = pipeline;
                activeVideoSrc = source;
                activeVideoCodec = codec;
            }
            try {
                pipeline.play();
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    if (activeVideoPipeline == pipeline) {
                        invalidateVideoPipeline();
                        activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                        videoAccessUnitGate.deactivate();
                    }
                }
                pipeline.stop();
                throw failure;
            }
        }
        log.info("Started GStreamer {} video pipeline", codec);
    }

    private void deactivateVideoPipeline() {
        synchronized (videoTransitionLock) {
            Pipeline pipeline;
            synchronized (this) {
                pipeline = invalidateVideoPipeline();
                activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                videoAccessUnitGate.deactivate();
            }
            stopAndDrainVideoPipeline(pipeline);
        }
    }

    /**
     * Rebinds the currently selected video pipeline after its native sink
     * surface has moved to another window.
     */
    protected boolean restartActiveVideoPipeline() {
        synchronized (videoTransitionLock) {
            Pipeline previous;
            VideoStreamInfo.Codec codec;
            synchronized (this) {
                if (closed || activeVideoPipeline == null
                        || activeVideoCodec == VideoStreamInfo.Codec.UNKNOWN) {
                    return false;
                }
                codec = activeVideoCodec;
                previous = invalidateVideoPipeline();
            }
            stopAndDrainVideoPipeline(previous);
            synchronized (this) {
                if (closed) {
                    return false;
                }
                videoAccessUnitGate.prepareRestart();
            }
            activateVideoPipeline(codec);
            return true;
        }
    }

    protected Pipeline invalidateVideoPipeline() {
        Pipeline pipeline = activeVideoPipeline;
        activeVideoPipeline = null;
        activeVideoSrc = null;
        videoGeneration++;
        videoQueue.clear();
        return pipeline;
    }

    protected void stopAndDrainVideoPipeline(Pipeline pipeline) {
        if (pipeline != null) {
            pipeline.stop();
        }
        boolean interrupted = false;
        synchronized (this) {
            while (videoPushesInFlight > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopAndClose(Element element) {
        element.stop();
        element.close();
    }

    private long fixedPointDeltaNanos(long first, long current) {
        long delta = current - first;
        long seconds = delta >> 32;
        long fraction = delta & 0xffff_ffffL;
        return seconds * TimeUnit.SECONDS.toNanos(1) + ((fraction * TimeUnit.SECONDS.toNanos(1)) >>> 32);
    }

    private record TimedMedia(byte[] bytes, long timestamp, AppSrc source, long generation) {
    }

    @Override
    public void close() {
        Throwable failure = null;
        synchronized (videoTransitionLock) {
            Pipeline pipeline;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pipeline = invalidateVideoPipeline();
                activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                videoAccessUnitGate.deactivate();
            }
            try {
                stopAndDrainVideoPipeline(pipeline);
            } catch (RuntimeException | Error closeFailure) {
                failure = closeFailure;
            }
        }

        videoFeeder.interrupt();
        videoQueue.clear();
        failure = runLifecycleAction(failure, h264Pipeline::stop);
        if (hevcPipeline != null) {
            failure = runLifecycleAction(failure, hevcPipeline::stop);
        }

        Pipeline hlsToClose;
        synchronized (hlsLock) {
            hlsToClose = hlsPipeline;
            hlsPipeline = null;
        }
        if (hlsToClose != null) {
            failure = runLifecycleAction(failure, () -> stopAndClose(hlsToClose));
        }

        failure = runLifecycleAction(failure, audioPlayer::close);
        joinFeeder(videoFeeder);
        failure = runLifecycleAction(failure, h264Pipeline::close);
        if (hevcPipeline != null) {
            failure = runLifecycleAction(failure, hevcPipeline::close);
        }
        rethrowUnchecked(failure);
    }

    private static void cleanupAfterConstructionFailure(
            Thread feeder,
            GstAudioPlayer audio,
            Pipeline hevc,
            Pipeline h264,
            Throwable failure) {
        if (feeder != null && feeder.isAlive()) {
            feeder.interrupt();
        }
        if (audio != null) {
            suppressCleanupFailure(failure, audio::close);
        }
        cleanupPipelineAfterConstructionFailure(hevc, failure);
        cleanupPipelineAfterConstructionFailure(h264, failure);
    }

    private static void cleanupPipelineAfterConstructionFailure(Pipeline pipeline, Throwable failure) {
        if (pipeline == null) {
            return;
        }
        suppressCleanupFailure(failure, pipeline::stop);
        suppressCleanupFailure(failure, pipeline::close);
    }

    private static void suppressCleanupFailure(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static Throwable runLifecycleAction(Throwable failure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error actionFailure) {
            if (failure == null) {
                return actionFailure;
            }
            failure.addSuppressed(actionFailure);
        }
        return failure;
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void joinFeeder(Thread feeder) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (feeder.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(feeder, remaining);
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (feeder.isAlive()) {
            log.warn("GStreamer feeder thread did not stop within the shutdown timeout: {}", feeder.getName());
        }
    }
}
