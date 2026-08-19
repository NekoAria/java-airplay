package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
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
import io.netty.handler.flow.FlowControlHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class VideoServer {

    private final AirPlay airPlay;

    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private AirPlayConsumer airPlayConsumer;
    private volatile VideoConnection activeVideoConnection;
    private VideoStreamInfo pendingVideoFormat;
    private long serverGeneration;

    @Getter
    private int port;

    public synchronized void start(AirPlayConsumer airPlayConsumer) throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return;
        }
        this.airPlayConsumer = airPlayConsumer;
        long generation = ++serverGeneration;
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
                            VideoConnection connection = openVideoConnection(ch, generation);
                            if (connection == null) {
                                ch.close();
                                return;
                            }
                            ch.pipeline().addLast("videoDecoder", new VideoDecoder());
                            ch.pipeline().addLast("videoFlowControl", new FlowControlHandler());
                            ch.pipeline().addLast("videoHandler", new VideoHandler(
                                    airPlay, connection, true, connection::decryptVideo));
                            connection.applyPendingFormat();
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.AUTO_READ, false);
            channel = serverBootstrap.bind().sync().channel();

            log.info("AirPlay video server listening on port: {}",
                    port = ((InetSocketAddress) channel.localAddress()).getPort());
        } catch (InterruptedException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() {
        serverGeneration++;
        pendingVideoFormat = null;
        disconnectActiveVideoConnection();
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

    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        pendingVideoFormat = videoStreamInfo;
        if (activeVideoConnection != null) {
            activeVideoConnection.onVideoFormat(videoStreamInfo);
        }
    }

    private synchronized VideoConnection openVideoConnection(SocketChannel channel, long generation) {
        if (generation != serverGeneration || airPlayConsumer == null) {
            return null;
        }
        disconnectActiveVideoConnection();
        VideoConnection connection = new VideoConnection(airPlayConsumer, channel);
        activeVideoConnection = connection;
        return connection;
    }

    private synchronized void disconnectActiveVideoConnection() {
        VideoConnection connection = activeVideoConnection;
        activeVideoConnection = null;
        if (connection != null) {
            connection.invalidate();
            connection.closeChannel();
            notifyVideoDisconnect();
        }
    }

    private synchronized void videoConnectionInactive(VideoConnection connection) {
        if (activeVideoConnection != connection) {
            connection.invalidate();
            return;
        }
        activeVideoConnection = null;
        connection.invalidate();
        notifyVideoDisconnect();
    }

    private void notifyVideoDisconnect() {
        try {
            airPlayConsumer.onVideoSrcDisconnect();
        } catch (RuntimeException | LinkageError e) {
            log.warn("Video consumer disconnect failed: {}", e.getMessage(), e);
        }
    }

    private final class VideoConnection implements AirPlayConsumer {

        private final AirPlayConsumer delegate;
        private final SocketChannel channel;
        private final AtomicBoolean valid = new AtomicBoolean(true);

        private VideoConnection(AirPlayConsumer delegate, SocketChannel channel) {
            this.delegate = delegate;
            this.channel = channel;
        }

        private void applyPendingFormat() {
            withCurrent(delegate -> {
                if (pendingVideoFormat != null) {
                    delegate.onVideoFormat(pendingVideoFormat);
                }
            });
        }

        private void invalidate() {
            valid.set(false);
        }

        private void closeChannel() {
            channel.close();
        }

        private boolean decryptVideo(byte[] payload) throws Exception {
            synchronized (VideoServer.this) {
                if (!valid.get() || activeVideoConnection != this) {
                    return false;
                }
                airPlay.decryptVideo(payload);
                return true;
            }
        }

        private void withCurrent(Consumer<AirPlayConsumer> callback) {
            synchronized (VideoServer.this) {
                if (valid.get() && activeVideoConnection == this) {
                    callback.accept(delegate);
                }
            }
        }

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
            withCurrent(delegate -> delegate.onVideoFormat(videoStreamInfo));
        }

        @Override
        public void onVideo(byte[] bytes) {
            withCurrent(delegate -> delegate.onVideo(bytes));
        }

        @Override
        public void onVideo(byte[] bytes, long timestamp) {
            withCurrent(delegate -> delegate.onVideo(bytes, timestamp));
        }

        @Override
        public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
            withCurrent(delegate -> delegate.onVideoFormatDetected(videoStreamInfo));
        }

        @Override
        public void onVideoSrcDisconnect() {
            videoConnectionInactive(this);
        }

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        }

        @Override
        public void onAudio(byte[] bytes) {
        }

        @Override
        public void onAudioSrcDisconnect() {
        }
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
