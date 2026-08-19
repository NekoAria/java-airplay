package wtf.nanoka.airplay.lib;

public class VideoStreamInfo implements MediaStreamInfo {

    private final String streamConnectionId;
    private final int width;
    private final int height;
    private final double fps;
    private final Codec codec;

    public VideoStreamInfo(String streamConnectionId) {
        this(streamConnectionId, 0, 0, 0, Codec.UNKNOWN);
    }

    public VideoStreamInfo(String streamConnectionId, int width, int height, double fps) {
        this(streamConnectionId, width, height, fps, Codec.H264);
    }

    public VideoStreamInfo(String streamConnectionId, int width, int height, double fps, Codec codec) {
        this.streamConnectionId = streamConnectionId;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.codec = codec;
    }

    @Override
    public StreamType getStreamType() {
        return MediaStreamInfo.StreamType.VIDEO;
    }

    public String getStreamConnectionId() {
        return streamConnectionId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getFps() {
        return fps;
    }

    public Codec getCodec() {
        return codec;
    }

    @Override
    public String toString() {
        return "VideoStreamInfo{" +
                "streamConnectionId='" + streamConnectionId + '\'' +
                ", width=" + width +
                ", height=" + height +
                ", fps=" + fps +
                ", codec=" + codec +
                '}';
    }

    public enum Codec {
        UNKNOWN,
        H264,
        HEVC
    }
}
