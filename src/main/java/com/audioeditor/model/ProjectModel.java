package com.audioeditor.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory, fully editable representation of an analysis JSON file.
 *
 * <p>Holds the audio path, BPM, the merged beat list (time + bar position),
 * the downbeat times, and the segments. Any unrecognised top-level JSON keys
 * are preserved verbatim in {@link #extraFields} so a save never drops data.
 *
 * <p>Every mutation marks the model dirty and notifies {@link Listener}s so the
 * timeline, the CRUD tables and the playhead stay synchronized in real time.
 */
public class ProjectModel {

    /** Notified whenever any property of the model changes. */
    public interface Listener {
        void modelChanged();
    }

    private String audioPath = "";
    private double bpm = 0;
    private final List<Beat> beats = new ArrayList<>();
    private final List<Double> downbeats = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();

    /** Top-level JSON keys we don't model explicitly, kept for round-trip fidelity. */
    private final Map<String, JsonNode> extraFields = new LinkedHashMap<>();

    private final List<Listener> listeners = new ArrayList<>();
    private boolean dirty = false;

    private final List<String> labelVocabulary = new ArrayList<>(List.of(
            "intro", "verse", "chorus", "bridge", "break", "outro", "end"));

    // ---- listeners -------------------------------------------------------

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Fire a change notification (call after any external mutation). */
    public void fireChanged() {
        dirty = true;
        for (Listener l : new ArrayList<>(listeners)) {
            l.modelChanged();
        }
    }

    // ---- scalars ---------------------------------------------------------

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath == null ? "" : audioPath;
        fireChanged();
    }

    public double getBpm() {
        return bpm;
    }

    public void setBpm(double bpm) {
        this.bpm = bpm;
        fireChanged();
    }

    public Map<String, JsonNode> getExtraFields() {
        return extraFields;
    }

    public List<String> getLabelVocabulary() {
        return labelVocabulary;
    }

    public void rememberLabel(String label) {
        if (label != null && !label.isBlank() && !labelVocabulary.contains(label)) {
            labelVocabulary.add(label);
        }
    }

    // ---- beats -----------------------------------------------------------

    public List<Beat> getBeats() {
        return beats;
    }

    public void addBeat(Beat b) {
        beats.add(b);
        fireChanged();
    }

    public void removeBeat(int index) {
        if (index >= 0 && index < beats.size()) {
            beats.remove(index);
            fireChanged();
        }
    }

    public void moveBeat(int from, int to) {
        move(beats, from, to);
    }

    /** Sort beats ascending by time (keeps table/timeline consistent). */
    public void sortBeats() {
        beats.sort(Comparator.comparingDouble(Beat::getTime));
        fireChanged();
    }

    // ---- downbeats -------------------------------------------------------

    public List<Double> getDownbeats() {
        return downbeats;
    }

    public void addDownbeat(double t) {
        downbeats.add(t);
        fireChanged();
    }

    public void removeDownbeat(int index) {
        if (index >= 0 && index < downbeats.size()) {
            downbeats.remove(index);
            fireChanged();
        }
    }

    public void moveDownbeat(int from, int to) {
        move(downbeats, from, to);
    }

    public void sortDownbeats() {
        downbeats.sort(Comparator.naturalOrder());
        fireChanged();
    }

    // ---- segments --------------------------------------------------------

    public List<Segment> getSegments() {
        return segments;
    }

    public void addSegment(Segment s) {
        segments.add(s);
        rememberLabel(s.getLabel());
        fireChanged();
    }

    public void removeSegment(int index) {
        if (index >= 0 && index < segments.size()) {
            segments.remove(index);
            fireChanged();
        }
    }

    public void moveSegment(int from, int to) {
        move(segments, from, to);
    }

    public void sortSegments() {
        segments.sort(Comparator.comparingDouble(Segment::getStart));
        fireChanged();
    }

    // ---- helpers ---------------------------------------------------------

    private <T> void move(List<T> list, int from, int to) {
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size() || from == to) {
            return;
        }
        T item = list.remove(from);
        list.add(to, item);
        fireChanged();
    }

    /** Largest time referenced anywhere, used to size the timeline. */
    public double getMaxTime() {
        double max = 0;
        for (Beat b : beats) max = Math.max(max, b.getTime());
        for (Double d : downbeats) max = Math.max(max, d);
        for (Segment s : segments) max = Math.max(max, s.getEnd());
        return max;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** Replace all contents from another freshly-loaded model (used on Open). */
    public void copyFrom(ProjectModel other) {
        this.audioPath = other.audioPath;
        this.bpm = other.bpm;
        this.beats.clear();
        this.beats.addAll(other.beats);
        this.downbeats.clear();
        this.downbeats.addAll(other.downbeats);
        this.segments.clear();
        this.segments.addAll(other.segments);
        this.extraFields.clear();
        this.extraFields.putAll(other.extraFields);
        for (Segment s : segments) {
            rememberLabel(s.getLabel());
        }
        dirty = false;
        fireChanged();
        dirty = false;
    }
}
