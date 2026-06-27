package com.audioeditor.ui;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.port.audio.AudioPlayer;
import com.audioeditor.ui.timeline.TimelineActions;
import com.audioeditor.ui.timeline.TimelineContextMenuFactory;
import com.audioeditor.ui.timeline.TimelineDragOperation;
import com.audioeditor.ui.timeline.TimelineGeometry;
import com.audioeditor.ui.timeline.TimelineHitTester;
import com.audioeditor.ui.timeline.TimelineInteractionController;
import com.audioeditor.ui.timeline.TimelineRenderer;
import com.audioeditor.ui.timeline.TimelineSelectionLookup;

import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Vertical-pile timeline. Each segment is drawn as its own row, stacked one
 * below the other. Within every row the segment's {@code [start, end]} range
 * maps left-to-right using a global pixels-per-second scale derived from the
 * longest segment. All editing flows through {@link ProjectEditor}; all drawing
 * is delegated to {@link TimelineRenderer}.
 */
public class TimelinePanel extends JPanel
        implements Scrollable, ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final int DEFAULT_ROW_HEIGHT_PIXELS = 120;

    private final ProjectModel model;
    private final TimelineGeometry geometry = new TimelineGeometry(DEFAULT_ROW_HEIGHT_PIXELS);
    private final TimelineRenderer renderer;
    private final TimelineHitTester hitTester;
    private final TimelineInteractionController interactionController;
    private final TimelineSelectionLookup selectionLookup;

    private int rowHeightPixels = DEFAULT_ROW_HEIGHT_PIXELS;
    private double playheadPositionInSeconds = 0.0;

    // Cached max segment duration — invalidated on modelChanged().
    private double cachedMaxDuration = 1e-3;

    // Scratch arrays for minimal repaint regions around the playhead.
    private final int[] playheadBoundsOld = new int[3];
    private final int[] playheadBoundsNew = new int[3];

    public TimelinePanel(ProjectModel model, ProjectEditor editor, AudioPlayer audio, SelectionModel selection) {
        this.model = model;
        this.cachedMaxDuration = recomputeMaxSegmentDuration();
        this.selectionLookup = new TimelineSelectionLookup(model);

        this.hitTester = new TimelineHitTester(model, geometry, selectionLookup);
        this.renderer = new TimelineRenderer(model, selection, geometry, audio, selectionLookup);

        TimelineActions actions = new TimelineActions(
                model, editor, selection,
                this,
                () -> playheadPositionInSeconds,
                t -> findSegmentContainingTime(t));

        TimelineContextMenuFactory contextMenuFactory = new TimelineContextMenuFactory(
                model, editor, selection, geometry, this.hitTester, selectionLookup, actions);

        this.interactionController = new TimelineInteractionController(
                model, editor, audio, selection, geometry,
                hitTester, selectionLookup,
                actions, contextMenuFactory,
                t -> setPlayheadPositionInSeconds(t));
        interactionController.setMaxDuration(cachedMaxDuration);

        setBackground(TimelineRenderer.PANEL_BACKGROUND);
        model.addProjectChangeListener(this);
        selection.addSelectionChangeListener(this);
        addMouseListener(interactionController);
        addMouseMotionListener(interactionController);
        setFocusable(true);
        registerKeyboardActions(actions);
        setToolTipText("");
    }

    // ---- public API --------------------------------------------------------

    public void setRowHeight(int rowHeightPixels) {
        this.rowHeightPixels = rowHeightPixels;
        geometry.setRowHeight(rowHeightPixels);
        revalidate();
        repaint();
    }

    public int getRowHeight() {
        return rowHeightPixels;
    }

    public void setPlayheadPositionInSeconds(double positionInSeconds) {
        if (positionInSeconds == this.playheadPositionInSeconds) return;
        renderer.computePlayheadBounds(this.playheadPositionInSeconds, playheadBoundsOld,
                cachedMaxDuration, Math.max(1, getWidth()));
        this.playheadPositionInSeconds = positionInSeconds;
        renderer.computePlayheadBounds(positionInSeconds, playheadBoundsNew,
                cachedMaxDuration, Math.max(1, getWidth()));

        if (playheadBoundsOld[0] >= 0 && playheadBoundsOld[0] == playheadBoundsNew[0]) {
            int xMin = Math.min(playheadBoundsOld[1], playheadBoundsNew[1]);
            int xMax = Math.max(playheadBoundsOld[2], playheadBoundsNew[2]);
            repaintPlayheadRow(playheadBoundsNew[0], xMin, xMax);
        } else {
            if (playheadBoundsOld[0] >= 0)
                repaintPlayheadRow(playheadBoundsOld[0], playheadBoundsOld[1], playheadBoundsOld[2]);
            if (playheadBoundsNew[0] >= 0)
                repaintPlayheadRow(playheadBoundsNew[0], playheadBoundsNew[1], playheadBoundsNew[2]);
        }
    }

    /** Y-centre of the segment row containing the playhead, or -1 if the playhead is outside all rows. */
    public int getPlayheadCenterY() {
        int si = findSegmentContainingTime(playheadPositionInSeconds);
        if (si < 0) return -1;
        return si * (rowHeightPixels + TimelineGeometry.ROW_SPACING_PIXELS) + rowHeightPixels / 2;
    }

    // ---- model/selection change callbacks ----------------------------------

    @Override
    public void modelChanged() {
        cachedMaxDuration = recomputeMaxSegmentDuration();
        interactionController.setMaxDuration(cachedMaxDuration);
        renderer.invalidateIntervalCache();
        revalidate();
        repaint();
    }

    @Override
    public void selectionChanged() {
        repaint();
    }

    // ---- painting ----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        renderer.paint(g, getWidth(), g.getClipBounds(),
                playheadPositionInSeconds, cachedMaxDuration,
                interactionController.getDragOperation(),
                interactionController.getDraggedItemIndex(),
                interactionController.getDragSegmentIndex());
    }

    // ---- Scrollable --------------------------------------------------------

    @Override
    public Dimension getPreferredSize() {
        int h = model.getSegments().size() * (rowHeightPixels + TimelineGeometry.ROW_SPACING_PIXELS) + 4;
        return new Dimension(800, Math.max(h, 100));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return rowHeightPixels + TimelineGeometry.ROW_SPACING_PIXELS;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(1, visibleRect.height - rowHeightPixels);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() { return true; }

    @Override
    public boolean getScrollableTracksViewportHeight() { return false; }

    // ---- tooltip -----------------------------------------------------------

    @Override
    public String getToolTipText(java.awt.event.MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        int w = getWidth();
        int si = geometry.hitSegmentRow(y, model.getSegments().size());
        if (si < 0) return null;
        Segment s = model.getSegments().get(si);
        int cw = geometry.contentWidth(s, w, cachedMaxDuration);
        if (x > cw) return String.format("%s  (empty)", s.getLabel());
        int hdrBot = geometry.headerBottom(si);
        int dbBot = geometry.downbeatZoneBottom(si);
        if (y >= hdrBot && y < dbBot) {
            int di = hitTester.hitDownbeatInSegment(x, si, w, cachedMaxDuration);
            if (di >= 0) {
                var bar = selectionLookup.barAt(di);
                if (bar != null && !bar.getBeats().isEmpty()) {
                    return String.format("downbeat %.3f s", bar.getBeats().get(0).getStart());
                }
            }
        }
        if (y >= dbBot) {
            int bi = hitTester.hitBeatInSegment(x, si, w, cachedMaxDuration);
            if (bi >= 0) {
                var b = selectionLookup.beatAt(bi);
                if (b != null) {
                    return String.format("beat %.3f–%.3f s  (%s)", b.getStart(), b.getEnd(),
                            b.isDownbeat() ? "downbeat" : "beat");
                }
            }
        }
        return String.format("%s  %.2f–%.2f s", s.getLabel(), s.getStart(), s.getEnd());
    }

    // ---- static color helper -----------------------------------------------

    static Color deriveSegmentLabelColor(String segmentLabel) {
        return TimelineRenderer.segmentLabelColor(segmentLabel);
    }

    // ---- private helpers ---------------------------------------------------

    private void registerKeyboardActions(TimelineActions actions) {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copySegments",
                e -> actions.copySelectedSegments());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "pasteSegments",
                e -> actions.pasteSegmentsAfterSelection());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK), "mergeSegments",
                e -> actions.mergeSelectedSegments());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "renameSegment",
                e -> actions.renamePrimarySelectedSegment());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "splitSegment", e -> actions.splitAtPlayhead());
    }

    private void bind(KeyStroke ks, String name, java.util.function.Consumer<ActionEvent> handler) {
        getInputMap(WHEN_FOCUSED).put(ks, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { handler.accept(e); }
        });
    }

    private void repaintPlayheadRow(int segIndex, int xMin, int xMax) {
        repaint(0, geometry.rowTop(segIndex) - 2, getWidth(), rowHeightPixels + 4);
    }

    int findSegmentContainingTime(double timeInSeconds) {
        java.util.List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (timeInSeconds >= s.getStart() && timeInSeconds <= s.getEnd()) return i;
        }
        return -1;
    }

    private double recomputeMaxSegmentDuration() {
        double max = 0;
        for (Segment s : model.getSegments()) max = Math.max(max, s.getDuration());
        return Math.max(max, 1e-3);
    }
}
