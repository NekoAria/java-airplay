package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.server.internal.handler.audio.AudioControlHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class AudioControlServer {

    private Channel channel;
    private EventLoopGroup workerGroup;
    private int port;
    private volatile InetSocketAddress remoteAddress;
    private final AtomicInteger controlSequence = new AtomicInteger();
    private Consumer<ByteBuf> resentPacketConsumer = packet -> { };
    private AudioControlHandler.SyncConsumer syncConsumer = (rtp, ntp) -> { };

    public synchronized void start(InetAddress remoteAddress, int remotePort) throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return;
        }
        if (remotePort < 0 || remotePort > 65535) {
            throw new IllegalArgumentException("Invalid remote audio control port: " + remotePort);
        }
        this.remoteAddress = remotePort == 0 ? null : new InetSocketAddress(remoteAddress, remotePort);
        var bootstrap = new Bootstrap();
        workerGroup = eventLoopGroup();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioControlHandler",
                                    new AudioControlHandler(resentPacketConsumer, syncConsumer));
                        }
                    });

            channel = bootstrap.bind().sync().channel();

            log.info("AirPlay audio control server listening on port: {}",
                    port = ((InetSocketAddress) channel.localAddress()).getPort());
        } catch (InterruptedException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            workerGroup = null;
        }
        log.info("AirPlay audio control server stopped");
        remoteAddress = null;
    }

    public void requestResend(int sequenceNumber, int count) {
        Channel activeChannel = channel;
        InetSocketAddress target = remoteAddress;
        if (activeChannel == null || !activeChannel.isActive() || target == null || count <= 0) {
            return;
        }
        ByteBuf request = activeChannel.alloc().buffer(8, 8);
        request.writeByte(0x80);
        request.writeByte(0xd5);
        request.writeShort(controlSequence.getAndIncrement() & 0xffff);
        request.writeShort(sequenceNumber & 0xffff);
        request.writeShort(count & 0xffff);
        activeChannel.writeAndFlush(new DatagramPacket(request, target));
    }

    public void setResentPacketConsumer(Consumer<ByteBuf> resentPacketConsumer) {
        this.resentPacketConsumer = resentPacketConsumer;
    }

    public void setSyncConsumer(AudioControlHandler.SyncConsumer syncConsumer) {
        this.syncConsumer = syncConsumer;
    }

    public int getPort() {
        return port;
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}
