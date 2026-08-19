package wtf.nanoka.airplay.app.menu;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.util.Objects;

@Slf4j
public class SystemTrayMenu {

    private TrayIcon trayIcon;

    public SystemTrayMenu(ApplicationContext context, Runnable showWindow, Runnable trayReady,
                          Runnable trayUnavailable) {
        if (!SystemTray.isSupported()) {
            log.warn("System tray is not supported in this desktop session");
            if (trayUnavailable != null) {
                trayUnavailable.run();
            }
            return;
        }

        var popup = new PopupMenu();
        if (showWindow != null) {
            var open = new MenuItem("Open Java AirPlay");
            open.addActionListener(event -> showWindow.run());
            popup.add(open);
            popup.addSeparator();
        }
        var quit = new MenuItem("Quit");
        quit.addActionListener(event -> {
            close();
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        });
        popup.add(quit);

        var imageUrl = Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png"));
        trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(imageUrl), "Java AirPlay", popup);
        trayIcon.setImageAutoSize(true);
        if (showWindow != null) {
            trayIcon.addActionListener(event -> showWindow.run());
        }
        try {
            SystemTray.getSystemTray().add(trayIcon);
            if (trayReady != null) {
                trayReady.run();
            }
        } catch (Exception e) {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            trayIcon = null;
            if (trayUnavailable != null) {
                trayUnavailable.run();
            }
            log.warn("Unable to install system tray icon: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }
}
