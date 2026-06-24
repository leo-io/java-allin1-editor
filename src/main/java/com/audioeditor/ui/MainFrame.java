package com.audioeditor.ui;

import com.audioeditor.audio.AudioEngine;
import com.audioeditor.io.JsonIO;
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
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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

/**
 * Main application window: menu + transport/edit toolbar, the timeline, and the
 * CRUD tables. Owns the playback position timer that drives the playhead and the
 * metronome, and the file open/save logic.
 */
public class MainFrame extends JFrame {

    private final ProjectModel model = new ProjectModel();
    private final AudioEngine audio = new AudioEngine();
    private final SelectionModel selection = new SelectionModel();

    private final TimelinePanel timeline;
    private final JScrollPane timelineScroll;

    private final JLabel timeLabel = new JLabel("0:00.0 / 0:00.0");
    private final JLabel statusLabel = new JLabel("No file loaded");
    private final JButton playBtn = new JButton("▶ Play");
    private final JCheckBox metronome = new JCheckBox("Metronome");
    private final JTextField bpmField = new JTextField(5);
    private final JTextField pathField = new JTextField(34);

    private File currentFile;
    private double lastPos = 0;

    public MainFrame() {
        super("Audio Analysis JSON Editor");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1280, 760);
        setLocationRelativeTo(null);

        timeline = new TimelinePanel(model, audio, selection);
        timelineScroll = new JScrollPane(timeline,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        timelineScroll.setBorder(BorderFactory.createTitledBorder("Timeline — click to seek, drag markers, double-click to add, right-click to delete"));

        setJMenuBar(buildMenu());

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(timelineScroll, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Segments", new SegmentsTablePanel(model, audio, selection));
        tabs.addTab("Beats", new BeatsTablePanel(model, audio, selection));
        tabs.addTab("Downbeats", new DownbeatsTablePanel(model, audio, selection));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, tabs);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        audio.setEndListener(() -> SwingUtilities.invokeLater(this::updatePlayButton));

        // Position timer: playhead + metronome + follow-scroll.
        Timer posTimer = new Timer(30, e -> onTick());
        posTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscard()) {
                    audio.close();
                    dispose();
                    System.exit(0);
                }
            }
        });

        installShortcuts();
        updateFieldsFromModel();
    }

    // ---- menu / toolbar --------------------------------------------------

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem open = new JMenuItem("Open…");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        open.addActionListener(a -> openDialog());

        JMenuItem save = new JMenuItem("Save");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        save.addActionListener(a -> save(false));

        JMenuItem saveAs = new JMenuItem("Save As…");
        saveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAs.addActionListener(a -> save(true));

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

        playBtn.addActionListener(a -> {
            audio.togglePlay();
            updatePlayButton();
        });
        JButton stop = new JButton("■ Stop");
        stop.addActionListener(a -> {
            audio.stop();
            updatePlayButton();
            timeline.setPlayhead(0);
        });

        tb.add(playBtn);
        tb.add(stop);
        tb.add(timeLabel);
        tb.addSeparator();

        tb.add(metronome);
        tb.addSeparator();

        tb.add(new JLabel(" Zoom "));
        JSlider zoom = new JSlider(5, 250, (int) timeline.getZoom());
        zoom.setMaximumSize(new Dimension(160, 30));
        zoom.addChangeListener(e -> timeline.setZoom(zoom.getValue()));
        tb.add(zoom);
        tb.addSeparator();

        tb.add(new JLabel(" BPM "));
        bpmField.setMaximumSize(new Dimension(60, 26));
        bpmField.addActionListener(a -> commitBpm());
        bpmField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                commitBpm();
            }
        });
        tb.add(bpmField);
        tb.addSeparator();

        tb.add(new JLabel(" Audio "));
        pathField.setMaximumSize(new Dimension(420, 26));
        pathField.addActionListener(a -> {
            model.setAudioPath(pathField.getText().trim());
            loadAudio(new File(model.getAudioPath()), false);
        });
        tb.add(pathField);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(a -> browseAudio());
        tb.add(browse);

        return tb;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        p.add(statusLabel, BorderLayout.WEST);
        return p;
    }

    private void installShortcuts() {
        // Space toggles playback when focus isn't in a text field.
        getRootPane().registerKeyboardAction(a -> {
                    if (!(getFocusOwner() instanceof JTextField)) {
                        audio.togglePlay();
                        updatePlayButton();
                    }
                }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
                JPanel.WHEN_IN_FOCUSED_WINDOW);
    }

    // ---- periodic tick ---------------------------------------------------

    private void onTick() {
        if (!audio.isLoaded()) {
            return;
        }
        double pos = audio.getPositionSeconds();
        timeline.setPlayhead(pos);
        timeLabel.setText(fmt(pos) + " / " + fmt(audio.getDurationSeconds()));

        if (audio.isPlaying()) {
            if (metronome.isSelected() && audio.hasMetronome()) {
                fireMetronome(lastPos, pos);
            }
            followPlayhead(pos);
        }
        lastPos = pos;
    }

    private void fireMetronome(double from, double to) {
        if (to <= from) {
            return;
        }
        for (var b : model.getBeats()) {
            double t = b.getTime();
            if (t > from && t <= to) {
                audio.playClick();
                break; // at most one click per tick is plenty at 30ms
            }
        }
    }

    private void followPlayhead(double pos) {
        int x = (int) Math.round(pos * timeline.getZoom());
        Rectangle view = timelineScroll.getViewport().getViewRect();
        if (x < view.x + 40 || x > view.x + view.width - 40) {
            int nx = Math.max(0, x - view.width / 2);
            timeline.scrollRectToVisible(new Rectangle(nx, 0, view.width, timeline.getHeight()));
        }
    }

    // ---- file operations -------------------------------------------------

    private void openDialog() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
        if (currentFile != null) {
            fc.setCurrentDirectory(currentFile.getParentFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(fc.getSelectedFile());
        }
    }

    public void openFile(File f) {
        try {
            ProjectModel loaded = JsonIO.load(f);
            model.copyFrom(loaded);
            currentFile = f;
            setTitle("Audio Analysis JSON Editor — " + f.getName());
            updateFieldsFromModel();
            selection.clear();
            loadAudio(new File(model.getAudioPath()), false);
            statusLabel.setText("Loaded " + f.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to open:\n" + ex.getMessage(),
                    "Open error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save(boolean forceChooser) {
        File target = currentFile;
        if (forceChooser || target == null) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Analysis JSON (*.json)", "json"));
            if (currentFile != null) {
                fc.setSelectedFile(currentFile);
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
            JsonIO.save(model, target);
            currentFile = target;
            setTitle("Audio Analysis JSON Editor — " + target.getName());
            statusLabel.setText("Saved " + target.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to save:\n" + ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean confirmDiscard() {
        if (!model.isDirty()) {
            return true;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Save before continuing?",
                "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION) {
            return false;
        }
        if (r == JOptionPane.YES_OPTION) {
            save(false);
        }
        return true;
    }

    private void browseAudio() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("WAV audio (*.wav)", "wav"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            model.setAudioPath(f.getAbsolutePath());
            pathField.setText(f.getAbsolutePath());
            loadAudio(f, true);
        }
    }

    /** Load audio for playback; if the path is missing, optionally prompt. */
    private void loadAudio(File f, boolean userChosen) {
        if (f == null || f.getPath().isEmpty()) {
            return;
        }
        if (!f.exists()) {
            statusLabel.setText("Audio not found: " + f.getPath());
            if (!userChosen) {
                int r = JOptionPane.showConfirmDialog(this,
                        "Audio file not found:\n" + f.getPath() + "\n\nBrowse for it?",
                        "Audio missing", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) {
                    browseAudio();
                }
            }
            return;
        }
        statusLabel.setText("Decoding audio… " + f.getName());
        new SwingWorker<Exception, Void>() {
            @Override
            protected Exception doInBackground() {
                try {
                    audio.load(f);
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
                if (ex != null) {
                    statusLabel.setText("Audio load failed: " + ex.getMessage());
                } else {
                    statusLabel.setText("Audio ready: " + f.getName()
                            + String.format("  (%.1fs)", audio.getDurationSeconds()));
                    timeLabel.setText(fmt(0) + " / " + fmt(audio.getDurationSeconds()));
                }
                updatePlayButton();
            }
        }.execute();
    }

    // ---- small helpers ---------------------------------------------------

    private void commitBpm() {
        try {
            double v = Double.parseDouble(bpmField.getText().trim());
            if (v != model.getBpm()) {
                model.setBpm(v);
            }
        } catch (NumberFormatException ex) {
            bpmField.setText(trimNum(model.getBpm()));
        }
    }

    private void updateFieldsFromModel() {
        bpmField.setText(trimNum(model.getBpm()));
        pathField.setText(model.getAudioPath());
    }

    private void updatePlayButton() {
        playBtn.setText(audio.isPlaying() ? "❚❚ Pause" : "▶ Play");
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String fmt(double seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int m = (int) (seconds / 60);
        double s = seconds - m * 60;
        return String.format("%d:%04.1f", m, s);
    }
}
