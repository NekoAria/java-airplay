package wtf.nanoka.airplay.client.control;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ControlHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    private final BlockingQueue<FullHttpResponse> responseQueue = new LinkedBlockingQueue<>(1);

    private ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        log.info("Control client connected");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Control client disconnected");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
        msg.retain();
        if (!responseQueue.offer(msg)) {
            msg.release();
            log.warn("Dropping an unexpected AirPlay control response");
        }
    }

    public void send(FullHttpRequest request) {
        ctx.writeAndFlush(request);
    }

    public FullHttpResponse receive() throws InterruptedException {
        FullHttpResponse response = responseQueue.poll(10, TimeUnit.SECONDS);
        if (response == null) {
            throw new IllegalStateException("Timed out waiting for an AirPlay control response");
        }
        return response;
    }
}
