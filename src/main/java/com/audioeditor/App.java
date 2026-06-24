package com.audioeditor;

import com.audioeditor.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;

/**
 * Entry point. Launches the editor; if a JSON path is passed as the first
 * argument, opens it (and auto-loads the audio referenced inside).
 */
public class App {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to default look and feel
        }
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                File f = new File(args[0]);
                if (f.exists()) {
                    frame.openFile(f);
                }
            }
        });
    }
}
