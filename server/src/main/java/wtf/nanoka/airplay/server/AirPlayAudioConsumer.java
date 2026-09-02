package wtf.nanoka.airplay.server;

import wtf.nanoka.airplay.lib.AudioStreamInfo;

/**
 * Receives one AirPlay audio stream while preserving its RTP timing metadata.
 */
public interface AirPlayAudioConsumer {

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    default void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        onAudio(bytes);
    }

    void onAudioSrcDisconnect();
}
