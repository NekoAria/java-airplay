package wtf.nanoka.airplay.app.power;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.util.Objects;

/** Applies display-awake policy around a visual AirPlay consumer. */
public final class DisplayAwakeAirPlayConsumer implements AirPlayConsumer {

    private final AirPlayConsumer delegate;
    private final DisplaySleepPreventer displaySleepPreventer;
    private final Object videoStateLock = new Object();
    private final Object playlistOperationLock = new Object();
    private boolean mirroringSessionActive;
    private boolean mirroringFrameReceived;
    private boolean playlistPlaying;

    public DisplayAwakeAirPlayConsumer(AirPlayConsumer delegate,
                                       DisplaySleepPreventer displaySleepPreventer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.displaySleepPreventer = Objects.requireNonNull(displaySleepPreventer, "displaySleepPreventer");
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        synchronized (videoStateLock) {
            if (videoStreamInfo.getCodec() == VideoStreamInfo.Codec.UNKNOWN) {
                mirroringSessionActive = false;
                mirroringFrameReceived = false;
                updateDisplaySleepPolicy();
            } else {
                mirroringSessionActive = true;
            }
        }
        delegate.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        if (videoStreamInfo.getCodec() != VideoStreamInfo.Codec.UNKNOWN) {
            synchronized (videoStateLock) {
                mirroringSessionActive = true;
            }
        }
        delegate.onVideoFormatDetected(videoStreamInfo);
    }

    @Override
    public void onVideo(byte[] bytes) {
        markMirroringFrameReceived();
        delegate.onVideo(bytes);
    }

    @Override
    public void onVideo(byte[] bytes, long timestamp) {
        markMirroringFrameReceived();
        delegate.onVideo(bytes, timestamp);
    }

    @Override
    public void onVideoSrcDisconnect() {
        synchronized (videoStateLock) {
            mirroringSessionActive = false;
            mirroringFrameReceived = false;
            updateDisplaySleepPolicy();
        }
        delegate.onVideoSrcDisconnect();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        delegate.onAudioFormat(audioStreamInfo);
    }

    @Override
    public void onAudio(byte[] bytes) {
        delegate.onAudio(bytes);
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        delegate.onAudio(bytes, timestamp, sequenceNumber);
    }

    @Override
    public void onAudioSrcDisconnect() {
        delegate.onAudioSrcDisconnect();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        synchronized (playlistOperationLock) {
            delegate.onMediaPlaylist(playlistUri);
            setPlaylistPlaying(true);
        }
    }

    @Override
    public void onMediaPlaylistRemove() {
        synchronized (playlistOperationLock) {
            delegate.onMediaPlaylistRemove();
            setPlaylistPlaying(false);
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        synchronized (playlistOperationLock) {
            delegate.onMediaPlaylistPause();
            setPlaylistPlaying(false);
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        synchronized (playlistOperationLock) {
            delegate.onMediaPlaylistResume();
            setPlaylistPlaying(true);
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        return delegate.playbackInfo();
    }

    private void markMirroringFrameReceived() {
        synchronized (videoStateLock) {
            if (mirroringSessionActive) {
                mirroringFrameReceived = true;
                updateDisplaySleepPolicy();
            }
        }
    }

    private void setPlaylistPlaying(boolean playing) {
        synchronized (videoStateLock) {
            playlistPlaying = playing;
            updateDisplaySleepPolicy();
        }
    }

    private void updateDisplaySleepPolicy() {
        if (mirroringFrameReceived || playlistPlaying) {
            displaySleepPreventer.preventDisplaySleep();
        } else {
            displaySleepPreventer.allowDisplaySleep();
        }
    }
}
