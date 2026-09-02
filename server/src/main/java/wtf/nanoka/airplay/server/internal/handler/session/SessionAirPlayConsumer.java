package wtf.nanoka.airplay.server.internal.handler.session;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;

final class SessionAirPlayConsumer implements AirPlayConsumer {

    enum StreamKind {
        VIDEO,
        AUDIO
    }

    private final SessionManager sessionManager;
    private final SessionManager.ControlSession controlSession;
    private final SessionManager.MediaLease lease;
    private final StreamKind streamKind;
    private final AirPlayConsumer delegate;
    private final SessionMediaCallbackGate callbackGate;

    SessionAirPlayConsumer(
            SessionManager sessionManager,
            SessionManager.ControlSession controlSession,
            SessionManager.MediaLease lease,
            StreamKind streamKind,
            AirPlayConsumer delegate,
            SessionMediaCallbackGate callbackGate) {
        this.sessionManager = sessionManager;
        this.controlSession = controlSession;
        this.lease = lease;
        this.streamKind = streamKind;
        this.delegate = delegate;
        this.callbackGate = callbackGate;
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        withLease(StreamKind.VIDEO, () -> delegate.onVideoFormat(videoStreamInfo));
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        withLease(StreamKind.VIDEO, () -> delegate.onVideoFormatDetected(videoStreamInfo));
    }

    @Override
    public void onVideo(byte[] bytes) {
        withLease(StreamKind.VIDEO, () -> delegate.onVideo(bytes));
    }

    @Override
    public void onVideo(byte[] bytes, long timestamp) {
        withLease(StreamKind.VIDEO, () -> delegate.onVideo(bytes, timestamp));
    }

    @Override
    public void onVideoSrcDisconnect() {
        withDisconnect(StreamKind.VIDEO, delegate::onVideoSrcDisconnect);
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        withLease(StreamKind.AUDIO, () -> delegate.onAudioFormat(audioStreamInfo));
    }

    @Override
    public void onAudio(byte[] bytes) {
        withLease(StreamKind.AUDIO, () -> delegate.onAudio(bytes));
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        withLease(StreamKind.AUDIO, () -> delegate.onAudio(bytes, timestamp, sequenceNumber));
    }

    @Override
    public void onAudioSrcDisconnect() {
        withDisconnect(StreamKind.AUDIO, delegate::onAudioSrcDisconnect);
    }

    private void withLease(StreamKind expectedKind, Runnable callback) {
        if (streamKind != expectedKind) {
            return;
        }
        callbackGate.dispatch(
                lease,
                this::ownsLease,
                () -> {
                    lease.markConnected();
                    callback.run();
                });
    }

    private void withDisconnect(StreamKind expectedKind, Runnable callback) {
        if (streamKind != expectedKind) {
            return;
        }
        callbackGate.dispatch(
                lease,
                this::ownsLease,
                () -> {
                    if (lease.markDisconnected()) {
                        callback.run();
                    }
                });
    }

    private boolean ownsLease() {
        return switch (streamKind) {
            case VIDEO -> sessionManager.ownsVideoLease(controlSession, lease);
            case AUDIO -> sessionManager.ownsAudioLease(controlSession, lease);
        };
    }
}
