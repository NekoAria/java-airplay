package wtf.nanoka.airplay.player.gstreamer;

/**
 * One adapter returned by DXGI. The index is the native DXGI index used by
 * the GStreamer D3D11/D3D12 sinks; it is not a Task Manager display number.
 */
public record GpuAdapter(int index,
                         String name,
                         int vendorId,
                         long dedicatedVideoMemory,
                         long adapterLuid,
                         int flags) {

    private static final int DXGI_ADAPTER_FLAG_SOFTWARE = 0x2;

    public boolean isSoftware() {
        return (flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0;
    }

    public boolean isHardware() {
        return !isSoftware();
    }

    public String description() {
        return String.format("[%d] %s (vendor=0x%04x, VRAM=%s, LUID=%d%s)",
                index,
                name,
                vendorId,
                formatMemory(dedicatedVideoMemory),
                adapterLuid,
                isSoftware() ? ", software" : "");
    }

    private static String formatMemory(long bytes) {
        if (bytes <= 0) {
            return "unknown";
        }
        long gibibytes = bytes / (1024L * 1024L * 1024L);
        if (gibibytes > 0) {
            return gibibytes + " GiB";
        }
        return (bytes / (1024L * 1024L)) + " MiB";
    }
}
