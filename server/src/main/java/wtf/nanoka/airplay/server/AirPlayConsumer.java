package wtf.nanoka.airplay.server;

import wtf.nanoka.airplay.lib.VideoStreamInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public interface AirPlayConsumer extends AirPlayAudioConsumer {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    default void onVideo(byte[] bytes, long timestamp) {
        onVideo(bytes);
    }

    default void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
    }

    void onVideoSrcDisconnect();

    // HLS stuff, youtube
    default void onMediaPlaylist(String playlistUri) {
    }

    default void onMediaPlaylistRemove() {
    }

    default void onMediaPlaylistPause() {
    }

    default void onMediaPlaylistResume() {
    }

    default PlaybackInfo playbackInfo() {
        return new PlaybackInfo(0, 0);
    }

    record PlaybackInfo(double duration, double position) {
    }
}
