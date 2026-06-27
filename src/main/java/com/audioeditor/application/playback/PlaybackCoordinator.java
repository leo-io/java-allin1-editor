package com.audioeditor.application.playback;

import com.audioeditor.domain.analysis.TimeRange;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.port.audio.AudioPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps derived playback schedules synchronized with project edits. */
public final class PlaybackCoordinator implements ProjectModel.DetailedProjectChangeListener {

    private final ProjectModel project;
    private final AudioPlayer audioPlayer;

    public PlaybackCoordinator(ProjectModel project, AudioPlayer audioPlayer) {
        this.project = Objects.requireNonNull(project, "project");
        this.audioPlayer = Objects.requireNonNull(audioPlayer, "audioPlayer");
        project.addDetailedProjectChangeListener(this);
    }

    @Override
    public void projectChanged(ProjectModel.ProjectChange change) {
        if (change.affectsPlaybackSchedule()) {
            synchronizeAll();
        }
    }

    public void synchronizeAll() {
        synchronizeMetronome();
        synchronizePlayableRanges();
    }

    private void synchronizeMetronome() {
        var beats = project.getAllBeatsFlat();
        double[] times = new double[beats.size()];
        for (int index = 0; index < times.length; index++) {
            times[index] = beats.get(index).getStart();
        }
        audioPlayer.setMetronomeBeatTimes(times);
    }

    private void synchronizePlayableRanges() {
        List<TimeRange> ranges = new ArrayList<>();
        for (var segment : project.getSegments()) {
            if (segment.getEnd() >= segment.getStart()) {
                ranges.add(new TimeRange(segment.getStart(), segment.getEnd()));
            }
        }
        audioPlayer.setPlayableRanges(ranges);
    }
}
