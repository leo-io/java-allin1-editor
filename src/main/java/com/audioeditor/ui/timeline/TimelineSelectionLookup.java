package com.audioeditor.ui.timeline;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

import java.util.Objects;

/** Resolves the timeline's flattened row indices without embedding traversal in the view. */
public final class TimelineSelectionLookup {

    private final ProjectModel project;

    public TimelineSelectionLookup(ProjectModel project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public Beat beatAt(int flatIndex) {
        var beats = project.getAllBeatsFlat();
        return flatIndex >= 0 && flatIndex < beats.size() ? beats.get(flatIndex) : null;
    }

    public Bar barAt(int flatIndex) {
        var bars = project.getAllBarsFlat();
        return flatIndex >= 0 && flatIndex < bars.size() ? bars.get(flatIndex) : null;
    }

    public int beatIndex(Beat target) {
        return target == null ? -1 : project.findBeatIndex(target.getId());
    }

    public int barIndex(Bar target) {
        return target == null ? -1 : project.findBarIndex(target.getId());
    }

    public int firstBarIndex(Segment target) {
        int index = 0;
        for (Segment segment : project.getSegments()) {
            if (segment == target) {
                return index;
            }
            index += segment.getBars().size();
        }
        return -1;
    }
}
