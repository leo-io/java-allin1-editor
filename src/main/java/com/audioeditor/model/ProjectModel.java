package com.audioeditor.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, fully editable representation of an analysis JSON file.
 *
 * <p>Holds the audio path, BPM, and a hierarchy of segments → bars → beats.
 * Beats (and the downbeat flag on each) are nested inside bars inside
 * segments — there is no longer a flat {@code beatList} / downbeat list. Any
 * unrecognised top-level JSON keys are preserved verbatim in
 * {@link #unmodelledJsonFields} so a save never drops data.
 *
 * <p>Every mutation marks the model dirty and notifies
 * {@link ProjectChangeListener}s so the timeline, the CRUD tables and the
 * playhead stay synchronized in real time.
 */
public class ProjectModel {

    public static final int BEATS_PER_BAR = 4;

    /** Notified whenever any property of the model changes. */
    public interface ProjectChangeListener {
        void modelChanged();
    }

    private String referencedAudioFilePath = "";
    private double beatsPerMinute = 0;
    private final List<Segment> segmentList = new ArrayList<>();

    /** Top-level JSON keys we don't model explicitly, kept for round-trip fidelity. */
    private final Map<String, JsonNode> unmodelledJsonFields = new LinkedHashMap<>();

    // Copy-on-write so notify() can iterate without a defensive copy — that copy
    // was allocated on every mouse-drag mutation, feeding the periodic GC stutter.
    private final List<ProjectChangeListener> projectChangeListeners = new CopyOnWriteArrayList<>();
    private boolean hasUnsavedChanges = false;

    private final List<String> knownSegmentLabelVocabulary = new ArrayList<>(List.of(
            "intro", "verse", "chorus", "bridge", "break", "outro", "end"));

    // ---- listeners -------------------------------------------------------

    public void addProjectChangeListener(ProjectChangeListener listener) {
        projectChangeListeners.add(listener);
    }

    public void removeProjectChangeListener(ProjectChangeListener listener) {
        projectChangeListeners.remove(listener);
    }

    /** Fire a change notification (call after any external mutation). */
    public void notifyAllProjectChangeListeners() {
        hasUnsavedChanges = true;
        // CopyOnWriteArrayList iteration is snapshot-safe without allocating here.
        for (ProjectChangeListener l : projectChangeListeners) {
            l.modelChanged();
        }
    }

    // ---- scalars ---------------------------------------------------------

    public String getAudioPath() {
        return referencedAudioFilePath;
    }

    public void setAudioPath(String audioPath) {
        this.referencedAudioFilePath = audioPath == null ? "" : audioPath;
        notifyAllProjectChangeListeners();
    }

    public double getBpm() {
        return beatsPerMinute;
    }

    public void setBpm(double bpm) {
        this.beatsPerMinute = bpm;
        notifyAllProjectChangeListeners();
    }

    public Map<String, JsonNode> getExtraFields() {
        return unmodelledJsonFields;
    }

    public List<String> getLabelVocabulary() {
        return knownSegmentLabelVocabulary;
    }

    public void rememberLabel(String label) {
        if (label != null && !label.isBlank() && !knownSegmentLabelVocabulary.contains(label)) {
            knownSegmentLabelVocabulary.add(label);
        }
    }

    // ---- segments (which own bars which own beats) ----------------------

    public List<Segment> getSegments() {
        return segmentList;
    }

    public void addSegment(Segment s) {
        segmentList.add(s);
        rememberLabel(s.getLabel());
        notifyAllProjectChangeListeners();
    }

    public void removeSegment(int index) {
        if (index >= 0 && index < segmentList.size()) {
            segmentList.remove(index);
            notifyAllProjectChangeListeners();
        }
    }

    public void moveSegment(int from, int to) {
        move(segmentList, from, to);
    }

    public boolean renameSegment(int segmentIndex, String newLabel) {
        if (!isValidSegmentIndex(segmentIndex) || newLabel == null || newLabel.isBlank()) {
            return false;
        }
        Segment segment = segmentList.get(segmentIndex);
        String trimmed = newLabel.trim();
        if (trimmed.equals(segment.getLabel())) {
            return false;
        }
        segment.setLabel(trimmed);
        rememberLabel(trimmed);
        notifyAllProjectChangeListeners();
        return true;
    }

    public List<Segment> copySegmentsAtIndices(List<Integer> segmentIndices) {
        List<Segment> copies = new ArrayList<>();
        for (int index : sortedUniqueValidSegmentIndices(segmentIndices)) {
            copies.add(segmentList.get(index).copy());
        }
        return copies;
    }

    public int pasteSegmentCopiesAfter(int afterSegmentIndex, List<Segment> segmentsToPaste) {
        if (segmentsToPaste == null || segmentsToPaste.isEmpty()) {
            return -1;
        }
        int insertAt = Math.max(0, Math.min(afterSegmentIndex + 1, segmentList.size()));
        int cursor = insertAt;
        for (Segment segment : segmentsToPaste) {
            if (segment == null) {
                continue;
            }
            Segment copy = segment.copy();
            segmentList.add(cursor++, copy);
            rememberLabel(copy.getLabel());
        }
        if (cursor == insertAt) {
            return -1;
        }
        notifyAllProjectChangeListeners();
        return insertAt;
    }

    public boolean canMergeAdjacentSegments(List<Integer> segmentIndices) {
        List<Integer> indices = sortedUniqueValidSegmentIndices(segmentIndices);
        return indices.size() >= 2 && areContiguous(indices);
    }

    public int mergeAdjacentSegments(List<Integer> segmentIndices) {
        List<Integer> indices = sortedUniqueValidSegmentIndices(segmentIndices);
        if (indices.size() < 2 || !areContiguous(indices)) {
            return -1;
        }
        int targetIndex = indices.get(0);
        Segment target = segmentList.get(targetIndex);
        for (int i = 1; i < indices.size(); i++) {
            target.getBars().addAll(segmentList.get(indices.get(i)).getBars());
        }
        for (int i = indices.size() - 1; i >= 1; i--) {
            segmentList.remove((int) indices.get(i));
        }
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
        return segmentList.indexOf(target);
    }

    public boolean canSplitSegmentAtNearestBarBoundary(int segmentIndex) {
        return isValidSegmentIndex(segmentIndex) && segmentList.get(segmentIndex).getBars().size() >= 2;
    }

    public int splitSegmentAtNearestBarBoundary(int segmentIndex, double timeInSeconds) {
        if (!canSplitSegmentAtNearestBarBoundary(segmentIndex)) {
            return -1;
        }
        Segment source = segmentList.get(segmentIndex);
        int splitBarIndex = nearestInternalBarBoundaryIndex(source, timeInSeconds);
        if (splitBarIndex <= 0 || splitBarIndex >= source.getBars().size()) {
            return -1;
        }
        Segment right = new Segment(source.getLabel());
        List<Bar> sourceBars = source.getBars();
        right.getBars().addAll(new ArrayList<>(sourceBars.subList(splitBarIndex, sourceBars.size())));
        sourceBars.subList(splitBarIndex, sourceBars.size()).clear();
        segmentList.add(segmentIndex + 1, right);
        rememberLabel(right.getLabel());
        notifyAllProjectChangeListeners();
        return segmentIndex + 1;
    }

    /** Sort segments ascending by computed start time (first beat's start). */
    public void sortSegments() {
        segmentList.sort(Comparator.comparingDouble(Segment::getStart));
        notifyAllProjectChangeListeners();
    }

    /**
     * Push incomplete edge bars out of the selected segment. This is a local,
     * low-risk segment-border repair: beat timestamps are not moved, only beat
     * ownership between the two bars touching a segment boundary.
     */
    public boolean shrinkSegmentToFullBarBorders(int segmentIndex) {
        if (!isValidSegmentIndex(segmentIndex)) {
            return false;
        }

        boolean changed = false;
        if (canMoveFirstFragmentToPreviousSegment(segmentIndex)) {
            moveFirstFragmentToPreviousSegment(segmentIndex);
            changed = true;
        }
        if (canMoveLastFragmentToNextSegment(segmentIndex)) {
            moveLastFragmentToNextSegment(segmentIndex);
            changed = true;
        }

        if (changed) {
            normalizeProjectStructure();
            notifyAllProjectChangeListeners();
        }
        return changed;
    }

    public boolean canShrinkSegmentToFullBarBorders(int segmentIndex) {
        return canMoveFirstFragmentToPreviousSegment(segmentIndex)
                || canMoveLastFragmentToNextSegment(segmentIndex);
    }

    /**
     * Pull adjacent incomplete edge fragments into the selected segment. This
     * completes split bars that straddle a segment boundary without changing
     * any beat timestamps.
     */
    public boolean expandSegmentToFullBarBorders(int segmentIndex) {
        if (!isValidSegmentIndex(segmentIndex)) {
            return false;
        }

        boolean changed = false;
        if (canCompleteFirstFragmentFromPreviousSegment(segmentIndex)) {
            completeFirstFragmentFromPreviousSegment(segmentIndex);
            changed = true;
        }
        if (canCompleteLastFragmentFromNextSegment(segmentIndex)) {
            completeLastFragmentFromNextSegment(segmentIndex);
            changed = true;
        }

        if (changed) {
            normalizeProjectStructure();
            notifyAllProjectChangeListeners();
        }
        return changed;
    }

    public boolean canExpandSegmentToFullBarBorders(int segmentIndex) {
        return canCompleteFirstFragmentFromPreviousSegment(segmentIndex)
                || canCompleteLastFragmentFromNextSegment(segmentIndex);
    }

    public boolean moveFirstBarsFromNextSegment(int segmentIndex, int barCount) {
        if (!canMoveFirstBarsFromNextSegment(segmentIndex, barCount)) {
            return false;
        }

        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        List<Bar> moved = removeBars(next, 0, barCount);
        current.getBars().addAll(moved);
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
        return true;
    }

    public boolean canMoveFirstBarsFromNextSegment(int segmentIndex, int barCount) {
        if (segmentIndex < 0 || segmentIndex >= segmentList.size() - 1) {
            return false;
        }
        return canTransferBars(segmentList.get(segmentIndex + 1), segmentList.get(segmentIndex), barCount);
    }

    public boolean moveLastBarsToNextSegment(int segmentIndex, int barCount) {
        if (!canMoveLastBarsToNextSegment(segmentIndex, barCount)) {
            return false;
        }

        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        List<Bar> moved = removeBars(current, current.getBars().size() - barCount, barCount);
        next.getBars().addAll(0, moved);
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
        return true;
    }

    public boolean canMoveLastBarsToNextSegment(int segmentIndex, int barCount) {
        if (segmentIndex < 0 || segmentIndex >= segmentList.size() - 1) {
            return false;
        }
        return canTransferBars(segmentList.get(segmentIndex), segmentList.get(segmentIndex + 1), barCount);
    }

    public boolean moveLastBarsFromPreviousSegment(int segmentIndex, int barCount) {
        if (!canMoveLastBarsFromPreviousSegment(segmentIndex, barCount)) {
            return false;
        }

        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        List<Bar> moved = removeBars(previous, previous.getBars().size() - barCount, barCount);
        current.getBars().addAll(0, moved);
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
        return true;
    }

    public boolean canMoveLastBarsFromPreviousSegment(int segmentIndex, int barCount) {
        if (segmentIndex <= 0 || segmentIndex >= segmentList.size()) {
            return false;
        }
        return canTransferBars(segmentList.get(segmentIndex - 1), segmentList.get(segmentIndex), barCount);
    }

    public boolean moveFirstBarsToPreviousSegment(int segmentIndex, int barCount) {
        if (!canMoveFirstBarsToPreviousSegment(segmentIndex, barCount)) {
            return false;
        }

        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        List<Bar> moved = removeBars(current, 0, barCount);
        previous.getBars().addAll(moved);
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
        return true;
    }

    public boolean canMoveFirstBarsToPreviousSegment(int segmentIndex, int barCount) {
        if (segmentIndex <= 0 || segmentIndex >= segmentList.size()) {
            return false;
        }
        return canTransferBars(segmentList.get(segmentIndex), segmentList.get(segmentIndex - 1), barCount);
    }

    /**
     * Restore ordering and basic timing invariants after low-level edits.
     * Bars/beats are sorted by time, every bar starts with exactly one downbeat,
     * and adjacent beats inside a bar are made contiguous.
     */
    public void normalizeProjectStructure() {
        for (Segment segment : segmentList) {
            segment.getBars().removeIf(bar -> bar.getBeats().isEmpty());
            for (Bar bar : segment.getBars()) {
                bar.getBeats().sort(Comparator.comparingDouble(Beat::getStart));
                for (int i = 0; i < bar.getBeats().size(); i++) {
                    Beat beat = bar.getBeats().get(i);
                    beat.setDownbeat(i == 0);
                    if (i < bar.getBeats().size() - 1) {
                        beat.setEnd(Math.max(beat.getStart(), bar.getBeats().get(i + 1).getStart()));
                    } else if (beat.getEnd() <= beat.getStart()) {
                        beat.setEnd(beat.getStart() + 0.5);
                    }
                }
            }
            segment.getBars().sort(Comparator.comparingDouble(Bar::getStartTime));
        }
        segmentList.sort(Comparator.comparingDouble(Segment::getStart));
    }

    public void normalizeProjectStructureAndNotify() {
        normalizeProjectStructure();
        notifyAllProjectChangeListeners();
    }

    // ---- flat-iteration helpers for the new hierarchy ------------------

    /** All beats across all segments/bars, in playback order. */
    public List<Beat> getAllBeatsFlat() {
        List<Beat> flat = new ArrayList<>();
        for (Segment s : segmentList) {
            for (Bar bar : s.getBars()) {
                flat.addAll(bar.getBeats());
            }
        }
        return flat;
    }

    /** All downbeat beats (beats with {@link Beat#isDownbeat()} == true) in playback order. */
    public List<Beat> getAllDownbeatBeatsFlat() {
        List<Beat> flat = new ArrayList<>();
        for (Segment s : segmentList) {
            for (Bar bar : s.getBars()) {
                for (Beat b : bar.getBeats()) {
                    if (b.isDownbeat()) {
                        flat.add(b);
                    }
                }
            }
        }
        return flat;
    }

    /** Which segment owns the given beat reference, or -1 if none. */
    public int findSegmentIndexForBeat(Beat beat) {
        for (int si = 0; si < segmentList.size(); si++) {
            for (Bar bar : segmentList.get(si).getBars()) {
                if (bar.getBeats().contains(beat)) {
                    return si;
                }
            }
        }
        return -1;
    }

    // ---- helpers ---------------------------------------------------------

    private <T> void move(List<T> list, int from, int to) {
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size() || from == to) {
            return;
        }
        T item = list.remove(from);
        list.add(to, item);
        notifyAllProjectChangeListeners();
    }

    private boolean isValidSegmentIndex(int segmentIndex) {
        return segmentIndex >= 0 && segmentIndex < segmentList.size();
    }

    private List<Integer> sortedUniqueValidSegmentIndices(List<Integer> segmentIndices) {
        if (segmentIndices == null || segmentIndices.isEmpty()) {
            return List.of();
        }
        return segmentIndices.stream()
                .filter(index -> index != null)
                .filter(this::isValidSegmentIndex)
                .distinct()
                .sorted()
                .toList();
    }

    private boolean areContiguous(List<Integer> sortedIndices) {
        for (int i = 1; i < sortedIndices.size(); i++) {
            if (sortedIndices.get(i) != sortedIndices.get(i - 1) + 1) {
                return false;
            }
        }
        return true;
    }

    private int nearestInternalBarBoundaryIndex(Segment segment, double timeInSeconds) {
        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 1; i < segment.getBars().size(); i++) {
            double boundaryTime = segment.getBars().get(i).getStartTime();
            double distance = Math.abs(boundaryTime - timeInSeconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private boolean canMoveFirstFragmentToPreviousSegment(int segmentIndex) {
        if (segmentIndex <= 0 || segmentIndex >= segmentList.size()) {
            return false;
        }
        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        return current.getBars().size() > 1
                && !previous.getBars().isEmpty()
                && areComplementaryEdgeFragments(lastBar(previous), firstBar(current));
    }

    private void moveFirstFragmentToPreviousSegment(int segmentIndex) {
        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        Bar first = current.getBars().remove(0);
        lastBar(previous).getBeats().addAll(first.getBeats());
    }

    private boolean canMoveLastFragmentToNextSegment(int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= segmentList.size() - 1) {
            return false;
        }
        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        return current.getBars().size() > 1
                && !next.getBars().isEmpty()
                && areComplementaryEdgeFragments(lastBar(current), firstBar(next));
    }

    private void moveLastFragmentToNextSegment(int segmentIndex) {
        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        Bar last = current.getBars().remove(current.getBars().size() - 1);
        firstBar(next).getBeats().addAll(0, last.getBeats());
    }

    private boolean canCompleteFirstFragmentFromPreviousSegment(int segmentIndex) {
        if (segmentIndex <= 0 || segmentIndex >= segmentList.size()) {
            return false;
        }
        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        return previous.getBars().size() > 1
                && !current.getBars().isEmpty()
                && areComplementaryEdgeFragments(lastBar(previous), firstBar(current));
    }

    private void completeFirstFragmentFromPreviousSegment(int segmentIndex) {
        Segment current = segmentList.get(segmentIndex);
        Segment previous = segmentList.get(segmentIndex - 1);
        Bar previousLast = previous.getBars().remove(previous.getBars().size() - 1);
        firstBar(current).getBeats().addAll(0, previousLast.getBeats());
    }

    private boolean canCompleteLastFragmentFromNextSegment(int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= segmentList.size() - 1) {
            return false;
        }
        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        return next.getBars().size() > 1
                && !current.getBars().isEmpty()
                && areComplementaryEdgeFragments(lastBar(current), firstBar(next));
    }

    private void completeLastFragmentFromNextSegment(int segmentIndex) {
        Segment current = segmentList.get(segmentIndex);
        Segment next = segmentList.get(segmentIndex + 1);
        Bar nextFirst = next.getBars().remove(0);
        lastBar(current).getBeats().addAll(nextFirst.getBeats());
    }

    private boolean areComplementaryEdgeFragments(Bar left, Bar right) {
        int leftBeats = left.getBeats().size();
        int rightBeats = right.getBeats().size();
        return leftBeats > 0
                && leftBeats < BEATS_PER_BAR
                && rightBeats > 0
                && rightBeats < BEATS_PER_BAR
                && leftBeats + rightBeats == BEATS_PER_BAR;
    }

    private boolean canTransferBars(Segment from, Segment to, int barCount) {
        if (barCount <= 0 || from.getBars().size() < barCount) {
            return false;
        }
        int fromAfter = from.getBars().size() - barCount;
        int toAfter = to.getBars().size() + barCount;
        return fromAfter > 0
                && toAfter > 0
                && fromAfter % 2 == 0
                && toAfter % 2 == 0;
    }

    private List<Bar> removeBars(Segment segment, int fromIndex, int barCount) {
        List<Bar> bars = segment.getBars();
        List<Bar> moved = new ArrayList<>(bars.subList(fromIndex, fromIndex + barCount));
        bars.subList(fromIndex, fromIndex + barCount).clear();
        return moved;
    }

    private Bar firstBar(Segment segment) {
        return segment.getBars().get(0);
    }

    private Bar lastBar(Segment segment) {
        return segment.getBars().get(segment.getBars().size() - 1);
    }

    /** Largest time referenced anywhere, used to size the timeline. */
    public double getMaxTime() {
        double max = 0;
        for (Segment s : segmentList) {
            max = Math.max(max, s.getEnd());
            for (Bar bar : s.getBars()) {
                max = Math.max(max, bar.getEndTime());
            }
        }
        return max;
    }

    public boolean isDirty() {
        return hasUnsavedChanges;
    }

    public void setDirty(boolean dirty) {
        this.hasUnsavedChanges = dirty;
    }

    /** Replace all contents from another freshly-loaded model (used on Open). */
    public void copyFrom(ProjectModel other) {
        this.referencedAudioFilePath = other.referencedAudioFilePath;
        this.beatsPerMinute = other.beatsPerMinute;
        this.segmentList.clear();
        for (Segment s : other.segmentList) {
            this.segmentList.add(s.copy());
        }
        this.unmodelledJsonFields.clear();
        this.unmodelledJsonFields.putAll(other.unmodelledJsonFields);
        for (Segment s : segmentList) {
            rememberLabel(s.getLabel());
        }
        hasUnsavedChanges = false;
        notifyAllProjectChangeListeners();
        hasUnsavedChanges = false;
    }
}
