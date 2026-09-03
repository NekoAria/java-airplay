package wtf.nanoka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.handler.session.Session;
import wtf.nanoka.airplay.server.internal.handler.session.SessionManager;
import wtf.nanoka.airplay.server.internal.handler.session.SessionMediaCoordinator;
import wtf.nanoka.airplay.server.internal.handler.util.PropertyListUtil;
import io.lindstrom.m3u8.model.*;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.MultivariantPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class ControlHandler extends SimpleChannelInboundHandler<FullHttpMessage> {

    private final SessionManager sessionManager;
    private final SessionMediaCoordinator mediaCoordinator;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;
    private final AirPlayIdentity identity;
    private String rtspSessionId;
    private final Set<String> retainedHttpSessionIds = new HashSet<>();
    private boolean reverseConnection;
    private boolean peerPaired;
    private SessionManager.ControlSession controlSession;
    private AirPlay.PendingRtspSetup pendingKeySetup;

    private static final HttpResponseStatus CLIENT_AUTHENTICATION_FAILURE =
            new HttpResponseStatus(470, "Client Authentication Failure");

    public ControlHandler(
            SessionManager sessionManager,
            SessionMediaCoordinator mediaCoordinator,
            AirPlayConfig airPlayConfig,
            AirPlayConsumer airPlayConsumer,
            AirPlayIdentity identity) {
        this.sessionManager = sessionManager;
        this.mediaCoordinator = mediaCoordinator;
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        this.identity = identity;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpMessage msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            if (RtspVersions.RTSP_1_0.equals(request.protocolVersion())) {
                if (rejectRtspSessionRebind(ctx, request)) {
                    return;
                }
                if (HttpMethod.GET.equals(request.method()) && "/info".equals(request.uri())) {
                    handleGetInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/pair-setup".equals(request.uri())) {
                    handlePairSetup(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/pair-verify".equals(request.uri())) {
                    handlePairVerify(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/fp-setup".equals(request.uri())) {
                    handleFairPlaySetup(ctx, request);
                } else if (RtspMethods.SETUP.equals(request.method())) {
                    handleRtspSetup(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/feedback".equals(request.uri())) {
                    handleRtspFeedback(ctx, request);
                } else if (RtspMethods.GET_PARAMETER.equals(request.method())) {
                    handleRtspGetParameter(ctx, request);
                } else if (RtspMethods.RECORD.equals(request.method())) {
                    handleRtspRecord(ctx, request);
                } else if (RtspMethods.SET_PARAMETER.equals(request.method())) {
                    handleRtspSetParameter(ctx, request);
                } else if ("FLUSH".equals(request.method().toString())) {
                    handleRtspFlush(ctx, request);
                } else if (RtspMethods.TEARDOWN.equals(request.method())) {
                    handleRtspTeardown(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && request.uri().equals("/audioMode")) {
                    handleRtspAudioMode(ctx, request);
                } else {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = createRtspResponse(request);
                    response.setStatus(HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            } else if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
                var decoder = new QueryStringDecoder(request.uri());
                if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/server-info")) {
                    handleGetServerInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/fp-setup")) {
                    sendNotImplemented(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/fp-setup2")) {
                    sendNotImplemented(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/reverse")) {
                    handleReverse(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/play")) {
                    handlePlay(ctx, request);
                } else if (HttpMethod.PUT.equals(request.method()) && decoder.path().equals("/setProperty")) {
                    handleSetProperty(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/rate")) {
                    handleRate(ctx, request);
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/playback-info")) {
                    handlePlaybackInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/action")) {
                    handleAction(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/getProperty")) {
                    handleGetProperty(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/scrub")) {
                    sendNotImplemented(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/stop")) {
                    handleStop(ctx, request);
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().startsWith("/playlist")) {
                    handleGetPlaylist(ctx, request);
                } else {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            }
            else {
                sendResponse(ctx, request, new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.HTTP_VERSION_NOT_SUPPORTED));
            }
        } else if (msg instanceof FullHttpResponse response) {
            // reverse connection response
        } else {
            log.error("Unknown control message type: {}", msg);
        }
    }

    /**
     * Resolves session by the request headers:<br/>
     * {@code Active-Remote} for RTSP<br/>
     * {@code X-Apple-Session-ID} for HTTP
     * <p>
     * The first RTSP request that resolves a session binds this connection to
     * one control generation. A later request with a different non-blank session
     * ID is rejected before dispatch.
     *
     * @param request incoming request
     * @return active session
     */
    private Session resolveSession(ChannelHandlerContext ctx, FullHttpRequest request) {
        var sessionId = sessionId(ctx, request);
        if (!RtspVersions.RTSP_1_0.equals(request.protocolVersion())) {
            return sessionManager.getSession(sessionId);
        }

        if (controlSession == null) {
            rtspSessionId = sessionId;
            controlSession = sessionManager.openControlSession(sessionId, ctx::close);
        }
        return controlSession.getSession();
    }

    private boolean rejectRtspSessionRebind(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (controlSession == null) {
            return false;
        }
        var requestedSessionId = request.headers().get("Active-Remote");
        var boundSessionId = controlSession.getSession().getId();
        if (requestedSessionId == null
                || requestedSessionId.isBlank()
                || requestedSessionId.equals(boundSessionId)) {
            return false;
        }

        log.warn("Rejecting RTSP control channel session change from {} to {}",
                boundSessionId, requestedSessionId);
        var response = createRtspResponse(request);
        response.setStatus(HttpResponseStatus.BAD_REQUEST);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        return true;
    }

    private Session resolveHttpSession(ChannelHandlerContext ctx, FullHttpRequest request) {
        var sessionId = sessionId(ctx, request);
        var session = sessionManager.findSession(sessionId);
        boolean existingSession = session != null;
        var peerAddress = peerAddress(ctx);
        boolean pairedPeer = sessionManager.isPeerPaired(peerAddress);
        if (session == null && airPlayConfig.isRequirePairing() && !pairedPeer) {
            return null;
        }
        if (session == null) {
            session = sessionManager.getSession(sessionId);
        }
        if (pairedPeer && !sessionManager.authorizeHttpSession(session, peerAddress)) {
            if (!existingSession) {
                sessionManager.removeSession(sessionId);
            }
            return null;
        }
        return retainHttpSession(session) ? session : null;
    }

    private boolean retainHttpSession(Session session) {
        if (retainedHttpSessionIds.add(session.getId())
                && !sessionManager.retainHttpSession(session)) {
            retainedHttpSessionIds.remove(session.getId());
            return false;
        }
        return true;
    }

    private InetAddress peerAddress(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress remote
                ? remote.getAddress() : null;
    }

    private String sessionId(ChannelHandlerContext ctx, FullHttpRequest request) {
        String headerName = RtspVersions.RTSP_1_0.equals(request.protocolVersion())
                ? "Active-Remote"
                : "X-Apple-Session-ID";
        String sessionId = request.headers().get(headerName);
        return sessionId == null || sessionId.isBlank()
                ? "channel:" + ctx.channel().id().asLongText()
                : sessionId;
    }

    private void handleGetInfo(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var info = PropertyListUtil.prepareInfoResponse(airPlayConfig, identity, requestsTxtAirPlay(request));
        var response = createRtspResponse(request);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-apple-binary-plist");
        response.content().writeBytes(info);
        sendResponse(ctx, request, response);
    }

    private boolean requestsTxtAirPlay(FullHttpRequest request) {
        if (!request.content().isReadable()) {
            return false;
        }
        try {
            var readerIndex = request.content().readerIndex();
            var requestInfo = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content(), false));
            request.content().readerIndex(readerIndex);
            return requestInfo.containsKey("qualifier") && requestInfo.get("qualifier").toXMLPropertyList().contains("txtAirPlay");
        } catch (Exception e) {
            request.content().resetReaderIndex();
            return false;
        }
    }

    private void handlePairSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        var pairing = mediaCoordinator.runControlOperation(controlSession, () -> {
            session.getAirPlay().pairSetup(new ByteBufOutputStream(response.content()));
            return true;
        });
        if (pairing.isEmpty()) {
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
        sendResponse(ctx, request, response);
    }

    private void handlePairVerify(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        boolean finishingVerification = request.content().isReadable()
                && request.content().getUnsignedByte(request.content().readerIndex()) == 0;
        var verification = mediaCoordinator.runControlOperation(controlSession, () -> {
            session.getAirPlay().pairVerify(new ByteBufInputStream(request.content()),
                    new ByteBufOutputStream(response.content()));
            boolean verified = session.getAirPlay().isPairVerified();
            if (finishingVerification && verified && !peerPaired
                    && ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
                sessionManager.markPeerPaired(remote.getAddress());
                peerPaired = true;
            }
            return verified;
        });
        if (verification.isEmpty()) {
            response.content().clear();
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
        } else if (finishingVerification && !verification.get()) {
            response.setStatus(CLIENT_AUTHENTICATION_FAILURE);
        }
        sendResponse(ctx, request, response);
    }

    private void handleFairPlaySetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (!isAuthorized(session)) {
            response.setStatus(CLIENT_AUTHENTICATION_FAILURE);
            sendResponse(ctx, request, response);
            return;
        }
        var fairPlaySetup = mediaCoordinator.runControlOperation(controlSession, () -> {
            session.getAirPlay().fairPlaySetup(new ByteBufInputStream(request.content()),
                    new ByteBufOutputStream(response.content()));
            return true;
        });
        if (fairPlaySetup.isEmpty()) {
            response.content().clear();
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
        sendResponse(ctx, request, response);
    }

    /*private void handleFairPlaySetup2(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }*/

    private void handleRtspSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (!isAuthorized(session)) {
            response.setStatus(CLIENT_AUTHENTICATION_FAILURE);
            sendResponse(ctx, request, response);
            return;
        }
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-apple-binary-plist");
        if (!sessionManager.isControlSessionUsable(controlSession, session)) {
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
            sendResponse(ctx, request, response);
            return;
        }
        var pendingSetup = session.getAirPlay().prepareRtspSetup(new ByteBufInputStream(request.content()));
        var setupInfo = pendingSetup.info();
        if (setupInfo.keySetup()) {
            if (!"NTP".equalsIgnoreCase(setupInfo.timingProtocol()) || setupInfo.timingPort() == 0) {
                response.setStatus(HttpResponseStatus.NOT_IMPLEMENTED);
                sendResponse(ctx, request, response);
                return;
            }
            var timingSetup = mediaCoordinator.setupTiming(controlSession, () -> {
                var remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
                session.getTimingServer().start(remoteAddress.getAddress(), setupInfo.timingPort());
                var timingResponse = PropertyListUtil.prepareSetupTimingResponse(
                        session.getTimingServer().getPort());
                pendingKeySetup = pendingSetup;
                return timingResponse;
            });
            if (timingSetup.isEmpty()) {
                response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
                sendResponse(ctx, request, response);
                return;
            }
            response.content().writeBytes(timingSetup.get());
        }
        var mediaStreamInfo = setupInfo.mediaStreamInfo();
        if (mediaStreamInfo.isPresent()) {
            var streamInfo = mediaStreamInfo.get();
            var activeControl = controlSession;
            try {
                switch (streamInfo.getStreamType()) {
                    case AUDIO -> {
                        var audioStreamInfo = (AudioStreamInfo) streamInfo;
                        var setup = mediaCoordinator.setupAudio(activeControl, sessionConsumer -> {
                            var remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
                            session.getAudioControlServer().start(
                                    remoteAddress.getAddress(), audioStreamInfo.getControlPort());
                            session.getAudioServer().start(sessionConsumer, audioStreamInfo);
                            sessionConsumer.onAudioFormat(audioStreamInfo);
                            return PropertyListUtil.prepareSetupAudioResponse(
                                    session.getAudioServer().getPort(),
                                    session.getAudioControlServer().getPort());
                        }, () -> commitPendingSetups(pendingSetup));
                        if (setup.isPresent()) {
                            response.content().writeBytes(setup.get());
                        } else {
                            response.content().clear();
                            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
                        }
                    }
                    case VIDEO -> {
                        var videoStreamInfo = (VideoStreamInfo) streamInfo;
                        var setup = mediaCoordinator.setupVideo(activeControl, sessionConsumer -> {
                            session.getVideoServer().start(sessionConsumer);
                            session.getVideoServer().onVideoFormat(videoStreamInfo);
                            return PropertyListUtil.prepareSetupVideoResponse(session.getVideoServer().getPort());
                        }, () -> commitPendingSetups(pendingSetup));
                        if (setup.isPresent()) {
                            response.content().writeBytes(setup.get());
                        } else {
                            response.content().clear();
                            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
                        }
                    }
                }
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Unable to set up AirPlay media for session {}: {}",
                        session.getId(), exception.getMessage(), exception);
                response.content().clear();
                response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
            }
        }
        sendResponse(ctx, request, response);
    }

    private void commitPendingSetups(AirPlay.PendingRtspSetup mediaSetup) {
        if (pendingKeySetup != null) {
            pendingKeySetup.commit();
        }
        mediaSetup.commit();
        pendingKeySetup = null;
    }

    private boolean isAuthorized(Session session) {
        return !airPlayConfig.isRequirePairing() || session.getAirPlay().isPairVerified();
    }

    private boolean rejectUnauthorized(ChannelHandlerContext ctx, FullHttpRequest request,
                                       Session session, FullHttpResponse response) {
        boolean rtspRequest = RtspVersions.RTSP_1_0.equals(request.protocolVersion());
        if (isAuthorizedRequest(ctx, request, session, rtspRequest)) {
            return false;
        }
        if (response == null) {
            response = rtspRequest
                    ? createRtspResponse(request)
                    : new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.OK);
        }
        response.setStatus(CLIENT_AUTHENTICATION_FAILURE);
        sendResponse(ctx, request, response);
        return true;
    }

    private boolean isAuthorizedRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            Session session,
            boolean rtspRequest) {
        if (session == null) {
            return false;
        }
        if (isAuthorized(session)) {
            return true;
        }
        if (rtspRequest) {
            return false;
        }

        InetAddress address = peerAddress(ctx);
        if (session.isHttpAuthorized(address)) {
            return true;
        }
        return request.uri().startsWith("/playlist")
                && address != null
                && address.isLoopbackAddress()
                && session.hasHttpAuthorization();
    }

    private void handleRtspFeedback(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        sendResponse(ctx, request, response);
    }

    private void handleRtspGetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        // TODO get requested param and respond accordingly
        byte[] content = "volume: 0.000000\r\n".getBytes(StandardCharsets.US_ASCII);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        response.content().writeBytes(content);
        sendResponse(ctx, request, response);
    }

    private void handleRtspRecord(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        response.headers().add("Audio-Latency", "11025");
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        sendResponse(ctx, request, response);
    }

    private void handleRtspSetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        // TODO get requested param and respond accordingly
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        sendResponse(ctx, request, response);
    }

    private void handleRtspFlush(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        sendResponse(ctx, request, response);
    }

    private void handleRtspTeardown(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        var activeControl = controlSession;
        if (activeControl == null || !sessionManager.isControlSessionUsable(activeControl, session)) {
            sendResponse(ctx, request, response);
            return;
        }
        var mediaStreamInfo = session.getAirPlay().rtspTeardown(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO -> mediaCoordinator.disconnectAudio(activeControl);
                case VIDEO -> mediaCoordinator.disconnectVideo(activeControl);
            }
        } else {
            mediaCoordinator.disconnectAll(activeControl);
        }
        sendResponse(ctx, request, response);
    }

    private void handleRtspAudioMode(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(ctx, request);
        var response = createRtspResponse(request);
        if (rejectUnauthorized(ctx, request, session, response)) {
            return;
        }
        sendResponse(ctx, request, response);
    }

    private void handleStop(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        airPlayConsumer.onMediaPlaylistRemove();
        sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    }

    private void handleGetServerInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var serverInfo = PropertyListUtil.prepareServerInfoResponse();
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        response.content().writeBytes(serverInfo);
        sendResponse(ctx, request, response);
    }

    private void handleReverse(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var purpose = request.headers().get("X-Apple-Purpose");
        if (purpose == null || purpose.isBlank()) {
            sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.setStatus(HttpResponseStatus.SWITCHING_PROTOCOLS);
        response.headers().add(HttpHeaderNames.UPGRADE, request.headers().get(HttpHeaderNames.UPGRADE));
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        sendResponse(ctx, request, response);

        ctx.pipeline().remove(RtspDecoder.class);
        ctx.pipeline().remove(RtspEncoder.class);
        ctx.pipeline().addFirst(new HttpClientCodec());
        reverseConnection = true;
        session.getReverseContexts().put(purpose, ctx);
    }

    private void handlePlay(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.debug("Request content:\n{}", play.toXMLPropertyList());

        if (!play.containsKey("clientProcName") || !play.containsKey("Content-Location")) {
            sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST));
            return;
        }
        var clientProcName = play.get("clientProcName").toJavaObject(String.class);
        if ("YouTube".equals(clientProcName)) {
            var playlistUri = play.get("Content-Location").toJavaObject(String.class);
            final String playlistUriLocal;
            try {
                playlistUriLocal = playlistUriToLocal(playlistUri, playlistBaseUrl(ctx), session.getId());
            } catch (IllegalArgumentException e) {
                log.warn("Rejecting invalid media playlist URI: {}", e.getMessage());
                sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_REQUEST));
                return;
            }

            // TODO Create MediaPlaylist record with UUID
            airPlayConsumer.onMediaPlaylist(playlistUriLocal);

            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            sendResponse(ctx, request, response);
        } else {
            log.error("Client proc name [{}] is not supported!", clientProcName);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED);
            sendResponse(ctx, request, response);
        }
    }

    private void handleSetProperty(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var decoder = new QueryStringDecoder(request.uri());
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.debug("Request content:\n{}", play.toXMLPropertyList());

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleRate(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var decoder = new QueryStringDecoder(request.uri());
        var rate = (int) Double.parseDouble(decoder.parameters().get("value").get(0));

        if (rate == 0) {
            airPlayConsumer.onMediaPlaylistPause();
        } else {
            airPlayConsumer.onMediaPlaylistResume();
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handlePlaybackInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        var playbackInfo = PropertyListUtil.preparePlaybackInfoResponse(airPlayConsumer.playbackInfo());
        response.content().writeBytes(playbackInfo);
        sendResponse(ctx, request, response);
    }

    private void handleAction(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        var action = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.debug("Request content:\n{}", action.toXMLPropertyList());

        var type = action.get("type").toJavaObject(String.class);
        if ("unhandledURLResponse".equals(type)) {
            var params = (NSDictionary) action.get("params");
            var fcupResponseURL = params.get("FCUP_Response_URL").toJavaObject(String.class);
            var fcupResponseBase64 = ((NSData) (params.get("FCUP_Response_Data"))).getBase64EncodedData();
            var fcupResponse = new String(Base64.getDecoder().decode(fcupResponseBase64));
            if (session.getPlaylistRequestContexts().containsKey(fcupResponseURL)) {
                if (fcupResponseURL.contains("master.m3u8")) {
                    var context = session.getPlaylistRequestContexts().get(fcupResponseURL);
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    response.content().writeCharSequence(multivariantPlaylistToLocalUrls(fcupResponse, playlistBaseUrl(ctx), session.getId()), StandardCharsets.UTF_8);
                    HttpUtil.setContentLength(response, response.content().readableBytes());
                    context.writeAndFlush(response);
                    session.getPlaylistRequestContexts().remove(fcupResponseURL);
                } else if (fcupResponseURL.contains("mediadata.m3u8")) {
                    var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
                    var mediaPlaylist = parser.readPlaylist(fcupResponse);

                    var condensedUrl = mediaPlaylist.comments().stream()
                            .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                            .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                            .flatMap(attributes -> Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))").matcher(attributes).results())
                            .collect(Collectors.toMap(matcher -> matcher.group(1), matcher -> matcher.group(2) != null ? matcher.group(2) : matcher.group(3)));

                    if (!condensedUrl.isEmpty()) {
                        mediaPlaylist = MediaPlaylist.builder()
                                .from(mediaPlaylist)
                                .mediaSegments(mediaPlaylist.mediaSegments().stream()
                                        .map(segment -> {
                                            var prefix = condensedUrl.get("PREFIX");
                                            var paramNames = condensedUrl.get("PARAMS").split(",");
                                            var paramValues = segment.uri().replaceFirst(prefix, "").split("/");
                                            var paramResult = new StringBuilder();
                                            for (int i = 0; i < paramNames.length; i++) {
                                                paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                                            }
                                            return MediaSegment.builder().from(segment).uri(condensedUrl.get("BASE-URI") + paramResult).build();
                                        })
                                        .toList())
                                .build();
                    }

                    var context = session.getPlaylistRequestContexts().get(fcupResponseURL);
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    response.content().writeCharSequence(parser.writePlaylistAsString(mediaPlaylist), StandardCharsets.UTF_8);
                    HttpUtil.setContentLength(response, response.content().readableBytes());
                    context.writeAndFlush(response);
                    session.getPlaylistRequestContexts().remove(fcupResponseURL);
                }
            }
        } else if ("playlistRemove".equals(type)) {
            /*<plist version="1.0">
            <dict>
            	<key>type</key>
            	<string>playlistRemove</string>
            	<key>params</key>
            	<dict>
            		<key>item</key>
            		<dict>
            			<key>uuid</key>
            			<string>59F93E62-4E79-4A8F-A55A-D7DA65247AF1</string>
            		</dict>
            	</dict>
            </dict>
            </plist>*/
            airPlayConsumer.onMediaPlaylistRemove();
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetProperty(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveHttpSession(ctx, request);
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        // TODO get requested param and respond accordingly
        var decoder = new QueryStringDecoder(request.uri());
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetPlaylist(ChannelHandlerContext ctx, FullHttpRequest request) {
        var playlistUriRemote = playlistPathToRemote(request.uri());
        var decoder = new QueryStringDecoder(request.uri());
        var sessionValues = decoder.parameters().get("session");
        if (sessionValues == null || sessionValues.isEmpty() || sessionValues.get(0).isBlank()) {
            sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST));
            return;
        }
        var session = sessionManager.findSession(sessionValues.get(0));
        if (rejectUnauthorized(ctx, request, session, null)) {
            return;
        }
        if (!retainHttpSession(session)) {
            sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.SERVICE_UNAVAILABLE));
            return;
        }
        if (!session.getReverseContexts().containsKey("event")) {
            sendResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.SERVICE_UNAVAILABLE));
            return;
        }
        session.getPlaylistRequestContexts().put(playlistUriRemote, ctx);
        sendEventRequest(session, playlistUriRemote);
    }

    static String playlistUriToLocal(String playlistUri, String baseUrl, String sessionId) {
        final URI remoteUri;
        try {
            remoteUri = URI.create(playlistUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("playlist URI is malformed", e);
        }
        if (!"mlhls".equalsIgnoreCase(remoteUri.getScheme())
                || !"localhost".equalsIgnoreCase(remoteUri.getHost())
                || remoteUri.getRawPath() == null
                || remoteUri.getRawFragment() != null) {
            throw new IllegalArgumentException("only mlhls://localhost playlist URIs are supported");
        }

        String suffix = remoteUri.getRawPath();
        if (remoteUri.getRawQuery() != null) {
            suffix += "?" + remoteUri.getRawQuery();
        }
        var playlistUriLocal = baseUrl + suffix;
        var queryEncoder = new QueryStringEncoder(playlistUriLocal);
        queryEncoder.addParam("session", sessionId);
        return queryEncoder.toString();
    }

    private String playlistPathToRemote(String playlistPath) {
        var playlistUriLocal = "mlhls://localhost" + playlistPath.replace("/playlist", "");
        return playlistUriLocal.split("\\?")[0]; // remove query
    }

    private String playlistBaseUrl(ChannelHandlerContext ctx) {
        var port = ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort();
        return String.format("http://localhost:%s/playlist", port);
    }

    private String multivariantPlaylistToLocalUrls(
            String multivariantPlaylist, String baseUrl, String sessionId) throws PlaylistParserException {
        var parser = new MultivariantPlaylistParser();
        var playlist = parser.readPlaylist(multivariantPlaylist);

        playlist = MultivariantPlaylist.builder().from(playlist)
                .alternativeRenditions(playlist.alternativeRenditions().stream()
                        .map(rendition -> AlternativeRendition.builder().from(rendition)
                                .uri(playlistUriToLocal(rendition.uri().get(), baseUrl, sessionId)).build()).toList())
                .variants(playlist.variants().stream()
                        .map(variant -> Variant.builder().from(variant)
                                .uri(playlistUriToLocal(variant.uri(), baseUrl, sessionId)).build()).toList())
                .build();

        return parser.writePlaylistAsString(playlist);
    }

    private DefaultFullHttpResponse createRtspResponse(FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        response.headers().clear();

        var cSeq = request.headers().get(RtspHeaderNames.CSEQ);
        if (cSeq != null) {
            response.headers().add(RtspHeaderNames.CSEQ, cSeq);
            response.headers().add(RtspHeaderNames.SERVER, "AirTunes/220.68");
        }

        return response;
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void sendNotImplemented(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendResponse(ctx, request, new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.NOT_IMPLEMENTED));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        var disconnectedControl = controlSession;
        controlSession = null;
        try {
            if (disconnectedControl != null) {
                mediaCoordinator.controlDisconnected(disconnectedControl);
            }
            sessionManager.removeContexts(ctx);
            retainedHttpSessionIds.forEach(sessionManager::releaseHttpSession);
            retainedHttpSessionIds.clear();
            if (peerPaired) {
                sessionManager.unmarkPeerPaired(peerAddress(ctx));
            }
            sessionManager.removeSession("channel:" + ctx.channel().id().asLongText());
            if (!reverseConnection && rtspSessionId != null
                    && (disconnectedControl == null
                    || !rtspSessionId.equals(disconnectedControl.getSession().getId()))) {
                sessionManager.removeSession(rtspSessionId);
            }
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("AirPlay control connection failed: {}", cause.getMessage());
        ctx.close();
    }

    private void sendEventRequest(Session session, String listUri) {
        var requestContent = PropertyListUtil.prepareEventRequest(session.getId(), listUri);

        DefaultFullHttpRequest event = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event");
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, requestContent.length);
        event.headers().add("X-Apple-Session-ID", session.getId());
        event.content().writeBytes(requestContent);

        var eventContext = session.getReverseContexts().get("event");
        if (eventContext != null && eventContext.channel().isActive()) {
            eventContext.writeAndFlush(event);
        } else {
            event.release();
        }
    }
}
