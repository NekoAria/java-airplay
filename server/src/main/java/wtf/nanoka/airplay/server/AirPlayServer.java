package wtf.nanoka.airplay.server;

import wtf.nanoka.airplay.lib.AirPlayBonjour;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.internal.ControlServer;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final ControlServer controlServer;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        try {
            airPlayConfig.validate();
            log.info("AirPlay display capability: {}x{} @ {} fps (configured: {}x{} @ {})",
                    airPlayConfig.getResolvedWidth(), airPlayConfig.getResolvedHeight(), airPlayConfig.getResolvedFps(),
                    airPlayConfig.getWidth(), airPlayConfig.getHeight(), airPlayConfig.getFps());
            var identity = AirPlayIdentity.loadOrCreate(Path.of(airPlayConfig.getIdentityFile()));
            airPlayBonjour = new AirPlayBonjour(airPlayConfig.getServerName(), identity);
            controlServer = new ControlServer(airPlayConfig, airPlayConsumer, identity);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize persistent AirPlay identity", e);
        }
    }

    public void start() throws Exception {
        controlServer.start();
        airPlayBonjour.start(controlServer.getPort());
    }

    public void stop() {
        airPlayBonjour.stop();
        controlServer.stop();
    }
}
