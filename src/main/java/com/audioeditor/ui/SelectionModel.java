package com.audioeditor.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared selection state so the timeline and the CRUD tables highlight the same
 * object. Holds which kind of object is selected and its index in the model's
 * list.
 */
public class SelectionModel {

    public enum SelectableItemType { NONE, BEAT, BAR, SEGMENT }

    public interface SelectionChangeListener {
        void selectionChanged();
    }

    private SelectableItemType selectedItemType = SelectableItemType.NONE;
    private int selectedItemIndex = -1;
    private final List<SelectionChangeListener> selectionChangeListeners = new ArrayList<>();

    public void addSelectionChangeListener(SelectionChangeListener listener) {
        selectionChangeListeners.add(listener);
    }

    public SelectableItemType getSelectedItemType() {
        return selectedItemType;
    }

    public int getSelectedItemIndex() {
        return selectedItemIndex;
    }

    public void selectItem(SelectableItemType itemType, int itemIndex) {
        if (this.selectedItemType == itemType && this.selectedItemIndex == itemIndex) {
            return;
        }
        this.selectedItemType = itemType;
        this.selectedItemIndex = itemIndex;
        for (SelectionChangeListener l : new ArrayList<>(selectionChangeListeners)) {
            l.selectionChanged();
        }
    }

    public void clearSelection() {
        selectItem(SelectableItemType.NONE, -1);
    }

    public boolean isItemSelected(SelectableItemType itemType, int itemIndex) {
        return selectedItemType == itemType && selectedItemIndex == itemIndex;
    }
}
