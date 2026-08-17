package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.internal.handler.control.ControlHandler;
import wtf.nanoka.airplay.server.internal.handler.session.SessionManager;
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
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ControlServer {

    private final SessionManager sessionManager;

    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;
    private final AirPlayIdentity identity;

    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Getter
    private int port;

    public ControlServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer, AirPlayIdentity identity) {
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        this.identity = identity;
        sessionManager = new SessionManager(identity, airPlayConfig.getAudioJitterPackets());
    }

    public synchronized void start() throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return;
        }
        var serverBootstrap = new ServerBootstrap();
        bossGroup = eventLoopGroup(1);
        workerGroup = eventLoopGroup(0);
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE),
                                    new ControlHandler(sessionManager, airPlayConfig, airPlayConsumer, identity));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = serverBootstrap.bind().sync().channel();

            log.info("AirPlay control server listening on port: {}",
                    port = ((InetSocketAddress) channel.localAddress()).getPort());
        } catch (InterruptedException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() {
        sessionManager.stopAll();
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
        log.info("AirPlay control server stopped");
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
