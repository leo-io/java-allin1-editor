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

    /** Sort segments ascending by computed start time (first beat's start). */
    public void sortSegments() {
        segmentList.sort(Comparator.comparingDouble(Segment::getStart));
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