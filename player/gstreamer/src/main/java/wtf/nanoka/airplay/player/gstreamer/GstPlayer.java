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
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;

    private Pipeline hlsPipeline;

    private AudioStreamInfo.CompressionType audioCompressionType;
    private final LinkedBlockingDeque<TimedMedia> videoQueue;
    private final LinkedBlockingDeque<TimedMedia> audioQueue = new LinkedBlockingDeque<>(32);
    private final long configuredVideoFrameDurationNanos;
    private volatile int audioSampleRate = 44100;
    private volatile int audioSamplesPerFrame = 480;
    private long firstVideoTimestamp = Long.MIN_VALUE;
    private volatile long lastVideoTimestamp = Long.MIN_VALUE;
    private long firstAudioTimestamp = Long.MIN_VALUE;
    private final Thread videoFeeder;
    private final Thread audioFeeder;

    public GstPlayer() {
        this(60, 3, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth) {
        this(fps, videoQueueDepth, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription) {
        videoQueue = new LinkedBlockingDeque<>(Math.max(1, videoQueueDepth));
        configuredVideoFrameDurationNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, fps);
        h264Pipeline = h264PipelineDescription == null
                ? createH264Pipeline()
                : (Pipeline) Gst.parseLaunch(h264PipelineDescription);

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        h264Src.setCaps(Caps.fromString("video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("emit-signals", false);
        h264Src.set("block", false);
        h264Src.setMaxBytes(4 * 1024 * 1024);

        alacPipeline = (Pipeline) Gst.parseLaunch("appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample ! autoaudiosink sync=false");

        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", false);
        alacSrc.set("block", false);
        alacSrc.setMaxBytes(512 * 1024);

        aacEldPipeline = (Pipeline) Gst.parseLaunch("appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample ! autoaudiosink sync=false");

        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", false);
        aacEldSrc.set("block", false);
        aacEldSrc.setMaxBytes(512 * 1024);

        videoFeeder = Thread.ofPlatform().name("gstreamer-video-feeder").daemon(true).start(this::feedVideo);
        audioFeeder = Thread.ofPlatform().name("gstreamer-audio-feeder").daemon(true).start(this::feedAudio);
    }

    protected abstract Pipeline createH264Pipeline();

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        videoQueue.clear();
        firstVideoTimestamp = Long.MIN_VALUE;
        lastVideoTimestamp = Long.MIN_VALUE;
        h264Pipeline.play();
    }

    @Override
    public void onVideo(byte[] bytes) {
        onVideo(bytes, -1);
    }

    @Override
    public void onVideo(byte[] bytes, long timestamp) {
        offerLatest(videoQueue, new TimedMedia(bytes, timestamp));
    }

    @Override
    public void onVideoSrcDisconnect() {
        videoQueue.clear();
        h264Pipeline.stop();
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
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedMedia media = videoQueue.takeFirst();
                if (firstVideoTimestamp == Long.MIN_VALUE && media.timestamp() >= 0) {
                    firstVideoTimestamp = media.timestamp();
                }
                long presentationTime = media.timestamp() >= 0 && firstVideoTimestamp != Long.MIN_VALUE
                        ? fixedPointDeltaNanos(firstVideoTimestamp, media.timestamp())
                        : 0;
                long duration = configuredVideoFrameDurationNanos;
                if (lastVideoTimestamp != Long.MIN_VALUE && media.timestamp() >= 0) {
                    long measuredDuration = fixedPointDeltaNanos(lastVideoTimestamp, media.timestamp());
                    if (measuredDuration > 0 && measuredDuration <= TimeUnit.SECONDS.toNanos(1)) {
                        duration = measuredDuration;
                    }
                }
                if (media.timestamp() >= 0) {
                    lastVideoTimestamp = media.timestamp();
                }
                pushBuffer(h264Src, media.bytes(), Math.max(0, presentationTime), duration, "video");
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
        buffer.setDecodeTimestamp(presentationTime);
        buffer.setDuration(duration);
        FlowReturn result = source.pushBuffer(buffer);
        if (result != FlowReturn.OK && result != FlowReturn.FLUSHING) {
            log.warn("GStreamer rejected {} buffer: {}", streamName, result);
        }
    }

    private <T> void offerLatest(LinkedBlockingDeque<T> queue, T value) {
        while (!queue.offerLast(value)) {
            queue.pollFirst();
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

    private record TimedMedia(byte[] bytes, long timestamp) {
    }

    @Override
    public void close() {
        videoFeeder.interrupt();
        audioFeeder.interrupt();
        videoQueue.clear();
        audioQueue.clear();
        h264Pipeline.stop();
        alacPipeline.stop();
        aacEldPipeline.stop();
        if (hlsPipeline != null) {
            hlsPipeline.stop();
        }
    }
}
