package com.audioeditor.ui.timeline;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.ui.SelectionModel;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleToIntFunction;
import java.util.logging.Logger;

/** Segment clipboard and edit operations triggered from keyboard shortcuts or the context menu. */
public final class TimelineActions {

    private static final Logger LOG = Logger.getLogger(TimelineActions.class.getName());

    private final ProjectModel model;
    private final ProjectEditor editor;
    private final SelectionModel selection;
    private final JComponent dialogParent;
    private final DoubleSupplier playheadSeconds;
    private final DoubleToIntFunction segmentAtTime;

    private final List<Segment> clipboard = new ArrayList<>();

    public TimelineActions(ProjectModel model, ProjectEditor editor, SelectionModel selection,
                    JComponent dialogParent, DoubleSupplier playheadSeconds,
                    DoubleToIntFunction segmentAtTime) {
        this.model = model;
        this.editor = editor;
        this.selection = selection;
        this.dialogParent = dialogParent;
        this.playheadSeconds = playheadSeconds;
        this.segmentAtTime = segmentAtTime;
    }

    public void renamePrimarySelectedSegment() {
        renameSegmentAt(selection.getPrimarySelectedSegmentIndex());
    }

    public void renameSegmentAt(int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= model.getSegments().size()) return;
        Segment segment = model.getSegments().get(segmentIndex);
        String newLabel = JOptionPane.showInputDialog(dialogParent, "Segment name:", segment.getLabel());
        if (newLabel == null) return;
        if (editor.renameSegment(segmentIndex, newLabel)) {
            selection.selectSegment(segmentIndex);
            LOG.fine("Timeline: renamed segment #" + segmentIndex + " to " + newLabel.trim());
        }
    }

    public void copySelectedSegments() {
        List<Integer> selectedIndices = selection.getSelectedSegmentIndices();
        if (selectedIndices.isEmpty()) return;
        clipboard.clear();
        clipboard.addAll(editor.copySegments(selectedIndices));
        LOG.fine("Timeline: copied " + clipboard.size() + " segment(s)");
    }

    public void pasteSegmentsAfterSelection() {
        if (clipboard.isEmpty()) return;
        int afterIndex = maxSelectedSegmentIndex();
        if (afterIndex < 0) return;
        int insertedAt = editor.pasteSegmentsAfter(afterIndex, clipboard);
        if (insertedAt >= 0) {
            selectSegmentRange(insertedAt, insertedAt + clipboard.size() - 1);
            LOG.fine("Timeline: pasted " + clipboard.size() + " segment(s) after #" + afterIndex);
        }
    }

    public void mergeSelectedSegments() {
        List<Integer> selectedIndices = selection.getSelectedSegmentIndices();
        int mergedIndex = editor.mergeSegments(selectedIndices);
        if (mergedIndex >= 0) {
            selection.selectSegment(mergedIndex);
            LOG.fine("Timeline: merged selected segments into #" + mergedIndex);
        }
    }

    public void splitAtPlayhead() {
        double t = playheadSeconds.getAsDouble();
        int segmentIndex = selection.getPrimarySelectedSegmentIndex();
        if (segmentIndex < 0 || segmentIndex >= model.getSegments().size()) {
            segmentIndex = segmentAtTime.applyAsInt(t);
        }
        splitSegmentAt(segmentIndex, t);
    }

    public void splitSegmentAt(int segmentIndex, double timeInSeconds) {
        int newIndex = editor.splitSegmentAt(segmentIndex, timeInSeconds);
        if (newIndex >= 0) {
            selection.selectSegment(newIndex);
            LOG.fine("Timeline: split segment #" + segmentIndex + " at " + timeInSeconds + "s");
        }
    }

    public boolean hasClipboard() {
        return !clipboard.isEmpty();
    }

    public List<Segment> clipboard() {
        return clipboard;
    }

    // ---- selection helpers -------------------------------------------------

    public void selectSegmentRange(int firstIndex, int lastIndex) {
        List<Integer> indices = new ArrayList<>();
        int max = model.getSegments().size() - 1;
        for (int i = Math.max(0, firstIndex); i <= Math.min(max, lastIndex); i++) indices.add(i);
        selection.selectSegments(indices);
    }

    private int maxSelectedSegmentIndex() {
        int max = -1;
        for (int index : selection.getSelectedSegmentIndices()) max = Math.max(max, index);
        return max;
    }
}
