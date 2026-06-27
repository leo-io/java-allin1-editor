package com.audioeditor.ui;

import com.audioeditor.port.audio.AudioPlayer;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.awt.Rectangle;

/** Owns the Swing playhead timer, time label, and follow-scroll view state. */
final class PlaybackViewController {

    private final AudioPlayer audioPlayer;
    private final TimelinePanel timeline;
    private final JScrollPane timelineScrollPane;
    private final JLabel positionLabel;
    private final Runnable transportRefresh;
    private final Rectangle scrollTarget = new Rectangle();

    private String durationText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(0);
    private String lastPositionText;
    private boolean followPlayhead = true;
    private boolean autoScrolling;

    PlaybackViewController(AudioPlayer audioPlayer,
                           TimelinePanel timeline,
                           JScrollPane timelineScrollPane,
                           JLabel positionLabel,
                           Runnable transportRefresh) {
        this.audioPlayer = audioPlayer;
        this.timeline = timeline;
        this.timelineScrollPane = timelineScrollPane;
        this.positionLabel = positionLabel;
        this.transportRefresh = transportRefresh;
        timelineScrollPane.getViewport().addChangeListener(event -> {
            if (!autoScrolling && audioPlayer.isPlaying()) {
                followPlayhead = false;
            }
        });
        new Timer(30, event -> tick()).start();
    }

    void audioLoaded() {
        durationText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(audioPlayer.getDurationSeconds());
        lastPositionText = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(0);
        positionLabel.setText(lastPositionText + " / " + durationText);
    }

    void playbackStarted() {
        followPlayhead = true;
    }

    private void tick() {
        if (!audioPlayer.isLoaded()) {
            return;
        }
        double position = audioPlayer.getPositionSeconds();
        timeline.setPlayheadPositionInSeconds(position);
        String rendered = PlaybackTimeFormatter.formatSecondsAsMinutesAndSeconds(position);
        if (!rendered.equals(lastPositionText)) {
            lastPositionText = rendered;
            positionLabel.setText(rendered + " / " + durationText);
        }
        transportRefresh.run();
        if (audioPlayer.isPlaying()) {
            keepPlayheadVisible();
        }
    }

    private void keepPlayheadVisible() {
        if (!followPlayhead) {
            return;
        }
        int playheadY = timeline.getPlayheadCenterY();
        if (playheadY < 0) {
            return;
        }
        Rectangle view = timelineScrollPane.getViewport().getViewRect();
        int margin = Math.max(40, view.height / 8);
        int newViewY = view.y;
        if (playheadY < view.y + margin) {
            newViewY = Math.max(0, playheadY - margin);
        } else if (playheadY > view.y + view.height - margin) {
            newViewY = playheadY - view.height + margin;
        }
        if (newViewY != view.y) {
            autoScrolling = true;
            scrollTarget.setBounds(0, newViewY, timeline.getWidth(), view.height);
            timeline.scrollRectToVisible(scrollTarget);
            autoScrolling = false;
        }
    }
}
