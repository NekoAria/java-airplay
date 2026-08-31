package wtf.nanoka.airplay.server.internal.handler.video;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.VideoPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
public class VideoHandler extends ChannelInboundHandlerAdapter {

    private static final double FPS_WINDOW_SECONDS = 1.0;
    private static final double FPS_REPORT_INTERVAL_SECONDS = 0.5;

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;
    private final ExecutorService callbackExecutor;
    private final VideoDecryptor videoDecryptor;
    private VideoStreamInfo detectedFormat;
    private VideoStreamInfo.Codec codec = VideoStreamInfo.Codec.UNKNOWN;
    private byte[] pendingParameterSets;
    private long lastVideoTimestamp = Long.MIN_VALUE;
    private long lastFpsReportTimestamp = Long.MIN_VALUE;
    private final ArrayDeque<Long> videoFrameTimestamps = new ArrayDeque<>();
    private boolean callbackPending;

    @FunctionalInterface
    public interface VideoDecryptor {
        boolean decrypt(byte[] payload) throws Exception;
    }

    public VideoHandler(AirPlay airPlay, AirPlayConsumer dataConsumer) {
        this(airPlay, dataConsumer, false, payload -> {
            airPlay.decryptVideo(payload);
            return true;
        });
    }

    public VideoHandler(AirPlay airPlay, AirPlayConsumer dataConsumer, boolean asynchronous) {
        this(airPlay, dataConsumer, asynchronous, payload -> {
            airPlay.decryptVideo(payload);
            return true;
        });
    }

    public VideoHandler(AirPlay airPlay, AirPlayConsumer dataConsumer, boolean asynchronous,
                        VideoDecryptor videoDecryptor) {
        this.airPlay = airPlay;
        this.dataConsumer = dataConsumer;
        this.videoDecryptor = videoDecryptor;
        callbackExecutor = asynchronous
                ? Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("airplay-video-dispatch").factory())
                : null;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (callbackExecutor != null) {
            ctx.read();
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        VideoPacket packet = (VideoPacket) msg;
        try {
            if (packet.getPayloadType() == 0) {
                if (!videoDecryptor.decrypt(packet.getPayload())) {
                    if (ctx != null) {
                        ctx.close();
                    }
                    return;
                }
                preparePictureNALUnits(packet.getPayload());
                VideoStreamInfo measuredFormat = updateMeasuredFps(packet.getTimestamp());
                byte[] accessUnit = packet.getPayload();
                if (pendingParameterSets != null) {
                    accessUnit = concatenate(pendingParameterSets, accessUnit);
                    pendingParameterSets = null;
                }
                byte[] finalAccessUnit = accessUnit;
                dispatch(ctx, () -> {
                    if (measuredFormat != null) {
                        dataConsumer.onVideoFormatDetected(measuredFormat);
                    }
                    dataConsumer.onVideo(finalAccessUnit, packet.getTimestamp());
                });
            } else if (packet.getPayloadType() == 1) {
                VideoCodecConfiguration configuration = VideoCodecConfiguration.parse(packet.getPayload());
                if (codec != VideoStreamInfo.Codec.UNKNOWN && codec != configuration.codec()) {
                    throw new IllegalArgumentException("Video codec changed within one mirroring connection");
                }
                codec = configuration.codec();
                pendingParameterSets = configuration.parameterSets();
                detectedFormat = detectedFormat(configuration);
                VideoStreamInfo configuredFormat = detectedFormat;
                dispatch(ctx, () -> dataConsumer.onVideoFormatDetected(configuredFormat));
                log.info("Detected sender video format: {} {}x{}", codec,
                        detectedFormat.getWidth(), detectedFormat.getHeight());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
        }
        dataConsumer.onVideoSrcDisconnect();
        super.channelInactive(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (callbackExecutor != null && !callbackPending && ctx.channel().isActive()) {
            ctx.read();
        }
        super.channelReadComplete(ctx);
    }

    private void dispatch(ChannelHandlerContext ctx, Runnable callback) {
        if (callbackExecutor == null) {
            callback.run();
            return;
        }
        callbackPending = true;
        try {
            callbackExecutor.execute(() -> {
                boolean success = false;
                try {
                    callback.run();
                    success = true;
                } catch (Throwable error) {
                    log.warn("Video consumer callback failed: {}", error.getMessage(), error);
                } finally {
                    completeDispatch(ctx, success);
                }
            });
        } catch (RejectedExecutionException e) {
            callbackPending = false;
            ctx.close();
        }
    }

    private void completeDispatch(ChannelHandlerContext ctx, boolean success) {
        ctx.executor().execute(() -> {
            callbackPending = false;
            if (!success) {
                ctx.close();
            } else if (ctx.channel().isActive()) {
                ctx.read();
            }
        });
    }

    private void preparePictureNALUnits(byte[] payload) {
        if (payload.length < 4) {
            throw new IllegalArgumentException("Video payload is shorter than a NAL length prefix");
        }
        if (readInt(payload, 0) == 1) {
            return;
        }

        int idx = 0;
        while (idx < payload.length) {
            if (payload.length - idx < 4) {
                throw new IllegalArgumentException("Video payload ends inside a NAL length prefix");
            }
            int naluSize = readInt(payload, idx);
            if (naluSize <= 0 || naluSize > payload.length - idx - 4) {
                throw new IllegalArgumentException("Video payload contains an invalid NAL length: " + naluSize);
            }
            payload[idx] = 0;
            payload[idx + 1] = 0;
            payload[idx + 2] = 0;
            payload[idx + 3] = 1;
            idx += naluSize + 4;
        }
    }

    private VideoStreamInfo detectedFormat(VideoCodecConfiguration configuration) {
        int width = 0;
        int height = 0;
        if (configuration.codec() == VideoStreamInfo.Codec.H264) {
            var format = H264SpsParser.parse(configuration.sequenceParameterSet());
            if (format != null) {
                width = format.width();
                height = format.height();
            }
        } else {
            var format = H265SpsParser.parse(configuration.sequenceParameterSet());
            if (format != null) {
                width = format.width();
                height = format.height();
            }
        }
        return new VideoStreamInfo(airPlay.getStreamConnectionID(), width, height, 0, configuration.codec());
    }

    private VideoStreamInfo updateMeasuredFps(long timestamp) {
        if (detectedFormat == null) {
            return null;
        }
        double frameSeconds = lastVideoTimestamp == Long.MIN_VALUE
                ? 0
                : fixedPointDeltaSeconds(lastVideoTimestamp, timestamp);
        if (lastVideoTimestamp != Long.MIN_VALUE && (frameSeconds <= 0 || frameSeconds > 1)) {
            videoFrameTimestamps.clear();
            lastFpsReportTimestamp = Long.MIN_VALUE;
        }
        lastVideoTimestamp = timestamp;
        videoFrameTimestamps.addLast(timestamp);

        while (videoFrameTimestamps.size() > 2
                && fixedPointDeltaSeconds(videoFrameTimestamps.getFirst(), timestamp) > FPS_WINDOW_SECONDS) {
            videoFrameTimestamps.removeFirst();
        }
        double elapsedSeconds = fixedPointDeltaSeconds(videoFrameTimestamps.getFirst(), timestamp);
        if (elapsedSeconds < FPS_REPORT_INTERVAL_SECONDS) {
            return null;
        }
        if (lastFpsReportTimestamp != Long.MIN_VALUE
                && fixedPointDeltaSeconds(lastFpsReportTimestamp, timestamp) < FPS_REPORT_INTERVAL_SECONDS) {
            return null;
        }
        double measuredFps = (videoFrameTimestamps.size() - 1) / elapsedSeconds;
        lastFpsReportTimestamp = timestamp;
        detectedFormat = new VideoStreamInfo(detectedFormat.getStreamConnectionId(),
                detectedFormat.getWidth(), detectedFormat.getHeight(), measuredFps, detectedFormat.getCodec());
        log.info("Measured sender video frame rate: {} fps", String.format("%.2f", measuredFps));
        return detectedFormat;
    }

    private double fixedPointDeltaSeconds(long first, long current) {
        long delta = current - first;
        return (double) (delta >> 32) + (double) (delta & 0xffff_ffffL) / 4_294_967_296.0;
    }

    private int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
