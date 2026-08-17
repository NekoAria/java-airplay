package wtf.nanoka.airplay.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "player")
public class PlayerProperties {

    private String implementation = "gstreamer";
    private final Tray tray = new Tray();
    private final Gstreamer gstreamer = new Gstreamer();

    public void validate() {
        if (implementation == null || implementation.isBlank()) {
            throw new IllegalArgumentException("player.implementation must not be blank");
        }
        if (gstreamer.videoDecoder == null || gstreamer.videoDecoder.isBlank()) {
            throw new IllegalArgumentException("player.gstreamer.videoDecoder must not be blank");
        }
        if (gstreamer.gpuAdapter == null || gstreamer.gpuAdapter.isBlank()) {
            throw new IllegalArgumentException("player.gstreamer.gpuAdapter must be auto or a non-negative GPU index");
        }
        if (!"auto".equalsIgnoreCase(gstreamer.gpuAdapter)) {
            try {
                if (Integer.parseInt(gstreamer.gpuAdapter) < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("player.gstreamer.gpuAdapter must be auto or a non-negative GPU index", e);
            }
        }
        if (gstreamer.videoQueueDepth < 1 || gstreamer.videoQueueDepth > 16) {
            throw new IllegalArgumentException("player.gstreamer.videoQueueDepth must be between 1 and 16");
        }
    }

    @Data
    public static class Tray {
        private boolean enabled = true;
    }

    @Data
    public static class Gstreamer {
        private boolean swing;
        private String videoDecoder = "auto";
        private String gpuAdapter = "auto";
        private int videoQueueDepth = 2;
    }
}
