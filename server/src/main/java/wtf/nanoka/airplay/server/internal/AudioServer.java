package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.decoder.AudioDecoder;
import wtf.nanoka.airplay.server.internal.handler.audio.AudioHandler;
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
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AudioServer {

    private final AirPlay airPlay;
    private final AudioHandler.ResendRequester resendRequester;
    private final int maxJitterPackets;

    private Channel channel;
    private EventLoopGroup workerGroup;
    private AirPlayConsumer airPlayConsumer;
    private volatile AudioHandler audioHandler;

    @Getter
    private int port;

    public AudioServer(AirPlay airPlay, AudioHandler.ResendRequester resendRequester, int maxJitterPackets) {
        this.airPlay = airPlay;
        this.resendRequester = resendRequester;
        this.maxJitterPackets = maxJitterPackets;
    }

    public synchronized void start(AirPlayConsumer airPlayConsumer, AudioStreamInfo streamInfo) throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return;
        }
        this.airPlayConsumer = airPlayConsumer;
        audioHandler = new AudioHandler(airPlay, airPlayConsumer, resendRequester, maxJitterPackets,
                streamInfo.getCompressionType());
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
                            ch.pipeline().addLast("audioDecoder", new DatagramPacketDecoder(new AudioDecoder()));
                            ch.pipeline().addLast("audioHandler", audioHandler);
                        }
                    });
            channel = bootstrap.bind().sync().channel();

            log.info("AirPlay audio server listening on port: {}",
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
        airPlayConsumer = null;
        audioHandler = null;
        log.info("AirPlay audio server stopped");
    }

    public void acceptResentPacket(io.netty.buffer.ByteBuf packet) {
        AudioHandler handler = audioHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.accept(AudioDecoder.decodePacket(packet));
        } catch (Exception e) {
            log.debug("Unable to process resent audio packet: {}", e.getMessage());
        }
    }

    public void updateSync(long rtpTimestamp, long remoteNtpTimestamp) {
        AudioHandler handler = audioHandler;
        if (handler != null) {
            handler.updateSync(rtpTimestamp, remoteNtpTimestamp);
        }
    }

    public synchronized boolean isRunning() {
        return channel != null && channel.isActive();
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
