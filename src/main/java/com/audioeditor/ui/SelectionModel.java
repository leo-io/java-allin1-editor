package com.audioeditor.ui;

import com.audioeditor.model.ProjectModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    private UUID selectedItemId;
    private final List<Integer> selectedSegmentIndices = new ArrayList<>();
    private final List<UUID> selectedSegmentIds = new ArrayList<>();
    private int segmentSelectionAnchorIndex = -1;
    private final List<SelectionChangeListener> selectionChangeListeners = new ArrayList<>();
    private ProjectModel project;

    public void bind(ProjectModel project) {
        this.project = project;
        project.addDetailedProjectChangeListener(change -> reconcileAfterProjectChange());
    }

    public void addSelectionChangeListener(SelectionChangeListener listener) {
        selectionChangeListeners.add(listener);
    }

    public SelectableItemType getSelectedItemType() {
        return selectedItemType;
    }

    public int getSelectedItemIndex() {
        return selectedItemIndex;
    }

    public UUID getSelectedItemId() {
        return selectedItemId;
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
        selectedSegmentIds.clear();
        segmentSelectionAnchorIndex = -1;
        this.selectedItemType = itemType;
        this.selectedItemIndex = itemIndex;
        this.selectedItemId = resolveId(itemType, itemIndex);
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
        selectedSegmentIds.clear();
        if (segmentIndex >= 0) {
            selectedSegmentIndices.add(segmentIndex);
            UUID id = resolveId(SelectableItemType.SEGMENT, segmentIndex);
            if (id != null) {
                selectedSegmentIds.add(id);
            }
            segmentSelectionAnchorIndex = segmentIndex;
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = segmentIndex;
            selectedItemId = id;
        } else {
            segmentSelectionAnchorIndex = -1;
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
            selectedItemId = null;
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
            int position = selectedSegmentIndices.indexOf(segmentIndex);
            selectedSegmentIndices.remove(position);
            if (position < selectedSegmentIds.size()) {
                selectedSegmentIds.remove(position);
            }
        } else {
            selectedSegmentIndices.add(segmentIndex);
            UUID id = resolveId(SelectableItemType.SEGMENT, segmentIndex);
            if (id != null) {
                selectedSegmentIds.add(id);
            }
        }
        Collections.sort(selectedSegmentIndices);
        rebuildSelectedSegmentIds();
        if (selectedSegmentIndices.isEmpty()) {
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
            selectedItemId = null;
            segmentSelectionAnchorIndex = -1;
        } else {
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = segmentIndex;
            selectedItemId = resolveId(SelectableItemType.SEGMENT, segmentIndex);
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
        selectedSegmentIds.clear();
        for (int i = from; i <= to; i++) {
            selectedSegmentIndices.add(i);
            UUID id = resolveId(SelectableItemType.SEGMENT, i);
            if (id != null) {
                selectedSegmentIds.add(id);
            }
        }
        selectedItemType = SelectableItemType.SEGMENT;
        selectedItemIndex = segmentIndex;
        selectedItemId = resolveId(SelectableItemType.SEGMENT, segmentIndex);
        notifySelectionChangeListeners();
    }

    public void selectSegments(List<Integer> segmentIndices) {
        selectedSegmentIndices.clear();
        selectedSegmentIds.clear();
        if (segmentIndices != null) {
            for (Integer index : segmentIndices) {
                if (index != null && index >= 0 && !selectedSegmentIndices.contains(index)) {
                    selectedSegmentIndices.add(index);
                    UUID id = resolveId(SelectableItemType.SEGMENT, index);
                    if (id != null) {
                        selectedSegmentIds.add(id);
                    }
                }
            }
        }
        Collections.sort(selectedSegmentIndices);
        rebuildSelectedSegmentIds();
        if (selectedSegmentIndices.isEmpty()) {
            selectedItemType = SelectableItemType.NONE;
            selectedItemIndex = -1;
            selectedItemId = null;
            segmentSelectionAnchorIndex = -1;
        } else {
            selectedItemType = SelectableItemType.SEGMENT;
            selectedItemIndex = selectedSegmentIndices.get(selectedSegmentIndices.size() - 1);
            selectedItemId = resolveId(SelectableItemType.SEGMENT, selectedItemIndex);
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

    private UUID resolveId(SelectableItemType type, int index) {
        if (project == null || index < 0) {
            return null;
        }
        return switch (type) {
            case SEGMENT -> index < project.getSegments().size()
                    ? project.getSegments().get(index).getId() : null;
            case BAR -> index < project.getAllBarsFlat().size()
                    ? project.getAllBarsFlat().get(index).getId() : null;
            case BEAT -> index < project.getAllBeatsFlat().size()
                    ? project.getAllBeatsFlat().get(index).getId() : null;
            case NONE -> null;
        };
    }

    private void rebuildSelectedSegmentIds() {
        selectedSegmentIds.clear();
        for (int index : selectedSegmentIndices) {
            UUID id = resolveId(SelectableItemType.SEGMENT, index);
            if (id != null) {
                selectedSegmentIds.add(id);
            }
        }
    }

    private void reconcileAfterProjectChange() {
        if (project == null || selectedItemType == SelectableItemType.NONE) {
            return;
        }
        if (selectedItemType == SelectableItemType.SEGMENT && !selectedSegmentIds.isEmpty()) {
            selectedSegmentIndices.clear();
            for (UUID id : selectedSegmentIds) {
                int index = project.findSegmentIndex(id);
                if (index >= 0) {
                    selectedSegmentIndices.add(index);
                }
            }
            selectedItemIndex = project.findSegmentIndex(selectedItemId);
        } else if (selectedItemId != null) {
            selectedItemIndex = switch (selectedItemType) {
                case BEAT -> project.findBeatIndex(selectedItemId);
                case BAR -> project.findBarIndex(selectedItemId);
                case SEGMENT -> project.findSegmentIndex(selectedItemId);
                case NONE -> -1;
            };
        }
        if (selectedItemIndex < 0) {
            selectedItemType = SelectableItemType.NONE;
            selectedItemId = null;
            selectedSegmentIndices.clear();
            selectedSegmentIds.clear();
        }
        notifySelectionChangeListeners();
    }
}
