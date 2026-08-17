package wtf.nanoka.airplay.lib;

public class VideoStreamInfo implements MediaStreamInfo {

    private final String streamConnectionId;
    private final int width;
    private final int height;
    private final double fps;

    public VideoStreamInfo(String streamConnectionId) {
        this(streamConnectionId, 0, 0, 0);
    }

    public VideoStreamInfo(String streamConnectionId, int width, int height, double fps) {
        this.streamConnectionId = streamConnectionId;
        this.width = width;
        this.height = height;
        this.fps = fps;
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

    @Override
    public String toString() {
        return "VideoStreamInfo{" +
                "streamConnectionId='" + streamConnectionId + '\'' +
                ", width=" + width +
                ", height=" + height +
                ", fps=" + fps +
                '}';
    }
}
