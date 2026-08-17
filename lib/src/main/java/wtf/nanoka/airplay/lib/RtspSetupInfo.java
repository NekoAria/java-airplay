package wtf.nanoka.airplay.lib;

import java.util.Optional;

public record RtspSetupInfo(Optional<MediaStreamInfo> mediaStreamInfo,
                            boolean keySetup,
                            int timingPort,
                            String timingProtocol) {
}
