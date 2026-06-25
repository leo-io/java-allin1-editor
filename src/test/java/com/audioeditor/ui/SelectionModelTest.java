package com.audioeditor.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionModelTest {

    @Test
    void singleSegmentSelectionSelectsOnlyOneSegment() {
        SelectionModel selection = new SelectionModel();

        selection.selectSegment(2);

        assertEquals(SelectionModel.SelectableItemType.SEGMENT, selection.getSelectedItemType());
        assertEquals(2, selection.getPrimarySelectedSegmentIndex());
        assertEquals(List.of(2), selection.getSelectedSegmentIndices());
        assertTrue(selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, 2));
        assertFalse(selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, 1));
    }

    @Test
    void ctrlToggleAddsAndRemovesSegments() {
        SelectionModel selection = new SelectionModel();

        selection.selectSegment(1);
        selection.toggleSegment(3);
        selection.toggleSegment(1);

        assertEquals(List.of(3), selection.getSelectedSegmentIndices());
        assertTrue(selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, 3));
        assertFalse(selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, 1));
    }

    @Test
    void shiftRangeSelectsContiguousSegmentsFromAnchor() {
        SelectionModel selection = new SelectionModel();

        selection.selectSegment(2);
        selection.selectSegmentRange(5);

        assertEquals(List.of(2, 3, 4, 5), selection.getSelectedSegmentIndices());
        assertEquals(5, selection.getPrimarySelectedSegmentIndex());
    }

    @Test
    void selectingBeatClearsSegmentMultiSelection() {
        SelectionModel selection = new SelectionModel();

        selection.selectSegments(List.of(1, 2, 3));
        selection.selectItem(SelectionModel.SelectableItemType.BEAT, 7);

        assertEquals(SelectionModel.SelectableItemType.BEAT, selection.getSelectedItemType());
        assertEquals(7, selection.getSelectedItemIndex());
        assertTrue(selection.getSelectedSegmentIndices().isEmpty());
    }
}
