package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.decoder.VideoDecoder;
import wtf.nanoka.airplay.server.internal.handler.video.VideoHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class VideoServer {

    private final AirPlay airPlay;

    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private AirPlayConsumer airPlayConsumer;

    @Getter
    private int port;

    public synchronized void start(AirPlayConsumer airPlayConsumer) throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return;
        }
        this.airPlayConsumer = airPlayConsumer;
        var serverBootstrap = new ServerBootstrap();
        bossGroup = eventLoopGroup(1);
        workerGroup = eventLoopGroup(1);
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast("videoDecoder", new VideoDecoder());
                            ch.pipeline().addLast("videoHandler", new VideoHandler(airPlay, airPlayConsumer));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = serverBootstrap.bind().sync().channel();

            log.info("AirPlay video server listening on port: {}",
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
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            workerGroup = null;
        }
        airPlayConsumer = null;
        log.info("AirPlay video server stopped");
    }

    public synchronized boolean isRunning() {
        return channel != null && channel.isActive();
    }

    private EventLoopGroup eventLoopGroup(int threads) {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(threads, EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
