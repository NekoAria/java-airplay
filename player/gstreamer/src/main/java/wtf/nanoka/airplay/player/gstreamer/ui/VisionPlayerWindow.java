package wtf.nanoka.airplay.player.gstreamer.ui;

import wtf.nanoka.airplay.lib.VideoStreamInfo;

import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JOptionPane;
import javax.swing.Scrollable;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JViewport;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import wtf.nanoka.airplay.player.gstreamer.VideoRenderMode;
import wtf.nanoka.airplay.player.gstreamer.GpuAdapter;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerDefault;

public final class VisionPlayerWindow implements AutoCloseable {

    private static final String RECEIVER_VIEW = "receiver";
    private static final String VIDEO_VIEW = "video";
    private static final String SETTINGS_VIEW = "settings";
    private static final String ALWAYS_ON_TOP_PREFERENCE = "always-on-top";

    private final Config config;
    private final Preferences preferences = Preferences.userNodeForPackage(VisionPlayerWindow.class);

    private ThemeManager themeManager;
    private JFrame frame;
    private AmbientPanel root;
    private SidebarPanel sidebar;
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private Canvas videoCanvas;
    private StatusChip statusChip;
    private VisionLabel headerTitle;
    private VisionLabel idleTitle;
    private VisionLabel idleSubtitle;
    private VisionLabel videoDetails;
    private ThemeSelector themeSelector;
    private JPanel receiverChips;
    private JPanel settingsStack;
    private final List<VisionLabel> settingDescriptions = new ArrayList<>();
    private ReceiverSettings savedSettings;
    private JTextField receiverNameField;
    private JTextField widthField;
    private JTextField heightField;
    private JTextField fpsField;
    private JTextField identityFileField;
    private JSpinner audioJitterSpinner;
    private VisionToggle pairingToggle;
    private VisionToggle hevcToggle;
    private JComboBox<String> playerImplementationCombo;
    private VisionToggle trayToggle;
    private VisionToggle swingToggle;
    private JComboBox<String> decoderCombo;
    private JComboBox<GpuAdapterChoice> gpuAdapterCombo;
    private JSpinner videoQueueSpinner;
    private JComboBox<RenderModeChoice> renderModeCombo;
    private VisionLabel settingsStatus;
    private final List<ActionButton> settingsActionButtons = new ArrayList<>();

    private boolean connected;
    private String activeSection = RECEIVER_VIEW;

    public VisionPlayerWindow(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        savedSettings = config.settings();
        themeManager = new ThemeManager();
        runOnEdtAndWait(this::initialize);
    }

    public Canvas videoCanvas() {
        return videoCanvas;
    }

    public void showVideoFormatDetected(VideoStreamInfo streamInfo) {
        runOnEdt(() -> {
            if (connected) {
                updateVideoDetails(streamInfo);
                return;
            }
            connected = false;
            statusChip.setState(ConnectionState.CONNECTING);
            idleTitle.setText("Preparing stream");
            idleSubtitle.setText("Establishing a secure connection");
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, RECEIVER_VIEW);
            }
            showFrame();
        });
    }

    public void showVideo(VideoStreamInfo streamInfo) {
        runOnEdt(() -> {
            connected = true;
            statusChip.setState(ConnectionState.CONNECTED);
            headerTitle.setText("Screen Mirroring");

            updateVideoDetails(streamInfo);
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, VIDEO_VIEW);
            }
            showFrame();
            videoCanvas.requestFocusInWindow();
        });
    }

    public void showIdle() {
        runOnEdt(() -> {
            connected = false;
            statusChip.setState(ConnectionState.READY);
            headerTitle.setText("Receiver");
            idleTitle.setText("Ready to receive");
            idleSubtitle.setText(config.receiverName());
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, RECEIVER_VIEW);
            }
            root.repaint();
        });
    }

    public void showWindow() {
        runOnEdt(this::showFrame);
    }

    private void updateVideoDetails(VideoStreamInfo streamInfo) {
        int width = streamInfo.getWidth() > 0 ? streamInfo.getWidth() : config.advertisedWidth();
        int height = streamInfo.getHeight() > 0 ? streamInfo.getHeight() : config.advertisedHeight();
        double fps = streamInfo.getFps() > 0 ? streamInfo.getFps() : config.fps();
        String codec = switch (streamInfo.getCodec()) {
            case H264 -> "H.264";
            case HEVC -> "HEVC";
            case UNKNOWN -> "Detecting codec";
        };
        videoDetails.setText(String.format(Locale.ROOT, "%d x %d  |  %.0f FPS  |  %s", width, height, fps, codec));
    }

    public void setCloseToTray(boolean closeToTray) {
        runOnEdtAndWait(() -> frame.setDefaultCloseOperation(
                closeToTray ? JFrame.HIDE_ON_CLOSE : JFrame.EXIT_ON_CLOSE));
    }

    private void initialize() {
        JFrame.setDefaultLookAndFeelDecorated(true);

        frame = new JFrame("Java AirPlay - " + config.receiverName());
        frame.setDefaultCloseOperation(config.closeToTray() ? JFrame.HIDE_ON_CLOSE : JFrame.EXIT_ON_CLOSE);
        Rectangle usableBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int availableWidth = Math.max(1, usableBounds.width - 32);
        int availableHeight = Math.max(1, usableBounds.height - 32);
        int initialWidth = Math.min(1180, availableWidth);
        int initialHeight = Math.min(760, availableHeight);
        frame.setMinimumSize(new Dimension(Math.min(780, initialWidth), Math.min(540, initialHeight)));
        frame.setSize(initialWidth, initialHeight);
        frame.setIconImage(createAppIcon());

        root = new AmbientPanel(this::palette);
        root.setLayout(new BorderLayout(0, 0));
        root.setBorder(null);

        sidebar = new SidebarPanel(this::palette);
        JPanel main = transparentPanel(new BorderLayout(0, 12));
        main.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        main.add(buildHeader(), BorderLayout.NORTH);
        main.add(buildViews(), BorderLayout.CENTER);

        root.add(sidebar, BorderLayout.WEST);
        root.add(main, BorderLayout.CENTER);
        frame.setContentPane(root);

        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                sidebar.setCompact(frame.getWidth() < 980);
                updateResponsiveLayout();
            }
        });

        themeManager.addListener(this::applyPalette);
        applyPalette();
        frame.setLocation(
                usableBounds.x + Math.max(0, (usableBounds.width - initialWidth) / 2),
                usableBounds.y + Math.max(0, (usableBounds.height - initialHeight) / 2));
        frame.setVisible(true);
        sidebar.setCompact(frame.getWidth() < 980);
        updateResponsiveLayout();
    }

    private JComponent buildHeader() {
        GlassPanel header = new GlassPanel(this::palette, 10, true);
        header.setLayout(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(11, 17, 11, 14));
        header.setPreferredSize(new Dimension(100, 58));

        headerTitle = label("Receiver", 17, Font.BOLD, TextTone.PRIMARY);
        headerTitle.setToolTipText(config.receiverName());
        statusChip = new StatusChip(this::palette, config.pairingRequired());
        header.add(headerTitle, BorderLayout.CENTER);
        header.add(statusChip, BorderLayout.EAST);
        return header;
    }

    private JComponent buildViews() {
        cardLayout = new CardLayout();
        cardPanel = transparentPanel(cardLayout);
        cardPanel.add(buildReceiverView(), RECEIVER_VIEW);
        cardPanel.add(buildVideoView(), VIDEO_VIEW);
        cardPanel.add(buildSettingsView(), SETTINGS_VIEW);
        return cardPanel;
    }

    private JComponent buildReceiverView() {
        JPanel view = new VerticalScrollPanel();
        view.setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.insets.bottom = 18;
        view.add(new ReceiverIllustration(this::palette), constraints);

        idleTitle = label("Ready to receive", 28, Font.BOLD, TextTone.PRIMARY);
        constraints.gridy++;
        constraints.insets.bottom = 6;
        view.add(idleTitle, constraints);

        idleSubtitle = label(config.receiverName(), 14, Font.PLAIN, TextTone.SECONDARY);
        constraints.gridy++;
        constraints.insets.bottom = 18;
        view.add(idleSubtitle, constraints);

        receiverChips = transparentPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        receiverChips.add(new InfoChip(this::palette,
                config.advertisedWidth() + " x " + config.advertisedHeight(), 118));
        receiverChips.add(new InfoChip(this::palette, config.fps() + " FPS", 82));
        receiverChips.add(new InfoChip(this::palette,
                config.pairingRequired() ? "Pairing on" : "Pairing off", 104));
        constraints.gridy++;
        constraints.insets.bottom = 0;
        view.add(receiverChips, constraints);
        JScrollPane scrollPane = new JScrollPane(view,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        return scrollPane;
    }

    private JComponent buildVideoView() {
        JPanel view = transparentPanel(new BorderLayout(0, 10));

        JPanel videoFrame = new JPanel(new BorderLayout());
        videoFrame.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        videoCanvas = new Canvas();
        videoCanvas.setBackground(palette().videoSurface());
        videoCanvas.setFocusable(false);
        videoFrame.add(videoCanvas, BorderLayout.CENTER);

        GlassPanel informationBar = new GlassPanel(this::palette, 8, false);
        informationBar.setLayout(new BorderLayout());
        informationBar.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        informationBar.setPreferredSize(new Dimension(100, 44));
        videoDetails = label("Waiting for stream details", 12, Font.PLAIN, TextTone.SECONDARY);
        informationBar.add(videoDetails, BorderLayout.WEST);

        view.add(videoFrame, BorderLayout.CENTER);
        view.add(informationBar, BorderLayout.SOUTH);
        return view;
    }

    private JComponent buildSettingsView() {
        JPanel settingsView = transparentPanel(new BorderLayout());
        settingsStack = new VerticalScrollPanel();
        settingsStack.setLayout(new BoxLayout(settingsStack, BoxLayout.Y_AXIS));
        settingsStack.setBorder(BorderFactory.createEmptyBorder(24, 30, 28, 30));

        VisionLabel title = label("Settings", 28, Font.BOLD, TextTone.PRIMARY);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsStack.add(title);
        settingsStack.add(Box.createVerticalStrut(20));

        settingsStack.add(sectionLabel("Appearance"));
        settingsStack.add(Box.createVerticalStrut(8));
        themeSelector = new ThemeSelector(this::palette, themeManager);
        settingsStack.add(settingRow("Theme", "Match the desktop or choose an appearance", themeSelector));
        VisionToggle alwaysOnTop = new VisionToggle(
                this::palette, preferences.getBoolean(ALWAYS_ON_TOP_PREFERENCE, false));
        alwaysOnTop.setToolTipText("Keep the receiver window above other windows");
        alwaysOnTop.getAccessibleContext().setAccessibleName("Always on top");
        frame.setAlwaysOnTop(alwaysOnTop.isSelected());
        alwaysOnTop.addActionListener(event -> {
            frame.setAlwaysOnTop(alwaysOnTop.isSelected());
            preferences.putBoolean(ALWAYS_ON_TOP_PREFERENCE, alwaysOnTop.isSelected());
        });
        settingsStack.add(settingRow("Always on top", "Keep the receiver visible while mirroring", alwaysOnTop));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("Receiver"));
        settingsStack.add(Box.createVerticalStrut(8));
        receiverNameField = formField(savedSettings.serverName(), 250);
        settingsStack.add(settingRow("Receiver name", "Shown in the iPhone Screen Mirroring list", receiverNameField));

        widthField = formField(savedSettings.width(), 76);
        heightField = formField(savedSettings.height(), 76);
        fpsField = formField(savedSettings.fps(), 64);
        widthField.getAccessibleContext().setAccessibleName("Advertised width");
        heightField.getAccessibleContext().setAccessibleName("Advertised height");
        fpsField.getAccessibleContext().setAccessibleName("Advertised frame rate");
        JPanel displayProfile = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        displayProfile.add(widthField);
        displayProfile.add(valueLabel("x"));
        displayProfile.add(heightField);
        displayProfile.add(valueLabel("@"));
        displayProfile.add(fpsField);
        displayProfile.add(valueLabel("FPS"));
        settingsStack.add(settingRow("Display profile", "Advertised width, height, and frame rate; auto is accepted",
                displayProfile));

        pairingToggle = new VisionToggle(this::palette, savedSettings.requirePairing());
        pairingToggle.setToolTipText("Require Pair-Verify before media setup");
        settingsStack.add(settingRow("Secure pairing", "Reject unverified media connections", pairingToggle));

        hevcToggle = new VisionToggle(this::palette, savedSettings.hevcEnabled());
        hevcToggle.setToolTipText("Advertise experimental HEVC screen mirroring support");
        settingsStack.add(settingRow("HEVC reception", "Experimental H.265 negotiation and decoding", hevcToggle));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("Playback"));
        settingsStack.add(Box.createVerticalStrut(8));

        playerImplementationCombo = choiceCombo(
                new String[]{"gstreamer", "ffmpeg", "vlc", "h264-dump"}, 180, false);
        settingsStack.add(settingRow("Player", "Media playback backend", playerImplementationCombo));

        renderModeCombo = new JComboBox<>();
        for (VideoRenderMode mode : VideoRenderMode.values()) {
            renderModeCombo.addItem(new RenderModeChoice(mode.propertyValue(), mode.label()));
        }
        renderModeCombo.setPreferredSize(new Dimension(180, 34));
        renderModeCombo.setMinimumSize(renderModeCombo.getPreferredSize());
        selectRenderMode(savedSettings.renderMode());
        settingsStack.add(settingRow("Render mode", "Balanced prevents tearing; low latency presents immediately",
                renderModeCombo));

        decoderCombo = choiceCombo(new String[]{
                "auto", "d3d12h264dec", "d3d11h264dec", "nvh264dec", "vulkanh264dec",
                "avdec_h264", "vah264dec", "v4l2h264dec", "vtdec_hw"
        }, 210, true);
        decoderCombo.setSelectedItem(savedSettings.videoDecoder());
        settingsStack.add(settingRow("Video decoder", "Automatic hardware selection or a GStreamer element",
                decoderCombo));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("Stream & decoding"));
        settingsStack.add(Box.createVerticalStrut(8));

        gpuAdapterCombo = new JComboBox<>();
        gpuAdapterCombo.addItem(GpuAdapterChoice.automatic());
        GstPlayerDefault.availableGpuAdapters().stream()
                .map(GpuAdapterChoice::detected)
                .forEach(gpuAdapterCombo::addItem);
        gpuAdapterCombo.setFont(interfaceFont(12, Font.PLAIN));
        gpuAdapterCombo.setPreferredSize(new Dimension(300, 34));
        gpuAdapterCombo.setMinimumSize(new Dimension(180, 34));
        selectGpuAdapter(savedSettings.gpuAdapter());
        settingsStack.add(settingRow("GPU adapter", "Hardware detected through DXGI; configuration stores its index",
                gpuAdapterCombo));

        videoQueueSpinner = numberSpinner(savedSettings.videoQueueDepth(), 1, 16, 1, 86);
        settingsStack.add(settingRow("Video buffer", "Decrypted access units queued before GStreamer",
                videoQueueSpinner));

        audioJitterSpinner = numberSpinner(savedSettings.audioJitterPackets(), 1, 64, 1, 86);
        settingsStack.add(settingRow("Audio jitter", "Packet reorder window for unstable networks",
                audioJitterSpinner));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("Application"));
        settingsStack.add(Box.createVerticalStrut(8));

        swingToggle = new VisionToggle(this::palette, savedSettings.swingEnabled());
        swingToggle.setToolTipText("Show the integrated desktop window");
        settingsStack.add(settingRow("Integrated window", "Use the native video surface in this application",
                swingToggle));

        trayToggle = new VisionToggle(this::palette, savedSettings.trayEnabled());
        trayToggle.setToolTipText("Show the Java AirPlay system tray menu");
        settingsStack.add(settingRow("System tray", "Keep Open and Quit actions in the tray", trayToggle));

        identityFileField = formField(savedSettings.identityFile(), 280);
        identityFileField.setToolTipText(savedSettings.identityFile());
        JPanel identityPicker = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        identityPicker.add(identityFileField);
        ActionButton browseIdentity = new ActionButton(this::palette, "Browse", 78, false);
        browseIdentity.setToolTipText("Choose an existing identity file or a new file location");
        browseIdentity.addActionListener(event -> browseIdentityFile());
        identityPicker.add(browseIdentity);
        ToolButton openIdentityFolder = new ToolButton(this::palette, ToolIcon.FOLDER);
        openIdentityFolder.setToolTipText("Open identity file folder");
        openIdentityFolder.addActionListener(event -> openIdentityFolder());
        identityPicker.add(openIdentityFolder);
        settingsStack.add(settingRow("Identity file", "Persistent receiver identity used for pairing",
                identityPicker));

        settingsStack.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(settingsStack,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        settingsView.add(scrollPane, BorderLayout.CENTER);
        settingsView.add(buildSettingsFooter(), BorderLayout.SOUTH);

        playerImplementationCombo.setSelectedItem(savedSettings.playerImplementation());
        playerImplementationCombo.addActionListener(event -> updateSettingsAvailability());
        updateSettingsAvailability();
        return settingsView;
    }

    private JComponent buildSettingsFooter() {
        SettingsFooter footer = new SettingsFooter(this::palette);
        footer.setLayout(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(11, 16, 12, 16));

        settingsStatus = label("", 11, Font.PLAIN, TextTone.SECONDARY);
        settingsStatus.setToolTipText(config.settingsController().settingsFile().toString());
        footer.add(settingsStatus, BorderLayout.CENTER);

        JPanel actions = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        ActionButton reset = new ActionButton(this::palette, "Reset", 72, false);
        reset.addActionListener(event -> resetSettingsForm(savedSettings));
        ActionButton save = new ActionButton(this::palette, "Save", 74, false);
        save.addActionListener(event -> saveSettings(false));
        ActionButton saveAndRestart = new ActionButton(this::palette, "Save & Restart", 132, true);
        saveAndRestart.addActionListener(event -> saveSettings(true));
        actions.add(reset);
        actions.add(save);
        actions.add(saveAndRestart);
        settingsActionButtons.add(reset);
        settingsActionButtons.add(save);
        settingsActionButtons.add(saveAndRestart);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private JTextField formField(String value, int width) {
        JTextField field = new JTextField(value);
        field.setFont(interfaceFont(12, Font.PLAIN));
        field.setPreferredSize(new Dimension(width, 34));
        field.setMinimumSize(new Dimension(Math.min(width, 64), 34));
        field.setMargin(new Insets(4, 9, 4, 9));
        field.putClientProperty("JComponent.roundRect", true);
        return field;
    }

    private JComboBox<String> choiceCombo(String[] values, int width, boolean editable) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setEditable(editable);
        combo.setFont(interfaceFont(12, Font.PLAIN));
        combo.setPreferredSize(new Dimension(width, 34));
        combo.setMinimumSize(new Dimension(Math.min(width, 90), 34));
        return combo;
    }

    private JSpinner numberSpinner(int value, int minimum, int maximum, int step, int width) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setFont(interfaceFont(12, Font.PLAIN));
        spinner.setPreferredSize(new Dimension(width, 34));
        spinner.setMinimumSize(spinner.getPreferredSize());
        return spinner;
    }

    private void saveSettings(boolean restart) {
        ReceiverSettings settings = collectSettings();
        setSettingsActionsEnabled(false);
        settingsStatus.setText(restart ? "Validating settings..." : "Saving...");
        Thread.ofVirtual().name("settings-save").start(() -> {
            SettingsController.Result saveResult;
            SettingsController.Result result;
            try {
                saveResult = config.settingsController().save(settings);
                result = saveResult;
                if (saveResult.success() && restart) {
                    result = config.settingsController().restart();
                }
            } catch (RuntimeException | LinkageError error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                saveResult = SettingsController.Result.failure(message);
                result = saveResult;
            }
            boolean settingsSaved = saveResult.success();
            SettingsController.Result finalResult = result;
            runOnEdt(() -> {
                settingsStatus.setText(finalResult.message());
                settingsStatus.setToolTipText(finalResult.message());
                if (settingsSaved) {
                    savedSettings = settings;
                }
                if (!finalResult.success()) {
                    Toolkit.getDefaultToolkit().beep();
                    JOptionPane.showMessageDialog(frame, finalResult.message(),
                            "Unable to apply settings", JOptionPane.ERROR_MESSAGE);
                }
                if (!restart || !finalResult.success()) {
                    setSettingsActionsEnabled(true);
                }
            });
        });
    }

    private void setSettingsActionsEnabled(boolean enabled) {
        settingsActionButtons.forEach(button -> button.setEnabled(enabled));
    }

    private ReceiverSettings collectSettings() {
        RenderModeChoice renderMode = (RenderModeChoice) renderModeCombo.getSelectedItem();
        return new ReceiverSettings(
                receiverNameField.getText(),
                widthField.getText(),
                heightField.getText(),
                fpsField.getText(),
                identityFileField.getText(),
                (Integer) audioJitterSpinner.getValue(),
                pairingToggle.isSelected(),
                hevcToggle.isSelected(),
                Objects.toString(playerImplementationCombo.getSelectedItem(), "gstreamer"),
                trayToggle.isSelected(),
                swingToggle.isSelected(),
                comboValue(decoderCombo),
                selectedGpuAdapter(),
                (Integer) videoQueueSpinner.getValue(),
                renderMode == null ? VideoRenderMode.BALANCED.propertyValue() : renderMode.value());
    }

    private void resetSettingsForm(ReceiverSettings settings) {
        receiverNameField.setText(settings.serverName());
        widthField.setText(settings.width());
        heightField.setText(settings.height());
        fpsField.setText(settings.fps());
        identityFileField.setText(settings.identityFile());
        audioJitterSpinner.setValue(settings.audioJitterPackets());
        pairingToggle.setSelected(settings.requirePairing());
        hevcToggle.setSelected(settings.hevcEnabled());
        playerImplementationCombo.setSelectedItem(settings.playerImplementation());
        trayToggle.setSelected(settings.trayEnabled());
        swingToggle.setSelected(settings.swingEnabled());
        decoderCombo.setSelectedItem(settings.videoDecoder());
        selectGpuAdapter(settings.gpuAdapter());
        videoQueueSpinner.setValue(settings.videoQueueDepth());
        selectRenderMode(settings.renderMode());
        settingsStatus.setText("");
        updateSettingsAvailability();
    }

    private void selectRenderMode(String value) {
        for (int index = 0; index < renderModeCombo.getItemCount(); index++) {
            if (renderModeCombo.getItemAt(index).value().equalsIgnoreCase(value)) {
                renderModeCombo.setSelectedIndex(index);
                return;
            }
        }
        renderModeCombo.setSelectedIndex(0);
    }

    private void selectGpuAdapter(String value) {
        String normalized = value == null || value.isBlank() ? "auto" : value.trim();
        for (int index = 0; index < gpuAdapterCombo.getItemCount(); index++) {
            if (gpuAdapterCombo.getItemAt(index).value().equalsIgnoreCase(normalized)) {
                gpuAdapterCombo.setSelectedIndex(index);
                return;
            }
        }
        gpuAdapterCombo.addItem(GpuAdapterChoice.unavailable(normalized));
        gpuAdapterCombo.setSelectedIndex(gpuAdapterCombo.getItemCount() - 1);
    }

    private String selectedGpuAdapter() {
        GpuAdapterChoice selected = (GpuAdapterChoice) gpuAdapterCombo.getSelectedItem();
        return selected == null ? "auto" : selected.value();
    }

    private void updateSettingsAvailability() {
        boolean gstreamer = "gstreamer".equalsIgnoreCase(
                Objects.toString(playerImplementationCombo.getSelectedItem(), ""));
        renderModeCombo.setEnabled(gstreamer);
        decoderCombo.setEnabled(gstreamer);
        gpuAdapterCombo.setEnabled(gstreamer);
        videoQueueSpinner.setEnabled(gstreamer);
        swingToggle.setEnabled(gstreamer);
        hevcToggle.setEnabled(gstreamer);
        if (!gstreamer) {
            hevcToggle.setSelected(false);
        }
    }

    private static String comboValue(JComboBox<String> combo) {
        Object value = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return Objects.toString(value, "").trim();
    }

    private VisionLabel sectionLabel(String text) {
        VisionLabel label = label(text.toUpperCase(Locale.ROOT), 11, Font.BOLD, TextTone.TERTIARY);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JComponent settingRow(String title, String description, JComponent control) {
        SettingRow row = new SettingRow(this::palette);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(11, 2, 11, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMinimumSize(new Dimension(0, 74));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));
        row.setPreferredSize(new Dimension(700, 74));

        JPanel copy = transparentPanel();
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        VisionLabel titleLabel = label(title, 14, Font.BOLD, TextTone.PRIMARY);
        titleLabel.setLabelFor(control);
        copy.add(titleLabel);
        copy.add(Box.createVerticalStrut(3));
        VisionLabel descriptionLabel = label(description, 12, Font.PLAIN, TextTone.SECONDARY);
        settingDescriptions.add(descriptionLabel);
        copy.add(descriptionLabel);
        JPanel controlWrapper = transparentPanel(new GridBagLayout());
        controlWrapper.add(control);
        if (control.getAccessibleContext().getAccessibleName() == null) {
            control.getAccessibleContext().setAccessibleName(title);
        }
        row.add(copy, BorderLayout.CENTER);
        row.add(controlWrapper, BorderLayout.EAST);
        return row;
    }

    private VisionLabel valueLabel(String text) {
        VisionLabel label = label(text, 13, Font.PLAIN, TextTone.SECONDARY);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        label.setToolTipText(text);
        Dimension preferred = label.getPreferredSize();
        label.setPreferredSize(new Dimension(Math.min(160, preferred.width), preferred.height));
        label.setMinimumSize(new Dimension(0, preferred.height));
        label.setMaximumSize(new Dimension(160, preferred.height));
        return label;
    }

    private void updateResponsiveLayout() {
        if (settingsStack == null || receiverChips == null) {
            return;
        }
        boolean compact = frame.getWidth() < 920;
        settingsStack.setBorder(BorderFactory.createEmptyBorder(
                compact ? 20 : 26,
                compact ? 16 : 34,
                compact ? 24 : 34,
                compact ? 16 : 34));
        settingDescriptions.forEach(description -> description.setVisible(!compact));
        receiverChips.setVisible(!compact);
        settingsStack.revalidate();
        cardPanel.revalidate();
        root.repaint();
    }

    private VisionLabel label(String text, float size, int style, TextTone tone) {
        return new VisionLabel(text, size, style, tone, this::palette);
    }

    private void selectSection(String section) {
        activeSection = section;
        sidebar.setSelectedSection(section);
        if (SETTINGS_VIEW.equals(section)) {
            headerTitle.setText("Settings");
            cardLayout.show(cardPanel, SETTINGS_VIEW);
        } else {
            headerTitle.setText(connected ? "Screen Mirroring" : "Receiver");
            cardLayout.show(cardPanel, connected ? VIDEO_VIEW : RECEIVER_VIEW);
        }
    }

    private void showFrame() {
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        if ((frame.getExtendedState() & JFrame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
        }
        frame.toFront();
    }

    private void applyPalette() {
        if (root == null) {
            return;
        }
        videoCanvas.setBackground(palette().videoSurface());
        if (themeSelector != null) {
            themeSelector.syncSelection();
        }
        root.revalidate();
        root.repaint();
    }

    private ThemeManager.ThemePalette palette() {
        return themeManager.palette();
    }

    private void browseIdentityFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose identity file");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        Path current = identityPath();
        if (current != null) {
            if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                chooser.setCurrentDirectory(current.getParent().toFile());
            }
            chooser.setSelectedFile(current.toFile());
        }
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString();
            identityFileField.setText(selected);
            identityFileField.setToolTipText(selected);
        }
    }

    private void openIdentityFolder() {
        Path identityPath = identityPath();
        Path configuredDirectory = identityPath == null ? null : identityPath.getParent();
        Path target = configuredDirectory != null && Files.isDirectory(configuredDirectory)
                ? configuredDirectory
                : config.settingsController().settingsFile().getParent();
        if (target == null || !Files.isDirectory(target)) {
            target = Path.of(System.getProperty("user.home"));
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target.toFile());
            }
        } catch (IOException | RuntimeException error) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private Path identityPath() {
        try {
            String value = identityFileField.getText().trim();
            return value.isEmpty() ? null : Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String formatDecoder(String decoder) {
        return "auto".equalsIgnoreCase(decoder) ? "Automatic decoder" : decoder;
    }

    private Image createAppIcon() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            enableQuality(graphics);
            graphics.setColor(palette().videoSurface());
            graphics.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 14, 14));
            drawBrandGlyph(graphics, 11, 11, 42, palette());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    public void close() {
        runOnEdt(() -> {
            themeManager.close();
            frame.dispose();
        });
    }

    private void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize the AirPlay window", error);
        }
    }

    private static JPanel transparentPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel transparentPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static final class VerticalScrollPanel extends JPanel implements Scrollable {

        VerticalScrollPanel() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(18, visibleRect.height - 36);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport
                    && viewport.getHeight() > getPreferredSize().height;
        }
    }

    private static Font interfaceFont(float size, int style) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, style, Math.round(size));
        }
        return base.deriveFont(style, size);
    }

    private static void enableQuality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void drawAirPlayGlyph(Graphics2D graphics, double x, double y, double width, double height,
                                         Color color, float strokeWidth) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            double scale = Math.min(width / 24.0, height / 24.0);
            copy.translate(x + (width - 24 * scale) / 2, y + (height - 24 * scale) / 2);
            copy.scale(scale, scale);
            copy.setColor(color);
            copy.setStroke(new BasicStroke((float) (strokeWidth / scale),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D screen = new Path2D.Double();
            screen.moveTo(5, 17);
            screen.lineTo(4, 17);
            screen.curveTo(2.9, 17, 2, 16.1, 2, 15);
            screen.lineTo(2, 5);
            screen.curveTo(2, 3.9, 2.9, 3, 4, 3);
            screen.lineTo(20, 3);
            screen.curveTo(21.1, 3, 22, 3.9, 22, 5);
            screen.lineTo(22, 15);
            screen.curveTo(22, 16.1, 21.1, 17, 20, 17);
            screen.lineTo(19, 17);
            copy.draw(screen);

            Path2D projection = new Path2D.Double();
            projection.moveTo(12, 15);
            projection.lineTo(17, 21);
            projection.lineTo(7, 21);
            projection.closePath();
            copy.draw(projection);
        } finally {
            copy.dispose();
        }
    }

    private static void drawBrandGlyph(Graphics2D graphics, double x, double y, double size,
                                        ThemeManager.ThemePalette colors) {
        drawAirPlayGlyph(graphics, x, y, size, size, colors.accent(),
                (float) Math.max(1.8, size * 0.06));
    }

    public record Config(
            String receiverName,
            int advertisedWidth,
             int advertisedHeight,
             int fps,
             boolean pairingRequired,
             boolean closeToTray,
             ReceiverSettings settings,
             SettingsController settingsController) {

        public Config {
             Objects.requireNonNull(receiverName, "receiverName");
             Objects.requireNonNull(settings, "settings");
             Objects.requireNonNull(settingsController, "settingsController");
         }
    }

    private enum ConnectionState {
        READY("Ready"),
        CONNECTING("Connecting"),
        CONNECTED("Connected");

        private final String label;

        ConnectionState(String label) {
            this.label = label;
        }
    }

    private enum TextTone {
        PRIMARY,
        SECONDARY,
        TERTIARY
    }

    private enum NavigationIcon {
        RECEIVER,
        SETTINGS
    }

    private enum ToolIcon {
        FOLDER
    }

    private record RenderModeChoice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record GpuAdapterChoice(String value, String label) {

        static GpuAdapterChoice automatic() {
            return new GpuAdapterChoice("auto", "Automatic (recommended)");
        }

        static GpuAdapterChoice detected(GpuAdapter adapter) {
            String memory = adapter.dedicatedVideoMemory() >= 1024L * 1024L * 1024L
                    ? " · " + adapter.dedicatedVideoMemory() / (1024L * 1024L * 1024L) + " GiB"
                    : "";
            return new GpuAdapterChoice(Integer.toString(adapter.index()),
                    "[" + adapter.index() + "] " + adapter.name() + memory);
        }

        static GpuAdapterChoice unavailable(String value) {
            return new GpuAdapterChoice(value, "[" + value + "] Previously configured (not detected)");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final class SidebarPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final JPanel brandCopy;
        private final VisionLabel brandTitle;
        private final VisionLabel brandSubtitle;
        private final NavButton receiverButton;
        private final NavButton settingsButton;
        private boolean compact;

        SidebarPanel(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
            setLayout(new BorderLayout());
            setPreferredSize(new Dimension(204, 100));
            setBorder(BorderFactory.createEmptyBorder(18, 12, 16, 12));

            JPanel top = transparentPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

            JPanel brand = transparentPanel(new BorderLayout(11, 0));
            brand.setAlignmentX(Component.LEFT_ALIGNMENT);
            brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            BrandMark mark = new BrandMark(palette);
            brand.add(mark, BorderLayout.WEST);
            brandCopy = transparentPanel();
            brandCopy.setLayout(new BoxLayout(brandCopy, BoxLayout.Y_AXIS));
            brandTitle = label("Java AirPlay", 15, Font.BOLD, TextTone.PRIMARY);
            brandSubtitle = label(config.receiverName(), 11, Font.PLAIN, TextTone.TERTIARY);
            brandSubtitle.setToolTipText(config.receiverName());
            brandCopy.add(brandTitle);
            brandCopy.add(Box.createVerticalStrut(3));
            brandCopy.add(brandSubtitle);
            brand.add(brandCopy, BorderLayout.CENTER);
            top.add(brand);
            top.add(Box.createVerticalStrut(24));

            receiverButton = new NavButton(palette, "Receiver", NavigationIcon.RECEIVER,
                    event -> selectSection(RECEIVER_VIEW));
            settingsButton = new NavButton(palette, "Settings", NavigationIcon.SETTINGS,
                    event -> selectSection(SETTINGS_VIEW));
            receiverButton.setSelectedState(true);
            top.add(receiverButton);
            top.add(Box.createVerticalStrut(4));
            top.add(settingsButton);

            add(top, BorderLayout.NORTH);
        }

        void setSelectedSection(String section) {
            receiverButton.setSelectedState(RECEIVER_VIEW.equals(section));
            settingsButton.setSelectedState(SETTINGS_VIEW.equals(section));
        }

        void setCompact(boolean compact) {
            if (this.compact == compact) {
                return;
            }
            this.compact = compact;
            setPreferredSize(new Dimension(compact ? 68 : 204, 100));
            brandCopy.setVisible(!compact);
            receiverButton.setCompact(compact);
            settingsButton.setCompact(compact);
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.glass());
                copy.fillRect(0, 0, getWidth(), getHeight());
                copy.setColor(colors.divider());
                copy.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static class GlassPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final int radius;
        private final boolean strong;

        GlassPanel(Supplier<ThemeManager.ThemePalette> palette, int radius, boolean strong) {
            this.palette = palette;
            this.radius = radius;
            this.strong = strong;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                double width = getWidth() - 2.0;
                double height = getHeight() - 3.0;
                copy.setColor(colors.shadow());
                copy.fill(new RoundRectangle2D.Double(1, 2, width, height, radius, radius));
                copy.setColor(strong ? colors.glassStrong() : colors.glass());
                copy.fill(new RoundRectangle2D.Double(1, 0, width, height, radius, radius));
                copy.setColor(colors.divider());
                copy.setStroke(new BasicStroke(1f));
                copy.draw(new RoundRectangle2D.Double(1.5, 0.5, width - 1, height - 1, radius, radius));
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class AmbientPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        AmbientPanel(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.backgroundStart());
                copy.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class VisionLabel extends JLabel {

        private final TextTone tone;
        private final Supplier<ThemeManager.ThemePalette> palette;

        VisionLabel(String text, float size, int style, TextTone tone,
                    Supplier<ThemeManager.ThemePalette> palette) {
            super(text);
            this.tone = tone;
            this.palette = palette;
            setFont(interfaceFont(size, style));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            ThemeManager.ThemePalette colors = palette.get();
            setForeground(switch (tone) {
                case PRIMARY -> colors.textPrimary();
                case SECONDARY -> colors.textSecondary();
                case TERTIARY -> colors.textTertiary();
            });
            super.paintComponent(graphics);
        }
    }

    private static final class BrandMark extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;

        BrandMark(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setPreferredSize(new Dimension(42, 42));
            setMinimumSize(getPreferredSize());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                drawBrandGlyph(copy, 3, 2, 36, palette.get());
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class NavButton extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final String label;
        private final NavigationIcon icon;
        private boolean compact;

        NavButton(Supplier<ThemeManager.ThemePalette> palette, String label, NavigationIcon icon,
                  ActionListener action) {
            super(label);
            this.palette = palette;
            this.label = label;
            this.icon = icon;
            setFont(interfaceFont(13, Font.BOLD));
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setHorizontalAlignment(SwingConstants.LEFT);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMinimumSize(new Dimension(180, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setPreferredSize(new Dimension(180, 46));
            setToolTipText(label);
            addActionListener(action);
        }

        void setSelectedState(boolean selected) {
            setSelected(selected);
        }

        void setCompact(boolean compact) {
            this.compact = compact;
            setPreferredSize(new Dimension(compact ? 44 : 180, 46));
            setMinimumSize(new Dimension(compact ? 44 : 180, 46));
            setMaximumSize(new Dimension(compact ? 44 : Integer.MAX_VALUE, 46));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                if (isSelected() || getModel().isRollover() || getModel().isArmed()) {
                    copy.setColor(isSelected() ? colors.controlHover() : colors.control());
                    copy.fill(new RoundRectangle2D.Double(0, 1, getWidth(), getHeight() - 2, 8, 8));
                }
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 2, getWidth() - 2, getHeight() - 4, 8, 8));
                }
                Color iconColor = isSelected() ? colors.accent() : colors.textSecondary();
                drawNavigationIcon(copy, icon, 15, 14, iconColor);
                if (!compact) {
                    copy.setFont(getFont());
                    copy.setColor(isSelected() ? colors.accent() : colors.textSecondary());
                    FontMetrics metrics = copy.getFontMetrics();
                    copy.drawString(label, 47, (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                }
            } finally {
                copy.dispose();
            }
        }

        private static void drawNavigationIcon(Graphics2D graphics, NavigationIcon icon, int x, int y, Color color) {
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (icon == NavigationIcon.RECEIVER) {
                drawAirPlayGlyph(graphics, x, y, 20, 20, color, 1.8f);
                return;
            }

            double centerX = x + 9.5;
            double centerY = y + 10;
            graphics.draw(new Ellipse2D.Double(centerX - 6, centerY - 6, 12, 12));
            graphics.draw(new Ellipse2D.Double(centerX - 2.2, centerY - 2.2, 4.4, 4.4));
            for (int index = 0; index < 8; index++) {
                double angle = Math.PI * index / 4;
                graphics.drawLine(
                        (int) Math.round(centerX + Math.cos(angle) * 7),
                        (int) Math.round(centerY + Math.sin(angle) * 7),
                        (int) Math.round(centerX + Math.cos(angle) * 9),
                        (int) Math.round(centerY + Math.sin(angle) * 9));
            }
        }
    }

    private static final class StatusChip extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final boolean pairingRequired;
        private ConnectionState state = ConnectionState.READY;

        StatusChip(Supplier<ThemeManager.ThemePalette> palette, boolean pairingRequired) {
            this.palette = palette;
            this.pairingRequired = pairingRequired;
            setOpaque(false);
            setPreferredSize(new Dimension(204, 32));
            setMinimumSize(getPreferredSize());
            getAccessibleContext().setAccessibleName(accessibleName(state));
        }

        void setState(ConnectionState state) {
            String oldName = accessibleName(this.state);
            this.state = state;
            String newName = accessibleName(state);
            getAccessibleContext().setAccessibleName(newName);
            firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, oldName, newName);
            repaint();
        }

        private static String accessibleName(ConnectionState state) {
            return "Receiver status: " + state.label;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 18, 18));
                Color stateColor = switch (state) {
                    case READY, CONNECTED -> colors.success();
                    case CONNECTING -> colors.warning();
                };
                copy.setColor(stateColor);
                copy.fill(new Ellipse2D.Double(12, 12, 8, 8));
                copy.setFont(interfaceFont(12, Font.BOLD));
                copy.setColor(colors.textPrimary());
                FontMetrics metrics = copy.getFontMetrics();
                copy.drawString(state.label, 29,
                        (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                copy.setColor(colors.divider());
                copy.drawLine(108, 8, 108, getHeight() - 8);
                drawLock(copy, 120, 9, pairingRequired ? colors.success() : colors.textTertiary());
                copy.setFont(interfaceFont(11, Font.BOLD));
                copy.setColor(colors.textSecondary());
                FontMetrics pairingMetrics = copy.getFontMetrics();
                copy.drawString(pairingRequired ? "Secure" : "Optional", 140,
                        (getHeight() + pairingMetrics.getAscent() - pairingMetrics.getDescent()) / 2);
            } finally {
                copy.dispose();
            }
        }

        private static void drawLock(Graphics2D graphics, int x, int y, Color color) {
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.draw(new RoundRectangle2D.Double(x, y + 7, 12, 10, 3, 3));
            Path2D shackle = new Path2D.Double();
            shackle.moveTo(x + 3, y + 7);
            shackle.lineTo(x + 3, y + 5);
            shackle.curveTo(x + 3, y + 1, x + 9, y + 1, x + 9, y + 5);
            shackle.lineTo(x + 9, y + 7);
            graphics.draw(shackle);
        }
    }

    private static final class ReceiverIllustration extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;

        ReceiverIllustration(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setPreferredSize(new Dimension(240, 168));
            setMinimumSize(new Dimension(140, 112));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                double glyphSize = Math.min(98, Math.min(getWidth() - 40.0, getHeight() - 38.0));
                double x = (getWidth() - glyphSize) / 2;
                double y = (getHeight() - glyphSize) / 2;
                Color glow = new Color(colors.accent().getRed(), colors.accent().getGreen(),
                        colors.accent().getBlue(), colors.dark() ? 45 : 30);
                drawAirPlayGlyph(copy, x, y, glyphSize, glyphSize, glow, 11f);
                drawAirPlayGlyph(copy, x, y, glyphSize, glyphSize, colors.accent(), 3.2f);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class InfoChip extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final String text;

        InfoChip(Supplier<ThemeManager.ThemePalette> palette, String text, int width) {
            this.palette = palette;
            this.text = text;
            setPreferredSize(new Dimension(width, 31));
            setMinimumSize(getPreferredSize());
            getAccessibleContext().setAccessibleName(text);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleInfoChip();
            }
            return accessibleContext;
        }

        private final class AccessibleInfoChip extends AccessibleJComponent {
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 17, 17));
                copy.setFont(interfaceFont(11, Font.BOLD));
                copy.setColor(colors.textSecondary());
                FontMetrics metrics = copy.getFontMetrics();
                int x = (getWidth() - metrics.stringWidth(text)) / 2;
                int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                copy.drawString(text, x, y);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class SettingRow extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        SettingRow(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(palette.get().divider());
                copy.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class SettingsFooter extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        SettingsFooter(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(palette.get().glassStrong());
                copy.fillRect(0, 0, getWidth(), getHeight());
                copy.setColor(palette.get().divider());
                copy.drawLine(0, 0, getWidth(), 0);
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class ThemeSelector extends GlassPanel {

        private final ThemeManager manager;
        private final JToggleButton[] buttons;

        ThemeSelector(Supplier<ThemeManager.ThemePalette> palette, ThemeManager manager) {
            super(palette, 8, false);
            this.manager = manager;
            setLayout(new GridLayout(1, ThemeManager.ThemeMode.values().length, 2, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 3, 5, 3));
            setPreferredSize(new Dimension(180, 38));
            setMinimumSize(getPreferredSize());

            ButtonGroup group = new ButtonGroup();
            ThemeManager.ThemeMode[] modes = ThemeManager.ThemeMode.values();
            buttons = new JToggleButton[modes.length];
            for (int index = 0; index < modes.length; index++) {
                ThemeManager.ThemeMode mode = modes[index];
                ThemeChoiceButton button = new ThemeChoiceButton(palette, mode);
                button.addActionListener(event -> manager.setMode(mode));
                group.add(button);
                add(button);
                buttons[index] = button;
            }
            syncSelection();
        }

        void syncSelection() {
            ThemeManager.ThemeMode[] modes = ThemeManager.ThemeMode.values();
            for (int index = 0; index < modes.length; index++) {
                buttons[index].setSelected(modes[index] == manager.mode());
            }
            repaint();
        }
    }

    private static final class ThemeChoiceButton extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final ThemeManager.ThemeMode mode;

        ThemeChoiceButton(Supplier<ThemeManager.ThemePalette> palette, ThemeManager.ThemeMode mode) {
            super(mode.label());
            this.palette = palette;
            this.mode = mode;
            setFont(interfaceFont(11, Font.BOLD));
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(mode == ThemeManager.ThemeMode.SYSTEM ? "Follow the operating system appearance" : null);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                if (isSelected()) {
                    copy.setColor(colors.selected());
                    copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() - 1, 13, 13));
                } else if (getModel().isRollover()) {
                    copy.setColor(colors.controlHover());
                    copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() - 1, 13, 13));
                }
                copy.setFont(getFont());
                copy.setColor(isSelected() ? colors.selectedText() : colors.textSecondary());
                FontMetrics metrics = copy.getFontMetrics();
                int x = (getWidth() - metrics.stringWidth(mode.label())) / 2;
                int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                copy.drawString(mode.label(), x, y);
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 3, 12, 12));
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class VisionToggle extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final Timer animation;
        private float thumbPosition;

        VisionToggle(Supplier<ThemeManager.ThemePalette> palette, boolean selected) {
            this.palette = palette;
            setSelected(selected);
            thumbPosition = selected ? 1f : 0f;
            setPreferredSize(new Dimension(48, 28));
            setMinimumSize(getPreferredSize());
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            animation = new Timer(16, event -> animateThumb());
            addActionListener(event -> animation.start());
        }

        private void animateThumb() {
            float target = isSelected() ? 1f : 0f;
            thumbPosition += (target - thumbPosition) * 0.32f;
            if (Math.abs(target - thumbPosition) < 0.01f) {
                thumbPosition = target;
                animation.stop();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(isSelected() ? colors.success() : colors.controlHover());
                copy.fill(new RoundRectangle2D.Double(0, 1, getWidth(), getHeight() - 2, 26, 26));
                double thumbX = 3 + thumbPosition * (getWidth() - 26);
                copy.setColor(Color.WHITE);
                copy.fill(new Ellipse2D.Double(thumbX, 4, 20, 20));
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(0.75, 1.75, getWidth() - 1.5, getHeight() - 3.5, 25, 25));
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class ToolButton extends JButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final ToolIcon icon;

        ToolButton(Supplier<ThemeManager.ThemePalette> palette, ToolIcon icon) {
            this.palette = palette;
            this.icon = icon;
            setPreferredSize(new Dimension(36, 36));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("Open identity file folder");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(getModel().isRollover() ? colors.controlHover() : colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 8, 8));
                if (icon == ToolIcon.FOLDER) {
                    drawFolder(copy, 8, 8, 20, colors.textSecondary());
                }
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 8, 8));
                }
            } finally {
                copy.dispose();
            }
        }

        private static void drawFolder(Graphics2D graphics, double x, double y, double size, Color color) {
            double scale = size / 24.0;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.translate(x, y);
                copy.scale(scale, scale);
                copy.setColor(color);
                copy.setStroke(new BasicStroke((float) (1.8 / scale),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D folder = new Path2D.Double();
                folder.moveTo(3, 6);
                folder.curveTo(3, 4.9, 3.9, 4, 5, 4);
                folder.lineTo(9, 4);
                folder.lineTo(11, 6);
                folder.lineTo(19, 6);
                folder.curveTo(20.1, 6, 21, 6.9, 21, 8);
                folder.lineTo(21, 18);
                folder.curveTo(21, 19.1, 20.1, 20, 19, 20);
                folder.lineTo(5, 20);
                folder.curveTo(3.9, 20, 3, 19.1, 3, 18);
                folder.closePath();
                copy.draw(folder);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class ActionButton extends JButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final boolean primary;

        ActionButton(Supplier<ThemeManager.ThemePalette> palette, String label, int width, boolean primary) {
            super(label);
            this.palette = palette;
            this.primary = primary;
            setFont(interfaceFont(12, Font.BOLD));
            setPreferredSize(new Dimension(width, 38));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setMargin(new Insets(6, 12, 7, 12));
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            ThemeManager.ThemePalette colors = palette.get();
            try {
                enableQuality(copy);
                copy.setColor(!isEnabled() ? colors.control()
                        : primary
                        ? colors.accent()
                        : getModel().isRollover() ? colors.controlHover() : colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 8, 8));
            } finally {
                copy.dispose();
            }
            setForeground(!isEnabled() ? colors.textTertiary()
                    : primary ? Color.WHITE : colors.textPrimary());
            super.paintComponent(graphics);
            if (isFocusOwner()) {
                Graphics2D focus = (Graphics2D) graphics.create();
                try {
                    enableQuality(focus);
                    focus.setColor(colors.accent());
                    focus.setStroke(new BasicStroke(1.5f));
                    focus.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 8, 8));
                } finally {
                    focus.dispose();
                }
            }
        }
    }
}
