package com.audioeditor.ui.timeline;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.ui.SelectionModel;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Builds right-click popup menus for the timeline canvas. */
public final class TimelineContextMenuFactory {

    private static final Logger LOG = Logger.getLogger(TimelineContextMenuFactory.class.getName());

    private final ProjectModel model;
    private final ProjectEditor editor;
    private final SelectionModel selection;
    private final TimelineGeometry geometry;
    private final TimelineHitTester hitTester;
    private final TimelineSelectionLookup selectionLookup;
    private final TimelineActions actions;

    public TimelineContextMenuFactory(ProjectModel model, ProjectEditor editor, SelectionModel selection,
                                TimelineGeometry geometry, TimelineHitTester hitTester,
                                TimelineSelectionLookup selectionLookup, TimelineActions actions) {
        this.model = model;
        this.editor = editor;
        this.selection = selection;
        this.geometry = geometry;
        this.hitTester = hitTester;
        this.selectionLookup = selectionLookup;
        this.actions = actions;
    }

    public void show(JComponent canvas, MouseEvent e, double maxDuration) {
        int x = e.getX();
        int y = e.getY();
        int w = canvas.getWidth();
        JPopupMenu menu = new JPopupMenu();

        int si = geometry.hitSegmentRow(y, model.getSegments().size());
        if (si < 0) {
            JMenuItem add = new JMenuItem("Add segment here");
            add.addActionListener(a -> {
                double start = model.getMaxTime();
                Segment segment = new Segment("verse");
                Bar bar = new Bar();
                bar.addBeat(new Beat(true, start, start + 10));
                segment.addBar(bar);
                editor.addSegment(segment);
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, model.getSegments().size() - 1);
                LOG.fine("Timeline: added segment at " + start + "s (context menu)");
            });
            menu.add(add);
            menu.show(canvas, x, y);
            return;
        }

        Segment s = model.getSegments().get(si);
        int hdrBot = geometry.headerBottom(si);
        int dbBot = geometry.downbeatZoneBottom(si);

        if (y >= hdrBot && y < dbBot) {
            int di = hitTester.hitDownbeatInSegment(x, si, w, maxDuration);
            if (di >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.BAR, di);
                JMenuItem del = new JMenuItem("Delete downbeat (bar)");
                del.addActionListener(a -> {
                    LOG.fine("Timeline: delete downbeat (bar) #" + di + " (context menu)");
                    Bar bar = selectionLookup.barAt(di);
                    if (bar != null) editor.removeBar(bar);
                });
                menu.add(del);
                menu.show(canvas, x, y);
                return;
            }
        }
        if (y >= dbBot) {
            int bi = hitTester.hitBeatInSegment(x, si, w, maxDuration);
            if (bi >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.BEAT, bi);
                JMenuItem del = new JMenuItem("Delete beat");
                del.addActionListener(a -> {
                    LOG.fine("Timeline: delete beat #" + bi + " (context menu)");
                    Beat b = selectionLookup.beatAt(bi);
                    if (b != null) editor.removeBeat(b);
                });
                menu.add(del);
                menu.show(canvas, x, y);
                return;
            }
        }

        if (!selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, si)) {
            selection.selectSegment(si);
        }
        int cw = geometry.contentWidth(s, w, maxDuration);
        double clickedTime = x < cw ? geometry.xToTime(x, s, w, maxDuration) : s.getStart();

        JMenuItem rename = new JMenuItem("Rename segment...");
        rename.addActionListener(a -> actions.renameSegmentAt(si));
        menu.add(rename);

        JMenuItem copy = new JMenuItem("Copy segment(s)");
        copy.addActionListener(a -> actions.copySelectedSegments());
        menu.add(copy);

        JMenuItem paste = new JMenuItem("Paste segment(s)");
        paste.setEnabled(actions.hasClipboard());
        paste.addActionListener(a -> actions.pasteSegmentsAfterSelection());
        menu.add(paste);

        JMenuItem merge = new JMenuItem("Merge selected segments");
        merge.setEnabled(model.canMergeAdjacentSegments(selection.getSelectedSegmentIndices()));
        merge.addActionListener(a -> actions.mergeSelectedSegments());
        menu.add(merge);

        JMenuItem split = new JMenuItem("Split segment here");
        split.setEnabled(x < cw && model.canSplitSegmentAtNearestBarBoundary(si));
        split.addActionListener(a -> actions.splitSegmentAt(si, clickedTime));
        menu.add(split);

        menu.addSeparator();
        JMenuItem del = new JMenuItem("Delete segment");
        del.addActionListener(a -> {
            LOG.fine("Timeline: delete segment #" + si + " (context menu)");
            editor.removeSegment(si);
        });
        menu.add(del);
        addBorderRepairItems(menu, si);

        if (x < cw) {
            final double t = clickedTime;
            menu.addSeparator();
            JMenuItem addBeat = new JMenuItem("Add beat here");
            addBeat.addActionListener(a -> {
                Beat newBeat = new Beat(false, t, t + 0.5);
                editor.addBeatAt(s, newBeat);
                LOG.fine("Timeline: add beat at " + t + "s (context menu)");
            });
            JMenuItem addDownbeat = new JMenuItem("Add downbeat here");
            addDownbeat.addActionListener(a -> {
                Beat dbBeat = new Beat(true, t, t + 0.5);
                Bar newBar = new Bar();
                newBar.addBeat(dbBeat);
                editor.addBar(s, newBar);
                LOG.fine("Timeline: add downbeat at " + t + "s (context menu)");
            });
            menu.add(addBeat);
            menu.add(addDownbeat);
        }
        menu.show(canvas, x, y);
    }

    private void addBorderRepairItems(JPopupMenu menu, int segmentIndex) {
        menu.addSeparator();
        addEditItem(menu, segmentIndex, "Shrink to full-bar borders",
                model.canShrinkSegmentToFullBarBorders(segmentIndex),
                () -> editor.shrinkSegmentToFullBarBorders(segmentIndex));
        addEditItem(menu, segmentIndex, "Expand to full-bar borders",
                model.canExpandSegmentToFullBarBorders(segmentIndex),
                () -> editor.expandSegmentToFullBarBorders(segmentIndex));
        menu.addSeparator();
        addEditItem(menu, segmentIndex, "Take 1 bar from next (odd fix)",
                model.canMoveFirstBarsFromNextSegment(segmentIndex, 1),
                () -> editor.moveFirstBarsFromNextSegment(segmentIndex, 1));
        addEditItem(menu, segmentIndex, "Send 1 bar to next (odd fix)",
                model.canMoveLastBarsToNextSegment(segmentIndex, 1),
                () -> editor.moveLastBarsToNextSegment(segmentIndex, 1));
        addEditItem(menu, segmentIndex, "Take 2 bars from next",
                model.canMoveFirstBarsFromNextSegment(segmentIndex, 2),
                () -> editor.moveFirstBarsFromNextSegment(segmentIndex, 2));
        addEditItem(menu, segmentIndex, "Send 2 bars to next",
                model.canMoveLastBarsToNextSegment(segmentIndex, 2),
                () -> editor.moveLastBarsToNextSegment(segmentIndex, 2));
    }

    private void addEditItem(JPopupMenu menu, int segmentIndex, String label,
                              boolean enabled, BooleanSupplier edit) {
        Segment captured = model.getSegments().get(segmentIndex);
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(a -> {
            if (edit.getAsBoolean()) {
                int newIndex = indexOfSegment(captured);
                if (newIndex >= 0) {
                    selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, newIndex);
                }
                LOG.fine("Timeline: " + label + " for segment #" + segmentIndex);
            }
        });
        menu.add(item);
    }

    private int indexOfSegment(Segment target) {
        List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) == target) return i;
        }
        return -1;
    }
}
