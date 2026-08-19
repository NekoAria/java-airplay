package wtf.nanoka.airplay.app;

import wtf.nanoka.airplay.server.AirPlayServer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import wtf.nanoka.airplay.app.config.UserSettingsController;
import wtf.nanoka.airplay.app.config.UserSettingsPropertySource;
import wtf.nanoka.airplay.app.config.RestartReadiness;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
@RequiredArgsConstructor
@SpringBootApplication
public class PlayerApp implements ApplicationRunner {

    private final AirPlayServer airPlayServer;
    private final Environment environment;

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PlayerApp.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .initializers(new UserSettingsPropertySource())
                .run(args);
        if (context.getEnvironment().getProperty("java-airplay.validation", Boolean.class, false)) {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!environment.getProperty("java-airplay.validation", Boolean.class, false)) {
            airPlayServer.start();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() throws IOException {
        if (!environment.getProperty("java-airplay.validation", Boolean.class, false)) {
            signalRestartReady();
        }
    }

    private void signalRestartReady() throws IOException {
        String readyFile = System.getenv(UserSettingsController.RESTART_READY_FILE_ENV);
        String token = System.getenv(UserSettingsController.RESTART_READY_TOKEN_ENV);
        if (readyFile == null || readyFile.isBlank() || token == null || token.isBlank()) {
            return;
        }
        RestartReadiness.signal(Path.of(readyFile), token, ProcessHandle.current().pid());
    }

    @PreDestroy
    private void preDestroy() {
        airPlayServer.stop();
    }
}
