package wtf.nanoka.airplay.client.video;

import wtf.nanoka.airplay.client.crypto.FairPlayVideoEncryptor;
import wtf.nanoka.airplay.client.video.source.GstTestSource;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class VideoClient extends ChannelInboundHandlerAdapter implements Runnable {

    private final String address;
    private final int port;
    private final FairPlayVideoEncryptor encryptor;

    private GstTestSource gstTestSource;
    private ChannelHandlerContext ctx;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile Throwable startFailure;

    public VideoClient(String address, int port, FairPlayVideoEncryptor encryptor) throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        new Thread(this).start();
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out connecting the AirPlay video client");
        }
        if (startFailure != null) {
            throw new IllegalStateException("Unable to connect the AirPlay video client", startFailure);
        }
    }

    @Override
    public void run() {
        var workerGroup = eventLoopGroup();
        var bootstrap = new Bootstrap();

        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(socketChannelClass());
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.remoteAddress(address, port);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast("video handler", VideoClient.this);
                }
            });

            var channelFuture = bootstrap.connect().sync();
            log.info("Video client started");

            started.countDown();

            channelFuture.channel().closeFuture().sync();
        } catch (Throwable e) {
            startFailure = e;
            started.countDown();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else {
                log.error("AirPlay video client failed: {}", e.getMessage());
            }
        } finally {
            log.info("Video client stopped");
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        log.info("Video client connected");
        gstTestSource = new GstTestSource(this::send);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Video client disconnected");
    }

    private void send(byte[] bytes) {
        ByteBuf header = Unpooled.buffer(128, 128);
        header.writeIntLE(bytes.length);
        header.writeShortLE(0);
        header.writerIndex(header.capacity());
        ctx.write(header);
        try {
            encryptor.encrypt(bytes);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes));
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    private Class<? extends SocketChannel> socketChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
