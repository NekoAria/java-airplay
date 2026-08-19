package wtf.nanoka.airplay.player.gstreamer;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingDeque;

@Slf4j
public abstract class GstPlayer implements AirPlayConsumer, AutoCloseable {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    protected final Pipeline h264Pipeline;
    protected final Pipeline hevcPipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;

    private final AppSrc h264Src;
    private final AppSrc hevcSrc;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;

    private Pipeline hlsPipeline;

    private AudioStreamInfo.CompressionType audioCompressionType;
    private final LinkedBlockingDeque<TimedMedia> videoQueue;
    private final LinkedBlockingDeque<TimedMedia> audioQueue = new LinkedBlockingDeque<>(32);
    private final int videoQueueDepth;
    private final long configuredVideoFrameDurationNanos;
    private volatile int audioSampleRate = 44100;
    private volatile int audioSamplesPerFrame = 480;
    private long firstAudioTimestamp = Long.MIN_VALUE;
    private volatile Pipeline activeVideoPipeline;
    private volatile AppSrc activeVideoSrc;
    private volatile long videoGeneration;
    private final Object videoTransitionLock = new Object();
    private int videoPushesInFlight;
    private volatile boolean closed;
    private final Thread videoFeeder;
    private final Thread audioFeeder;

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
        this.videoQueueDepth = Math.max(1, videoQueueDepth);
        videoQueue = new LinkedBlockingDeque<>(this.videoQueueDepth);
        configuredVideoFrameDurationNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, fps);
        h264Pipeline = h264PipelineDescription == null
                ? createH264Pipeline()
                : (Pipeline) Gst.parseLaunch(h264PipelineDescription);
        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        configureVideoSource(h264Src,
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au");

        hevcPipeline = hevcPipelineDescription == null
                ? null
                : (Pipeline) Gst.parseLaunch(hevcPipelineDescription);
        hevcSrc = hevcPipeline == null ? null : (AppSrc) hevcPipeline.getElementByName("hevc-src");
        if (hevcSrc != null) {
            configureVideoSource(hevcSrc,
                    "video/x-h265,stream-format=(string)byte-stream,alignment=(string)au");
        }

        alacPipeline = (Pipeline) Gst.parseLaunch("appsrc name=alac-src ! avdec_alac ! audioconvert "
                + "! audioresample ! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false");

        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", false);
        alacSrc.set("block", true);
        alacSrc.set("max-buffers", 32L);
        alacSrc.setMaxBytes(512 * 1024);

        aacEldPipeline = (Pipeline) Gst.parseLaunch("appsrc name=aac-eld-src ! avdec_aac ! audioconvert "
                + "! audioresample ! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false");

        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", false);
        aacEldSrc.set("block", true);
        aacEldSrc.set("max-buffers", 32L);
        aacEldSrc.setMaxBytes(512 * 1024);

        videoFeeder = Thread.ofPlatform().name("gstreamer-video-feeder").daemon(true).start(this::feedVideo);
        audioFeeder = Thread.ofPlatform().name("gstreamer-audio-feeder").daemon(true).start(this::feedAudio);
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
            media = new TimedMedia(bytes, timestamp, activeVideoSrc, videoGeneration);
        }
        try {
            if (!videoQueue.offerLast(media, 500, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("GStreamer video input remained blocked for 500 ms");
            }
            synchronized (this) {
                if (closed || media.generation() != videoGeneration || media.source() != activeVideoSrc) {
                    videoQueue.remove(media);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        deactivateVideoPipeline();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        audioSampleRate = audioStreamInfo.getSampleRate() > 0 ? audioStreamInfo.getSampleRate() : 44100;
        audioSamplesPerFrame = audioStreamInfo.getSamplesPerFrame() > 0 ? audioStreamInfo.getSamplesPerFrame() : 480;
        audioQueue.clear();
        firstAudioTimestamp = Long.MIN_VALUE;
        configureAudioCaps();
        switch (audioCompressionType) {
            case ALAC -> alacPipeline.play();
            case AAC_ELD -> aacEldPipeline.play();
            default -> log.warn("Audio codec {} is advertised but not playable by this receiver", audioCompressionType);
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, -1, 0);
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        offerLatest(audioQueue, new TimedMedia(bytes, timestamp));
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioQueue.clear();
        alacPipeline.stop();
        aacEldPipeline.stop();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        hlsPipeline = (Pipeline) Gst.parseLaunch("playbin3 uri=" + playlistUri);
        hlsPipeline.play();
    }

    @Override
    public void onMediaPlaylistRemove() {
        if (hlsPipeline != null) {
            hlsPipeline.stop();
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        if (hlsPipeline != null && hlsPipeline.isPlaying()) {
            hlsPipeline.pause();
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        if (hlsPipeline != null && !hlsPipeline.isPlaying()) {
            hlsPipeline.play();
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        if (hlsPipeline != null) {
            return new PlaybackInfo(
                    hlsPipeline.queryDuration(TimeUnit.SECONDS),
                    hlsPipeline.queryPosition(TimeUnit.SECONDS));
        }
        return AirPlayConsumer.super.playbackInfo();
    }

    private void configureAudioCaps() {
        String alacCodecData = String.format("00000024616c616300000000000001600010280a0e0200ff000000000000000000%08x",
                audioSampleRate);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)"
                + audioSampleRate + ",stream-format=raw,codec_data=(buffer)" + alacCodecData));
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)"
                + audioSampleRate + ",stream-format=raw,codec_data=(buffer)f8e85000"));
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

    private void feedAudio() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedMedia media = audioQueue.takeFirst();
                if (firstAudioTimestamp == Long.MIN_VALUE && media.timestamp() >= 0) {
                    firstAudioTimestamp = media.timestamp();
                }
                long presentationTime = media.timestamp() >= 0 && firstAudioTimestamp != Long.MIN_VALUE
                        ? unsignedRtpDelta(firstAudioTimestamp, media.timestamp()) * TimeUnit.SECONDS.toNanos(1) / audioSampleRate
                        : 0;
                long duration = (long) audioSamplesPerFrame * TimeUnit.SECONDS.toNanos(1) / audioSampleRate;
                AppSrc source = audioCompressionType == AudioStreamInfo.CompressionType.ALAC ? alacSrc : aacEldSrc;
                pushBuffer(source, media.bytes(), presentationTime, duration, "audio");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Unable to feed GStreamer audio: {}", e.getMessage());
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

    private void configureVideoSource(AppSrc source, String caps) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.setCaps(Caps.fromString(caps));
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("block", true);
        source.set("max-buffers", (long) videoQueueDepth);
        source.set("leaky-type", 0);
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
                videoGeneration++;
                videoQueue.clear();
                activeVideoPipeline = pipeline;
                activeVideoSrc = source;
            }
            try {
                pipeline.play();
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    if (activeVideoPipeline == pipeline) {
                        invalidateVideoPipeline();
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
            }
            stopAndDrainVideoPipeline(pipeline);
        }
    }

    private Pipeline invalidateVideoPipeline() {
        Pipeline pipeline = activeVideoPipeline;
        activeVideoPipeline = null;
        activeVideoSrc = null;
        videoGeneration++;
        videoQueue.clear();
        return pipeline;
    }

    private void stopAndDrainVideoPipeline(Pipeline pipeline) {
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

    private long fixedPointDeltaNanos(long first, long current) {
        long delta = current - first;
        long seconds = delta >> 32;
        long fraction = delta & 0xffff_ffffL;
        return seconds * TimeUnit.SECONDS.toNanos(1) + ((fraction * TimeUnit.SECONDS.toNanos(1)) >>> 32);
    }

    private long unsignedRtpDelta(long first, long current) {
        return (current - first) & 0xffff_ffffL;
    }

    private record TimedMedia(byte[] bytes, long timestamp, AppSrc source, long generation) {

        private TimedMedia(byte[] bytes, long timestamp) {
            this(bytes, timestamp, null, 0);
        }
    }

    @Override
    public void close() {
        synchronized (videoTransitionLock) {
            Pipeline pipeline;
            synchronized (this) {
                closed = true;
                pipeline = invalidateVideoPipeline();
            }
            stopAndDrainVideoPipeline(pipeline);
        }
        videoFeeder.interrupt();
        audioFeeder.interrupt();
        videoQueue.clear();
        audioQueue.clear();
        h264Pipeline.stop();
        if (hevcPipeline != null) {
            hevcPipeline.stop();
        }
        alacPipeline.stop();
        aacEldPipeline.stop();
        if (hlsPipeline != null) {
            hlsPipeline.stop();
        }
        joinFeeder(videoFeeder);
        joinFeeder(audioFeeder);
    }

    private void joinFeeder(Thread feeder) {
        try {
            feeder.join(TimeUnit.SECONDS.toMillis(3));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (feeder.isAlive()) {
            log.warn("GStreamer feeder thread did not stop within the shutdown timeout: {}", feeder.getName());
        }
    }
}
