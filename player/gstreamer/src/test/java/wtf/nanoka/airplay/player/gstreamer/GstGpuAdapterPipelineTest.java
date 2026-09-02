package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(GstTestSupport.NATIVE_GSTREAMER_TAG)
class GstGpuAdapterPipelineTest {

    @Test
    void ignoresAdaptersWithoutRegisteredDecoderFactories() {
        GstTestSupport.initialize("GpuAdapterListTest");

        assertTrue(GstPlayerDefault.availableGpuAdapterIndices().contains("auto"));
        GstPlayerDefault.availableGpuAdapters().forEach(adapter -> {
            assertTrue(adapter.index() >= 0);
            assertTrue(!adapter.name().isBlank());
            assertTrue(GstPlayerDefault.availableGpuAdapterIndices().contains(Integer.toString(adapter.index())));
        });
    }

    @Test
    void usesDecoderFactoryRegisteredForSelectedDxgiAdapter() {
        GstTestSupport.initialize("GpuAdapterPipelineTest");
        GstTestSupport.assumeElementFactories(
                "appsrc", "videotestsrc", "x264enc", "h264parse", "queue", "clocksync",
                "d3d12videosink", "fakesink");

        List<GpuAdapter> capableAdapters = GpuAdapterScanner.scan().stream()
                .filter(GpuAdapter::isHardware)
                .filter(adapter -> GstTestSupport.isElementFactoryAvailable(
                        GstPlayerDefault.d3dDecoderForAdapter("d3d12", adapter.index())))
                .toList();
        Assumptions.assumeFalse(capableAdapters.isEmpty(), "No D3D12 H.264 decoder adapters found");

        GpuAdapter selected = capableAdapters.size() > 1 ? capableAdapters.get(1) : capableAdapters.get(0);
        String decoderName = GstPlayerDefault.d3dDecoderForAdapter("d3d12", selected.index());
        String configuredPipeline = GstPlayerDefault.createPipelineDescription(
                "d3d12h264dec", Integer.toString(selected.index()));
        assertTrue(configuredPipeline.contains("! " + decoderName + " !"));
        assertTrue(configuredPipeline.contains("d3d12videosink adapter=" + selected.index()));
        assertTrue(configuredPipeline.contains("error-on-closed=false"));
        assertTrue(configuredPipeline.indexOf("! " + decoderName + " !")
                < configuredPipeline.indexOf("! queue "));
        assertTrue(configuredPipeline.contains("sync=true"));

        String testPipeline = "videotestsrc num-buffers=10 "
                + "! video/x-raw,width=640,height=360,framerate=30/1 "
                + "! x264enc tune=zerolatency "
                + "! h264parse ! " + decoderName + " name=decoder ! fakesink sync=false";
        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch(testPipeline)) {
            try {
                pipeline.play();
                pipeline.getState(10, TimeUnit.SECONDS);
                Number adapterLuid = (Number) pipeline.getElementByName("decoder").get("adapter-luid");
                assertEquals(selected.adapterLuid(), adapterLuid.longValue());
            } finally {
                pipeline.stop();
            }
        }
    }
}
