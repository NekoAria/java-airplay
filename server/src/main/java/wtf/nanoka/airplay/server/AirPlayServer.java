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
    private final Object lifecycleMonitor = new Object();
    private LifecycleState lifecycleState = LifecycleState.STOPPED;
    private boolean stopRequestedDuringStart;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        try {
            airPlayConfig.validate();
            log.info("AirPlay display capability: {}x{} @ {} fps (configured: {}x{} @ {})",
                    airPlayConfig.getResolvedWidth(), airPlayConfig.getResolvedHeight(), airPlayConfig.getResolvedFps(),
                    airPlayConfig.getWidth(), airPlayConfig.getHeight(), airPlayConfig.getFps());
            var identity = AirPlayIdentity.loadOrCreate(Path.of(airPlayConfig.getIdentityFile()));
            airPlayBonjour = new AirPlayBonjour(
                    airPlayConfig.getServerName(), identity, airPlayConfig.isHevc());
            controlServer = new ControlServer(airPlayConfig, airPlayConsumer, identity);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize persistent AirPlay identity", e);
        }
    }

    public void start() throws Exception {
        synchronized (lifecycleMonitor) {
            if (lifecycleState == LifecycleState.STARTED
                    || lifecycleState == LifecycleState.STARTING) {
                return;
            }
            if (lifecycleState == LifecycleState.STOPPING) {
                throw new IllegalStateException("Cannot start AirPlay while it is stopping");
            }
            lifecycleState = LifecycleState.STARTING;
        }

        try {
            controlServer.start();
            airPlayBonjour.start(controlServer.getPort());
            boolean stopAfterStart;
            synchronized (lifecycleMonitor) {
                lifecycleState = LifecycleState.STARTED;
                stopAfterStart = stopRequestedDuringStart;
                stopRequestedDuringStart = false;
            }
            if (stopAfterStart) {
                stop();
            }
        } catch (Exception exception) {
            try {
                stopComponents();
            } catch (RuntimeException cleanupError) {
                exception.addSuppressed(cleanupError);
            }
            synchronized (lifecycleMonitor) {
                lifecycleState = LifecycleState.STOPPED;
                stopRequestedDuringStart = false;
            }
            throw exception;
        }
    }

    public void stop() {
        synchronized (lifecycleMonitor) {
            if (lifecycleState == LifecycleState.STOPPED
                    || lifecycleState == LifecycleState.STOPPING) {
                return;
            }
            if (lifecycleState == LifecycleState.STARTING) {
                stopRequestedDuringStart = true;
                return;
            }
            lifecycleState = LifecycleState.STOPPING;
        }

        try {
            stopComponents();
        } finally {
            synchronized (lifecycleMonitor) {
                lifecycleState = LifecycleState.STOPPED;
            }
        }
    }

    private void stopComponents() {
        RuntimeException failure = null;
        try {
            airPlayBonjour.stop();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            controlServer.stop();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private enum LifecycleState {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING
    }
}
