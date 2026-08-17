package wtf.nanoka.airplay.player.gstreamer;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class GstPlayerDefault extends GstPlayer {

    private static final Pattern DEVICE_DECODER =
            Pattern.compile("d3d(11|12)h264device(\\d+)dec", Pattern.CASE_INSENSITIVE);

    public GstPlayerDefault() {
        this(60, 3, "auto", "auto");
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder) {
        this(fps, videoQueueDepth, videoDecoder, "auto");
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder, String gpuAdapter) {
        super(fps, videoQueueDepth, createPipelineDescription(videoDecoder, gpuAdapter));
    }

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch(createPipelineDescription("auto", "auto"));
    }

    static String createPipelineDescription(String configuredDecoder, String configuredAdapter) {
        DecoderSelection selection = selectDecoder(configuredDecoder, configuredAdapter);
        String sink = selectSink(selection);
        return "appsrc name=h264-src ! h264parse config-interval=-1 "
                + "! queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream "
                + "! " + selection.decoder() + " ! " + sink;
    }

    private static DecoderSelection selectDecoder(String configuredDecoder, String configuredAdapter) {
        String decoder = normalize(configuredDecoder, "auto");
        String adapterValue = normalize(configuredAdapter, "auto");
        Integer requestedIndex = parseAdapterIndex(adapterValue);
        List<GpuAdapter> adapters = GpuAdapterScanner.scan();
        logAdapters(adapters);

        if (!"auto".equalsIgnoreCase(decoder)) {
            D3dDecoder requestedD3d = parseD3dDecoder(decoder);
            if (requestedD3d != null) {
                return selectD3dDecoder(requestedD3d, requestedIndex, adapters);
            }
            if (requestedIndex != null) {
                throw new IllegalArgumentException("player.gstreamer.gpuAdapter is only supported with "
                        + "D3D12 or D3D11 decoders; selected decoder is " + decoder);
            }
            requireElement(decoder, "video decoder");
            log.info("Using configured GStreamer H.264 decoder: {}", decoder);
            return new DecoderSelection(decoder, null);
        }

        if (requestedIndex != null) {
            GpuAdapter adapter = requireAdapter(adapters, requestedIndex);
            for (String api : new String[]{"d3d12", "d3d11"}) {
                String candidate = d3dDecoderForAdapter(api, adapter.index());
                if (elementExists(candidate)) {
                    log.info("Selected {} for configured DXGI adapter {}", candidate, adapter.description());
                    return new DecoderSelection(candidate, adapter);
                }
            }
            throw new IllegalArgumentException("DXGI adapter " + requestedIndex
                    + " does not expose a GStreamer D3D12 or D3D11 H.264 decoder");
        }

        GpuAdapter d3d12Adapter = bestCapableAdapter(adapters, "d3d12");
        if (d3d12Adapter != null) {
            String candidate = d3dDecoderForAdapter("d3d12", d3d12Adapter.index());
            log.info("Automatically selected {} on {}", candidate, d3d12Adapter.description());
            return new DecoderSelection(candidate, d3d12Adapter);
        }
        if (adapters.isEmpty() && elementExists("d3d12h264dec")) {
            log.info("Automatically selected d3d12h264dec; DXGI enumeration was unavailable");
            return new DecoderSelection("d3d12h264dec", null);
        }
        if (elementExists("nvh264dec")) {
            log.info("Automatically selected GStreamer H.264 decoder: nvh264dec");
            return new DecoderSelection("nvh264dec", null);
        }

        GpuAdapter d3d11Adapter = bestCapableAdapter(adapters, "d3d11");
        if (d3d11Adapter != null) {
            String candidate = d3dDecoderForAdapter("d3d11", d3d11Adapter.index());
            log.info("Automatically selected {} on {}", candidate, d3d11Adapter.description());
            return new DecoderSelection(candidate, d3d11Adapter);
        }
        if (adapters.isEmpty() && elementExists("d3d11h264dec")) {
            log.info("Automatically selected d3d11h264dec; DXGI enumeration was unavailable");
            return new DecoderSelection("d3d11h264dec", null);
        }

        for (String candidate : new String[]{"vulkanh264dec", "vah264dec", "v4l2h264dec", "vtdec_hw"}) {
            if (elementExists(candidate)) {
                log.info("Automatically selected GStreamer H.264 decoder: {}", candidate);
                return new DecoderSelection(candidate, null);
            }
        }
        requireElement("avdec_h264", "software video decoder");
        log.info("No supported hardware H.264 decoder found; using avdec_h264");
        return new DecoderSelection("avdec_h264", null);
    }

    private static DecoderSelection selectD3dDecoder(D3dDecoder requested,
                                                       Integer configuredIndex,
                                                       List<GpuAdapter> adapters) {
        Integer requestedIndex = requested.adapterIndex();
        if (requestedIndex != null && configuredIndex != null && !requestedIndex.equals(configuredIndex)) {
            throw new IllegalArgumentException("Decoder " + requested.configuredName() + " targets DXGI adapter "
                    + requestedIndex + " but player.gstreamer.gpuAdapter=" + configuredIndex);
        }
        Integer selectedIndex = configuredIndex != null ? configuredIndex : requestedIndex;
        if (selectedIndex != null) {
            GpuAdapter adapter = requireAdapter(adapters, selectedIndex);
            String candidate = d3dDecoderForAdapter(requested.api(), adapter.index());
            requireElement(candidate, requested.api().toUpperCase() + " H.264 decoder for DXGI adapter " + selectedIndex);
            log.info("Using {} on configured DXGI adapter {}", candidate, adapter.description());
            return new DecoderSelection(candidate, adapter);
        }

        GpuAdapter adapter = bestCapableAdapter(adapters, requested.api());
        if (adapter != null) {
            String candidate = d3dDecoderForAdapter(requested.api(), adapter.index());
            log.info("Automatically selected {} on {}", candidate, adapter.description());
            return new DecoderSelection(candidate, adapter);
        }
        String baseDecoder = requested.api() + "h264dec";
        requireElement(baseDecoder, requested.api().toUpperCase() + " H.264 decoder");
        log.info("Using configured GStreamer H.264 decoder: {}", baseDecoder);
        return new DecoderSelection(baseDecoder, null);
    }

    private static String selectSink(DecoderSelection selection) {
        String decoder = selection.decoder();
        if (isD3dDecoder(decoder, "d3d12")) {
            return requireD3dSink("d3d12videosink", decoder, selection.adapter());
        }
        if (isD3dDecoder(decoder, "d3d11")) {
            return requireD3dSink("d3d11videosink", decoder, selection.adapter());
        }
        if ("nvh264dec".equals(decoder)) {
            requireElement("cudadownload", "NVDEC download element");
            return "cudadownload ! autovideosink sync=false";
        }
        if ("vulkanh264dec".equals(decoder)) {
            requireElement("vulkansink", "Vulkan video sink");
            return "vulkansink sync=false";
        }
        return "autovideosink sync=false";
    }

    private static String requireD3dSink(String sink, String decoder, GpuAdapter adapter) {
        requireElement(sink, "native video sink for " + decoder);
        String adapterProperty = adapter == null ? "" : " adapter=" + adapter.index();
        return sink + adapterProperty + " sync=false";
    }

    static String d3dDecoderForAdapter(String api, int adapterIndex) {
        if (adapterIndex < 0) {
            throw new IllegalArgumentException("DXGI adapter index must not be negative");
        }
        return adapterIndex == 0
                ? api + "h264dec"
                : api + "h264device" + adapterIndex + "dec";
    }

    private static GpuAdapter bestCapableAdapter(List<GpuAdapter> adapters, String api) {
        return adapters.stream()
                .filter(GpuAdapter::isHardware)
                .filter(adapter -> elementExists(d3dDecoderForAdapter(api, adapter.index())))
                .max(Comparator.comparingLong(GpuAdapter::dedicatedVideoMemory)
                        .thenComparingInt(adapter -> -adapter.index()))
                .orElse(null);
    }

    private static GpuAdapter requireAdapter(List<GpuAdapter> adapters, int index) {
        return adapters.stream()
                .filter(adapter -> adapter.index() == index)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DXGI GPU adapter " + index
                        + " was not found. Available adapters: " + availableAdapters(adapters)));
    }

    private static Integer parseAdapterIndex(String configuredAdapter) {
        if ("auto".equalsIgnoreCase(configuredAdapter)) {
            return null;
        }
        try {
            int index = Integer.parseInt(configuredAdapter);
            if (index < 0) {
                throw new NumberFormatException();
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("GPU adapter must be auto or a non-negative DXGI index", e);
        }
    }

    private static D3dDecoder parseD3dDecoder(String decoder) {
        String normalized = decoder.toLowerCase();
        if ("d3d12h264dec".equals(normalized)) {
            return new D3dDecoder("d3d12", null, decoder);
        }
        if ("d3d11h264dec".equals(normalized)) {
            return new D3dDecoder("d3d11", null, decoder);
        }
        Matcher matcher = DEVICE_DECODER.matcher(normalized);
        if (matcher.matches()) {
            return new D3dDecoder("d3d" + matcher.group(1), Integer.parseInt(matcher.group(2)), decoder);
        }
        return null;
    }

    private static boolean isD3dDecoder(String decoder, String api) {
        return decoder.startsWith(api + "h264") && decoder.endsWith("dec");
    }

    private static boolean elementExists(String element) {
        return ElementFactory.find(element) != null;
    }

    private static void requireElement(String element, String purpose) {
        if (!elementExists(element)) {
            throw new IllegalArgumentException("GStreamer " + purpose + " is not installed: " + element);
        }
    }

    private static void logAdapters(List<GpuAdapter> adapters) {
        if (!adapters.isEmpty()) {
            log.info("Detected DXGI GPU adapters: {}",
                    adapters.stream().map(GpuAdapter::description).collect(Collectors.joining(", ")));
        }
    }

    private static String availableAdapters(List<GpuAdapter> adapters) {
        if (adapters.isEmpty()) {
            return "none (DXGI enumeration unavailable)";
        }
        return adapters.stream().map(adapter -> Integer.toString(adapter.index())).collect(Collectors.joining(", "));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record DecoderSelection(String decoder, GpuAdapter adapter) {
    }

    private record D3dDecoder(String api, Integer adapterIndex, String configuredName) {
    }
}
