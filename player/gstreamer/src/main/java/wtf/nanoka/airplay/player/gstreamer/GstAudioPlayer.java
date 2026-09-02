package wtf.nanoka.airplay.player.gstreamer;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.elements.AppSrc;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.server.AirPlayAudioConsumer;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Plays AirPlay ALAC and AAC-ELD streams without constructing a video pipeline.
 */
@Slf4j
public final class GstAudioPlayer implements AirPlayAudioConsumer, AutoCloseable {

    private static final int AUDIO_QUEUE_CAPACITY = 32;
    private static final long FEEDER_JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(3);
    private static final String ALAC_PIPELINE_DESCRIPTION =
            "appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample "
                    + "! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false";
    private static final String AAC_ELD_PIPELINE_DESCRIPTION =
            "appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample "
                    + "! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false";

    static {
        GstPlayerUtils.initialize();
    }

    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;
    private final LinkedBlockingDeque<TimedAudio> audioQueue =
            new LinkedBlockingDeque<>(AUDIO_QUEUE_CAPACITY);
    private final Object transitionLock = new Object();
    private final Thread audioFeeder;
    private final RtpAudioTiming.SequenceTracker sequenceTracker =
            new RtpAudioTiming.SequenceTracker();

    private AppSrc activeSource;
    private int activeSampleRate = 44100;
    private int activeSamplesPerFrame = 480;
    private long generation;
    private int pushesInFlight;
    private boolean closed;

    public GstAudioPlayer() {
        AudioPipelines pipelines = createPipelines();
        alacPipeline = pipelines.alac();
        aacEldPipeline = pipelines.aacEld();

        AppSrc createdAlacSrc;
        AppSrc createdAacEldSrc;
        Thread createdFeeder;
        try {
            createdAlacSrc = Objects.requireNonNull(
                    (AppSrc) alacPipeline.getElementByName("alac-src"), "alac-src");
            createdAacEldSrc = Objects.requireNonNull(
                    (AppSrc) aacEldPipeline.getElementByName("aac-eld-src"), "aac-eld-src");
            configureSource(createdAlacSrc);
            configureSource(createdAacEldSrc);
            createdFeeder = Thread.ofPlatform()
                    .name("gstreamer-audio-feeder")
                    .daemon(true)
                    .unstarted(this::feedAudio);
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(aacEldPipeline, failure);
            cleanupAfterConstructionFailure(alacPipeline, failure);
            throw failure;
        }
        alacSrc = createdAlacSrc;
        aacEldSrc = createdAacEldSrc;
        audioFeeder = createdFeeder;
        try {
            audioFeeder.start();
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(aacEldPipeline, failure);
            cleanupAfterConstructionFailure(alacPipeline, failure);
            throw failure;
        }
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        Objects.requireNonNull(audioStreamInfo, "audioStreamInfo");
        synchronized (transitionLock) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                generation++;
                activeSource = null;
                sequenceTracker.reset();
                audioQueue.clear();
            }

            Throwable stopFailure = stopPipelines(null);
            waitForPushes();
            rethrowUnchecked(stopFailure);

            GstAudioFormat.Configuration format = GstAudioFormat.from(audioStreamInfo);
            Pipeline pipeline = format.compressionType() == AudioStreamInfo.CompressionType.ALAC
                    ? alacPipeline : aacEldPipeline;
            AppSrc source = format.compressionType() == AudioStreamInfo.CompressionType.ALAC
                    ? alacSrc : aacEldSrc;
            source.setCaps(Caps.fromString(format.caps()));
            try {
                pipeline.play();
            } catch (RuntimeException | Error failure) {
                try {
                    pipeline.stop();
                } catch (RuntimeException | Error stopFailureAfterPlay) {
                    failure.addSuppressed(stopFailureAfterPlay);
                }
                throw failure;
            }
            synchronized (this) {
                activeSource = source;
                activeSampleRate = format.sampleRate();
                activeSamplesPerFrame = format.samplesPerFrame();
            }
            log.info("Started GStreamer {} audio pipeline at {} Hz",
                    format.compressionType(), format.sampleRate());
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, -1, -1);
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        Objects.requireNonNull(bytes, "bytes");
        synchronized (this) {
            if (closed || activeSource == null) {
                return;
            }
            if (!sequenceTracker.accept(sequenceNumber)) {
                log.debug("Ignoring duplicate or late AirPlay audio sequence {}", sequenceNumber & 0xffff);
                return;
            }
            TimedAudio audio = new TimedAudio(
                    bytes, timestamp, sequenceNumber, activeSource, generation,
                    activeSampleRate, activeSamplesPerFrame);
            offerLatest(audioQueue, audio);
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        synchronized (transitionLock) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                generation++;
                activeSource = null;
                sequenceTracker.reset();
                audioQueue.clear();
            }
            Throwable failure = stopPipelines(null);
            waitForPushes();
            rethrowUnchecked(failure);
        }
    }

    private void configureSource(AppSrc source) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("block", true);
        source.set("max-buffers", (long) AUDIO_QUEUE_CAPACITY);
        source.setMaxBytes(512 * 1024);
    }

    private void feedAudio() {
        long feederGeneration = Long.MIN_VALUE;
        RtpAudioTiming.Timeline timeline = new RtpAudioTiming.Timeline();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedAudio audio = audioQueue.takeFirst();
                if (!isCurrent(audio)) {
                    continue;
                }
                if (audio.generation() != feederGeneration) {
                    feederGeneration = audio.generation();
                    timeline = new RtpAudioTiming.Timeline();
                }
                long duration = (long) audio.samplesPerFrame()
                        * TimeUnit.SECONDS.toNanos(1) / audio.sampleRate();
                long presentationTime = timeline.presentationTime(
                        audio.timestamp(), audio.sequenceNumber(), audio.sampleRate(), duration);
                pushAudioBufferIfCurrent(audio, presentationTime, duration);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                log.warn("Unable to feed GStreamer audio: {}", error.getMessage());
            }
        }
    }

    private synchronized boolean isCurrent(TimedAudio audio) {
        return !closed && audio.generation() == generation && audio.source() == activeSource;
    }

    private void pushAudioBufferIfCurrent(TimedAudio audio, long presentationTime, long duration) {
        synchronized (this) {
            if (closed || audio.generation() != generation || audio.source() != activeSource) {
                return;
            }
            pushesInFlight++;
        }
        try {
            pushBuffer(audio.source(), audio.bytes(), presentationTime, duration);
        } finally {
            synchronized (this) {
                pushesInFlight--;
                notifyAll();
            }
        }
    }

    private void pushBuffer(AppSrc source, byte[] bytes, long presentationTime, long duration) {
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
            log.warn("GStreamer rejected audio buffer: {}", result);
        }
    }

    private <T> void offerLatest(LinkedBlockingDeque<T> queue, T value) {
        while (!queue.offerLast(value)) {
            queue.pollFirst();
        }
    }

    private void waitForPushes() {
        boolean interrupted = false;
        synchronized (this) {
            while (pushesInFlight > 0) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinFeeder() {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FEEDER_JOIN_TIMEOUT_MILLIS);
        while (audioFeeder.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(audioFeeder, remaining);
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (audioFeeder.isAlive()) {
            log.warn("GStreamer feeder thread did not stop within the shutdown timeout: {}",
                    audioFeeder.getName());
        }
    }

    @Override
    public void close() {
        synchronized (transitionLock) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                generation++;
                activeSource = null;
                sequenceTracker.reset();
                audioQueue.clear();
            }
            audioFeeder.interrupt();
            Throwable failure = stopPipelines(null);
            waitForPushes();
            joinFeeder();
            failure = closePipelines(failure);
            rethrowUnchecked(failure);
        }
    }

    private Throwable stopPipelines(Throwable failure) {
        failure = runLifecycleAction(failure, alacPipeline::stop);
        return runLifecycleAction(failure, aacEldPipeline::stop);
    }

    private Throwable closePipelines(Throwable failure) {
        failure = runLifecycleAction(failure, alacPipeline::close);
        return runLifecycleAction(failure, aacEldPipeline::close);
    }

    private Throwable runLifecycleAction(Throwable failure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error next) {
            if (failure == null) {
                return next;
            }
            failure.addSuppressed(next);
        }
        return failure;
    }

    private static AudioPipelines createPipelines() {
        Pipeline alac = null;
        Pipeline aacEld = null;
        try {
            alac = Objects.requireNonNull(
                    (Pipeline) Gst.parseLaunch(ALAC_PIPELINE_DESCRIPTION), "ALAC pipeline");
            aacEld = Objects.requireNonNull(
                    (Pipeline) Gst.parseLaunch(AAC_ELD_PIPELINE_DESCRIPTION), "AAC-ELD pipeline");
            return new AudioPipelines(alac, aacEld);
        } catch (RuntimeException | Error failure) {
            if (aacEld != null) {
                cleanupAfterConstructionFailure(aacEld, failure);
            }
            if (alac != null) {
                cleanupAfterConstructionFailure(alac, failure);
            }
            throw failure;
        }
    }

    private static void cleanupAfterConstructionFailure(Element element, Throwable failure) {
        try {
            element.stop();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            element.close();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private record AudioPipelines(Pipeline alac, Pipeline aacEld) {
    }

    private record TimedAudio(
            byte[] bytes,
            long timestamp,
            int sequenceNumber,
            AppSrc source,
            long generation,
            int sampleRate,
            int samplesPerFrame) {
    }
}
