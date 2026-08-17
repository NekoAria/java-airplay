package wtf.nanoka.airplay.server.internal.handler.audio;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class AudioHandler extends ChannelInboundHandlerAdapter {

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;

    private final ResendRequester resendRequester;
    private final int maxJitterPackets;
    private final Map<Integer, AudioPacket> buffer = new HashMap<>();

    private Integer nextSequenceNumber;
    private int lastResendSequence = -1;
    private long lastResendNanos;
    private long syncRtpTimestamp = -1;
    private long syncRemoteNtpTimestamp = -1;
    private final AudioStreamInfo.CompressionType compressionType;
    private boolean alacConfigSkipped;

    public AudioHandler(AirPlay airPlay, AirPlayConsumer dataConsumer) {
        this(airPlay, dataConsumer, (sequence, count) -> { }, 4, null);
    }

    public AudioHandler(AirPlay airPlay, AirPlayConsumer dataConsumer,
                        ResendRequester resendRequester, int maxJitterPackets,
                        AudioStreamInfo.CompressionType compressionType) {
        this.airPlay = airPlay;
        this.dataConsumer = dataConsumer;
        this.resendRequester = resendRequester;
        this.maxJitterPackets = Math.max(1, maxJitterPackets);
        this.compressionType = compressionType;
    }

    @Override
    public synchronized void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        accept((AudioPacket) msg);
    }

    public synchronized void accept(AudioPacket packet) throws Exception {
        int sequenceNumber = packet.getSequenceNumber() & 0xffff;
        if (nextSequenceNumber == null) {
            nextSequenceNumber = sequenceNumber;
        }

        int distance = forwardDistance(nextSequenceNumber, sequenceNumber);
        if (distance >= 0x8000) {
            return;
        }
        buffer.putIfAbsent(sequenceNumber, packet);

        if (distance > 0) {
            requestResend(nextSequenceNumber, Math.min(distance, maxJitterPackets));
        }
        if (distance >= maxJitterPackets) {
            int packetsToSkip = distance - maxJitterPackets + 1;
            nextSequenceNumber = (nextSequenceNumber + packetsToSkip) & 0xffff;
            int currentExpected = nextSequenceNumber;
            buffer.entrySet().removeIf(entry -> forwardDistance(currentExpected, entry.getKey()) >= 0x8000);
            log.debug("Skipping {} missing AirPlay audio packet(s)", packetsToSkip);
        }

        AudioPacket queued;
        while ((queued = buffer.remove(nextSequenceNumber)) != null) {
            if (!isCodecPlaceholder(queued)) {
                airPlay.decryptAudio(queued.getEncodedAudio(), queued.getEncodedAudioSize());
                dataConsumer.onAudio(Arrays.copyOf(queued.getEncodedAudio(), queued.getEncodedAudioSize()),
                        queued.getTimestamp(), queued.getSequenceNumber());
            }
            nextSequenceNumber = (nextSequenceNumber + 1) & 0xffff;
        }
    }

    private boolean isCodecPlaceholder(AudioPacket packet) {
        byte[] payload = packet.getEncodedAudio();
        if (compressionType == AudioStreamInfo.CompressionType.AAC_ELD && payload.length == 4) {
            return payload[0] == 0 && payload[1] == 0x68 && payload[2] == 0x34 && payload[3] == 0;
        }
        if (compressionType == AudioStreamInfo.CompressionType.ALAC && !alacConfigSkipped && payload.length == 32) {
            alacConfigSkipped = true;
            return true;
        }
        return false;
    }

    private void requestResend(int sequenceNumber, int count) {
        long now = System.nanoTime();
        if (lastResendSequence != sequenceNumber || now - lastResendNanos >= 5_000_000L) {
            resendRequester.request(sequenceNumber, count);
            lastResendSequence = sequenceNumber;
            lastResendNanos = now;
        }
    }

    private int forwardDistance(int from, int to) {
        return (to - from) & 0xffff;
    }

    public synchronized void updateSync(long rtpTimestamp, long remoteNtpTimestamp) {
        syncRtpTimestamp = rtpTimestamp;
        syncRemoteNtpTimestamp = remoteNtpTimestamp;
        log.debug("Updated AirPlay audio sync anchor: RTP {}, NTP {}", syncRtpTimestamp, syncRemoteNtpTimestamp);
    }

    @FunctionalInterface
    public interface ResendRequester {
        void request(int sequenceNumber, int count);
    }
}
