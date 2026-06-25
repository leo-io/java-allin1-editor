package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Vertical-pile timeline. Each segment is drawn as its own row, stacked one
 * below the other. Within every row the segment's {@code [start, end]} range
 * maps left-to-right using a <b>global</b> pixels-per-second scale derived from
 * the longest segment, so a segment's visual width is proportional to its
 * duration and the remaining space on the right is left empty. Because the
 * scale is shared, beats and downbeats at the same offset within different
 * segments align vertically across rows, and the playhead travels at a
 * constant pixel speed regardless of which segment is playing.
 *
 * <p>There is no ruler and no horizontal scrolling — the panel tracks the
 * viewport width and scrolls vertically only. All marker editing (seek,
 * select, drag-to-move, drag-to-resize segments, double-click-to-play,
 * right-click-to-delete) happens here and flows through {@link ProjectModel} so
 * the tables and playhead update live.
 */
public class TimelinePanel extends JPanel implements Scrollable, ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(TimelinePanel.class.getName());

    // ---- row layout (each segment = one full-width row) ----------------
    private static final int DEFAULT_ROW_HEIGHT_PIXELS = 120;
    private static final int HEADER_HEIGHT_PIXELS = 22; // label strip at top of each row
    private static final int HIT_TEST_TOLERANCE_PIXELS = 5;
    private static final int SEGMENT_EDGE_GRAB_WIDTH_PIXELS = 6;
    private static final int ROW_SPACING_PIXELS = 2;

    // ---- reused paint resources (hoisted to avoid per-paint allocation) ----
    private static final Color PANEL_BACKGROUND = new Color(0x1e1e1e);
    private static final Color DOWNBEAT_COLOR = new Color(0xff6e6e);
    private static final Color BEAT_ONE_COLOR = new Color(0x6ec1ff);
    private static final Color BEAT_OTHER_COLOR = new Color(0x4a7a99);
    private static final Color BEAT_LABEL_COLOR = new Color(0x99c7e0);
    private static final Color ZONE_SEPARATOR_COLOR = new Color(0, 0, 0, 70);
    private static final Color SEGMENT_EDGE_HANDLE_COLOR = new Color(255, 255, 255, 120);
    private static final Color PLAYHEAD_COLOR = new Color(0xffd24a);
    private static final int PLAYHEAD_BLOCK_ALPHA = 60;
    private static final Color PLAYHEAD_BLOCK_COLOR = new Color(
            PLAYHEAD_COLOR.getRed(), PLAYHEAD_COLOR.getGreen(), PLAYHEAD_COLOR.getBlue(), PLAYHEAD_BLOCK_ALPHA);

    private static final BasicStroke SEGMENT_STROKE_SELECTED = new BasicStroke(2f);
    private static final BasicStroke SEGMENT_STROKE = new BasicStroke(1f);
    private static final BasicStroke DOWNBEAT_STROKE_SELECTED = new BasicStroke(3f);
    private static final BasicStroke DOWNBEAT_STROKE = new BasicStroke(2f);
    private static final BasicStroke BEAT_STROKE_SELECTED = new BasicStroke(2.5f);
    private static final BasicStroke BEAT_STROKE_ONE = new BasicStroke(1.6f);
    private static final BasicStroke BEAT_STROKE = new BasicStroke(1f);

    private static final int SEGMENT_FILL_ALPHA_SELECTED = 235;
    private static final int SEGMENT_FILL_ALPHA = 170;

    // Per-label color cache: label → {base, headerNormal, headerSelected, bodyNormal, bodySelected, border, separator}
    // Populated on first access per label; avoids per-frame Color allocation in the hot paint path.
    private static final Map<String, Color[]> SEGMENT_COLORS = new HashMap<>();

    // Interned bar-position labels so the hot paint path never calls
    // Integer.toString() per beat per frame.
    private static final String[] BAR_POSITION_LABELS = new String[33];
    static {
        for (int i = 0; i < BAR_POSITION_LABELS.length; i++) {
            BAR_POSITION_LABELS[i] = Integer.toString(i);
        }
    }

    private static String barPositionLabel(int position) {
        return (position >= 0 && position < BAR_POSITION_LABELS.length)
                ? BAR_POSITION_LABELS[position]
                : Integer.toString(position);
    }

    private static Color[] segmentColors(String label) {
        return SEGMENT_COLORS.computeIfAbsent(label == null ? "" : label, l -> {
            int hue = Math.floorMod(l.toLowerCase().hashCode(), 360);
            Color base = Color.getHSBColor(hue / 360f, 0.55f, 0.75f);
            return new Color[]{
                base,
                new Color(base.getRed(), base.getGreen(), base.getBlue(), SEGMENT_FILL_ALPHA),
                new Color(base.getRed(), base.getGreen(), base.getBlue(), SEGMENT_FILL_ALPHA_SELECTED),
                new Color(base.getRed(), base.getGreen(), base.getBlue(), 60),
                new Color(base.getRed(), base.getGreen(), base.getBlue(), 95),
                base.darker(),
                base.darker().darker(),
            };
        });
    }

    private final ProjectModel model;
    private final PcmWavPlaybackEngine audio;
    private final SelectionModel selection;

    private int rowHeightPixels = DEFAULT_ROW_HEIGHT_PIXELS;
    private double playheadPositionInSeconds = 0.0;

    // drag state
    private enum TimelineDragOperation {
        NO_DRAG_ACTIVE, DRAGGING_BEAT_MARKER, DRAGGING_DOWNBEAT_MARKER,
        DRAGGING_SEGMENT_BODY, DRAGGING_SEGMENT_START_EDGE, DRAGGING_SEGMENT_END_EDGE
    }

    private TimelineDragOperation activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
    private int draggedItemIndex = -1;
    private int dragSegmentIndex = -1;
    private double segmentDragGrabOffsetInSeconds = 0;
    private double dragInitialSegmentStart = 0;
    private double dragInitialSegmentEnd = 0;

    // Derived once on first paint, then reused to avoid per-paint Font allocation.
    private Font segmentLabelFont;
    private Font beatLabelFont;
    private final int[] playheadTriangleX = new int[3];
    private final int[] playheadTriangleY = new int[3];
    // Reusable {segmentRow, xMin, xMax} scratch for narrow playhead repaints.
    private final int[] playheadBoundsOld = new int[3];
    private final int[] playheadBoundsNew = new int[3];

    // Cached derived values — invalidated in modelChanged() to avoid per-frame recomputation.
    private double cachedMaxDuration = 1e-3;
    private double[] cachedBeatInterval = null;
    private double[] cachedDownbeatInterval = null;
    private int cachedIntervalsForSegment = -1;

    public TimelinePanel(ProjectModel model, PcmWavPlaybackEngine audio, SelectionModel selection) {
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.cachedMaxDuration = recomputeMaxSegmentDuration();
        setBackground(PANEL_BACKGROUND);
        model.addProjectChangeListener(this);
        selection.addSelectionChangeListener(this);
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        setToolTipText("");
    }

    public void setRowHeight(int rowHeightPixels) {
        this.rowHeightPixels = rowHeightPixels;
        revalidate();
        repaint();
    }

    public int getRowHeight() {
        return rowHeightPixels;
    }

    public void setPlayheadPositionInSeconds(double positionInSeconds) {
        if (positionInSeconds == this.playheadPositionInSeconds) {
            return;
        }
        // Compute the exact x-extent the playhead block occupies before and after
        // the move (triangle + the beat/downbeat interval blocks), so we repaint
        // only those columns of the affected row(s) instead of every full row.
        computePlayheadPaintBounds(this.playheadPositionInSeconds, playheadBoundsOld);
        this.playheadPositionInSeconds = positionInSeconds;
        computePlayheadPaintBounds(positionInSeconds, playheadBoundsNew);

        if (playheadBoundsOld[0] >= 0 && playheadBoundsOld[0] == playheadBoundsNew[0]) {
            // Same row: one repaint over the union of both blocks' x-range.
            int xMin = Math.min(playheadBoundsOld[1], playheadBoundsNew[1]);
            int xMax = Math.max(playheadBoundsOld[2], playheadBoundsNew[2]);
            repaintPlayheadRow(playheadBoundsNew[0], xMin, xMax);
        } else {
            // Different (or vanishing/appearing) rows: repaint each side's block.
            if (playheadBoundsOld[0] >= 0) {
                repaintPlayheadRow(playheadBoundsOld[0], playheadBoundsOld[1], playheadBoundsOld[2]);
            }
            if (playheadBoundsNew[0] >= 0) {
                repaintPlayheadRow(playheadBoundsNew[0], playheadBoundsNew[1], playheadBoundsNew[2]);
            }
        }
    }

    private void repaintPlayheadRow(int segIndex, int xMin, int xMax) {
        // Always start at x=0 so the header strip (where the bar/beat counter lives) is included.
        repaint(0, rowTopY(segIndex) - 2, xMax + 4, rowHeightPixels + 4);
    }

    /**
     * Fill {@code out} with {@code {segmentRow, xMin, xMax}} describing the pixel
     * columns the playhead (triangle + interval blocks) covers at {@code time};
     * {@code out[0] = -1} when the time is in no segment. Mirrors {@link #drawPlayhead}.
     */
    private void computePlayheadPaintBounds(double time, int[] out) {
        int si = findSegmentContainingTime(time);
        if (si < 0) {
            out[0] = -1;
            return;
        }
        Segment s = model.getSegments().get(si);
        int w = Math.max(1, getWidth());
        int x = timeToXInSegment(time, s, w);
        int xMin = x - 6; // triangle half-width (5) + slack
        int xMax = x + 6;
        double[] db = currentDownbeatInterval(s, time);
        if (db != null) {
            xMin = Math.min(xMin, timeToXInSegment(db[0], s, w));
            xMax = Math.max(xMax, timeToXInSegment(db[1], s, w));
        }
        double[] be = currentBeatInterval(s, time);
        if (be != null) {
            xMin = Math.min(xMin, timeToXInSegment(be[0], s, w));
            xMax = Math.max(xMax, timeToXInSegment(be[1], s, w));
        }
        out[0] = si;
        out[1] = xMin;
        out[2] = xMax;
    }

    /** Y-centre of the segment row containing the playhead (-1 if in no segment). */
    public int getPlayheadCenterY() {
        int si = findSegmentContainingTime(playheadPositionInSeconds);
        if (si < 0) {
            return -1;
        }
        return si * (rowHeightPixels + ROW_SPACING_PIXELS) + rowHeightPixels / 2;
    }

    @Override
    public void modelChanged() {
        cachedMaxDuration = recomputeMaxSegmentDuration();
        cachedBeatInterval = null;
        cachedDownbeatInterval = null;
        cachedIntervalsForSegment = -1;
        revalidate();
        repaint();
    }

    @Override
    public void selectionChanged() {
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int h = model.getSegments().size() * (rowHeightPixels + ROW_SPACING_PIXELS) + 4;
        return new Dimension(800, Math.max(h, 100));
    }

    // ---- Scrollable: track viewport width, scroll vertically -------------

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return rowHeightPixels + ROW_SPACING_PIXELS;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(1, visibleRect.height - rowHeightPixels);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // ---- row geometry helpers -------------------------------------------

    private int rowTopY(int segmentIndex) {
        return segmentIndex * (rowHeightPixels + ROW_SPACING_PIXELS);
    }

    private int rowBottomY(int segmentIndex) {
        return rowTopY(segmentIndex) + rowHeightPixels;
    }

    private int headerBottomY(int segmentIndex) {
        return rowTopY(segmentIndex) + HEADER_HEIGHT_PIXELS;
    }

    private int downbeatZoneHeight() {
        return Math.max(16, (rowHeightPixels - HEADER_HEIGHT_PIXELS) / 3);
    }

    private int downbeatZoneBottomY(int segmentIndex) {
        return headerBottomY(segmentIndex) + downbeatZoneHeight();
    }

    // ---- time ↔ x mapping (global scale, shared by all segment rows) ----

    /** Largest segment duration — defines the global px/s scale for every row. Cached; see cachedMaxDuration. */
    private double maxSegmentDuration() {
        return cachedMaxDuration;
    }

    private double recomputeMaxSegmentDuration() {
        double max = 0;
        for (Segment s : model.getSegments()) {
            max = Math.max(max, s.getDuration());
        }
        return Math.max(max, 1e-3);
    }

    /**
     * Visual width in pixels of a segment's content area. Proportional to the
     * segment's duration relative to the longest segment; the remaining panel
     * width to the right is empty space.
     */
    private int segmentContentWidth(Segment s, int panelWidth) {
        return (int) Math.round((s.getDuration() / maxSegmentDuration()) * panelWidth);
    }

    private int timeToXInSegment(double timeInSeconds, Segment s, int panelWidth) {
        double scale = panelWidth / maxSegmentDuration(); // px per second (global)
        return (int) Math.round((timeInSeconds - s.getStart()) * scale);
    }

    private double xToTimeInSegment(int pixelX, Segment s, int panelWidth) {
        if (panelWidth <= 0) {
            return s.getStart();
        }
        double scale = maxSegmentDuration() / panelWidth; // seconds per pixel (global)
        return s.getStart() + pixelX * scale;
    }

    private int findSegmentContainingTime(double timeInSeconds) {
        for (int i = 0; i < model.getSegments().size(); i++) {
            Segment s = model.getSegments().get(i);
            if (timeInSeconds >= s.getStart() && timeInSeconds <= s.getEnd()) {
                return i;
            }
        }
        return -1;
    }

    private boolean isTimeWithinAnySegment(double timeInSeconds) {
        return findSegmentContainingTime(timeInSeconds) >= 0;
    }

    private int playheadRowTopY(double timeInSeconds) {
        int si = findSegmentContainingTime(timeInSeconds);
        if (si < 0) {
            return -rowHeightPixels;
        }
        return rowTopY(si);
    }

    // ---- painting --------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (segmentLabelFont == null) {
            Font base = g.getFont();
            segmentLabelFont = base.deriveFont(Font.BOLD, 11f);
            beatLabelFont = base.deriveFont(9f);
        }
        Rectangle clip = g.getClipBounds();
        int w = getWidth();
        int yMin = clip.y - 4;
        int yMax = clip.y + clip.height + 4;

        drawSegments(g, w, yMin, yMax);
        drawDraggedOrphanMarker(g, w, yMin, yMax);
        drawPlayhead(g, w);
    }

    private void drawSegments(Graphics2D g, int w, int yMin, int yMax) {
        g.setFont(segmentLabelFont);
        int dbZoneH = downbeatZoneHeight();
        List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            int yTop = rowTopY(i);
            int yBot = rowBottomY(i);
            if (yBot < yMin || yTop > yMax) {
                continue;
            }
            int cw = segmentContentWidth(s, w);
            int hdrH = HEADER_HEIGHT_PIXELS;
            int hdrBot = yTop + hdrH;
            int dbBot = hdrBot + dbZoneH;
            int bodyH = rowHeightPixels - hdrH;
            // All Color objects for this label are pre-computed and cached:
            // [0]=base [1]=headerNormal [2]=headerSelected [3]=bodyNormal [4]=bodySelected [5]=border [6]=separator
            Color[] colors = segmentColors(s.getLabel());
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, i);
            g.setColor(sel ? colors[2] : colors[1]);
            g.fillRect(0, yTop, cw, hdrH);
            g.setColor(sel ? colors[4] : colors[3]);
            g.fillRect(0, hdrBot, cw, bodyH);
            g.setColor(sel ? Color.WHITE : colors[5]);
            g.setStroke(sel ? SEGMENT_STROKE_SELECTED : SEGMENT_STROKE);
            g.drawRect(0, yTop, cw - 1, rowHeightPixels - 1);
            g.setColor(colors[6]);
            g.drawLine(0, hdrBot, cw, hdrBot);
            g.setColor(ZONE_SEPARATOR_COLOR);
            g.drawLine(0, dbBot, cw, dbBot);
            // edge handles (left & right borders of the content area)
            g.setColor(SEGMENT_EDGE_HANDLE_COLOR);
            g.fillRect(0, yTop, 2, rowHeightPixels);
            g.fillRect(cw - 2, yTop, 2, rowHeightPixels);
            // label + bar/beat counter in header
            g.setColor(Color.WHITE);
            String headerText = cw > 150
                    ? String.format("%s - %s", s.getLabel(), buildSegmentCounter(s))
                    : s.getLabel();
            g.drawString(headerText, 6, yTop + 15);
            // markers that fall inside this segment's time range
            drawDownbeatsInSegment(g, s, i, w, hdrBot, dbBot, yMin, yMax);
            drawBeatsInSegment(g, s, i, w, dbBot, yBot, yMin, yMax);
        }
    }

    private void drawDownbeatsInSegment(Graphics2D g, Segment s, int segIndex,
                                        int w, int zoneTop, int zoneBottom, int yMin, int yMax) {
        if (zoneBottom <= zoneTop) {
            return;
        }
        List<Double> downbeats = model.getDownbeats();
        for (int i = 0; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int x = timeToXInSegment(t, s, w);
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.DOWNBEAT, i);
            g.setColor(sel ? Color.WHITE : DOWNBEAT_COLOR);
            g.setStroke(sel ? DOWNBEAT_STROKE_SELECTED : DOWNBEAT_STROKE);
            g.drawLine(x, zoneTop, x, zoneBottom);
        }
    }

    private void drawBeatsInSegment(Graphics2D g, Segment s, int segIndex,
                                    int w, int zoneTop, int zoneBottom, int yMin, int yMax) {
        if (zoneBottom <= zoneTop) {
            return;
        }
        List<Beat> beats = model.getBeats();
        for (int i = 0; i < beats.size(); i++) {
            Beat b = beats.get(i);
            double t = b.getTime();
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int x = timeToXInSegment(t, s, w);
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.BEAT, i);
            boolean one = b.getPosition() == 1;
            if (sel) {
                g.setColor(Color.WHITE);
                g.setStroke(BEAT_STROKE_SELECTED);
            } else {
                g.setColor(one ? BEAT_ONE_COLOR : BEAT_OTHER_COLOR);
                g.setStroke(one ? BEAT_STROKE_ONE : BEAT_STROKE);
            }
            g.drawLine(x, zoneTop, x, zoneBottom);
            // bar-position label at the bottom of the line when there is room
            if (zoneBottom - zoneTop > 18) {
                g.setFont(beatLabelFont);
                g.setColor(sel ? Color.WHITE : BEAT_LABEL_COLOR);
                g.drawString(barPositionLabel(b.getPosition()), x + 2, zoneBottom - 3);
            }
        }
    }

    private String buildSegmentCounter(Segment s) {
        double start = s.getStart();
        double end = s.getEnd();
        double pos = playheadPositionInSeconds;
        List<Double> downbeats = model.getDownbeats();
        List<Beat> beats = model.getBeats();

        int totalBars = 0;
        for (double db : downbeats) {
            if (db >= start && db <= end) totalBars++;
        }
        int totalBeats = 0;
        for (Beat b : beats) {
            if (b.getTime() >= start && b.getTime() <= end) totalBeats++;
        }

        boolean active = audio.isPlaying() && pos >= start && pos <= end;
        int playingBar = 0;
        int playingBeat = 0;
        if (active) {
            for (double db : downbeats) {
                if (db >= start && db <= pos) playingBar++;
            }
            for (Beat b : beats) {
                if (b.getTime() >= start && b.getTime() <= pos) playingBeat++;
            }
        }

        return String.format("%02d / %02d - %02d / %02d", playingBar, totalBars, playingBeat, totalBeats);
    }

    /**
     * While a beat/downbeat is being dragged it may leave every segment's time
     * range; draw it clamped to the original row edge so it doesn't vanish
     * mid-drag (it snaps back into a segment on release / sort).
     */
    private void drawDraggedOrphanMarker(Graphics2D g, int w, int yMin, int yMax) {
        if (dragSegmentIndex < 0 || draggedItemIndex < 0) {
            return;
        }
        int yTop = rowTopY(dragSegmentIndex);
        if (yTop + rowHeightPixels < yMin || yTop > yMax) {
            return;
        }
        Segment dragSeg = model.getSegments().get(dragSegmentIndex);
        int cw = segmentContentWidth(dragSeg, w);
        if (activeDragOperation == TimelineDragOperation.DRAGGING_BEAT_MARKER) {
            Beat b = model.getBeats().get(draggedItemIndex);
            if (!isTimeWithinAnySegment(b.getTime())) {
                int x = Math.max(0, Math.min(cw, timeToXInSegment(b.getTime(), dragSeg, w)));
                int zoneTop = downbeatZoneBottomY(dragSegmentIndex);
                int zoneBottom = rowBottomY(dragSegmentIndex);
                g.setColor(Color.WHITE);
                g.setStroke(BEAT_STROKE_SELECTED);
                g.drawLine(x, zoneTop, x, zoneBottom);
            }
        } else if (activeDragOperation == TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER) {
            double t = model.getDownbeats().get(draggedItemIndex);
            if (!isTimeWithinAnySegment(t)) {
                int x = Math.max(0, Math.min(cw, timeToXInSegment(t, dragSeg, w)));
                int zoneTop = headerBottomY(dragSegmentIndex);
                int zoneBottom = downbeatZoneBottomY(dragSegmentIndex);
                g.setColor(Color.WHITE);
                g.setStroke(DOWNBEAT_STROKE_SELECTED);
                g.drawLine(x, zoneTop, x, zoneBottom);
            }
        }
    }

    /**
     * Draw the playhead as a block whose width is the current beat/downbeat
     * interval being played. In the downbeat zone the block spans from the
     * current downbeat to the next (or the segment end); in the beat zone it
     * spans from the current beat to the next (or the segment end). A triangle
     * marker at the top edge indicates the exact playhead position.
     */
    private void drawPlayhead(Graphics2D g, int w) {
        int si = findSegmentContainingTime(playheadPositionInSeconds);
        if (si < 0) {
            return;
        }
        Segment s = model.getSegments().get(si);
        int x = timeToXInSegment(playheadPositionInSeconds, s, w);
        int yTop = rowTopY(si);
        int yBot = rowBottomY(si);
        int hdrBot = headerBottomY(si);
        int dbBot = downbeatZoneBottomY(si);

        // Invalidate cached intervals when the active segment row changes.
        if (si != cachedIntervalsForSegment) {
            cachedBeatInterval = null;
            cachedDownbeatInterval = null;
            cachedIntervalsForSegment = si;
        }
        // Recompute only when the playhead crosses into a new beat/downbeat slot.
        if (cachedDownbeatInterval == null
                || playheadPositionInSeconds < cachedDownbeatInterval[0]
                || playheadPositionInSeconds >= cachedDownbeatInterval[1]) {
            cachedDownbeatInterval = currentDownbeatInterval(s, playheadPositionInSeconds);
        }
        if (cachedBeatInterval == null
                || playheadPositionInSeconds < cachedBeatInterval[0]
                || playheadPositionInSeconds >= cachedBeatInterval[1]) {
            cachedBeatInterval = currentBeatInterval(s, playheadPositionInSeconds);
        }

        // downbeat interval block
        if (cachedDownbeatInterval != null) {
            int x1 = timeToXInSegment(cachedDownbeatInterval[0], s, w);
            int x2 = timeToXInSegment(cachedDownbeatInterval[1], s, w);
            g.setColor(PLAYHEAD_BLOCK_COLOR);
            g.fillRect(x1, hdrBot, Math.max(1, x2 - x1), dbBot - hdrBot);
        }

        // beat interval block
        if (cachedBeatInterval != null) {
            int x1 = timeToXInSegment(cachedBeatInterval[0], s, w);
            int x2 = timeToXInSegment(cachedBeatInterval[1], s, w);
            g.setColor(PLAYHEAD_BLOCK_COLOR);
            g.fillRect(x1, dbBot, Math.max(1, x2 - x1), yBot - dbBot);
        }

        // triangle marker at the top edge indicating the exact playhead position
        g.setColor(PLAYHEAD_COLOR);
        playheadTriangleX[0] = x - 5;
        playheadTriangleX[1] = x + 5;
        playheadTriangleX[2] = x;
        playheadTriangleY[0] = yTop;
        playheadTriangleY[1] = yTop;
        playheadTriangleY[2] = yTop + 8;
        g.fillPolygon(playheadTriangleX, playheadTriangleY, 3);
    }

    /**
     * Returns {@code [currentBeatTime, nextBeatTime]} for the beat interval
     * containing {@code time}, or null if no beat is at or before {@code time}
     * within the segment. The next boundary is the following beat inside the
     * segment, or the segment end if this is the last beat.
     */
    private double[] currentBeatInterval(Segment s, double time) {
        List<Beat> beats = model.getBeats();
        int current = -1;
        for (int i = 0; i < beats.size(); i++) {
            double t = beats.get(i).getTime();
            if (t >= s.getStart() && t <= s.getEnd() && t <= time) {
                current = i;
            } else if (t > time) {
                break;
            }
        }
        if (current < 0) {
            return null;
        }
        double currentT = beats.get(current).getTime();
        double nextT = s.getEnd();
        for (int i = current + 1; i < beats.size(); i++) {
            double t = beats.get(i).getTime();
            if (t >= s.getStart() && t <= s.getEnd()) {
                nextT = t;
                break;
            }
        }
        return new double[]{currentT, nextT};
    }

    /**
     * Returns {@code [currentDownbeatTime, nextDownbeatTime]} for the downbeat
     * interval containing {@code time}, or null if no downbeat is at or before
     * {@code time} within the segment. The next boundary is the following
     * downbeat inside the segment, or the segment end if this is the last.
     */
    private double[] currentDownbeatInterval(Segment s, double time) {
        List<Double> downbeats = model.getDownbeats();
        int current = -1;
        for (int i = 0; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t >= s.getStart() && t <= s.getEnd() && t <= time) {
                current = i;
            } else if (t > time) {
                break;
            }
        }
        if (current < 0) {
            return null;
        }
        double currentT = downbeats.get(current);
        double nextT = s.getEnd();
        for (int i = current + 1; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t >= s.getStart() && t <= s.getEnd()) {
                nextT = t;
                break;
            }
        }
        return new double[]{currentT, nextT};
    }

    // ---- hit testing -----------------------------------------------------

    /** Segment row whose y-range covers the given y (-1 if below all rows). */
    private int hitSegmentRow(int y) {
        int rowStride = rowHeightPixels + ROW_SPACING_PIXELS;
        int index = y / rowStride;
        if (index < 0 || index >= model.getSegments().size()) {
            return -1;
        }
        // reject clicks that land in the spacing gap between rows
        if (y >= rowBottomY(index)) {
            return -1;
        }
        return index;
    }

    /** Closest beat within x tolerance that lies inside the given segment row. */
    private int hitBeatInSegment(int x, int segIndex, int w) {
        if (segIndex < 0) {
            return -1;
        }
        Segment s = model.getSegments().get(segIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;
        List<Beat> beats = model.getBeats();
        for (int i = 0; i < beats.size(); i++) {
            double t = beats.get(i).getTime();
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int d = Math.abs(timeToXInSegment(t, s, w) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Closest downbeat within x tolerance that lies inside the given segment row. */
    private int hitDownbeatInSegment(int x, int segIndex, int w) {
        if (segIndex < 0) {
            return -1;
        }
        Segment s = model.getSegments().get(segIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;
        List<Double> downbeats = model.getDownbeats();
        for (int i = 0; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int d = Math.abs(timeToXInSegment(t, s, w) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ---- mouse interaction ----------------------------------------------

    private class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            int x = e.getX();
            int y = e.getY();
            int w = getWidth();

            if (SwingUtilities.isRightMouseButton(e)) {
                showContextMenu(e);
                return;
            }
            if (e.getClickCount() == 2) {
                int si = hitSegmentRow(y);
                if (si >= 0) {
                    Segment s = model.getSegments().get(si);
                    selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                    audio.seekSeconds(s.getStart());
                    setPlayheadPositionInSeconds(s.getStart());
                    audio.play();
                    LOG.fine("Timeline: double-click play segment #" + si + " from " + s.getStart() + "s");
                }
                return;
            }

            int si = hitSegmentRow(y);
            if (si < 0) {
                return; // empty space below all rows — nothing to do
            }
            Segment s = model.getSegments().get(si);
            int cw = segmentContentWidth(s, w);

            // edge grabs take priority (left = start, right = end)
            if (x <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_START_EDGE;
                draggedItemIndex = si;
                dragSegmentIndex = si;
                dragInitialSegmentStart = s.getStart();
                dragInitialSegmentEnd = s.getEnd();
                LOG.fine("Timeline: grab segment #" + si + " start edge");
                return;
            }
            if (x >= cw - SEGMENT_EDGE_GRAB_WIDTH_PIXELS && x < cw + SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_END_EDGE;
                draggedItemIndex = si;
                dragSegmentIndex = si;
                dragInitialSegmentStart = s.getStart();
                dragInitialSegmentEnd = s.getEnd();
                LOG.fine("Timeline: grab segment #" + si + " end edge");
                return;
            }

            // clicks in the empty space to the right of a short segment's
            // content area just select the segment without seeking
            if (x > cw) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
                draggedItemIndex = -1;
                dragSegmentIndex = si;
                return;
            }

            int hdrBot = headerBottomY(si);
            int dbBot = downbeatZoneBottomY(si);

            // header zone → select + drag segment body
            if (y < hdrBot) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_BODY;
                draggedItemIndex = si;
                dragSegmentIndex = si;
                dragInitialSegmentStart = s.getStart();
                dragInitialSegmentEnd = s.getEnd();
                double clickTime = xToTimeInSegment(x, s, w);
                segmentDragGrabOffsetInSeconds = clickTime - s.getStart();
                LOG.fine("Timeline: selected segment #" + si + " (body) at " + s.getStart() + "s");
                audio.seekSeconds(s.getStart());
                setPlayheadPositionInSeconds(s.getStart());
                return;
            }

            // downbeat zone
            if (y < dbBot) {
                int di = hitDownbeatInSegment(x, si, w);
                if (di >= 0) {
                    selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, di);
                    activeDragOperation = TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER;
                    draggedItemIndex = di;
                    dragSegmentIndex = si;
                    double t = model.getDownbeats().get(di);
                    LOG.fine("Timeline: selected downbeat #" + di + " at " + t + "s");
                    seekAndUpdatePlayhead(t);
                    return;
                }
            }
            // beat zone
            if (y >= dbBot) {
                int bi = hitBeatInSegment(x, si, w);
                if (bi >= 0) {
                    selection.selectItem(SelectionModel.SelectableItemType.BEAT, bi);
                    activeDragOperation = TimelineDragOperation.DRAGGING_BEAT_MARKER;
                    draggedItemIndex = bi;
                    dragSegmentIndex = si;
                    double t = model.getBeats().get(bi).getTime();
                    LOG.fine("Timeline: selected beat #" + bi + " at " + t + "s");
                    seekAndUpdatePlayhead(t);
                    return;
                }
            }

            // click in a row but not on a marker or edge → seek within segment
            activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
            draggedItemIndex = -1;
            dragSegmentIndex = si;
            double t = xToTimeInSegment(x, s, w);
            LOG.fine("Timeline: seek to " + t + "s (segment #" + si + ")");
            audio.seekSeconds(t);
            setPlayheadPositionInSeconds(t);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            int w = getWidth();
            if (activeDragOperation == TimelineDragOperation.NO_DRAG_ACTIVE) {
                // scrubbing the playhead within the segment where the press happened
                if (dragSegmentIndex >= 0 && dragSegmentIndex < model.getSegments().size()) {
                    Segment s = model.getSegments().get(dragSegmentIndex);
                    double t = xToTimeInSegment(e.getX(), s, w);
                    audio.seekSeconds(t);
                    setPlayheadPositionInSeconds(t);
                }
                return;
            }
            if (draggedItemIndex < 0) {
                return;
            }
            switch (activeDragOperation) {
                case DRAGGING_BEAT_MARKER -> {
                    Segment dragSeg = model.getSegments().get(dragSegmentIndex);
                    double t = xToTimeInSegment(e.getX(), dragSeg, w);
                    model.getBeats().get(draggedItemIndex).setTime(t);
                }
                case DRAGGING_DOWNBEAT_MARKER -> {
                    Segment dragSeg = model.getSegments().get(dragSegmentIndex);
                    double t = xToTimeInSegment(e.getX(), dragSeg, w);
                    model.getDownbeats().set(draggedItemIndex, t);
                }
                case DRAGGING_SEGMENT_START_EDGE -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    double timeAtX = dragInitialSegmentStart
                            + (e.getX() / (double) w) * maxSegmentDuration();
                    s.setStart(Math.min(timeAtX, dragInitialSegmentEnd - 0.05));
                }
                case DRAGGING_SEGMENT_END_EDGE -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    double timeAtX = dragInitialSegmentStart
                            + (e.getX() / (double) w) * maxSegmentDuration();
                    s.setEnd(Math.max(timeAtX, dragInitialSegmentStart + 0.05));
                }
                case DRAGGING_SEGMENT_BODY -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    double initialDuration = dragInitialSegmentEnd - dragInitialSegmentStart;
                    double timeAtX = dragInitialSegmentStart
                            + (e.getX() / (double) w) * maxSegmentDuration();
                    double ns = Math.max(0, timeAtX - segmentDragGrabOffsetInSeconds);
                    s.setStart(ns);
                    s.setEnd(ns + initialDuration);
                }
                default -> {
                }
            }
            model.notifyAllProjectChangeListeners();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (activeDragOperation == TimelineDragOperation.DRAGGING_BEAT_MARKER) {
                LOG.fine("Timeline: finished dragging beat #" + draggedItemIndex);
                model.sortBeats();
                reselect(SelectionModel.SelectableItemType.BEAT, e.getX());
            } else if (activeDragOperation == TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER) {
                LOG.fine("Timeline: finished dragging downbeat #" + draggedItemIndex);
                model.sortDownbeats();
                reselect(SelectionModel.SelectableItemType.DOWNBEAT, e.getX());
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
            int w = getWidth();
            int cursor = Cursor.DEFAULT_CURSOR;
            int si = hitSegmentRow(y);
            if (si >= 0) {
                Segment s = model.getSegments().get(si);
                int cw = segmentContentWidth(s, w);
                if (x <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS
                        || (x >= cw - SEGMENT_EDGE_GRAB_WIDTH_PIXELS && x < cw + SEGMENT_EDGE_GRAB_WIDTH_PIXELS)) {
                    cursor = Cursor.E_RESIZE_CURSOR;
                } else if (x <= cw) {
                    int hdrBot = headerBottomY(si);
                    int dbBot = downbeatZoneBottomY(si);
                    if (y < hdrBot) {
                        cursor = Cursor.MOVE_CURSOR;
                    } else if (y < dbBot) {
                        if (hitDownbeatInSegment(x, si, w) >= 0) {
                            cursor = Cursor.HAND_CURSOR;
                        }
                    } else {
                        if (hitBeatInSegment(x, si, w) >= 0) {
                            cursor = Cursor.HAND_CURSOR;
                        }
                    }
                }
            }
            setCursor(Cursor.getPredefinedCursor(cursor));
        }
    }

    private void reselect(SelectionModel.SelectableItemType itemType, int mx) {
        if (dragSegmentIndex < 0 || dragSegmentIndex >= model.getSegments().size()) {
            return;
        }
        Segment s = model.getSegments().get(dragSegmentIndex);
    }

    private void seekAndUpdatePlayhead(double targetTimeInSeconds) {
        audio.seekSeconds(targetTimeInSeconds);
        setPlayheadPositionInSeconds(targetTimeInSeconds);
    }

    private void showContextMenu(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        int w = getWidth();
        JPopupMenu menu = new JPopupMenu();

        int si = hitSegmentRow(y);
        if (si < 0) {
            JMenuItem add = new JMenuItem("Add segment here");
            add.addActionListener(a -> {
                double start = model.getMaxTime();
                model.addSegment(new Segment(start, start + 10, "verse"));
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, model.getSegments().size() - 1);
                LOG.fine("Timeline: added segment at " + start + "s (context menu)");
            });
            menu.add(add);
            menu.show(this, x, y);
            return;
        }

        Segment s = model.getSegments().get(si);
        int hdrBot = headerBottomY(si);
        int dbBot = downbeatZoneBottomY(si);

        // marker delete actions take priority when right-clicking a marker
        if (y >= hdrBot && y < dbBot) {
            int di = hitDownbeatInSegment(x, si, w);
            if (di >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, di);
                JMenuItem del = new JMenuItem("Delete downbeat");
                del.addActionListener(a -> {
                    LOG.fine("Timeline: delete downbeat #" + di + " (context menu)");
                    model.removeDownbeat(di);
                });
                menu.add(del);
                menu.show(this, x, y);
                return;
            }
        }
        if (y >= dbBot) {
            int bi = hitBeatInSegment(x, si, w);
            if (bi >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.BEAT, bi);
                JMenuItem del = new JMenuItem("Delete beat");
                del.addActionListener(a -> {
                    LOG.fine("Timeline: delete beat #" + bi + " (context menu)");
                    model.removeBeat(bi);
                });
                menu.add(del);
                menu.show(this, x, y);
                return;
            }
        }

        selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
        JMenuItem del = new JMenuItem("Delete segment");
        del.addActionListener(a -> {
            LOG.fine("Timeline: delete segment #" + si + " (context menu)");
            model.removeSegment(si);
        });
        menu.add(del);
        int cw = segmentContentWidth(s, w);
        if (x < cw) {
            double t = xToTimeInSegment(x, s, w);
            menu.addSeparator();
            JMenuItem addBeat = new JMenuItem("Add beat here");
            addBeat.addActionListener(a -> {
                model.addBeat(new Beat(t, 1));
                model.sortBeats();
                LOG.fine("Timeline: add beat at " + t + "s (context menu)");
            });
            JMenuItem addDownbeat = new JMenuItem("Add downbeat here");
            addDownbeat.addActionListener(a -> {
                model.addDownbeat(t);
                model.sortDownbeats();
                LOG.fine("Timeline: add downbeat at " + t + "s (context menu)");
            });
            menu.add(addBeat);
            menu.add(addDownbeat);
        }
        menu.show(this, x, y);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        int w = getWidth();
        int si = hitSegmentRow(y);
        if (si < 0) {
            return null;
        }
        Segment s = model.getSegments().get(si);
        int cw = segmentContentWidth(s, w);
        if (x > cw) {
            return String.format("%s  (empty)", s.getLabel());
        }
        int hdrBot = headerBottomY(si);
        int dbBot = downbeatZoneBottomY(si);
        if (y >= hdrBot && y < dbBot) {
            int di = hitDownbeatInSegment(x, si, w);
            if (di >= 0) {
                return String.format("downbeat %.3f s", model.getDownbeats().get(di));
            }
        }
        if (y >= dbBot) {
            int bi = hitBeatInSegment(x, si, w);
            if (bi >= 0) {
                Beat b = model.getBeats().get(bi);
                return String.format("beat %.3f s  (pos %d)", b.getTime(), b.getPosition());
            }
        }
        return String.format("%s  %.2f–%.2f s", s.getLabel(), s.getStart(), s.getEnd());
    }

    // ---- misc ------------------------------------------------------------

    static Color deriveSegmentLabelColor(String segmentLabel) {
        return segmentColors(segmentLabel)[0];
    }
}
