package wtf.nanoka.airplay.player.gstreamer.ui;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * A separate toplevel window that can host the GStreamer video surface.
 *
 * <p>The window is created once and reused. When detached, the video canvas is
 * moved out of the main window into this frame; when attached back, it is
 * returned to the main window's video view. Closing the window while detached
 * attaches the video back so the mirror is never lost.</p>
 */
public final class DetachedVideoWindow implements AutoCloseable {

    private final Supplier<ThemeManager.ThemePalette> palette;
    private final Runnable canvasAttached;
    private final Consumer<Canvas> onCloseRequest;
    private final I18n i18n;

    private JFrame frame;
    private Canvas canvas;
    private VisionPlayerWindow.VisionLabel statusText;
    private VisionPlayerWindow.VisionLabel detailsLabel;
    private Status status = Status.READY;
    private String streamDetails;

    /** Creates the window without showing it. */
    public DetachedVideoWindow(Supplier<ThemeManager.ThemePalette> palette,
                               Runnable canvasAttached,
                               Consumer<Canvas> onCloseRequest,
                               I18n i18n) {
        this.palette = palette;
        this.canvasAttached = canvasAttached;
        this.onCloseRequest = onCloseRequest;
        this.i18n = i18n;
        initialize();
    }

    /** Returns the currently hosted canvas, or null when no video is detached. */
    public Canvas hostedCanvas() {
        return canvas;
    }

    /** Attaches the video surface (canvas) into this window and shows it. */
    public void attachCanvas(Canvas canvas) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas");
        }
        if (this.canvas == canvas) {
            SwingUtilities.invokeLater(this::showFrame);
            return;
        }
        this.canvas = canvas;
        canvas.setFocusable(true);
        JPanel videoFrame = new JPanel(new BorderLayout());
        videoFrame.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        videoFrame.add(canvas, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            frame.getContentPane().add(videoFrame, BorderLayout.CENTER);
            frame.getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);
            frame.getContentPane().revalidate();
            frame.getContentPane().repaint();
            frame.setTitle(i18n.tr("frame.videoWindow"));
            updateStatus(status);
            showFrame();
        });
    }

    /** Moves the canvas back to the main window (without closing the main one). */
    public void detachCanvas() {
        Canvas previous = canvas;
        canvas = null;
        if (previous != null) {
            previous.setFocusable(false);
            canvasAttached.run();
            SwingUtilities.invokeLater(() -> frame.setVisible(false));
        }
    }

    /** Raises the window; shows it empty when no video is detached yet. */
    public void showFrame() {
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        if ((frame.getExtendedState() & JFrame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
        }
        frame.toFront();
    }

    /** Runs the close-request callback; closing while detached re-attaches the video. */
    public void requestClose() {
        if (canvas != null) {
            onCloseRequest.accept(canvas);
        }
    }

    /** Toggles full screen state of the detached video window. */
    public void toggleFullscreen() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        boolean fullscreen = environment.getDefaultScreenDevice().getFullScreenWindow() == frame;
        setFullscreen(!fullscreen);
    }

    public void setFullscreen(boolean fullscreen) {
        SwingUtilities.invokeLater(() -> {
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            if (fullscreen) {
                if (environment.getDefaultScreenDevice().isFullScreenSupported()) {
                    environment.getDefaultScreenDevice().setFullScreenWindow(frame);
                } else {
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
            } else {
                if (environment.getDefaultScreenDevice().getFullScreenWindow() == frame) {
                    environment.getDefaultScreenDevice().setFullScreenWindow(null);
                }
                frame.setExtendedState(JFrame.NORMAL);
            }
            frame.setVisible(true);
            frame.toFront();
        });
    }

    public boolean isVisible() {
        return frame.isVisible();
    }

    /** Re-renders texts after a language change. */
    public void updateLanguage() {
        if (frame == null) {
            return;
        }
        frame.setTitle(i18n.tr("frame.videoWindow"));
        updateStatus(status);
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(() -> {
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            if (environment.getDefaultScreenDevice().getFullScreenWindow() == frame) {
                environment.getDefaultScreenDevice().setFullScreenWindow(null);
            }
            frame.dispose();
        });
    }

    private enum Status {
        READY("frame.videoWindow.status.ready"),
        CONNECTING("frame.videoWindow.status.connecting"),
        CONNECTED("frame.videoWindow.status.connected");

        private final String key;

        Status(String key) {
            this.key = key;
        }
    }

    private void updateStatus(Status status) {
        this.status = status;
        if (statusText != null) {
            statusText.setText(i18n.tr(status.key));
        }
        if (detailsLabel != null) {
            detailsLabel.setText(status == Status.CONNECTED && streamDetails != null
                    ? streamDetails : i18n.tr("frame.videoWindow.details.waiting"));
        }
    }

    /** Mirrors the receiver state onto the detached window status bar. */
    public void setStatusReady() {
        streamDetails = null;
        updateStatus(Status.READY);
    }

    /** Mirrors the receiver state onto the detached window status bar. */
    public void setStatusConnecting() {
        streamDetails = null;
        updateStatus(Status.CONNECTING);
    }

    /** Mirrors the receiver state onto the detached window status bar. */
    public void setStatusConnected(String details) {
        streamDetails = details;
        updateStatus(Status.CONNECTED);
    }

    private void initialize() {
        Runnable createWindow = () -> {
            frame = new JFrame(i18n.tr("frame.videoWindow"));
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    requestClose();
                }
            });
            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    // Kept for future responsive layout of the status bar.
                }
            });
            Rectangle usableBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setSize(Math.min(960, usableBounds.width), Math.min(600, usableBounds.height));
            frame.setMinimumSize(new Dimension(320, 200));
            frame.setLocationRelativeTo(null);

            // ESC exits full screen (standard desktop convention).
            frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitFullscreen");
            frame.getRootPane().getActionMap().put("exitFullscreen", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    setFullscreen(false);
                }
            });
        };
        if (SwingUtilities.isEventDispatchThread()) {
            createWindow.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(createWindow);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize the detached video window", error);
        }
    }

    private JComponent buildStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setOpaque(true);
        statusBar.setBackground(palette.get().glassStrong());
        statusBar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        statusText = new VisionPlayerWindow.VisionLabel(i18n.tr("frame.videoWindow.status.ready"), 12, Font.BOLD,
                VisionPlayerWindow.TextTone.PRIMARY, palette);
        detailsLabel = new VisionPlayerWindow.VisionLabel(i18n.tr("frame.videoWindow.details.waiting"), 12, Font.PLAIN,
                VisionPlayerWindow.TextTone.SECONDARY, palette);
        statusBar.add(statusText, BorderLayout.WEST);
        statusBar.add(detailsLabel, BorderLayout.EAST);
        return statusBar;
    }
}
