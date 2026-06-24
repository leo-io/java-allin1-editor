package com.audioeditor.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared selection state so the timeline and the CRUD tables highlight the same
 * object. Holds which kind of object is selected and its index in the model's
 * list.
 */
public class SelectionModel {

    public enum Kind { NONE, BEAT, DOWNBEAT, SEGMENT }

    public interface Listener {
        void selectionChanged();
    }

    private Kind kind = Kind.NONE;
    private int index = -1;
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public Kind getKind() {
        return kind;
    }

    public int getIndex() {
        return index;
    }

    public void set(Kind kind, int index) {
        if (this.kind == kind && this.index == index) {
            return;
        }
        this.kind = kind;
        this.index = index;
        for (Listener l : new ArrayList<>(listeners)) {
            l.selectionChanged();
        }
    }

    public void clear() {
        set(Kind.NONE, -1);
    }

    public boolean is(Kind k, int i) {
        return kind == k && index == i;
    }
}
