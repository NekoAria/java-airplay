package wtf.nanoka.airplay.server.internal.decoder;

import wtf.nanoka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class VideoDecoder extends ReplayingDecoder<VideoDecoder.DecoderState> {

    static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    public enum DecoderState {
        READ_HEADER,
        READ_PAYLOAD
    }

    public VideoDecoder() {
        super(DecoderState.READ_HEADER);
    }

    private int payloadSize;
    private short payloadType;
    private long timestamp;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state()) {
            case READ_HEADER:
                ByteBuf headerBuf = in.readSlice(128);
                long unsignedPayloadSize = headerBuf.readUnsignedIntLE();
                if (unsignedPayloadSize == 0) {
                    throw new CorruptedFrameException("Video payload must not be empty");
                }
                if (unsignedPayloadSize > MAX_PAYLOAD_SIZE) {
                    throw new TooLongFrameException("Video payload exceeds " + MAX_PAYLOAD_SIZE + " bytes");
                }
                payloadSize = (int) unsignedPayloadSize;
                payloadType = (short) (headerBuf.readUnsignedShortLE() & 0xff);
                headerBuf.skipBytes(2); // payload options
                timestamp = headerBuf.readLongLE();
                checkpoint(DecoderState.READ_PAYLOAD);
            case READ_PAYLOAD:
                if (payloadType == 0 || payloadType == 1) {
                    ByteBuf payloadBuf = in.readSlice(payloadSize);
                    byte[] payloadBytes = new byte[payloadSize];
                    payloadBuf.readBytes(payloadBytes);
                    checkpoint(DecoderState.READ_HEADER);
                    out.add(new VideoPacket(payloadType, payloadSize, timestamp, payloadBytes));
                } else {
                    log.debug("Video packet with type: {}, length: {} bytes is skipped", payloadType, payloadSize);
                    in.skipBytes(payloadSize);
                    checkpoint(DecoderState.READ_HEADER);
                }
                break;
            default:
                throw new Error("Shouldn't reach here.");
        }
    }
}
