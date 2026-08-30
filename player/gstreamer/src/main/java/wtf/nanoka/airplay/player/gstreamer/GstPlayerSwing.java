package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.message.Message;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.player.gstreamer.ui.VisionPlayerWindow;

@Slf4j
public class GstPlayerSwing extends GstPlayer {

    private final VisionPlayerWindow window;
    private volatile long videoWindowHandle;

    public GstPlayerSwing(int fps,
                          int videoQueueDepth,
                          String videoDecoder,
                          String gpuAdapter,
                          String renderMode,
                          boolean hevcEnabled,
                          WindowOptions options) {
        super(fps, videoQueueDepth,
                GstPlayerDefault.createPipelineDescription(
                        VideoStreamInfo.Codec.H264, videoDecoder, gpuAdapter, renderMode),
                hevcEnabled ? GstPlayerDefault.createPipelineDescription(
                        VideoStreamInfo.Codec.HEVC, videoDecoder, gpuAdapter, renderMode) : null);
        window = new VisionPlayerWindow(new VisionPlayerWindow.Config(
                options.receiverName(),
                options.advertisedWidth(),
                options.advertisedHeight(),
                fps,
                options.pairingRequired(),
                options.closeToTray(),
                options.settings(),
                options.settingsController(),
                this::restartVideoPipelines));
        videoWindowHandle = Native.getComponentID(window.videoCanvas());
        h264Pipeline.getBus().setSyncHandler(this::handleSyncMessage);
        if (hevcPipeline != null) {
            hevcPipeline.getBus().setSyncHandler(this::handleSyncMessage);
        }
    }

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch(GstPlayerDefault.createPipelineDescription(
                "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    @Override
    protected Pipeline createHevcPipeline() {
        return (Pipeline) Gst.parseLaunch(GstPlayerDefault.createPipelineDescription(
                VideoStreamInfo.Codec.HEVC, "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    private BusSyncReply handleSyncMessage(Message message) {
        if (!VideoOverlay.isPrepareWindowHandleMessage(message)) {
            return BusSyncReply.PASS;
        }
        if (!(message.getSource() instanceof Element sink)) {
            log.warn("GStreamer requested a video window handle from a non-element source");
            return BusSyncReply.PASS;
        }
        try {
            VideoOverlay.wrap(sink).setWindowHandle(videoWindowHandle);
            return BusSyncReply.DROP;
        } catch (RuntimeException error) {
            log.warn("Unable to attach the GStreamer video sink to the application window: {}",
                    error.getMessage());
            return BusSyncReply.DROP;
        }
    }

    /**
     * The video canvas moved between the main window and the detached video
     * window. The D3D sink keeps the old native surface, so the whole video
     * pipeline is stopped and restarted to re-bind the new window handle.
     */
    private void restartVideoPipelines() {
        videoWindowHandle = Native.getComponentID(window.videoCanvas());
        if (restartActiveVideoPipeline()) {
            log.info("Restarted the video pipeline for the moved video surface");
        }
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        window.showVideoFormatDetected(videoStreamInfo);
        super.onVideoFormatDetected(videoStreamInfo);
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        window.showVideo(videoStreamInfo);
        super.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoSrcDisconnect() {
        super.onVideoSrcDisconnect();
        window.showIdle();
    }

    public void showWindow() {
        window.showWindow();
    }

    public void showDetachedVideo() {
        window.showDetachedVideo();
    }

    public void toggleVideoFullscreen() {
        window.toggleVideoFullscreen();
    }

    public void toggleLanguage() {
        window.toggleLanguage();
    }

    public String languageLabel() {
        return window.languageLabel();
    }

    public String localized(String key) {
        return window.localized(key);
    }

    public void addLanguageChangeListener(Runnable listener) {
        window.addLanguageChangeListener(listener);
    }

    public void setCloseToTray(boolean closeToTray) {
        window.setCloseToTray(closeToTray);
    }

    @Override
    public void close() {
        h264Pipeline.getBus().clearSyncHandler();
        if (hevcPipeline != null) {
            hevcPipeline.getBus().clearSyncHandler();
        }
        super.close();
        window.close();
    }

    public record WindowOptions(
            String receiverName,
            int advertisedWidth,
            int advertisedHeight,
            boolean pairingRequired,
            boolean closeToTray,
            wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings settings,
            wtf.nanoka.airplay.player.gstreamer.ui.SettingsController settingsController) {
    }
}
