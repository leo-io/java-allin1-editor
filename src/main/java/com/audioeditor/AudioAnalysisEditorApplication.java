package com.audioeditor;

import com.audioeditor.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Entry point. Launches the editor; if a JSON path is passed as the first
 * argument, opens it (and auto-loads the audio referenced inside).
 */
public class AudioAnalysisEditorApplication {

    private static final Logger LOG = Logger.getLogger("com.audioeditor");

    public static void main(String[] args) {
        configureApplicationLogging();
        LOG.info("Application starting");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to default look and feel
        }
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                File commandLineJsonFile = new File(args[0]);
                if (commandLineJsonFile.exists()) {
                    frame.loadAnalysisFileIntoEditor(commandLineJsonFile);
                } else {
                    LOG.warning("File not found from command line: " + commandLineJsonFile.getAbsolutePath());
                }
            }
        });
    }

    private static void configureApplicationLogging() {
        Logger root = Logger.getLogger("com.audioeditor");
        root.setUseParentHandlers(false);
        root.setLevel(Level.ALL);

        CompactLogFormatter formatter = new CompactLogFormatter();

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(formatter);
        root.addHandler(console);

        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".java-allin1-editor", "logs");
            Files.createDirectories(logDir);
            // 1 MB per file, 3 rotating files, append on restart.
            FileHandler file = new FileHandler(logDir.resolve("app%g.log").toString(),
                    1_000_000, 3, true);
            file.setLevel(Level.FINE);
            file.setFormatter(formatter);
            root.addHandler(file);
        } catch (IOException e) {
            root.warning("Could not create log file: " + e.getMessage());
        }
    }

    /** Compact single-line log formatter that trims the package prefix from the logger name. */
    private static final class CompactLogFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            String name = r.getLoggerName();
            // Trim package prefix to keep lines short.
            int dot = name.lastIndexOf('.');
            String simple = dot >= 0 ? name.substring(dot + 1) : name;
            return String.format("%1$tH:%1$tM:%1$tS.%1$tL [%2$s] %3$-7s %4$s%n",
                    r.getMillis(), simple, r.getLevel().getName(), r.getMessage());
        }
    }
}
