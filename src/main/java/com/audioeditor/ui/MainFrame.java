package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
import com.audioeditor.io.AllIn1JsonFileRepository;
import com.audioeditor.io.MusicAnalysisFileRepository;
import com.audioeditor.model.ProjectModel;

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
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application window: menu + transport/edit toolbar, the timeline, and the
 * status bar. Owns the playback position timer that drives the playhead and the
 * metronome, and the file open/save logic.
 */
public class MainFrame extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());

    private final ProjectModel projectModel = new ProjectModel();
    private final PcmWavPlaybackEngine pcmWavPlaybackEngine = new PcmWavPlaybackEngine();
    private final SelectionModel sharedSelectionModel = new SelectionModel();
    private final MusicAnalysisFileRepository musicAnalysisFileRepository = AllIn1JsonFileRepository.INSTANCE;

    private final TimelinePanel timelinePanel;
    private final JScrollPane timelineScrollPane;

    private final JLabel playbackPositionTimeLabel = new JLabel("0:00.0 / 0:00.0");
    private final JLabel applicationStatusLabel = new JLabel("No file loaded");
    private final JButton playPauseButton = new JButton("▶ Play");
    private final JCheckBox metronomeEnabledCheckBox = new JCheckBox("Metronome");
    private final JTextField beatsPerMinuteTextField = new JTextField(5);
    private final JTextField audioFilePathTextField = new JTextField(34);

    private File currentlyOpenedAnalysisFile;
    private boolean lastKnownIsPlaying = false;
    // Cached label state so the 33 Hz tick allocates/repaints only when the
    // displayed text actually changes.
    private String cachedDurationLabelText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(0);
    private String lastRenderedPositionText = null;
    // Reused for follow-scroll so the 33 Hz tick never allocates a Rectangle.
    private final Rectangle scrollTargetRect = new Rectangle();
    // When true the viewport auto-scrolls to keep the playhead visible.
    // Cleared when the user manually scrolls during playback; restored on play-start.
    private boolean followPlayhead = true;
    private boolean isAutoScrolling = false;

    public MainFrame() {
        super("Audio Analysis JSON Editor");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1280, 760);
        setLocationRelativeTo(null);

        timelinePanel = new TimelinePanel(projectModel, pcmWavPlaybackEngine, sharedSelectionModel);
        timelineScrollPane = new JScrollPane(timelinePanel,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        timelineScrollPane.setBorder(BorderFactory.createTitledBorder("Timeline — segments stacked vertically: click to seek, drag markers/segments, double-click to play, right-click to delete"));
        timelineScrollPane.getViewport().addChangeListener(e -> {
            if (!isAutoScrolling && pcmWavPlaybackEngine.isPlaying()) {
                followPlayhead = false;
            }
        });

        setJMenuBar(buildMenu());

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(timelineScrollPane, BorderLayout.CENTER);

        add(top, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        pcmWavPlaybackEngine.setPlaybackCompletionListener(() -> SwingUtilities.invokeLater(this::refreshPlayPauseButtonLabel));

        // Keep the engine's sample-accurate metronome schedule in sync with edits.
        projectModel.addProjectChangeListener(this::pushMetronomeBeatsToEngine);
        // Restrict playback to the surviving segments so editor deletions are not
        // heard (the engine plays only these ranges, stitching across the gaps).
        projectModel.addProjectChangeListener(this::pushPlayableRangesToEngine);

        // Position timer: playhead + follow-scroll (the metronome is mixed in by
        // the audio engine itself, sample-accurately — not driven from this tick).
        Timer posTimer = new Timer(30, e -> onPlaybackPositionTimerTick());
        posTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LOG.fine("Window close requested");
                if (confirmDiscardUnsavedChanges()) {
                    pcmWavPlaybackEngine.close();
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
            pcmWavPlaybackEngine.togglePlay();
            refreshPlayPauseButtonLabel();
        });
        JButton stop = new JButton("■ Stop");
        stop.addActionListener(a -> {
            LOG.fine("Transport: stop requested (button)");
            pcmWavPlaybackEngine.stop();
            refreshPlayPauseButtonLabel();
            // stop() rewinds to the first kept range, which may not be t=0; mirror it
            // so the playhead lands inside a segment (and stays visible).
            timelinePanel.setPlayheadPositionInSeconds(pcmWavPlaybackEngine.getPositionSeconds());
        });

        tb.add(playPauseButton);
        tb.add(stop);
        tb.add(playbackPositionTimeLabel);
        tb.addSeparator();

        metronomeEnabledCheckBox.addActionListener(a -> {
            boolean on = metronomeEnabledCheckBox.isSelected();
            pcmWavPlaybackEngine.setMetronomeEnabled(on);
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
                        pcmWavPlaybackEngine.togglePlay();
                        refreshPlayPauseButtonLabel();
                    }
                }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
                JPanel.WHEN_IN_FOCUSED_WINDOW);
    }

    // ---- periodic tick ---------------------------------------------------

    private void onPlaybackPositionTimerTick() {
        if (!pcmWavPlaybackEngine.isLoaded()) {
            return;
        }
        double pos = pcmWavPlaybackEngine.getPositionSeconds();
        timelinePanel.setPlayheadPositionInSeconds(pos);

        // Only touch the label when the rendered text changes (~3 ticks per tenth
        // of a second), so the steady tick does not allocate/repaint every 30 ms.
        String positionText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(pos);
        if (!positionText.equals(lastRenderedPositionText)) {
            lastRenderedPositionText = positionText;
            playbackPositionTimeLabel.setText(positionText + " / " + cachedDurationLabelText);
        }

        refreshPlayPauseButtonLabel();
        if (pcmWavPlaybackEngine.isPlaying()) {
            scrollTimelineToKeepPlayheadVisible(pos);
        }
    }

    /** Build the seconds→engine metronome schedule from the current beat list. */
    private void pushMetronomeBeatsToEngine() {
        var beats = projectModel.getAllBeatsFlat();
        double[] times = new double[beats.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = beats.get(i).getStart();
        }
        pcmWavPlaybackEngine.setMetronomeBeatTimes(times);
    }

    /** Restrict playback to the surviving segments' time ranges (an edit-decision list). */
    private void pushPlayableRangesToEngine() {
        var segments = projectModel.getSegments();
        double[] ranges = new double[segments.size() * 2];
        int i = 0;
        for (var s : segments) {
            ranges[i++] = s.getStart();
            ranges[i++] = s.getEnd();
        }
        pcmWavPlaybackEngine.setPlayableTimeRangesSeconds(ranges);
    }

    private void scrollTimelineToKeepPlayheadVisible(double playheadPositionInSeconds) {
        if (!followPlayhead) {
            return;
        }
        int y = timelinePanel.getPlayheadCenterY();
        if (y < 0) {
            return;
        }
        Rectangle view = timelineScrollPane.getViewport().getViewRect();
        // Keep the active segment row inside a vertical margin band. When it
        // leaves the band we nudge the view by only the overflow (a few px per
        // 30 ms tick), not a half-viewport recenter — that avoids the large
        // repaint/revalidate spike a centered jump would cause.
        int margin = Math.max(40, view.height / 8);
        int newViewY = view.y;
        if (y < view.y + margin) {
            newViewY = Math.max(0, y - margin);
        } else if (y > view.y + view.height - margin) {
            newViewY = y - view.height + margin;
        }
        if (newViewY != view.y) {
            isAutoScrolling = true;
            scrollTargetRect.setBounds(0, newViewY, timelinePanel.getWidth(), view.height);
            timelinePanel.scrollRectToVisible(scrollTargetRect);
            isAutoScrolling = false;
        }
    }

    // ---- file operations -------------------------------------------------

    private void showOpenFileDialog() {
        LOG.fine("Open file dialog requested");
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
        if (currentlyOpenedAnalysisFile != null) {
            fc.setCurrentDirectory(currentlyOpenedAnalysisFile.getParentFile());
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
                return musicAnalysisFileRepository.loadFromFile(analysisJsonFile);
            }

            @Override
            protected void done() {
                try {
                    ProjectModel loaded = get();
                    projectModel.copyFrom(loaded);
                    currentlyOpenedAnalysisFile = analysisJsonFile;
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
        File target = currentlyOpenedAnalysisFile;
        if (forceShowSaveDialog || target == null) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
            if (currentlyOpenedAnalysisFile != null) {
                fc.setSelectedFile(currentlyOpenedAnalysisFile);
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
            musicAnalysisFileRepository.saveToFile(projectModel, target);
            currentlyOpenedAnalysisFile = target;
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
        if (!projectModel.isDirty()) {
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
        new AudioFileDecodingWorker(audioFile, pcmWavPlaybackEngine, ex -> {
            if (ex != null) {
                LOG.log(Level.WARNING, "Audio load failed: " + audioFile.getAbsolutePath(), ex);
                applicationStatusLabel.setText("Audio load failed: " + ex.getMessage());
            } else {
                applicationStatusLabel.setText("Audio ready: " + audioFile.getName()
                        + String.format("  (%.1fs)", pcmWavPlaybackEngine.getDurationSeconds()));
                cachedDurationLabelText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(
                        pcmWavPlaybackEngine.getDurationSeconds());
                lastRenderedPositionText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(0);
                playbackPositionTimeLabel.setText(lastRenderedPositionText + " / " + cachedDurationLabelText);
                // Sample rate is known now: (re)publish the metronome schedule and
                // the playable segment ranges, and honour the current checkbox state.
                pushMetronomeBeatsToEngine();
                pushPlayableRangesToEngine();
                pcmWavPlaybackEngine.setMetronomeEnabled(metronomeEnabledCheckBox.isSelected());
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
        boolean isPlaying = pcmWavPlaybackEngine.isPlaying();
        if (isPlaying != lastKnownIsPlaying) {
            lastKnownIsPlaying = isPlaying;
            playPauseButton.setText(isPlaying ? "❚❚ Pause" : "▶ Play");
            timelinePanel.repaint();
            if (isPlaying) {
                followPlayhead = true;
            }
        }
    }

    private static String formatNumericValueOmittingTrailingZero(double numericValue) {
        return numericValue == Math.rint(numericValue) ? Long.toString((long) numericValue) : Double.toString(numericValue);
    }

    /** Decodes a WAV file off the EDT, reporting success/failure via a callback. */
    private static final class AudioFileDecodingWorker extends SwingWorker<Exception, Void> {
        private final File audioFileToLoad;
        private final PcmWavPlaybackEngine playbackEngine;
        private final Consumer<Exception> onCompletionCallback;

        AudioFileDecodingWorker(File audioFileToLoad,
                                PcmWavPlaybackEngine playbackEngine,
                                Consumer<Exception> onCompletionCallback) {
            this.audioFileToLoad = audioFileToLoad;
            this.playbackEngine = playbackEngine;
            this.onCompletionCallback = onCompletionCallback;
        }

        @Override
        protected Exception doInBackground() {
            try {
                playbackEngine.loadAndDecodeWavFile(audioFileToLoad);
                return null;
            } catch (Exception ex) {
                return ex;
            }
        }

        @Override
        protected void done() {
            Exception ex;
            try {
                ex = get();
            } catch (Exception e) {
                ex = e;
            }
            onCompletionCallback.accept(ex);
        }
    }
}
