package wtf.nanoka.airplay.server.internal;

import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.handler.control.ControlHandler;
import wtf.nanoka.airplay.server.internal.handler.session.SessionManager;
import wtf.nanoka.airplay.server.internal.handler.session.SessionMediaCoordinator;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
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
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ControlServer {

    private final SessionManager sessionManager;
    private final SessionMediaCoordinator mediaCoordinator;

    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;
    private final AirPlayIdentity identity;

    private final Object lifecycleMonitor = new Object();
    private final List<EventLoopGroup> retiringEventLoopGroups = new ArrayList<>();
    private LifecycleState lifecycleState = LifecycleState.STOPPED;
    private CompletableFuture<Void> lifecycleTransition = CompletableFuture.completedFuture(null);
    private boolean stopRequestedDuringStart;

    private Channel channel;
    private ChannelGroup childChannels;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Getter
    private volatile int port;

    public ControlServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer, AirPlayIdentity identity) {
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        this.identity = identity;
        sessionManager = new SessionManager(identity, airPlayConfig.getAudioJitterPackets());
        mediaCoordinator = new SessionMediaCoordinator(sessionManager, airPlayConsumer);
    }

    public void start() throws InterruptedException {
        CompletableFuture<Void> ownTransition;
        while (true) {
            CompletableFuture<Void> transitionToAwait;
            synchronized (lifecycleMonitor) {
                if (lifecycleState == LifecycleState.STARTED) {
                    return;
                }
                if (lifecycleState == LifecycleState.STOPPED) {
                    if (inRetiringEventLoopLocked()) {
                        throw new IllegalStateException(
                                "Cannot restart the AirPlay control server from its retiring event loop");
                    }
                    lifecycleState = LifecycleState.STARTING;
                    ownTransition = new CompletableFuture<>();
                    lifecycleTransition = ownTransition;
                    break;
                }
                if (lifecycleState == LifecycleState.STOPPING
                        && (mediaCoordinator.isInMediaCallback() || inRetiringEventLoopLocked())) {
                    throw new IllegalStateException(
                            "Cannot restart the AirPlay control server from a callback being stopped");
                }
                transitionToAwait = lifecycleTransition;
            }
            awaitTransition(transitionToAwait);
        }

        Channel startedChannel = null;
        ChannelGroup generationChannels = null;
        EventLoopGroup startedBossGroup = null;
        EventLoopGroup startedWorkerGroup = null;
        try {
            awaitRetiringEventLoops();
            mediaCoordinator.resume();

            generationChannels = new DefaultChannelGroup(
                    "airplay-control-" + System.nanoTime(), GlobalEventExecutor.INSTANCE, true);
            startedBossGroup = eventLoopGroup(1);
            startedWorkerGroup = eventLoopGroup(0);
            var serverBootstrap = new ServerBootstrap();
            ChannelGroup acceptedChannels = generationChannels;
            serverBootstrap
                    .group(startedBossGroup, startedWorkerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            acceptedChannels.add(ch);
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE),
                                    new ControlHandler(sessionManager, mediaCoordinator,
                                            airPlayConfig, airPlayConsumer, identity));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            startedChannel = serverBootstrap.bind().sync().channel();
            int startedPort = ((InetSocketAddress) startedChannel.localAddress()).getPort();
            boolean stopAfterStart;
            synchronized (lifecycleMonitor) {
                channel = startedChannel;
                childChannels = generationChannels;
                bossGroup = startedBossGroup;
                workerGroup = startedWorkerGroup;
                port = startedPort;
                lifecycleState = LifecycleState.STARTED;
                stopAfterStart = stopRequestedDuringStart;
                stopRequestedDuringStart = false;
                ownTransition.complete(null);
            }
            log.info("AirPlay control server listening on port: {}", startedPort);
            if (stopAfterStart) {
                stop();
            }
        } catch (InterruptedException | RuntimeException | Error failure) {
            var resources = new ServerResources(
                    startedChannel, generationChannels, startedBossGroup, startedWorkerGroup);
            Throwable cleanupFailure = cleanupFailedStart(resources);
            synchronized (lifecycleMonitor) {
                port = 0;
                lifecycleState = LifecycleState.STOPPED;
                stopRequestedDuringStart = false;
                ownTransition.complete(null);
            }
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void stop() {
        ServerResources resources = null;
        CompletableFuture<Void> ownTransition = null;
        boolean drainOnly = false;
        synchronized (lifecycleMonitor) {
            switch (lifecycleState) {
                case STOPPED -> {
                    if (inRetiringEventLoopLocked()) {
                        return;
                    }
                    drainOnly = true;
                }
                case STARTING -> {
                    stopRequestedDuringStart = true;
                    return;
                }
                case STOPPING -> {
                    return;
                }
                case STARTED -> {
                    lifecycleState = LifecycleState.STOPPING;
                    ownTransition = new CompletableFuture<>();
                    lifecycleTransition = ownTransition;
                    resources = new ServerResources(channel, childChannels, bossGroup, workerGroup);
                    channel = null;
                    childChannels = null;
                    bossGroup = null;
                    workerGroup = null;
                    port = 0;
                    registerRetiringEventLoopsLocked(resources);
                }
            }
        }

        if (drainOnly) {
            awaitRetiringEventLoops();
            return;
        }

        mediaCoordinator.pause();
        if (mediaCoordinator.isInMediaCallback()) {
            ServerResources asynchronousResources = resources;
            CompletableFuture<Void> asynchronousTransition = ownTransition;
            Thread.ofPlatform()
                    .daemon(true)
                    .name("airplay-control-stop")
                    .start(() -> {
                        try {
                            finishStop(asynchronousResources, asynchronousTransition);
                        } catch (RuntimeException | Error failure) {
                            log.error("Asynchronous AirPlay control stop failed", failure);
                        }
                    });
            return;
        }
        finishStop(resources, ownTransition);
    }

    private void finishStop(
            ServerResources resources,
            CompletableFuture<Void> ownTransition) {
        boolean calledFromControlEventLoop = inEventLoop(resources.bossGroup())
                || inEventLoop(resources.workerGroup());
        Throwable failure = stopResources(resources, calledFromControlEventLoop);

        synchronized (lifecycleMonitor) {
            lifecycleState = LifecycleState.STOPPED;
            ownTransition.complete(null);
        }
        log.info("AirPlay control server stopped");
        rethrowUnchecked(failure);
    }

    private Throwable cleanupFailedStart(ServerResources resources) {
        synchronized (lifecycleMonitor) {
            registerRetiringEventLoopsLocked(resources);
        }
        return stopResources(resources, false);
    }

    private Throwable stopResources(ServerResources resources, boolean calledFromControlEventLoop) {
        Throwable failure = null;
        try {
            closeChannels(resources, calledFromControlEventLoop);
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        try {
            shutDownEventLoops(resources);
        } catch (RuntimeException | Error error) {
            failure = appendFailure(failure, error);
        }
        try {
            mediaCoordinator.stopAll();
        } catch (RuntimeException | Error error) {
            failure = appendFailure(failure, error);
        }
        try {
            sessionManager.stopAll();
        } catch (RuntimeException | Error error) {
            failure = appendFailure(failure, error);
        }
        if (!calledFromControlEventLoop) {
            try {
                awaitRetiringEventLoops();
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private void closeChannels(ServerResources resources, boolean calledFromControlEventLoop) {
        if (resources.channel() != null) {
            var closeFuture = resources.channel().close();
            if (!calledFromControlEventLoop) {
                closeFuture.syncUninterruptibly();
            }
        }
        if (resources.childChannels() != null) {
            var closeFuture = resources.childChannels().close();
            if (!calledFromControlEventLoop) {
                closeFuture.awaitUninterruptibly();
            }
        }
    }

    private void shutDownEventLoops(ServerResources resources) {
        if (resources.bossGroup() != null) {
            resources.bossGroup().shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (resources.workerGroup() != null) {
            resources.workerGroup().shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    private void registerRetiringEventLoopsLocked(ServerResources resources) {
        if (resources.bossGroup() != null && !retiringEventLoopGroups.contains(resources.bossGroup())) {
            retiringEventLoopGroups.add(resources.bossGroup());
        }
        if (resources.workerGroup() != null && !retiringEventLoopGroups.contains(resources.workerGroup())) {
            retiringEventLoopGroups.add(resources.workerGroup());
        }
    }

    private void awaitRetiringEventLoops() {
        while (true) {
            EventLoopGroup eventLoopGroup;
            synchronized (lifecycleMonitor) {
                retiringEventLoopGroups.removeIf(group -> group.terminationFuture().isDone());
                if (retiringEventLoopGroups.isEmpty()) {
                    return;
                }
                eventLoopGroup = retiringEventLoopGroups.get(0);
                if (inEventLoop(eventLoopGroup)) {
                    throw new IllegalStateException(
                            "Cannot wait for an AirPlay control event loop from that event loop");
                }
            }
            eventLoopGroup.terminationFuture().syncUninterruptibly();
        }
    }

    private boolean inRetiringEventLoopLocked() {
        return retiringEventLoopGroups.stream().anyMatch(this::inEventLoop);
    }

    private boolean inEventLoop(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null) {
            return false;
        }
        for (EventExecutor eventExecutor : eventLoopGroup) {
            if (eventExecutor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    private void awaitTransition(CompletableFuture<Void> transition) throws InterruptedException {
        try {
            transition.get();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("AirPlay control lifecycle transition failed", impossible);
        }
    }

    private Throwable appendFailure(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private EventLoopGroup eventLoopGroup(int threads) {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(threads, EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    private enum LifecycleState {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING
    }

    private record ServerResources(
            Channel channel,
            ChannelGroup childChannels,
            EventLoopGroup bossGroup,
            EventLoopGroup workerGroup) {
    }
}
