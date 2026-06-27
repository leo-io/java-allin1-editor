package com.audioeditor.ui;

import com.audioeditor.port.audio.AudioPlayer;

import javax.swing.SwingWorker;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Runs audio decoding/device setup away from Swing's event-dispatch thread. */
final class AudioFileDecodingWorker extends SwingWorker<Exception, Void> {

    private final Path audioFile;
    private final AudioPlayer audioPlayer;
    private final Consumer<Exception> completion;

    AudioFileDecodingWorker(Path audioFile, AudioPlayer audioPlayer, Consumer<Exception> completion) {
        this.audioFile = audioFile;
        this.audioPlayer = audioPlayer;
        this.completion = completion;
    }

    @Override
    protected Exception doInBackground() {
        try {
            audioPlayer.load(audioFile);
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    @Override
    protected void done() {
        try {
            completion.accept(get());
        } catch (Exception exception) {
            completion.accept(exception);
        }
    }
}
