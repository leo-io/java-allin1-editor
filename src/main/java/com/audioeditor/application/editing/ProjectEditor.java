package com.audioeditor.application.editing;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

import java.util.List;
import java.util.Objects;

/**
 * Single application boundary for user-initiated project mutations. Views may
 * inspect the model but cannot mutate its nested collections directly.
 */
public final class ProjectEditor {

    private final ProjectModel project;

    public ProjectEditor(ProjectModel project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public void addSegment(Segment segment) {
        project.addSegment(segment);
    }

    public void removeSegment(int index) {
        project.removeSegment(index);
    }

    public void moveSegment(int from, int to) {
        project.moveSegment(from, to);
    }

    public void sortSegments() {
        project.sortSegments();
    }

    public void duplicateSegment(int segmentIndex) {
        if (segmentIndex >= 0 && segmentIndex < project.getSegments().size()) {
            project.addSegment(segmentIndex + 1, project.getSegments().get(segmentIndex).copy());
        }
    }

    public boolean renameSegment(int segmentIndex, String label) {
        return project.renameSegment(segmentIndex, label);
    }

    public void updateBeat(Beat beat, double startSeconds, double endSeconds, boolean downbeat) {
        beat.setStart(Math.max(0, startSeconds));
        beat.setEnd(Math.max(beat.getStart(), endSeconds));
        beat.setDownbeat(downbeat);
        project.normalizeProjectStructureAndNotify();
    }

    /** Fast drag update; normalization is deferred until the gesture completes. */
    public void previewBeatTiming(Beat beat, double startSeconds, double endSeconds) {
        beat.setStart(Math.max(0, startSeconds));
        beat.setEnd(Math.max(beat.getStart(), endSeconds));
        project.notifyAllProjectChangeListeners();
    }

    public void removeBeat(Beat beat) {
        for (Segment segment : project.getSegments()) {
            for (Bar bar : segment.getBars()) {
                if (bar.removeBeat(beat)) {
                    project.normalizeProjectStructureAndNotify();
                    return;
                }
            }
        }
    }

    public void moveBeat(Beat beat, int direction) {
        for (Segment segment : project.getSegments()) {
            for (Bar bar : segment.getBars()) {
                int current = bar.getBeats().indexOf(beat);
                int target = current + direction;
                if (current >= 0 && target >= 0 && target < bar.getBeats().size()) {
                    bar.moveBeat(current, target);
                    project.normalizeProjectStructureAndNotify();
                    return;
                }
            }
        }
    }

    public void removeBar(Bar bar) {
        for (Segment segment : project.getSegments()) {
            if (segment.removeBar(bar)) {
                project.normalizeProjectStructureAndNotify();
                return;
            }
        }
    }

    public void addBeatAt(Segment segment, Beat beat) {
        for (Bar bar : segment.getBars()) {
            if (bar.getStartTime() <= beat.getStart() && bar.getEndTime() >= beat.getStart()) {
                bar.addBeat(beat);
                project.normalizeProjectStructureAndNotify();
                return;
            }
        }
        Bar newBar = new Bar();
        newBar.addBeat(beat);
        segment.addBar(newBar);
        project.normalizeProjectStructureAndNotify();
    }

    public void addBar(Segment segment, Bar bar) {
        segment.addBar(bar);
        project.normalizeProjectStructureAndNotify();
    }

    public boolean shrinkSegmentToFullBarBorders(int index) {
        return project.shrinkSegmentToFullBarBorders(index);
    }

    public boolean expandSegmentToFullBarBorders(int index) {
        return project.expandSegmentToFullBarBorders(index);
    }

    public boolean moveFirstBarsFromNextSegment(int index, int count) {
        return project.moveFirstBarsFromNextSegment(index, count);
    }

    public boolean moveLastBarsToNextSegment(int index, int count) {
        return project.moveLastBarsToNextSegment(index, count);
    }

    public void moveBar(Bar bar, int direction) {
        for (Segment segment : project.getSegments()) {
            int current = segment.getBars().indexOf(bar);
            int target = current + direction;
            if (current >= 0 && target >= 0 && target < segment.getBars().size()) {
                segment.moveBar(current, target);
                project.normalizeProjectStructureAndNotify();
                return;
            }
        }
    }

    public void finishBeatDrag(Beat beat, int preferredSegmentIndex) {
        Segment targetSegment = findSegmentContainingTimeExcludingBeat(beat.getStart(), beat);
        if (targetSegment == null && isValidSegmentIndex(preferredSegmentIndex)) {
            targetSegment = project.getSegments().get(preferredSegmentIndex);
        }
        if (targetSegment != null) {
            Bar targetBar = findBarContainingTimeExcludingBeat(targetSegment, beat.getStart(), beat);
            if (targetBar == null) {
                targetBar = new Bar();
                targetSegment.addBar(targetBar);
            }
            for (Segment segment : project.getSegments()) {
                for (Bar bar : segment.getBars()) {
                    if (bar != targetBar && bar.removeBeat(beat)) {
                        targetBar.addBeat(beat);
                        project.normalizeProjectStructureAndNotify();
                        return;
                    }
                }
            }
            if (!targetBar.getBeats().contains(beat)) {
                targetBar.addBeat(beat);
            }
        }
        project.normalizeProjectStructureAndNotify();
    }

    public void finishBarDrag(Bar bar, int preferredSegmentIndex) {
        if (bar.getBeats().isEmpty()) {
            return;
        }
        Segment target = findSegmentContainingTimeExcludingBar(bar.getStartTime(), bar);
        if (target == null && isValidSegmentIndex(preferredSegmentIndex)) {
            target = project.getSegments().get(preferredSegmentIndex);
        }
        if (target != null && !target.getBars().contains(bar)) {
            for (Segment segment : project.getSegments()) {
                if (segment.removeBar(bar)) {
                    target.addBar(bar);
                    break;
                }
            }
        }
        project.normalizeProjectStructureAndNotify();
    }

    public List<Segment> copySegments(List<Integer> indices) {
        return project.copySegmentsAtIndices(indices);
    }

    public int pasteSegmentsAfter(int index, List<Segment> copies) {
        return project.pasteSegmentCopiesAfter(index, copies);
    }

    public int mergeSegments(List<Integer> indices) {
        return project.mergeAdjacentSegments(indices);
    }

    public int splitSegmentAt(int index, double seconds) {
        return project.splitSegmentAtNearestBarBoundary(index, seconds);
    }

    private boolean isValidSegmentIndex(int index) {
        return index >= 0 && index < project.getSegments().size();
    }

    private Segment findSegmentContainingTimeExcludingBeat(double time, Beat excludedBeat) {
        return project.getSegments().stream()
                .filter(segment -> contains(segment, time, excludedBeat, null))
                .findFirst().orElse(null);
    }

    private Segment findSegmentContainingTimeExcludingBar(double time, Bar excludedBar) {
        return project.getSegments().stream()
                .filter(segment -> contains(segment, time, null, excludedBar))
                .findFirst().orElse(null);
    }

    private boolean contains(Segment segment, double time, Beat excludedBeat, Bar excludedBar) {
        double start = Double.POSITIVE_INFINITY;
        double end = Double.NEGATIVE_INFINITY;
        for (Bar bar : segment.getBars()) {
            if (bar == excludedBar) {
                continue;
            }
            for (Beat beat : bar.getBeats()) {
                if (beat != excludedBeat) {
                    start = Math.min(start, beat.getStart());
                    end = Math.max(end, beat.getEnd());
                }
            }
        }
        return start != Double.POSITIVE_INFINITY && time >= start && time <= end;
    }

    private Bar findBarContainingTimeExcludingBeat(Segment segment, double time, Beat excludedBeat) {
        for (Bar bar : segment.getBars()) {
            double start = Double.POSITIVE_INFINITY;
            double end = Double.NEGATIVE_INFINITY;
            for (Beat beat : bar.getBeats()) {
                if (beat != excludedBeat) {
                    start = Math.min(start, beat.getStart());
                    end = Math.max(end, beat.getEnd());
                }
            }
            if (start != Double.POSITIVE_INFINITY && time >= start && time <= end) {
                return bar;
            }
        }
        return null;
    }
}
