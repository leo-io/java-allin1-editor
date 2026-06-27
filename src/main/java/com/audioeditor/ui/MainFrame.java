package com.audioeditor.ui;

import com.audioeditor.application.EditorSession;
import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.application.playback.PlaybackCoordinator;
import com.audioeditor.application.project.ProjectFileController;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.port.audio.AudioPlayer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application window: menu + transport/edit toolbar, the timeline, and the
 * status bar. Owns the playback position timer that drives the playhead and the
 * metronome, and the file open/save logic.
 */
public class MainFrame extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());

    private final ProjectModel projectModel;
    private final ProjectEditor projectEditor;
    private final ProjectFileController projectFileController;
    private final PlaybackCoordinator playbackCoordinator;
    private final AudioPlayer audioPlayer;
    private final SelectionModel sharedSelectionModel;

    private final TimelinePanel timelinePanel;
    private final JScrollPane timelineScrollPane;
    private final PlaybackViewController playbackViewController;

    private final JLabel playbackPositionTimeLabel = new JLabel("0:00.0 / 0:00.0");
    private final JLabel applicationStatusLabel = new JLabel("No file loaded");
    private final JButton playPauseButton = new JButton("▶ Play");
    private final JCheckBox metronomeEnabledCheckBox = new JCheckBox("Metronome");
    private final JTextField beatsPerMinuteTextField = new JTextField(5);
    private final JTextField audioFilePathTextField = new JTextField(34);

    private boolean lastKnownIsPlaying = false;

    public MainFrame(EditorSession editorSession,
                     ProjectEditor projectEditor,
                     ProjectFileController projectFileController,
                     PlaybackCoordinator playbackCoordinator,
                     AudioPlayer audioPlayer,
                     SelectionModel sharedSelectionModel) {
        super("Audio Analysis JSON Editor");
        this.projectModel = Objects.requireNonNull(editorSession, "editorSession").project();
        this.projectEditor = Objects.requireNonNull(projectEditor, "projectEditor");
        this.projectFileController = Objects.requireNonNull(projectFileController, "projectFileController");
        this.playbackCoordinator = Objects.requireNonNull(playbackCoordinator, "playbackCoordinator");
        this.audioPlayer = Objects.requireNonNull(audioPlayer, "audioPlayer");
        this.sharedSelectionModel = Objects.requireNonNull(sharedSelectionModel, "sharedSelectionModel");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1280, 760);
        setLocationRelativeTo(null);

        timelinePanel = new TimelinePanel(projectModel, projectEditor, audioPlayer, sharedSelectionModel);
        timelineScrollPane = new JScrollPane(timelinePanel,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        timelineScrollPane.setBorder(BorderFactory.createTitledBorder("Timeline — segments stacked vertically: click to seek, drag markers/segments, double-click to play, right-click to delete"));
        playbackViewController = new PlaybackViewController(
                audioPlayer, timelinePanel, timelineScrollPane,
                playbackPositionTimeLabel, this::refreshPlayPauseButtonLabel);

        setJMenuBar(buildMenu());

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        JTabbedPane editorTables = new JTabbedPane();
        editorTables.addTab("Segments", new SegmentsTablePanel(
                projectModel, projectEditor, audioPlayer, sharedSelectionModel));
        editorTables.addTab("Beats", new BeatsTablePanel(
                projectModel, projectEditor, audioPlayer, sharedSelectionModel));
        editorTables.addTab("Downbeats", new DownbeatsTablePanel(
                projectModel, projectEditor, audioPlayer, sharedSelectionModel));
        JSplitPane editorSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, timelineScrollPane, editorTables);
        editorSplit.setResizeWeight(0.68);
        editorSplit.setOneTouchExpandable(true);
        top.add(editorSplit, BorderLayout.CENTER);

        add(top, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        audioPlayer.onPlaybackCompleted(() -> SwingUtilities.invokeLater(this::refreshPlayPauseButtonLabel));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LOG.fine("Window close requested");
                if (confirmDiscardUnsavedChanges()) {
                    audioPlayer.close();
                    dispose();
                    LOG.info("Application shutting down");
                    System.exit(0);
                }
            }
        });

        registerGlobalKeyboardShortcuts();
        refreshToolbarFieldsFromProjectModel();
    }

    // ---- menu / toolbar --------------------------------------------------

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem open = new JMenuItem("Open…");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        open.addActionListener(a -> showOpenFileDialog());

        JMenuItem save = new JMenuItem("Save");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        save.addActionListener(a -> saveCurrentAnalysisFile(false));

        JMenuItem saveAs = new JMenuItem("Save As…");
        saveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAs.addActionListener(a -> saveCurrentAnalysisFile(true));

        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(a -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

        file.add(open);
        file.add(save);
        file.add(saveAs);
        file.addSeparator();
        file.add(exit);
        bar.add(file);
        return bar;
    }

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        playPauseButton.addActionListener(a -> {
            LOG.fine("Transport: play/pause toggled (button)");
            audioPlayer.togglePlay();
            refreshPlayPauseButtonLabel();
        });
        JButton stop = new JButton("■ Stop");
        stop.addActionListener(a -> {
            LOG.fine("Transport: stop requested (button)");
            audioPlayer.stop();
            refreshPlayPauseButtonLabel();
            // stop() rewinds to the first kept range, which may not be t=0; mirror it
            // so the playhead lands inside a segment (and stays visible).
            timelinePanel.setPlayheadPositionInSeconds(audioPlayer.getPositionSeconds());
        });

        tb.add(playPauseButton);
        tb.add(stop);
        tb.add(playbackPositionTimeLabel);
        tb.addSeparator();

        metronomeEnabledCheckBox.addActionListener(a -> {
            boolean on = metronomeEnabledCheckBox.isSelected();
            audioPlayer.setMetronomeEnabled(on);
            LOG.fine("Metronome " + (on ? "enabled" : "disabled"));
        });
        tb.add(metronomeEnabledCheckBox);
        tb.addSeparator();

        tb.add(new JLabel(" Zoom "));
        JSlider zoom = new JSlider(60, 300, timelinePanel.getRowHeight());
        zoom.setMaximumSize(new Dimension(160, 30));
        zoom.addChangeListener(e -> {
            timelinePanel.setRowHeight(zoom.getValue());
            if (!zoom.getValueIsAdjusting()) {
                LOG.fine("Row height set to " + zoom.getValue() + " px");
            }
        });
        tb.add(zoom);
        tb.addSeparator();

        tb.add(new JLabel(" BPM "));
        beatsPerMinuteTextField.setMaximumSize(new Dimension(60, 26));
        beatsPerMinuteTextField.addActionListener(a -> commitBeatsPerMinuteFieldValueToModel());
        beatsPerMinuteTextField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                commitBeatsPerMinuteFieldValueToModel();
            }
        });
        tb.add(beatsPerMinuteTextField);
        tb.addSeparator();

        tb.add(new JLabel(" Audio "));
        audioFilePathTextField.setMaximumSize(new Dimension(420, 26));
        audioFilePathTextField.addActionListener(a -> {
            String path = audioFilePathTextField.getText().trim();
            LOG.fine("Audio path entered manually: " + path);
            projectModel.setAudioPath(path);
            loadAudioFileForPlayback(new File(path), false);
        });
        tb.add(audioFilePathTextField);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(a -> showBrowseAudioFileDialog());
        tb.add(browse);

        return tb;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        p.add(applicationStatusLabel, BorderLayout.WEST);
        return p;
    }

    private void registerGlobalKeyboardShortcuts() {
        // Space toggles playback when focus isn't in a text field.
        getRootPane().registerKeyboardAction(a -> {
                    if (!(getFocusOwner() instanceof JTextField)) {
                        LOG.fine("Transport: play/pause toggled (space key)");
                        audioPlayer.togglePlay();
                        refreshPlayPauseButtonLabel();
                    }
                }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
                JPanel.WHEN_IN_FOCUSED_WINDOW);
    }

    // ---- file operations -------------------------------------------------

    private void showOpenFileDialog() {
        LOG.fine("Open file dialog requested");
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
        if (projectFileController.currentFile() != null) {
            fc.setCurrentDirectory(projectFileController.currentFile().toFile().getParentFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadAnalysisFileIntoEditor(fc.getSelectedFile());
        }
    }

    public void loadAnalysisFileIntoEditor(File analysisJsonFile) {
        LOG.info("Opening analysis file: " + analysisJsonFile.getAbsolutePath());
        applicationStatusLabel.setText("Loading " + analysisJsonFile.getName() + "…");
        new SwingWorker<ProjectModel, Void>() {
            @Override
            protected ProjectModel doInBackground() throws Exception {
                return projectFileController.load(analysisJsonFile.toPath());
            }

            @Override
            protected void done() {
                try {
                    ProjectModel loaded = get();
                    projectFileController.activate(loaded, analysisJsonFile.toPath());
                    setTitle("Audio Analysis JSON Editor — " + analysisJsonFile.getName());
                    refreshToolbarFieldsFromProjectModel();
                    sharedSelectionModel.clearSelection();
                    loadAudioFileForPlayback(new File(projectModel.getAudioPath()), false);
                    applicationStatusLabel.setText("Loaded " + analysisJsonFile.getName());
                    int totalBeats = projectModel.getAllBeatsFlat().size();
                    int totalBars = projectModel.getSegments().stream()
                            .mapToInt(s -> s.getBars().size()).sum();
                    LOG.info("Opened: segments=" + projectModel.getSegments().size()
                            + " bars=" + totalBars + " beats=" + totalBeats);
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, "Failed to open file: " + analysisJsonFile.getAbsolutePath(), ex);
                    applicationStatusLabel.setText("Failed to open: " + ex.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this, "Failed to open:\n" + ex.getMessage(),
                            "Open error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void saveCurrentAnalysisFile(boolean forceShowSaveDialog) {
        LOG.fine("Save requested (forceShowSaveDialog=" + forceShowSaveDialog + ")");
        File target = projectFileController.currentFile() == null ? null : projectFileController.currentFile().toFile();
        if (forceShowSaveDialog || target == null) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
            if (projectFileController.currentFile() != null) {
                fc.setSelectedFile(projectFileController.currentFile().toFile());
            }
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            target = fc.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".json")) {
                target = new File(target.getParentFile(), target.getName() + ".json");
            }
        }
        try {
            projectFileController.save(target.toPath());
            setTitle("Audio Analysis JSON Editor — " + target.getName());
            applicationStatusLabel.setText("Saved " + target.getName());
            LOG.info("Saved analysis file: " + target.getAbsolutePath());
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Failed to save file: " + target.getAbsolutePath(), ex);
            JOptionPane.showMessageDialog(this, "Failed to save:\n" + ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean confirmDiscardUnsavedChanges() {
        if (!projectFileController.hasUnsavedChanges()) {
            return true;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Save before continuing?",
                "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION) {
            LOG.fine("Discard-changes dialog: user cancelled");
            return false;
        }
        if (r == JOptionPane.YES_OPTION) {
            LOG.fine("Discard-changes dialog: user chose to save first");
            saveCurrentAnalysisFile(false);
        } else {
            LOG.fine("Discard-changes dialog: user chose to discard");
        }
        return true;
    }

    private void showBrowseAudioFileDialog() {
        LOG.fine("Browse audio file dialog requested");
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("WAV audio (*.wav)", "wav"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            projectModel.setAudioPath(f.getAbsolutePath());
            audioFilePathTextField.setText(f.getAbsolutePath());
            loadAudioFileForPlayback(f, true);
        }
    }

    /** Load audio for playback; if the path is missing, optionally prompt. */
    private void loadAudioFileForPlayback(File audioFile, boolean wasExplicitlyChosenByUser) {
        if (audioFile == null || audioFile.getPath().isEmpty()) {
            return;
        }
        if (!audioFile.exists()) {
            applicationStatusLabel.setText("Audio not found: " + audioFile.getPath());
            if (!wasExplicitlyChosenByUser) {
                int r = JOptionPane.showConfirmDialog(this,
                        "Audio file not found:\n" + audioFile.getPath() + "\n\nBrowse for it?",
                        "Audio missing", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) {
                    showBrowseAudioFileDialog();
                }
            }
            return;
        }
        applicationStatusLabel.setText("Decoding audio… " + audioFile.getName());
        LOG.info("Loading audio for playback: " + audioFile.getAbsolutePath());
        new AudioFileDecodingWorker(audioFile.toPath(), audioPlayer, ex -> {
            if (ex != null) {
                LOG.log(Level.WARNING, "Audio load failed: " + audioFile.getAbsolutePath(), ex);
                applicationStatusLabel.setText("Audio load failed: " + ex.getMessage());
            } else {
                applicationStatusLabel.setText("Audio ready: " + audioFile.getName()
                        + String.format("  (%.1fs)", audioPlayer.getDurationSeconds()));
                playbackViewController.audioLoaded();
                // Sample rate is known now: (re)publish the metronome schedule and
                // the playable segment ranges, and honour the current checkbox state.
                playbackCoordinator.synchronizeAll();
                audioPlayer.setMetronomeEnabled(metronomeEnabledCheckBox.isSelected());
            }
            refreshPlayPauseButtonLabel();
        }).execute();
    }

    // ---- small helpers ---------------------------------------------------

    private void commitBeatsPerMinuteFieldValueToModel() {
        try {
            double v = Double.parseDouble(beatsPerMinuteTextField.getText().trim());
            if (v != projectModel.getBpm()) {
                LOG.fine("BPM changed: " + projectModel.getBpm() + " -> " + v);
                projectModel.setBpm(v);
            }
        } catch (NumberFormatException ex) {
            LOG.warning("Invalid BPM value entered: " + beatsPerMinuteTextField.getText());
            beatsPerMinuteTextField.setText(formatNumericValueOmittingTrailingZero(projectModel.getBpm()));
        }
    }

    private void refreshToolbarFieldsFromProjectModel() {
        beatsPerMinuteTextField.setText(formatNumericValueOmittingTrailingZero(projectModel.getBpm()));
        audioFilePathTextField.setText(projectModel.getAudioPath());
    }

    private void refreshPlayPauseButtonLabel() {
        boolean isPlaying = audioPlayer.isPlaying();
        if (isPlaying != lastKnownIsPlaying) {
            lastKnownIsPlaying = isPlaying;
            playPauseButton.setText(isPlaying ? "❚❚ Pause" : "▶ Play");
            timelinePanel.repaint();
            if (isPlaying) {
                playbackViewController.playbackStarted();
            }
        }
    }

    private static String formatNumericValueOmittingTrailingZero(double numericValue) {
        return numericValue == Math.rint(numericValue) ? Long.toString((long) numericValue) : Double.toString(numericValue);
    }

}
