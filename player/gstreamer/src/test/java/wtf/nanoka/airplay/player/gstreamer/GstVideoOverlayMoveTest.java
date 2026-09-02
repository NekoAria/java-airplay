package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Native;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.message.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(GstTestSupport.NATIVE_GSTREAMER_TAG)
class GstVideoOverlayMoveTest {

    @Test
    void rebindsPlayingVideoSinkAfterCanvasMovesBetweenFrames() throws Exception {
        GstTestSupport.initialize("VideoOverlayMoveTest");
        GstTestSupport.assumeElementFactories("videotestsrc");
        String sink;
        if (GstTestSupport.isElementFactoryAvailable("d3d12videosink")) {
            sink = "d3d12videosink error-on-closed=false sync=false";
        } else {
            Assumptions.assumeTrue(
                    GstTestSupport.isElementFactoryAvailable("d3d11videosink"),
                    "No Windows VideoOverlay sink is available");
            sink = "d3d11videosink sync=false";
        }

        var first = new JFrame("First video host");
        var second = new JFrame("Second video host");
        var canvas = new Canvas();
        SwingUtilities.invokeAndWait(() -> {
            first.setLayout(new BorderLayout());
            first.add(canvas, BorderLayout.CENTER);
            first.setSize(640, 400);
            first.setVisible(true);
            second.setLayout(new BorderLayout());
            second.setSize(640, 400);
            second.setLocation(first.getX() + 80, first.getY() + 80);
            second.setVisible(true);
        });

        var overlay = new AtomicReference<VideoOverlay>();
        var pipelineError = new AtomicReference<String>();
        long initialHandle = Native.getComponentID(canvas);
        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch("videotestsrc is-live=true ! " + sink)) {
            try {
                pipeline.getBus().setSyncHandler((Message message) -> {
                    if (!VideoOverlay.isPrepareWindowHandleMessage(message)
                            || !(message.getSource() instanceof Element element)) {
                        return BusSyncReply.PASS;
                    }
                    VideoOverlay current = VideoOverlay.wrap(element);
                    current.setWindowHandle(initialHandle);
                    overlay.set(current);
                    return BusSyncReply.DROP;
                });
                pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> pipelineError.set(message));
                pipeline.play();
                for (int attempt = 0; attempt < 50 && overlay.get() == null; attempt++) {
                    Thread.sleep(100);
                }
                assertTrue(pipeline.isPlaying());
                assertTrue(overlay.get() != null);

                SwingUtilities.invokeAndWait(() -> {
                    first.remove(canvas);
                    second.add(canvas, BorderLayout.CENTER);
                    second.revalidate();
                    second.repaint();
                });
                long movedHandle = Native.getComponentID(canvas);
                overlay.get().setWindowHandle(movedHandle);
                overlay.get().expose();
                Thread.sleep(TimeUnit.SECONDS.toMillis(2));

                assertNull(pipelineError.get());
                assertTrue(pipeline.isPlaying());
            } finally {
                pipeline.getBus().clearSyncHandler();
                pipeline.stop();
                pipeline.getState(5, TimeUnit.SECONDS);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                first.dispose();
                second.dispose();
            });
        }
    }
}
