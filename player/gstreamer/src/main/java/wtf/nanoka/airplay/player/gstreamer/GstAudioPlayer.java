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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Plays AirPlay ALAC and AAC-ELD streams without constructing a video pipeline.
 */
@Slf4j
public final class GstAudioPlayer implements AirPlayAudioConsumer, AutoCloseable {

    static final int AUDIO_QUEUE_CAPACITY = 32;
    private static final long FEEDER_JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(3);
    private static final String ALAC_PIPELINE_DESCRIPTION =
            "appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample "
                    + "! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false";
    private static final String AAC_ELD_PIPELINE_DESCRIPTION =
            "appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample "
                    + "! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false";

    private final AudioPipeline alacPipeline;
    private final AudioPipeline aacEldPipeline;
    private final LinkedBlockingDeque<TimedAudio> audioQueue =
            new LinkedBlockingDeque<>(AUDIO_QUEUE_CAPACITY);
    private final Object transitionLock = new Object();
    private final Thread audioFeeder;
    private final RtpAudioTiming.SequenceTracker sequenceTracker =
            new RtpAudioTiming.SequenceTracker();

    private AudioPipeline activePipeline;
    private int activeSampleRate = 44100;
    private int activeSamplesPerFrame = 480;
    private long generation;
    private int pushesInFlight;
    private boolean closed;

    public GstAudioPlayer() {
        this(createNativePipelines(), Thread::new);
    }

    GstAudioPlayer(AudioPipelines pipelines, ThreadFactory feederThreadFactory) {
        Objects.requireNonNull(pipelines, "pipelines");
        alacPipeline = pipelines.alac();
        aacEldPipeline = pipelines.aacEld();

        Thread createdFeeder;
        try {
            Objects.requireNonNull(feederThreadFactory, "feederThreadFactory");
            createdFeeder = Objects.requireNonNull(
                    feederThreadFactory.newThread(this::feedAudio),
                    "feederThreadFactory.newThread()");
            createdFeeder.setName("gstreamer-audio-feeder");
            createdFeeder.setDaemon(true);
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(aacEldPipeline, failure);
            cleanupAfterConstructionFailure(alacPipeline, failure);
            throw failure;
        }
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
                activePipeline = null;
                sequenceTracker.reset();
                audioQueue.clear();
            }

            Throwable stopFailure = stopPipelines(null);
            waitForPushes();
            rethrowUnchecked(stopFailure);

            GstAudioFormat.Configuration format = GstAudioFormat.from(audioStreamInfo);
            AudioPipeline pipeline = format.compressionType() == AudioStreamInfo.CompressionType.ALAC
                    ? alacPipeline : aacEldPipeline;
            pipeline.configure(format.caps());
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
                activePipeline = pipeline;
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
            if (closed || activePipeline == null) {
                return;
            }
            if (!sequenceTracker.accept(sequenceNumber)) {
                log.debug("Ignoring duplicate or late AirPlay audio sequence {}", sequenceNumber & 0xffff);
                return;
            }
            TimedAudio audio = new TimedAudio(
                    bytes, timestamp, sequenceNumber, activePipeline, generation,
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
                activePipeline = null;
                sequenceTracker.reset();
                audioQueue.clear();
            }
            Throwable failure = stopPipelines(null);
            waitForPushes();
            rethrowUnchecked(failure);
        }
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
        return !closed && audio.generation() == generation && audio.pipeline() == activePipeline;
    }

    private void pushAudioBufferIfCurrent(TimedAudio audio, long presentationTime, long duration) {
        synchronized (this) {
            if (closed || audio.generation() != generation || audio.pipeline() != activePipeline) {
                return;
            }
            pushesInFlight++;
        }
        try {
            audio.pipeline().push(audio.bytes(), presentationTime, duration);
        } finally {
            synchronized (this) {
                pushesInFlight--;
                notifyAll();
            }
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
                activePipeline = null;
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

    private static AudioPipelines createNativePipelines() {
        GstPlayerUtils.initialize();
        Pipeline alac = null;
        Pipeline aacEld = null;
        try {
            alac = Objects.requireNonNull(
                    (Pipeline) Gst.parseLaunch(ALAC_PIPELINE_DESCRIPTION), "ALAC pipeline");
            aacEld = Objects.requireNonNull(
                    (Pipeline) Gst.parseLaunch(AAC_ELD_PIPELINE_DESCRIPTION), "AAC-ELD pipeline");
            return new AudioPipelines(
                    new NativeAudioPipeline(alac, "alac-src"),
                    new NativeAudioPipeline(aacEld, "aac-eld-src"));
        } catch (RuntimeException | Error failure) {
            if (aacEld != null) {
                cleanupNativeAfterConstructionFailure(aacEld, failure);
            }
            if (alac != null) {
                cleanupNativeAfterConstructionFailure(alac, failure);
            }
            throw failure;
        }
    }

    private static void configureSource(AppSrc source) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("block", true);
        source.set("max-buffers", (long) AUDIO_QUEUE_CAPACITY);
        source.setMaxBytes(512 * 1024);
    }

    private static void cleanupNativeAfterConstructionFailure(Element element, Throwable failure) {
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

    private static void cleanupAfterConstructionFailure(AudioPipeline pipeline, Throwable failure) {
        try {
            pipeline.stop();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            pipeline.close();
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

    /** Keeps queue, timing, and lifecycle control independent from native GStreamer objects. */
    interface AudioPipeline extends AutoCloseable {
        void configure(String caps);

        void play();

        void stop();

        void push(byte[] bytes, long presentationTime, long duration);

        @Override
        void close();
    }

    record AudioPipelines(AudioPipeline alac, AudioPipeline aacEld) {
        AudioPipelines {
            Objects.requireNonNull(alac, "alac");
            Objects.requireNonNull(aacEld, "aacEld");
        }
    }

    private static final class NativeAudioPipeline implements AudioPipeline {
        private final Pipeline pipeline;
        private final AppSrc source;

        private NativeAudioPipeline(Pipeline pipeline, String sourceName) {
            this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
            source = Objects.requireNonNull(
                    (AppSrc) pipeline.getElementByName(sourceName), sourceName);
            configureSource(source);
        }

        @Override
        public void configure(String caps) {
            source.setCaps(Caps.fromString(caps));
        }

        @Override
        public void play() {
            pipeline.play();
        }

        @Override
        public void stop() {
            pipeline.stop();
        }

        @Override
        public void push(byte[] bytes, long presentationTime, long duration) {
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

        @Override
        public void close() {
            pipeline.close();
        }
    }

    private record TimedAudio(
            byte[] bytes,
            long timestamp,
            int sequenceNumber,
            AudioPipeline pipeline,
            long generation,
            int sampleRate,
            int samplesPerFrame) {
    }
}
