package wtf.nanoka.airplay.app.menu;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import wtf.nanoka.airplay.app.lifecycle.ApplicationShutdown;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * System tray menu. Besides Open/Quit it offers the video window and language
 * shortcuts so the GUI remains reachable when the integrated window is closed.
 */
@Slf4j
public class SystemTrayMenu {

    private TrayIcon trayIcon;
    private JPopupMenu popupMenu;
    private JFrame popupAnchor;
    private Font menuFont;
    private JMenuItem openMenuItem;
    private JMenuItem videoWindowMenuItem;
    private JMenuItem fullscreenMenuItem;
    private JMenuItem languageMenuItem;
    private JMenuItem quitMenuItem;
    private final Supplier<Labels> labels;

    public SystemTrayMenu(ApplicationShutdown applicationShutdown,
                          Runnable showWindow, Runnable showDetachedVideo,
                          Runnable toggleVideoFullscreen, Runnable toggleLanguage,
                          Supplier<Labels> labels,
                          Runnable trayReady, Runnable trayUnavailable) {
        Objects.requireNonNull(applicationShutdown, "applicationShutdown");
        this.labels = labels;
        if (!SystemTray.isSupported()) {
            log.warn("System tray is not supported in this desktop session");
            if (trayUnavailable != null) {
                trayUnavailable.run();
            }
            return;
        }

        Labels initialLabels = currentLabels();
        runOnEdtAndWait(() -> initializePopupMenu(applicationShutdown, showWindow, showDetachedVideo,
                toggleVideoFullscreen, toggleLanguage, initialLabels));

        var imageUrl = Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png"));
        trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(imageUrl), initialLabels.tooltip());
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showPopupIfTriggered(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopupIfTriggered(event);
            }
        });
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
            runOnEdtAndWait(this::closePopupMenu);
            if (trayUnavailable != null) {
                trayUnavailable.run();
            }
            log.warn("Unable to install system tray icon: {}", e.getMessage());
        }
    }

    private void initializePopupMenu(ApplicationShutdown applicationShutdown,
                                     Runnable showWindow, Runnable showDetachedVideo,
                                     Runnable toggleVideoFullscreen, Runnable toggleLanguage,
                                     Labels initialLabels) {
        popupMenu = new JPopupMenu();
        popupMenu.setLightWeightPopupEnabled(false);
        popupAnchor = new JFrame();
        popupAnchor.setUndecorated(true);
        popupAnchor.setType(Window.Type.POPUP);
        popupAnchor.setAlwaysOnTop(true);
        popupAnchor.setFocusableWindowState(true);
        popupAnchor.setSize(1, 1);
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                hidePopupAnchor();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                hidePopupAnchor();
            }
        });
        menuFont = logicalMenuFont(Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font") instanceof Font font
                ? font : null);
        popupMenu.setFont(menuFont);
        if (showWindow != null) {
            openMenuItem = new JMenuItem(initialLabels.open());
            openMenuItem.addActionListener(event -> showWindow.run());
            popupMenu.add(openMenuItem);
        }
        if (showDetachedVideo != null) {
            videoWindowMenuItem = new JMenuItem(initialLabels.videoWindow());
            videoWindowMenuItem.addActionListener(event -> showDetachedVideo.run());
            popupMenu.add(videoWindowMenuItem);
            if (toggleVideoFullscreen != null) {
                fullscreenMenuItem = new JMenuItem(initialLabels.fullscreen());
                fullscreenMenuItem.addActionListener(event -> toggleVideoFullscreen.run());
                popupMenu.add(fullscreenMenuItem);
            }
        }
        if (toggleLanguage != null && labels != null) {
            popupMenu.addSeparator();
            languageMenuItem = new JMenuItem(initialLabels.languageMenuLabel());
            languageMenuItem.addActionListener(event -> toggleLanguage.run());
            popupMenu.add(languageMenuItem);
        }
        popupMenu.addSeparator();
        quitMenuItem = new JMenuItem(initialLabels.quit());
        quitMenuItem.addActionListener(event -> applicationShutdown.requestQuit());
        popupMenu.add(quitMenuItem);
        applyMenuFont();
    }

    public void updateLabels() {
        if (labels == null) {
            return;
        }
        Labels current = currentLabels();
        runOnEdt(() -> updateLabels(current));
    }

    private void updateLabels(Labels current) {
        if (openMenuItem != null) {
            openMenuItem.setText(current.open());
        }
        if (videoWindowMenuItem != null) {
            videoWindowMenuItem.setText(current.videoWindow());
        }
        if (fullscreenMenuItem != null) {
            fullscreenMenuItem.setText(current.fullscreen());
        }
        if (languageMenuItem != null) {
            languageMenuItem.setText(current.languageMenuLabel());
        }
        if (quitMenuItem != null) {
            quitMenuItem.setText(current.quit());
        }
        if (trayIcon != null) {
            trayIcon.setToolTip(current.tooltip());
        }
        applyMenuFont();
    }

    private void showPopupIfTriggered(MouseEvent event) {
        boolean rightButtonReleased = event.getID() == MouseEvent.MOUSE_RELEASED
                && event.getButton() == MouseEvent.BUTTON3;
        if (!event.isPopupTrigger() && !rightButtonReleased) {
            return;
        }
        event.consume();
        runOnEdt(this::showPopup);
    }

    private void showPopup() {
        if (popupMenu == null || popupAnchor == null || popupMenu.isVisible()) {
            return;
        }
        var pointer = MouseInfo.getPointerInfo();
        if (pointer == null) {
            return;
        }
        Point location = popupLocation(pointer.getLocation(), pointer.getDevice().getDefaultConfiguration(),
                popupMenu.getPreferredSize());
        popupAnchor.setLocation(location);
        popupAnchor.setVisible(true);
        popupAnchor.toFront();
        popupAnchor.requestFocus();
        popupMenu.show(popupAnchor.getContentPane(), 0, 0);
    }

    static Point popupLocation(Point pointer, GraphicsConfiguration configuration, Dimension popupSize) {
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int left = screen.x + insets.left;
        int top = screen.y + insets.top;
        int right = screen.x + screen.width - insets.right;
        int bottom = screen.y + screen.height - insets.bottom;
        return popupLocation(pointer, new Rectangle(left, top, right - left, bottom - top), popupSize);
    }

    static Point popupLocation(Point pointer, Rectangle usableScreen, Dimension popupSize) {
        int left = usableScreen.x;
        int top = usableScreen.y;
        int right = usableScreen.x + usableScreen.width;
        int bottom = usableScreen.y + usableScreen.height;
        int x = pointer.x + popupSize.width <= right ? pointer.x : pointer.x - popupSize.width;
        int y = pointer.y + popupSize.height <= bottom
                ? pointer.y : Math.min(pointer.y, bottom) - popupSize.height;
        return new Point(
                Math.max(left, Math.min(x, right - popupSize.width)),
                Math.max(top, Math.min(y, bottom - popupSize.height)));
    }

    private void hidePopupAnchor() {
        if (popupAnchor != null) {
            popupAnchor.setVisible(false);
        }
    }

    private void applyMenuFont() {
        if (menuFont == null) {
            return;
        }
        if (popupMenu != null) {
            popupMenu.setFont(menuFont);
        }
        if (openMenuItem != null) {
            openMenuItem.setFont(menuFont);
        }
        if (videoWindowMenuItem != null) {
            videoWindowMenuItem.setFont(menuFont);
        }
        if (fullscreenMenuItem != null) {
            fullscreenMenuItem.setFont(menuFont);
        }
        if (languageMenuItem != null) {
            languageMenuItem.setFont(menuFont);
        }
        if (quitMenuItem != null) {
            quitMenuItem.setFont(menuFont);
        }
    }

    static Font logicalMenuFont(Font platformFont) {
        // Dialog is a Java2D composite font with CJK fallback, unlike the native AWT tray menu font.
        int style = platformFont == null ? Font.PLAIN : platformFont.getStyle();
        float size = platformFont == null ? 13f : platformFont.getSize2D();
        return new Font(Font.DIALOG, style, Math.round(size)).deriveFont(size);
    }

    private Labels currentLabels() {
        return labels == null ? Labels.english() : Objects.requireNonNull(labels.get(), "labels.get()");
    }

    @PreDestroy
    public void close() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        runOnEdtAndWait(this::closePopupMenu);
    }

    private void closePopupMenu() {
        if (popupMenu != null) {
            popupMenu.setVisible(false);
        }
        if (popupAnchor != null) {
            popupAnchor.dispose();
            popupAnchor = null;
        }
        languageMenuItem = null;
        openMenuItem = null;
        videoWindowMenuItem = null;
        fullscreenMenuItem = null;
        quitMenuItem = null;
        popupMenu = null;
        menuFont = null;
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to update the system tray menu", error);
        }
    }

    public record Labels(String open, String videoWindow, String fullscreen,
                         String language, String currentLanguage, String quit, String tooltip) {

        public Labels {
            Objects.requireNonNull(open, "open");
            Objects.requireNonNull(videoWindow, "videoWindow");
            Objects.requireNonNull(fullscreen, "fullscreen");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(currentLanguage, "currentLanguage");
            Objects.requireNonNull(quit, "quit");
            Objects.requireNonNull(tooltip, "tooltip");
        }

        String languageMenuLabel() {
            return language + ": " + currentLanguage;
        }

        static Labels english() {
            return new Labels("Open Java AirPlay", "Show video window", "Full screen",
                    "Language", "English", "Quit", "Java AirPlay");
        }
    }
}
