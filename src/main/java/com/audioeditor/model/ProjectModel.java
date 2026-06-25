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
 * <p>Holds the audio path, BPM, the merged beat list (time + bar position),
 * the downbeat times, and the segments. Any unrecognised top-level JSON keys
 * are preserved verbatim in {@link #unmodelledJsonFields} so a save never drops data.
 *
 * <p>Every mutation marks the model dirty and notifies {@link ProjectChangeListener}s so the
 * timeline, the CRUD tables and the playhead stay synchronized in real time.
 */
public class ProjectModel {

    /** Notified whenever any property of the model changes. */
    public interface ProjectChangeListener {
        void modelChanged();
    }

    private String referencedAudioFilePath = "";
    private double beatsPerMinute = 0;
    private final List<Beat> beatList = new ArrayList<>();
    private final List<Double> downbeatTimeList = new ArrayList<>();
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

    // ---- beats -----------------------------------------------------------

    public List<Beat> getBeats() {
        return beatList;
    }

    public void addBeat(Beat b) {
        beatList.add(b);
        notifyAllProjectChangeListeners();
    }

    public void removeBeat(int index) {
        if (index >= 0 && index < beatList.size()) {
            beatList.remove(index);
            notifyAllProjectChangeListeners();
        }
    }

    public void moveBeat(int from, int to) {
        move(beatList, from, to);
    }

    /** Sort beats ascending by time (keeps table/timeline consistent). */
    public void sortBeats() {
        beatList.sort(Comparator.comparingDouble(Beat::getTime));
        notifyAllProjectChangeListeners();
    }

    // ---- downbeats -------------------------------------------------------

    public List<Double> getDownbeats() {
        return downbeatTimeList;
    }

    public void addDownbeat(double t) {
        downbeatTimeList.add(t);
        notifyAllProjectChangeListeners();
    }

    public void removeDownbeat(int index) {
        if (index >= 0 && index < downbeatTimeList.size()) {
            downbeatTimeList.remove(index);
            notifyAllProjectChangeListeners();
        }
    }

    public void moveDownbeat(int from, int to) {
        move(downbeatTimeList, from, to);
    }

    public void sortDownbeats() {
        downbeatTimeList.sort(Comparator.naturalOrder());
        notifyAllProjectChangeListeners();
    }

    // ---- segments --------------------------------------------------------

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

    public void sortSegments() {
        segmentList.sort(Comparator.comparingDouble(Segment::getStart));
        notifyAllProjectChangeListeners();
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
        for (Beat b : beatList) max = Math.max(max, b.getTime());
        for (Double d : downbeatTimeList) max = Math.max(max, d);
        for (Segment s : segmentList) max = Math.max(max, s.getEnd());
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
        this.beatList.clear();
        this.beatList.addAll(other.beatList);
        this.downbeatTimeList.clear();
        this.downbeatTimeList.addAll(other.downbeatTimeList);
        this.segmentList.clear();
        this.segmentList.addAll(other.segmentList);
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
