package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import java.util.List;
import java.util.logging.Logger;

/**
 * Zoomable, marker-only timeline. Renders a time ruler and the segment blocks,
 * and draws each segment's beats and downbeats inside the segment's own area
 * (a label header plus downbeat and beat sub-zones), plus the playhead. All
 * marker editing — seek, select, drag-to-move, drag-to-resize segments,
 * double-click-to-play, right-click-to-delete — happens here and flows through
 * {@link ProjectModel} so the tables and playhead update live.
 */
public class TimelinePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(TimelinePanel.class.getName());

    private static final int TIME_RULER_HEIGHT_PIXELS = 22;
    private static final int SEGMENT_BAND_TOP_Y_PIXELS = 26;
    private static final int SEGMENT_BAND_BOTTOM_Y_PIXELS = 192;
    // Each segment block is a container split into a label header plus two
    // marker zones that render the downbeats and beats falling inside the
    // segment's time range — so a segment shows its own beats/downbeats.
    private static final int SEGMENT_HEADER_BOTTOM_Y_PIXELS = 46;
    private static final int SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS = 46;
    private static final int SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS = 104;
    private static final int SEGMENT_BEAT_ZONE_TOP_Y_PIXELS = 104;
    private static final int SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS = 192;
    private static final int PREFERRED_PANEL_HEIGHT_PIXELS = 202;
    private static final int HIT_TEST_TOLERANCE_PIXELS = 5;        // px tolerance for hit-testing
    private static final int SEGMENT_EDGE_GRAB_WIDTH_PIXELS = 6;       // px for segment edge grab

    // ---- reused paint resources (hoisted to avoid per-paint allocation) ----
    private static final Color PANEL_BACKGROUND = new Color(0x1e1e1e);
    private static final Color RULER_BACKGROUND = new Color(0x2b2b2b);
    private static final Color RULER_BOTTOM_LINE = new Color(0x3c3c3c);
    private static final Color RULER_TICK_COLOR = new Color(0x555555);
    private static final Color RULER_LABEL_COLOR = new Color(0xaaaaaa);
    private static final Color DOWNBEAT_COLOR = new Color(0xff6e6e);
    private static final Color BEAT_ONE_COLOR = new Color(0x6ec1ff);
    private static final Color BEAT_OTHER_COLOR = new Color(0x4a7a99);
    private static final Color BEAT_LABEL_COLOR = new Color(0x99c7e0);
    private static final Color SEGMENT_EDGE_HANDLE_COLOR = new Color(255, 255, 255, 120);
    private static final Color PLAYHEAD_COLOR = new Color(0xffd24a);

    private static final BasicStroke SEGMENT_STROKE_SELECTED = new BasicStroke(2f);
    private static final BasicStroke SEGMENT_STROKE = new BasicStroke(1f);
    private static final BasicStroke DOWNBEAT_STROKE_SELECTED = new BasicStroke(3f);
    private static final BasicStroke DOWNBEAT_STROKE = new BasicStroke(2f);
    private static final BasicStroke BEAT_STROKE_SELECTED = new BasicStroke(2.5f);
    private static final BasicStroke BEAT_STROKE_ONE = new BasicStroke(1.6f);
    private static final BasicStroke BEAT_STROKE = new BasicStroke(1f);
    private static final BasicStroke PLAYHEAD_STROKE = new BasicStroke(1.5f);

    private static final int SEGMENT_FILL_ALPHA_SELECTED = 235;
    private static final int SEGMENT_FILL_ALPHA = 170;

    private final ProjectModel model;
    private final PcmWavPlaybackEngine audio;
    private final SelectionModel selection;

    private double pixelsPerSecond = 40.0;        // pixels per second (zoom)
    private double playheadPositionInSeconds = 0.0;

    // drag state
    private enum TimelineDragOperation {
        NO_DRAG_ACTIVE, DRAGGING_BEAT_MARKER, DRAGGING_DOWNBEAT_MARKER,
        DRAGGING_SEGMENT_BODY, DRAGGING_SEGMENT_START_EDGE, DRAGGING_SEGMENT_END_EDGE
    }

    private TimelineDragOperation activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
    private int draggedItemIndex = -1;
    private double segmentDragGrabOffsetInSeconds = 0; // for segment move: click time - seg.start

    // Derived once on first paint, then reused to avoid per-paint Font allocation.
    private Font rulerFont;
    private Font segmentLabelFont;
    private Font bandLabelFont;
    // Reused playhead triangle scratch (paint is single-threaded on the EDT).
    private final int[] playheadTriangleX = new int[3];
    private final int[] playheadTriangleY = new int[3];

    public TimelinePanel(ProjectModel model, PcmWavPlaybackEngine audio, SelectionModel selection) {
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        setBackground(PANEL_BACKGROUND);
        model.addProjectChangeListener(this);
        selection.addSelectionChangeListener(this);
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        setToolTipText("");
    }

    public void setPixelsPerSecond(double pixelsPerSecond) {
        this.pixelsPerSecond = pixelsPerSecond;
        revalidate();
        repaint();
    }

    public double getPixelsPerSecond() {
        return pixelsPerSecond;
    }

    public void setPlayheadPositionInSeconds(double positionInSeconds) {
        int oldX = convertTimeToPixelX(playheadPositionInSeconds);
        this.playheadPositionInSeconds = positionInSeconds;
        int newX = convertTimeToPixelX(positionInSeconds);
        // Repaint only the strips around the old and new playhead positions.
        int x = Math.min(oldX, newX) - 8;
        int w = Math.abs(newX - oldX) + 16;
        repaint(x, 0, w, getHeight());
    }

    @Override
    public void modelChanged() {
        revalidate();
        repaint();
    }

    @Override
    public void selectionChanged() {
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = (int) ((model.getMaxTime() + 3) * pixelsPerSecond) + 20;
        return new Dimension(Math.max(w, 800), PREFERRED_PANEL_HEIGHT_PIXELS);
    }

    // ---- coordinate helpers ---------------------------------------------

    private int convertTimeToPixelX(double timeInSeconds) {
        return (int) Math.round(timeInSeconds * pixelsPerSecond);
    }

    private double convertPixelXToTime(int pixelX) {
        return Math.max(0, pixelX / pixelsPerSecond);
    }

    // ---- painting --------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        // Shapes are axis-aligned lines/rects that gain nothing from AA and cost
        // EDT time; keep them aliased. Smooth only the text (a separate hint) so
        // labels stay crisp without taxing the per-marker shape loop.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (rulerFont == null) {
            Font base = g.getFont();
            rulerFont = base.deriveFont(10f);
            segmentLabelFont = base.deriveFont(Font.BOLD, 11f);
            bandLabelFont = base.deriveFont(9f);
        }
        Rectangle clip = g.getClipBounds();
        int h = getHeight();
        int xMin = clip.x - 4;
        int xMax = clip.x + clip.width + 4;

        drawRuler(g, clip, h, xMin, xMax);
        drawSegments(g, xMin, xMax);
        drawDraggedOrphanMarker(g, xMin, xMax);
        drawPlayhead(g, h);
    }

    private void drawRuler(Graphics2D g, Rectangle clip, int h, int xMin, int xMax) {
        g.setColor(RULER_BACKGROUND);
        g.fillRect(clip.x, 0, clip.width, TIME_RULER_HEIGHT_PIXELS);
        g.setColor(RULER_BOTTOM_LINE);
        g.drawLine(clip.x, TIME_RULER_HEIGHT_PIXELS, clip.x + clip.width, TIME_RULER_HEIGHT_PIXELS);

        // adaptive seconds-per-label so labels never crowd
        double minLabelPx = 60;
        double step = Math.max(1, Math.ceil(minLabelPx / pixelsPerSecond));
        g.setFont(rulerFont);
        double maxT = model.getMaxTime() + 3;
        double tStart = Math.max(0, Math.floor(convertPixelXToTime(xMin) / step) * step);
        for (double t = tStart; t <= maxT; t += step) {
            int x = convertTimeToPixelX(t);
            if (x > xMax) {
                break;
            }
            g.setColor(RULER_TICK_COLOR);
            g.drawLine(x, TIME_RULER_HEIGHT_PIXELS - 6, x, TIME_RULER_HEIGHT_PIXELS);
            g.setColor(RULER_LABEL_COLOR);
            g.drawString(formatRulerTickLabel(t), x + 2, 12);
        }
    }

    private void drawSegments(Graphics2D g, int xMin, int xMax) {
        g.setFont(segmentLabelFont);
        int blockHeight = SEGMENT_BAND_BOTTOM_Y_PIXELS - SEGMENT_BAND_TOP_Y_PIXELS;
        int headerHeight = SEGMENT_HEADER_BOTTOM_Y_PIXELS - SEGMENT_BAND_TOP_Y_PIXELS;
        int bodyHeight = SEGMENT_BAND_BOTTOM_Y_PIXELS - SEGMENT_HEADER_BOTTOM_Y_PIXELS;
        List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            int x1 = convertTimeToPixelX(s.getStart());
            int x2 = convertTimeToPixelX(s.getEnd());
            if (x2 < xMin || x1 > xMax) {
                continue;
            }
            int w = Math.max(1, x2 - x1);
            Color base = deriveSegmentLabelColor(s.getLabel());
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, i);
            // Header strip carries the label at full opacity; the marker body
            // below it is only lightly tinted so the beat/downbeat lines read.
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                    sel ? SEGMENT_FILL_ALPHA_SELECTED : SEGMENT_FILL_ALPHA));
            g.fillRect(x1, SEGMENT_BAND_TOP_Y_PIXELS, w, headerHeight);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                    sel ? 95 : 60));
            g.fillRect(x1, SEGMENT_HEADER_BOTTOM_Y_PIXELS, w, bodyHeight);
            g.setColor(sel ? Color.WHITE : base.darker());
            g.setStroke(sel ? SEGMENT_STROKE_SELECTED : SEGMENT_STROKE);
            g.drawRect(x1, SEGMENT_BAND_TOP_Y_PIXELS, w, blockHeight);
            g.setColor(base.darker().darker());
            g.drawLine(x1, SEGMENT_HEADER_BOTTOM_Y_PIXELS, x2, SEGMENT_HEADER_BOTTOM_Y_PIXELS);
            // edge handles
            g.setColor(SEGMENT_EDGE_HANDLE_COLOR);
            g.fillRect(x1, SEGMENT_BAND_TOP_Y_PIXELS, 2, blockHeight);
            g.fillRect(x2 - 2, SEGMENT_BAND_TOP_Y_PIXELS, 2, blockHeight);
            // label
            g.setColor(Color.WHITE);
            if (w > 24) {
                g.drawString(s.getLabel(), x1 + 4, SEGMENT_BAND_TOP_Y_PIXELS + 16);
            }
            // markers that fall inside this segment's time range
            drawDownbeatsInSegment(g, s, xMin, xMax);
            drawBeatsInSegment(g, s, xMin, xMax);
        }
    }

    private void drawDownbeatsInSegment(Graphics2D g, Segment s, int xMin, int xMax) {
        List<Double> downbeats = model.getDownbeats();
        for (int i = 0; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int x = convertTimeToPixelX(t);
            if (x < xMin || x > xMax) {
                continue;
            }
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.DOWNBEAT, i);
            g.setColor(sel ? Color.WHITE : DOWNBEAT_COLOR);
            g.setStroke(sel ? DOWNBEAT_STROKE_SELECTED : DOWNBEAT_STROKE);
            g.drawLine(x, SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS, x, SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS);
        }
    }

    private void drawBeatsInSegment(Graphics2D g, Segment s, int xMin, int xMax) {
        boolean drawLabels = pixelsPerSecond >= 14; // only show bar positions when readable
        if (drawLabels) {
            g.setFont(bandLabelFont);
        }
        List<Beat> beats = model.getBeats();
        for (int i = 0; i < beats.size(); i++) {
            Beat b = beats.get(i);
            double t = b.getTime();
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int x = convertTimeToPixelX(t);
            if (x < xMin || x > xMax) {
                continue;
            }
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.BEAT, i);
            boolean one = b.getPosition() == 1;
            if (sel) {
                g.setColor(Color.WHITE);
                g.setStroke(BEAT_STROKE_SELECTED);
            } else {
                g.setColor(one ? BEAT_ONE_COLOR : BEAT_OTHER_COLOR);
                g.setStroke(one ? BEAT_STROKE_ONE : BEAT_STROKE);
            }
            g.drawLine(x, SEGMENT_BEAT_ZONE_TOP_Y_PIXELS, x, SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS);
            if (drawLabels) {
                g.setColor(sel ? Color.WHITE : BEAT_LABEL_COLOR);
                g.drawString(Integer.toString(b.getPosition()), x + 1, SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS - 4);
            }
        }
    }

    /**
     * While a beat/downbeat is being dragged it may leave every segment's time
     * range; draw it at its current position anyway so it doesn't vanish
     * mid-drag (it snaps back into a segment on release / sort).
     */
    private void drawDraggedOrphanMarker(Graphics2D g, int xMin, int xMax) {
        if (activeDragOperation == TimelineDragOperation.DRAGGING_BEAT_MARKER && draggedItemIndex >= 0) {
            Beat b = model.getBeats().get(draggedItemIndex);
            if (!isTimeWithinAnySegment(b.getTime())) {
                int x = convertTimeToPixelX(b.getTime());
                if (x >= xMin && x <= xMax) {
                    g.setColor(Color.WHITE);
                    g.setStroke(BEAT_STROKE_SELECTED);
                    g.drawLine(x, SEGMENT_BEAT_ZONE_TOP_Y_PIXELS, x, SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS);
                }
            }
        } else if (activeDragOperation == TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER && draggedItemIndex >= 0) {
            double t = model.getDownbeats().get(draggedItemIndex);
            if (!isTimeWithinAnySegment(t)) {
                int x = convertTimeToPixelX(t);
                if (x >= xMin && x <= xMax) {
                    g.setColor(Color.WHITE);
                    g.setStroke(DOWNBEAT_STROKE_SELECTED);
                    g.drawLine(x, SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS, x, SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS);
                }
            }
        }
    }

    private boolean isTimeWithinAnySegment(double timeInSeconds) {
        for (Segment s : model.getSegments()) {
            if (timeInSeconds >= s.getStart() && timeInSeconds <= s.getEnd()) {
                return true;
            }
        }
        return false;
    }

    private void drawPlayhead(Graphics2D g, int h) {
        int x = convertTimeToPixelX(playheadPositionInSeconds);
        g.setColor(PLAYHEAD_COLOR);
        g.setStroke(PLAYHEAD_STROKE);
        g.drawLine(x, 0, x, h);
        playheadTriangleX[0] = x - 5;
        playheadTriangleX[1] = x + 5;
        playheadTriangleX[2] = x;
        playheadTriangleY[0] = 0;
        playheadTriangleY[1] = 0;
        playheadTriangleY[2] = 8;
        g.fillPolygon(playheadTriangleX, playheadTriangleY, 3);
    }

    // ---- hit testing -----------------------------------------------------

    private int hitSegment(int mx) {
        for (int i = model.getSegments().size() - 1; i >= 0; i--) {
            Segment s = model.getSegments().get(i);
            if (mx >= convertTimeToPixelX(s.getStart()) - SEGMENT_EDGE_GRAB_WIDTH_PIXELS
                    && mx <= convertTimeToPixelX(s.getEnd()) + SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the segment whose [start,end] strictly contains the time at x (-1 if none). */
    private int hitSegmentContaining(int mx) {
        double t = convertPixelXToTime(mx);
        for (int i = model.getSegments().size() - 1; i >= 0; i--) {
            Segment s = model.getSegments().get(i);
            if (t >= s.getStart() && t <= s.getEnd()) {
                return i;
            }
        }
        return -1;
    }

    /** Closest beat within tolerance of mx that also lies inside the given segment. */
    private int hitBeatInSegment(int mx, int segmentIndex) {
        if (segmentIndex < 0) {
            return -1;
        }
        Segment s = model.getSegments().get(segmentIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;
        List<Beat> beats = model.getBeats();
        for (int i = 0; i < beats.size(); i++) {
            double t = beats.get(i).getTime();
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int d = Math.abs(convertTimeToPixelX(t) - mx);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Closest downbeat within tolerance of mx that also lies inside the given segment. */
    private int hitDownbeatInSegment(int mx, int segmentIndex) {
        if (segmentIndex < 0) {
            return -1;
        }
        Segment s = model.getSegments().get(segmentIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;
        List<Double> downbeats = model.getDownbeats();
        for (int i = 0; i < downbeats.size(); i++) {
            double t = downbeats.get(i);
            if (t < s.getStart() || t > s.getEnd()) {
                continue;
            }
            int d = Math.abs(convertTimeToPixelX(t) - mx);
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

            if (SwingUtilities.isRightMouseButton(e)) {
                showContextMenu(e);
                return;
            }
            if (e.getClickCount() == 2) {
                if (y >= SEGMENT_BAND_TOP_Y_PIXELS && y <= SEGMENT_BAND_BOTTOM_Y_PIXELS) {
                    int si = hitSegment(x);
                    if (si >= 0) {
                        Segment s = model.getSegments().get(si);
                        selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                        audio.seekSeconds(s.getStart());
                        setPlayheadPositionInSeconds(s.getStart());
                        audio.play();
                        LOG.fine("Timeline: double-click play segment #" + si + " from " + s.getStart() + "s");
                    }
                }
                return;
            }

            // Everything interactive now lives inside the segment band: each
            // segment block contains its own downbeats and beats, so a click in
            // the band first tries a marker (by sub-zone), then the segment
            // body/edge, then falls back to seek.
            if (y >= SEGMENT_BAND_TOP_Y_PIXELS && y <= SEGMENT_BAND_BOTTOM_Y_PIXELS) {
                int containingSegment = hitSegmentContaining(x);
                if (y >= SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS && y < SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS) {
                    int di = hitDownbeatInSegment(x, containingSegment);
                    if (di >= 0) {
                        selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, di);
                        activeDragOperation = TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER;
                        draggedItemIndex = di;
                        double t = model.getDownbeats().get(di);
                        LOG.fine("Timeline: selected downbeat #" + di + " at " + t + "s");
                        seekAndUpdatePlayhead(t);
                        return;
                    }
                }
                if (y >= SEGMENT_BEAT_ZONE_TOP_Y_PIXELS && y <= SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS) {
                    int bi = hitBeatInSegment(x, containingSegment);
                    if (bi >= 0) {
                        selection.selectItem(SelectionModel.SelectableItemType.BEAT, bi);
                        activeDragOperation = TimelineDragOperation.DRAGGING_BEAT_MARKER;
                        draggedItemIndex = bi;
                        double t = model.getBeats().get(bi).getTime();
                        LOG.fine("Timeline: selected beat #" + bi + " at " + t + "s");
                        seekAndUpdatePlayhead(t);
                        return;
                    }
                }
                int si = hitSegment(x);
                if (si >= 0) {
                    Segment s = model.getSegments().get(si);
                    selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                    int xs = convertTimeToPixelX(s.getStart());
                    int xe = convertTimeToPixelX(s.getEnd());
                    if (Math.abs(x - xs) <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                        activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_START_EDGE;
                    } else if (Math.abs(x - xe) <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                        activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_END_EDGE;
                    } else {
                        activeDragOperation = TimelineDragOperation.DRAGGING_SEGMENT_BODY;
                        segmentDragGrabOffsetInSeconds = convertPixelXToTime(x) - s.getStart();
                    }
                    draggedItemIndex = si;
                    LOG.fine("Timeline: selected segment #" + si + " (" + activeDragOperation + ") at " + s.getStart() + "s");
                    audio.seekSeconds(s.getStart());
                    setPlayheadPositionInSeconds(s.getStart());
                    return;
                }
            }

            // empty space anywhere -> seek
            activeDragOperation = TimelineDragOperation.NO_DRAG_ACTIVE;
            double t = convertPixelXToTime(x);
            LOG.fine("Timeline: seek to " + t + "s");
            audio.seekSeconds(t);
            setPlayheadPositionInSeconds(t);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (activeDragOperation == TimelineDragOperation.NO_DRAG_ACTIVE || draggedItemIndex < 0) {
                // scrubbing the playhead over empty space
                double t = convertPixelXToTime(e.getX());
                audio.seekSeconds(t);
                setPlayheadPositionInSeconds(t);
                return;
            }
            double t = convertPixelXToTime(e.getX());
            switch (activeDragOperation) {
                case DRAGGING_BEAT_MARKER -> model.getBeats().get(draggedItemIndex).setTime(t);
                case DRAGGING_DOWNBEAT_MARKER -> model.getDownbeats().set(draggedItemIndex, t);
                case DRAGGING_SEGMENT_START_EDGE -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    s.setStart(Math.min(t, s.getEnd() - 0.05));
                }
                case DRAGGING_SEGMENT_END_EDGE -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    s.setEnd(Math.max(t, s.getStart() + 0.05));
                }
                case DRAGGING_SEGMENT_BODY -> {
                    Segment s = model.getSegments().get(draggedItemIndex);
                    double dur = s.getDuration();
                    double ns = Math.max(0, t - segmentDragGrabOffsetInSeconds);
                    s.setStart(ns);
                    s.setEnd(ns + dur);
                }
                default -> {
                }
            }
            model.notifyAllProjectChangeListeners();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            // keep beat/downbeat lists time-ordered after a move
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
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();
            int cursor = Cursor.DEFAULT_CURSOR;
            if (y >= SEGMENT_BAND_TOP_Y_PIXELS && y <= SEGMENT_BAND_BOTTOM_Y_PIXELS) {
                int si = hitSegment(x);
                if (si >= 0) {
                    Segment s = model.getSegments().get(si);
                    if (Math.abs(x - convertTimeToPixelX(s.getStart())) <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS
                            || Math.abs(x - convertTimeToPixelX(s.getEnd())) <= SEGMENT_EDGE_GRAB_WIDTH_PIXELS) {
                        cursor = Cursor.E_RESIZE_CURSOR;
                    }
                }
                if (cursor == Cursor.DEFAULT_CURSOR) {
                    int containingSegment = hitSegmentContaining(x);
                    if (y >= SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS && y < SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS
                            && hitDownbeatInSegment(x, containingSegment) >= 0) {
                        cursor = Cursor.HAND_CURSOR;
                    } else if (y >= SEGMENT_BEAT_ZONE_TOP_Y_PIXELS && y <= SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS
                            && hitBeatInSegment(x, containingSegment) >= 0) {
                        cursor = Cursor.HAND_CURSOR;
                    } else if (si >= 0) {
                        cursor = Cursor.MOVE_CURSOR;
                    }
                }
            }
            setCursor(Cursor.getPredefinedCursor(cursor));
        }
    }

    private void reselect(SelectionModel.SelectableItemType itemType, int mx) {
        int containingSegment = hitSegmentContaining(mx);
        if (itemType == SelectionModel.SelectableItemType.BEAT) {
            selection.selectItem(itemType, hitBeatInSegment(mx, containingSegment));
        } else if (itemType == SelectionModel.SelectableItemType.DOWNBEAT) {
            selection.selectItem(itemType, hitDownbeatInSegment(mx, containingSegment));
        }
    }

    private void seekAndUpdatePlayhead(double targetTimeInSeconds) {
        audio.seekSeconds(targetTimeInSeconds);
        setPlayheadPositionInSeconds(targetTimeInSeconds);
    }

    private void addAtDoubleClick(int x, int y) {
        double t = convertPixelXToTime(x);
        double end = Math.min(model.getMaxTime() + 5, t + 5);
        Segment s = new Segment(t, Math.max(end, t + 1), "verse");
        model.addSegment(s);
        selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, model.getSegments().size() - 1);
        LOG.fine("Timeline: added segment at " + t + "s (double-click)");
    }

    private void showContextMenu(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        JPopupMenu menu = new JPopupMenu();

        if (y >= SEGMENT_BAND_TOP_Y_PIXELS && y <= SEGMENT_BAND_BOTTOM_Y_PIXELS) {
            int containingSegment = hitSegmentContaining(x);
            // marker delete actions take priority when right-clicking a marker
            if (y >= SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS && y < SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS) {
                int di = hitDownbeatInSegment(x, containingSegment);
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
            if (y >= SEGMENT_BEAT_ZONE_TOP_Y_PIXELS && y <= SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS) {
                int bi = hitBeatInSegment(x, containingSegment);
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
            int si = hitSegment(x);
            if (si >= 0) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, si);
                JMenuItem del = new JMenuItem("Delete segment");
                del.addActionListener(a -> {
                    LOG.fine("Timeline: delete segment #" + si + " (context menu)");
                    model.removeSegment(si);
                });
                menu.add(del);
                if (containingSegment >= 0) {
                    double t = convertPixelXToTime(x);
                    menu.addSeparator();
                    JMenuItem addBeat = new JMenuItem("Add beat here");
                    addBeat.addActionListener(a -> {
                        model.addBeat(new Beat(t, 1));
                        model.sortBeats();
                        selection.selectItem(SelectionModel.SelectableItemType.BEAT,
                                hitBeatInSegment(x, hitSegmentContaining(x)));
                        LOG.fine("Timeline: add beat at " + t + "s (context menu)");
                    });
                    JMenuItem addDownbeat = new JMenuItem("Add downbeat here");
                    addDownbeat.addActionListener(a -> {
                        model.addDownbeat(t);
                        model.sortDownbeats();
                        selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT,
                                hitDownbeatInSegment(x, hitSegmentContaining(x)));
                        LOG.fine("Timeline: add downbeat at " + t + "s (context menu)");
                    });
                    menu.add(addBeat);
                    menu.add(addDownbeat);
                }
            } else {
                JMenuItem add = new JMenuItem("Add segment here");
                add.addActionListener(a -> addAtDoubleClick(x, y));
                menu.add(add);
            }
        }
        menu.show(this, x, y);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        if (y >= SEGMENT_BAND_TOP_Y_PIXELS && y <= SEGMENT_BAND_BOTTOM_Y_PIXELS) {
            int containingSegment = hitSegmentContaining(x);
            if (y >= SEGMENT_DOWNBEAT_ZONE_TOP_Y_PIXELS && y < SEGMENT_DOWNBEAT_ZONE_BOTTOM_Y_PIXELS) {
                int di = hitDownbeatInSegment(x, containingSegment);
                if (di >= 0) {
                    return String.format("downbeat %.3f s", model.getDownbeats().get(di));
                }
            }
            if (y >= SEGMENT_BEAT_ZONE_TOP_Y_PIXELS && y <= SEGMENT_BEAT_ZONE_BOTTOM_Y_PIXELS) {
                int bi = hitBeatInSegment(x, containingSegment);
                if (bi >= 0) {
                    Beat b = model.getBeats().get(bi);
                    return String.format("beat %.3f s  (pos %d)", b.getTime(), b.getPosition());
                }
            }
            int si = hitSegment(x);
            if (si >= 0) {
                Segment s = model.getSegments().get(si);
                return String.format("%s  %.2f–%.2f s", s.getLabel(), s.getStart(), s.getEnd());
            }
        }
        return String.format("%.2f s", convertPixelXToTime(x));
    }

    // ---- misc ------------------------------------------------------------

    static Color deriveSegmentLabelColor(String segmentLabel) {
        if (segmentLabel == null) {
            segmentLabel = "";
        }
        int hue = Math.floorMod(segmentLabel.toLowerCase().hashCode(), 360);
        return Color.getHSBColor(hue / 360f, 0.55f, 0.75f);
    }

    private static String formatRulerTickLabel(double positionInSeconds) {
        int total = (int) Math.floor(positionInSeconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }
}
