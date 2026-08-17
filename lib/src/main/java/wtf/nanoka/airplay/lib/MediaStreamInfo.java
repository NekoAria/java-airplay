package wtf.nanoka.airplay.lib;

public interface MediaStreamInfo {

    StreamType getStreamType();

    enum StreamType {
        AUDIO,
        VIDEO
    }
}
