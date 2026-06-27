package com.audioeditor.ui.timeline;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.port.audio.AudioPlayer;
import com.audioeditor.ui.SelectionModel;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;

/**
 * Mouse handler for the timeline canvas. Maintains drag state and delegates to
 * hit-testing, audio transport, actions, and the context-menu factory.
 */
public final class TimelineInteractionController extends MouseAdapter {

    private static final Logger LOG = Logger.getLogger(TimelineInteractionController.class.getName());
    private static final int SEGMENT_EDGE_GRAB_WIDTH_PIXELS = 6;

    private final ProjectModel model;
    private final ProjectEditor editor;
    private final AudioPlayer audio;
    private final SelectionModel selection;
    private final TimelineGeometry geometry;
    private final TimelineHitTester hitTester;
    private final TimelineSelectionLookup selectionLookup;
    private final TimelineActions actions;
    private final TimelineContextMenuFactory contextMenu;
    /** Callback to update the panel's playhead position field and trigger repaint. */
    private final DoubleConsumer playheadUpdater;

    // drag state (read by TimelineRenderer for orphan marker drawing)
    private TimelineDragOperation activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
    private int draggedItemIndex = -1;
    private int dragSegmentIndex = -1;
    private double segmentDragGrabOffsetInSeconds = 0;
    private double maxDurationSupplied = 1e-3; // updated before each mouse event via setMaxDuration()

    public TimelineInteractionController(ProjectModel model, ProjectEditor editor, AudioPlayer audio,
                                   SelectionModel selection, TimelineGeometry geometry,
                                   TimelineHitTester hitTester, TimelineSelectionLookup selectionLookup,
                                   TimelineActions actions, TimelineContextMenuFactory contextMenu,
                                   DoubleConsumer playheadUpdater) {
        this.model = model;
        this.editor = editor;
        this.audio = audio;
        this.selection = selection;
        this.geometry = geometry;
        this.hitTester = hitTester;
        this.selectionLookup = selectionLookup;
        this.actions = actions;
        this.contextMenu = contextMenu;
        this.playheadUpdater = playheadUpdater;
    }

    // ---- drag state accessors (for TimelineRenderer) -----------------------

    public TimelineDragOperation getDragOperation() { return activeDragOperation; }
    public int getDraggedItemIndex() { return draggedItemIndex; }
    public int getDragSegmentIndex() { return dragSegmentIndex; }

    public void setMaxDuration(double maxDuration) {
        this.maxDurationSupplied = maxDuration;
    }

    // ---- mouse handling ----------------------------------------------------

    @Override
    public void mousePressed(MouseEvent e) {
        ((JComponent) e.getSource()).requestFocusInWindow();
        int x = e.getX();
        int y = e.getY();
        int w = ((JComponent) e.getSource()).getWidth();

        if (SwingUtilities.isRightMouseButton(e)) {
            contextMenu.show((JComponent) e.getSource(), e, maxDurationSupplied);
            return;
        }
        if (e.getClickCount() == 2) {
            int si = geometry.hitSegmentRow(y, model.getSegments().size());
            if (si >= 0) {
                Segment s = model.getSegments().get(si);
                selection.selectSegment(si);
                audio.seekSeconds(s.getStart());
                playheadUpdater.accept(s.getStart());
                audio.play();
                LOG.fine("Timeline: double-click play segment #" + si + " from " + s.getStart() + "s");
            }
            return;
        }

        int si = geometry.hitSegmentRow(y, model.getSegments().size());
        if (si < 0) return;
        Segment s = model.getSegments().get(si);
        int cw = geometry.contentWidth(s, w, maxDurationSupplied);

        if (x <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
            handleSegmentSelectionClick(e, si);
            startDrag(TimelineDragOperation.DRAGGING_SEGMENT_START_EDGE, si, si);
            LOG.fine("Timeline: grab segment #" + si + " start edge");
            return;
        }
        if (x >= cw - SEGMENT_EDGE_GRAB_WIDTH_PIXELS && x < cw + SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
            handleSegmentSelectionClick(e, si);
            startDrag(TimelineDragOperation.DRAGGING_SEGMENT_END_EDGE, si, si);
            LOG.fine("Timeline: grab segment #" + si + " end edge");
            return;
        }
        if (x > cw) {
            handleSegmentSelectionClick(e, si);
            activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
            draggedItemIndex = -1;
            dragSegmentIndex = si;
            return;
        }

        int hdrBot = geometry.headerBottom(si);
        int dbBot = geometry.downbeatZoneBottom(si);

        if (y < hdrBot) {
            handleSegmentSelectionClick(e, si);
            startDrag(TimelineDragOperation.DRAGGING_SEGMENT_BODY, si, si);
            double clickTime = geometry.xToTime(x, s, w, maxDurationSupplied);
            segmentDragGrabOffsetInSeconds = clickTime - s.getStart();
            LOG.fine("Timeline: selected segment #" + si + " (body) at " + s.getStart() + "s");
            audio.seekSeconds(s.getStart());
            playheadUpdater.accept(s.getStart());
            return;
        }

        if (y < dbBot) {
            int di = hitTester.hitDownbeatInSegment(x, si, w, maxDurationSupplied);
            if (di >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.BAR, di);
                startDrag(TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER, di, si);
                Bar bar = selectionLookup.barAt(di);
                double t = (bar != null && !bar.getBeats().isEmpty()) ? bar.getBeats().get(0).getStart() : 0;
                LOG.fine("Timeline: selected downbeat (bar) #" + di + " at " + t + "s");
                seek(t);
                return;
            }
        }
        if (y >= dbBot) {
            int bi = hitTester.hitBeatInSegment(x, si, w, maxDurationSupplied);
            if (bi >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.BEAT, bi);
                startDrag(TimelineDragOperation.DRAGGING_BEAT_MARKER, bi, si);
                Beat b = selectionLookup.beatAt(bi);
                double t = (b != null) ? b.getStart() : 0;
                LOG.fine("Timeline: selected beat #" + bi + " at " + t + "s");
                seek(t);
                return;
            }
        }

        activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
        draggedItemIndex = -1;
        dragSegmentIndex = si;
        double t = geometry.xToTime(x, s, w, maxDurationSupplied);
        LOG.fine("Timeline: seek to " + t + "s (segment #" + si + ")");
        audio.seekSeconds(t);
        playheadUpdater.accept(t);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        int w = ((JComponent) e.getSource()).getWidth();
        if (activeDragOperation == TimelineDragOperation.NO_DRAG_ACTIVE) {
            if (dragSegmentIndex >= 0 && dragSegmentIndex < model.getSegments().size()) {
                Segment s = model.getSegments().get(dragSegmentIndex);
                double t = geometry.xToTime(e.getX(), s, w, maxDurationSupplied);
                audio.seekSeconds(t);
                playheadUpdater.accept(t);
            }
            return;
        }
        if (draggedItemIndex < 0) return;
        switch (activeDragOperation) {
            case DRAGGING_BEAT_MARKER -> {
                Beat b = selectionLookup.beatAt(draggedItemIndex);
                if (b != null) {
                    Segment dragSeg = model.getSegments().get(dragSegmentIndex);
                    double t = geometry.xToTime(e.getX(), dragSeg, w, maxDurationSupplied);
                    editor.previewBeatTiming(b, t, t + (b.getEnd() - b.getStart()));
                }
            }
            case DRAGGING_DOWNBEAT_MARKER -> {
                Bar bar = selectionLookup.barAt(draggedItemIndex);
                if (bar != null && !bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
                    Segment dragSeg = model.getSegments().get(dragSegmentIndex);
                    double t = geometry.xToTime(e.getX(), dragSeg, w, maxDurationSupplied);
                    Beat firstBeat = bar.getBeats().get(0);
                    editor.previewBeatTiming(firstBeat, t, t + (firstBeat.getEnd() - firstBeat.getStart()));
                }
            }
            // Segment edge/body dragging disabled — edges are computed from bars
            default -> { }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (activeDragOperation == TimelineDragOperation.DRAGGING_BEAT_MARKER) {
            LOG.fine("Timeline: finished dragging beat #" + draggedItemIndex);
            finishMarkerDrag(SelectionModel.SelectableItemType.BEAT);
        } else if (activeDragOperation == TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER) {
            LOG.fine("Timeline: finished dragging downbeat (bar) #" + draggedItemIndex);
            finishMarkerDrag(SelectionModel.SelectableItemType.BAR);
        } else if (activeDragOperation != TimelineDragOperation.NO_DRAG_ACTIVE) {
            LOG.fine("Timeline: finished dragging segment #" + draggedItemIndex + " (" + activeDragOperation + ")");
        }
        activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
        draggedItemIndex = -1;
        dragSegmentIndex = -1;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        int w = ((JComponent) e.getSource()).getWidth();
        int cursor = Cursor.DEFAULT_CURSOR;
        int si = geometry.hitSegmentRow(y, model.getSegments().size());
        if (si >= 0) {
            Segment s = model.getSegments().get(si);
            int cw = geometry.contentWidth(s, w, maxDurationSupplied);
            if (x <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS
                    || (x >= cw - SEGMENT_EDGE_GRAB_WIDTH_PIXELS && x < cw + SEGMENT_EDGE_GRAB_WIDTH_PIXELS)) {
                cursor = Cursor.E_RESIZE_CURSOR;
            } else if (x <= cw) {
                int hdrBot = geometry.headerBottom(si);
                int dbBot = geometry.downbeatZoneBottom(si);
                if (y < hdrBot) {
                    cursor = Cursor.MOVE_CURSOR;
                } else if (y < dbBot) {
                    if (hitTester.hitDownbeatInSegment(x, si, w, maxDurationSupplied) >= 0)
                        cursor = Cursor.HAND_CURSOR;
                } else {
                    if (hitTester.hitBeatInSegment(x, si, w, maxDurationSupplied) >= 0)
                        cursor = Cursor.HAND_CURSOR;
                }
            }
        }
        ((JComponent) e.getSource()).setCursor(Cursor.getPredefinedCursor(cursor));
    }

    // ---- helpers -----------------------------------------------------------

    private void startDrag(TimelineDragOperation op, int itemIndex, int segmentIndex) {
        activeDragOperation = op;
        draggedItemIndex = itemIndex;
        dragSegmentIndex = segmentIndex;
    }

    private void finishMarkerDrag(SelectionModel.SelectableItemType itemType) {
        if (itemType == SelectionModel.SelectableItemType.BEAT) {
            Beat beat = selectionLookup.beatAt(draggedItemIndex);
            if (beat != null) {
                editor.finishBeatDrag(beat, dragSegmentIndex);
                int newIndex = selectionLookup.beatIndex(beat);
                if (newIndex >= 0) selection.selectItem(SelectionModel.SelectableItemType.BEAT, newIndex);
            }
        } else if (itemType == SelectionModel.SelectableItemType.BAR) {
            Bar bar = selectionLookup.barAt(draggedItemIndex);
            if (bar != null) {
                editor.finishBarDrag(bar, dragSegmentIndex);
                int newIndex = selectionLookup.barIndex(bar);
                if (newIndex >= 0) selection.selectItem(SelectionModel.SelectableItemType.BAR, newIndex);
            }
        }
    }

    private void handleSegmentSelectionClick(MouseEvent e, int segmentIndex) {
        if (e.isShiftDown()) selection.selectSegmentRange(segmentIndex);
        else if (e.isControlDown() || e.isMetaDown()) selection.toggleSegment(segmentIndex);
        else selection.selectSegment(segmentIndex);
    }

    private void seek(double t) {
        audio.seekSeconds(t);
        playheadUpdater.accept(t);
    }
}
