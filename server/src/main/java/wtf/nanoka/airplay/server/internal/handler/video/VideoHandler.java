package wtf.nanoka.airplay.server.internal.handler.video;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.VideoPacket;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class VideoHandler extends ChannelInboundHandlerAdapter {

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;
    private VideoStreamInfo detectedFormat;
    private long firstVideoTimestamp = Long.MIN_VALUE;
    private long lastVideoTimestamp = Long.MIN_VALUE;
    private int measuredFrames;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        VideoPacket packet = (VideoPacket) msg;
        try {
            if (packet.getPayloadType() == 0) {
                airPlay.decryptVideo(packet.getPayload());
                preparePictureNALUnits(packet.getPayload());
                updateMeasuredFps(packet.getTimestamp());
                dataConsumer.onVideo(packet.getPayload(), packet.getTimestamp());
            } else if (packet.getPayloadType() == 1) {
                byte[] spsPps = prepareSpsPpsNALUnits(packet.getPayload());
                var format = H264SpsParser.parse(extractSps(packet.getPayload()));
                if (format != null) {
                    detectedFormat = new VideoStreamInfo(
                            airPlay.getStreamConnectionID(), format.width(), format.height(), format.fps());
                    dataConsumer.onVideoFormatDetected(detectedFormat);
                    log.info("Detected sender video capability: {}x{}", format.width(), format.height());
                }
                dataConsumer.onVideo(spsPps, packet.getTimestamp());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
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

    private byte[] prepareSpsPpsNALUnits(byte[] payload) {
        if (payload.length < 11) {
            throw new IllegalArgumentException("Video codec configuration is too short");
        }
        int offset = 6;
        int spsLen = readUnsignedShort(payload, offset);
        offset += 2;
        if (spsLen == 0 || spsLen > payload.length - offset) {
            throw new IllegalArgumentException("Invalid SPS length: " + spsLen);
        }
        byte[] sequenceParameterSet = new byte[spsLen];
        System.arraycopy(payload, offset, sequenceParameterSet, 0, spsLen);
        offset += spsLen;
        if (payload.length - offset < 3) {
            throw new IllegalArgumentException("Video codec configuration has no PPS");
        }
        offset++; // pps count
        int ppsLen = readUnsignedShort(payload, offset);
        offset += 2;
        if (ppsLen == 0 || ppsLen > payload.length - offset) {
            throw new IllegalArgumentException("Invalid PPS length: " + ppsLen);
        }
        byte[] pictureParameterSet = new byte[ppsLen];
        System.arraycopy(payload, offset, pictureParameterSet, 0, ppsLen);

        int spsPpsLen = spsLen + ppsLen + 8;
        log.info("SPS PPS length: {}", spsPpsLen);
        byte[] spsPps = new byte[spsPpsLen];
        spsPps[0] = 0;
        spsPps[1] = 0;
        spsPps[2] = 0;
        spsPps[3] = 1;
        System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen);
        spsPps[spsLen + 4] = 0;
        spsPps[spsLen + 5] = 0;
        spsPps[spsLen + 6] = 0;
        spsPps[spsLen + 7] = 1;
        System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen);

        return spsPps;
    }

    private byte[] extractSps(byte[] payload) {
        if (payload.length < 8) {
            throw new IllegalArgumentException("Video codec configuration is too short");
        }
        int offset = 6;
        int spsLength = readUnsignedShort(payload, offset);
        offset += 2;
        if (spsLength <= 0 || spsLength > payload.length - offset) {
            throw new IllegalArgumentException("Invalid SPS length: " + spsLength);
        }
        byte[] sps = new byte[spsLength];
        System.arraycopy(payload, offset, sps, 0, spsLength);
        return sps;
    }

    private void updateMeasuredFps(long timestamp) {
        if (detectedFormat == null) {
            return;
        }
        if (firstVideoTimestamp == Long.MIN_VALUE) {
            firstVideoTimestamp = timestamp;
            lastVideoTimestamp = timestamp;
            return;
        }
        double frameSeconds = fixedPointDeltaSeconds(lastVideoTimestamp, timestamp);
        lastVideoTimestamp = timestamp;
        if (frameSeconds <= 0 || frameSeconds > 1) {
            return;
        }
        measuredFrames++;
        if (measuredFrames < 10 || measuredFrames % 10 != 0) {
            return;
        }
        double elapsedSeconds = fixedPointDeltaSeconds(firstVideoTimestamp, timestamp);
        if (elapsedSeconds <= 0) {
            return;
        }
        double measuredFps = measuredFrames / elapsedSeconds;
        detectedFormat = new VideoStreamInfo(detectedFormat.getStreamConnectionId(),
                detectedFormat.getWidth(), detectedFormat.getHeight(), measuredFps);
        dataConsumer.onVideoFormatDetected(detectedFormat);
        log.info("Measured sender video frame rate: {} fps", String.format("%.2f", measuredFps));
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

    private int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }
}
