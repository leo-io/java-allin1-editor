package com.audioeditor.ui;

import java.util.ArrayList;
import java.util.Collections;
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
    private final List<Integer> selectedSegmentIndices = new ArrayList<>();
    private int segmentSelectionAnchorIndex = -1;
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

    public List<Integer> getSelectedSegmentIndices() {
        return new ArrayList<>(selectedSegmentIndices);
    }

    public int getPrimarySelectedSegmentIndex() {
        return selectedItemType == SelectableItemType.SEGMENT ? selectedItemIndex : -1;
    }

    public void selectItem(SelectableItemType itemType, int itemIndex) {
        if (itemType == SelectableItemType.SEGMENT) {
            selectSegment(itemIndex);
            return;
        }
        if (this.selectedItemType == itemType && this.selectedItemIndex == itemIndex
                && selectedSegmentIndices.isEmpty()) {
            return;
        }
        selectedSegmentIndices.clear();
        segmentSelectionAnchorIndex = -1;
        this.selectedItemType = itemType;
        this.selectedItemIndex = itemIndex;
        notifySelectionChangeListeners();
    }

    public void selectSegment(int segmentIndex) {
        if (selectedItemType == SelectableItemType.SEGMENT
                && selectedItemIndex == segmentIndex
                && selectedSegmentIndices.size() == 1
                && selectedSegmentIndices.contains(segmentIndex)) {
            return;
        }
        selectedSegmentIndices.clear();
        if (segmentIndex >= 0) {
            selectedSegmentIndices.add(segmentIndex);
            segmentSelectionAnchorIndex = segmentIndex;
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = segmentIndex;
        } else {
            segmentSelectionAnchorIndex = -1;
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
        }
        notifySelectionChangeListeners();
    }

    public void toggleSegment(int segmentIndex) {
        if (segmentIndex < 0) {
            return;
        }
        if (selectedItemType != SelectableItemType.SEGMENT) {
            selectedSegmentIndices.clear();
        }
        if (selectedSegmentIndices.contains(segmentIndex)) {
            selectedSegmentIndices.remove(Integer.valueOf(segmentIndex));
        } else {
            selectedSegmentIndices.add(segmentIndex);
        }
        Collections.sort(selectedSegmentIndices);
        if (selectedSegmentIndices.isEmpty()) {
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
            segmentSelectionAnchorIndex = -1;
        } else {
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = segmentIndex;
            segmentSelectionAnchorIndex = segmentIndex;
        }
        notifySelectionChangeListeners();
    }

    public void selectSegmentRange(int segmentIndex) {
        if (segmentIndex < 0) {
            return;
        }
        if (selectedItemType != SelectableItemType.SEGMENT || segmentSelectionAnchorIndex < 0) {
            selectSegment(segmentIndex);
            return;
        }
        int from = Math.min(segmentSelectionAnchorIndex, segmentIndex);
        int to = Math.max(segmentSelectionAnchorIndex, segmentIndex);
        selectedSegmentIndices.clear();
        for (int i = from; i <= to; i++) {
            selectedSegmentIndices.add(i);
        }
        selectedItemType = SelectableItemType.SEGMENT;
        selectedItemIndex = segmentIndex;
        notifySelectionChangeListeners();
    }

    public void selectSegments(List<Integer> segmentIndices) {
        selectedSegmentIndices.clear();
        if (segmentIndices != null) {
            for (Integer index : segmentIndices) {
                if (index != null && index >= 0 && !selectedSegmentIndices.contains(index)) {
                    selectedSegmentIndices.add(index);
                }
            }
        }
        Collections.sort(selectedSegmentIndices);
        if (selectedSegmentIndices.isEmpty()) {
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
            segmentSelectionAnchorIndex = -1;
        } else {
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = selectedSegmentIndices.get(selectedSegmentIndices.size() - 1);
            segmentSelectionAnchorIndex = selectedItemIndex;
        }
        notifySelectionChangeListeners();
    }

    private void notifySelectionChangeListeners() {
        for (SelectionChangeListener l : new ArrayList<>(selectionChangeListeners)) {
            l.selectionChanged();
        }
    }

    public void clearSelection() {
        selectItem(SelectableItemType.NONE, -1);
    }

    public boolean isItemSelected(SelectableItemType itemType, int itemIndex) {
        if (itemType == SelectableItemType.SEGMENT) {
            return selectedItemType == SelectableItemType.SEGMENT && selectedSegmentIndices.contains(itemIndex);
        }
        return selectedItemType == itemType && selectedItemIndex == itemIndex;
    }
}
