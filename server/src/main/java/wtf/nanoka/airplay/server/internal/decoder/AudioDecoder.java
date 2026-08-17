package wtf.nanoka.airplay.server.internal.decoder;

import wtf.nanoka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

public class AudioDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(decodePacket(msg));
    }

    public static AudioPacket decodePacket(ByteBuf msg) {
        if (msg.readableBytes() < 12) {
            throw new CorruptedFrameException("RTP audio packet is shorter than 12 bytes");
        }
        int flag = msg.readUnsignedByte();
        int type = msg.readUnsignedByte() & 0x7f;
        int seqNumber = msg.readUnsignedShort();
        long timestamp = msg.readUnsignedInt();
        long ssrc = msg.readUnsignedInt();
        byte[] encodedAudio = new byte[msg.readableBytes()];
        msg.readBytes(encodedAudio);

        AudioPacket audioPacket = AudioPacket.builder()
                .flag(flag)
                .type(type)
                .sequenceNumber(seqNumber)
                .timestamp(timestamp)
                .ssrc(ssrc)
                .available(true)
                .encodedAudio(encodedAudio)
                .encodedAudioSize(encodedAudio.length)
                .build();
        return audioPacket;
    }
}
