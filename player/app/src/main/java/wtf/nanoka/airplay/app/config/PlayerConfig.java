package wtf.nanoka.airplay.app.config;

import wtf.nanoka.airplay.app.menu.SystemTrayMenu;
import wtf.nanoka.airplay.player.ffmpeg.FFmpegPlayer;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerDefault;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.h264dump.H264Dump;
import wtf.nanoka.airplay.player.vlc.VlcPlayer;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.AirPlayServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlayerProperties.class)
public class PlayerConfig {

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "gstreamer")
    public AirPlayConsumer gstreamer(AirPlayConfig airPlayConfig,
                                     PlayerProperties playerProperties) {
        playerProperties.validate();
        var gstreamer = playerProperties.getGstreamer();
        return gstreamer.isSwing() ? new GstPlayerSwing()
                : new GstPlayerDefault(airPlayConfig.getResolvedFps(), gstreamer.getVideoQueueDepth(),
                gstreamer.getVideoDecoder(), gstreamer.getGpuAdapter());
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "h264-dump", matchIfMissing = true)
    public AirPlayConsumer h264dump() throws Exception {
        return new H264Dump();
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "vlc")
    public AirPlayConsumer vlc() {
        return new VlcPlayer();
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "ffmpeg")
    public AirPlayConsumer ffmpeg() {
        return new FFmpegPlayer();
    }

    @Bean
    @ConfigurationProperties(prefix = "airplay")
    public AirPlayConfig airPlayConfig() {
        return new AirPlayConfig();
    }

    @Bean
    @ConditionalOnProperty(value = "player.tray.enabled", havingValue = "true")
    public SystemTrayMenu systemTrayMenu(ApplicationContext context) {
        return new SystemTrayMenu(context);
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig,
                                       AirPlayConsumer airPlayConsumer) {
        return new AirPlayServer(airPlayConfig, airPlayConsumer);
    }
}
