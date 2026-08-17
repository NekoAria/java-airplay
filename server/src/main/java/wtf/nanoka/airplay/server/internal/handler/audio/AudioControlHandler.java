package wtf.nanoka.airplay.server.internal.handler.audio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class AudioControlHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final Consumer<ByteBuf> resentPacketConsumer;
    private final SyncConsumer syncConsumer;

    public AudioControlHandler(Consumer<ByteBuf> resentPacketConsumer, SyncConsumer syncConsumer) {
        this.resentPacketConsumer = resentPacketConsumer;
        this.syncConsumer = syncConsumer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        ByteBuf content = msg.content();
        int contentLength = content.readableBytes();
        if (contentLength < 2) {
            return;
        }
        int readerIndex = content.readerIndex();
        int type = content.getUnsignedByte(readerIndex + 1) & ~0x80;
        log.debug("Got audio control packet, type: {}, length: {}", type, contentLength);
        if (type == 0x56 && contentLength >= 16) {
            resentPacketConsumer.accept(content.slice(readerIndex + 4, contentLength - 4));
        } else if (type == 0x54 && contentLength >= 20) {
            long rtpTimestamp = content.getUnsignedInt(readerIndex + 4);
            long remoteNtpTimestamp = content.getLong(readerIndex + 8);
            syncConsumer.accept(rtpTimestamp, remoteNtpTimestamp);
        }
    }

    @FunctionalInterface
    public interface SyncConsumer {
        void accept(long rtpTimestamp, long remoteNtpTimestamp);
    }
}
