package wtf.nanoka.airplay.client.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ControlClient implements Runnable {

    private final String address;
    private final int port;

    private int cseqIdx;

    private ControlHandler controlHandler;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile Throwable startFailure;

    public ControlClient(String address, int port) throws InterruptedException {
        this.address = address;
        this.port = port;
        new Thread(this).start();
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out connecting the AirPlay control client");
        }
        if (startFailure != null) {
            throw new IllegalStateException("Unable to connect the AirPlay control client", startFailure);
        }
    }

    @Override
    public void run() {
        var workerGroup = eventLoopGroup();
        var bootstrap = new Bootstrap();

        controlHandler = new ControlHandler();

        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(socketChannelClass());
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.remoteAddress(address, port);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast("encoder", new RtspEncoder());
                    p.addLast("decoder", new RtspDecoder());
                    p.addLast("logger", new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE));
                    p.addLast("aggregator", new HttpObjectAggregator(64 * 1024));
                    p.addLast("control handler", controlHandler);
                }
            });

            var channelFuture = bootstrap.connect().sync();
            log.info("Control client started");

            started.countDown();

            channelFuture.channel().closeFuture().sync();
        } catch (Throwable e) {
            startFailure = e;
            started.countDown();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else {
                log.error("AirPlay control client failed: {}", e.getMessage());
            }
        } finally {
            log.info("Control client stopped");
            workerGroup.shutdownGracefully();
        }
    }

    public NSDictionary requestInfo() throws InterruptedException, PropertyListFormatException, IOException {
        FullHttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.GET, "/info");

        FullHttpResponse response = exchange(request);
        try {
            return (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(response.content()));
        } finally {
            response.release();
        }
    }

    public byte[] pairSetup() throws InterruptedException {
        FullHttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.POST, "/pair-setup");

        FullHttpResponse response = exchange(request);
        try {
            byte[] responseBytes = new byte[response.content().readableBytes()];
            response.content().readBytes(responseBytes);
            return responseBytes;
        } finally {
            response.release();
        }
    }

    public byte[] pairVerify(byte[] requestBytes) throws InterruptedException {
        FullHttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.POST, "/pair-verify");
        request.content().writeBytes(requestBytes);

        FullHttpResponse response = exchange(request);
        try {
            byte[] responseBytes = new byte[response.content().readableBytes()];
            response.content().readBytes(responseBytes);
            return responseBytes;
        } finally {
            response.release();
        }
    }

    public byte[] fpSetup(byte[] requestBytes) throws InterruptedException {
        FullHttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.POST, "/fp-setup");
        request.content().writeBytes(requestBytes);

        FullHttpResponse response = exchange(request);
        try {
            byte[] responseBytes = new byte[response.content().readableBytes()];
            response.content().readBytes(responseBytes);
            return responseBytes;
        } finally {
            response.release();
        }
    }

    public NSDictionary rtspSetup(NSDictionary setup) throws InterruptedException, IOException, PropertyListFormatException {
        FullHttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, RtspMethods.SETUP, "rtsp://bla-bla");
        request.content().writeBytes(BinaryPropertyListWriter.writeToArray(setup));

        FullHttpResponse response = exchange(request);
        try {
            if (response.content().readableBytes() > 0) {
                return (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(response.content()));
            }
            return null;
        } finally {
            response.release();
        }
    }

    private FullHttpResponse exchange(FullHttpRequest request) throws InterruptedException {
        request.headers().add(RtspHeaderNames.CSEQ, ++cseqIdx);
        request.headers().add("DACP-ID", "184F380D0A5B7139");
        request.headers().add("Active-Remote", "1589992423");
        request.headers().add(RtspHeaderNames.USER_AGENT, "AirPlay/670.6.2");
        request.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().add(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());

        controlHandler.send(request);
        return controlHandler.receive();
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
