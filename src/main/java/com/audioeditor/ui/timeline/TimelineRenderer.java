package com.audioeditor.ui.timeline;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.port.audio.AudioPlayer;
import com.audioeditor.ui.SelectionModel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paint delegate for the timeline canvas. Owns all color resources, font
 * caches, and the beat/downbeat interval cache used to minimize repaint regions.
 */
public final class TimelineRenderer {

    // ---- paint resources ---------------------------------------------------
    public static final Color PANEL_BACKGROUND = new Color(0x1e1e1e);
    private static final Color DOWNBEAT_COLOR = new Color(0xff6e6e);
    private static final Color BEAT_ONE_COLOR = new Color(0x6ec1ff);
    private static final Color BEAT_OTHER_COLOR = new Color(0x4a7a99);
    private static final Color BEAT_LABEL_COLOR = new Color(0x99c7e0);
    private static final Color ZONE_SEPARATOR_COLOR = new Color(0, 0, 0, 70);
    private static final Color SEGMENT_EDGE_HANDLE_COLOR = new Color(255, 255, 255, 120);
    static final Color PLAYHEAD_COLOR = new Color(0xffd24a);
    private static final Color PLAYHEAD_BLOCK_COLOR = new Color(
            0xffd24a >> 16 & 0xff, 0xffd24a >> 8 & 0xff, 0xffd24a & 0xff, 60);

    private static final BasicStroke SEGMENT_STROKE_SELECTED = new BasicStroke(2f);
    private static final BasicStroke SEGMENT_STROKE = new BasicStroke(1f);
    private static final BasicStroke DOWNBEAT_STROKE_SELECTED = new BasicStroke(3f);
    private static final BasicStroke DOWNBEAT_STROKE = new BasicStroke(2f);
    private static final BasicStroke BEAT_STROKE_SELECTED = new BasicStroke(2.5f);
    private static final BasicStroke BEAT_STROKE_ONE = new BasicStroke(1.6f);
    private static final BasicStroke BEAT_STROKE = new BasicStroke(1f);

    private static final int SEGMENT_FILL_ALPHA_SELECTED = 235;
    private static final int SEGMENT_FILL_ALPHA = 170;

    // [base, headerNormal, headerSelected, bodyNormal, bodySelected, border, separator]
    private static final Map<String, Color[]> SEGMENT_COLORS = new HashMap<>();

    private static final String[] BAR_POSITION_LABELS = new String[33];
    static {
        for (int i = 0; i < BAR_POSITION_LABELS.length; i++) BAR_POSITION_LABELS[i] = Integer.toString(i);
    }

    // ---- collaborators -----------------------------------------------------
    private final ProjectModel model;
    private final SelectionModel selection;
    private final TimelineGeometry geometry;
    private final AudioPlayer audio;
    private final TimelineSelectionLookup selectionLookup;

    // ---- lazy font cache ---------------------------------------------------
    private Font segmentLabelFont;
    private Font beatLabelFont;

    // ---- playhead triangle scratch (avoids per-frame array allocation) -----
    private final int[] triangleX = new int[3];
    private final int[] triangleY = new int[3];

    // ---- beat/downbeat interval cache (invalidated on model change) --------
    private double[] cachedBeatInterval = null;
    private double[] cachedDownbeatInterval = null;
    private int cachedIntervalsForSegment = -1;

    public TimelineRenderer(ProjectModel model, SelectionModel selection,
                     TimelineGeometry geometry, AudioPlayer audio,
                     TimelineSelectionLookup selectionLookup) {
        this.model = model;
        this.selection = selection;
        this.geometry = geometry;
        this.audio = audio;
        this.selectionLookup = selectionLookup;
    }

    // ---- main paint entry point --------------------------------------------

    public void paint(Graphics2D g, int panelWidth, Rectangle clip, double playheadSeconds, double maxDuration,
               TimelineDragOperation dragOp, int dragItemIndex, int dragSegmentIndex) {
        if (segmentLabelFont == null) {
            Font base = g.getFont();
            segmentLabelFont = base.deriveFont(Font.BOLD, 15.4f);
            beatLabelFont = base.deriveFont(12.6f);
        }
        int yMin = clip.y - 4;
        int yMax = clip.y + clip.height + 4;
        drawSegments(g, panelWidth, yMin, yMax, maxDuration, playheadSeconds);
        drawDraggedOrphanMarker(g, panelWidth, yMin, yMax, maxDuration, dragOp, dragItemIndex, dragSegmentIndex);
        drawPlayhead(g, panelWidth, playheadSeconds, maxDuration);
    }

    /**
     * Computes {@code out[]{segmentRow, xMin, xMax}} for the playhead bounds at {@code time}.
     * Sets {@code out[0] = -1} when no segment contains the time.
     */
    public void computePlayheadBounds(double time, int[] out, double maxDuration, int panelWidth) {
        int si = findSegmentContainingTime(time);
        if (si < 0) { out[0] = -1; return; }
        Segment s = model.getSegments().get(si);
        int x = geometry.timeToX(time, s, panelWidth, maxDuration);
        int xMin = x - 6;
        int xMax = x + 6;
        double[] db = computeDownbeatInterval(s, time);
        if (db != null) {
            xMin = Math.min(xMin, geometry.timeToX(db[0], s, panelWidth, maxDuration));
            xMax = Math.max(xMax, geometry.timeToX(db[1], s, panelWidth, maxDuration));
        }
        double[] be = computeBeatInterval(s, time);
        if (be != null) {
            xMin = Math.min(xMin, geometry.timeToX(be[0], s, panelWidth, maxDuration));
            xMax = Math.max(xMax, geometry.timeToX(be[1], s, panelWidth, maxDuration));
        }
        out[0] = si;
        out[1] = xMin;
        out[2] = xMax;
    }

    /** Clears the cached beat/downbeat intervals — call from {@code modelChanged()}. */
    public void invalidateIntervalCache() {
        cachedBeatInterval = null;
        cachedDownbeatInterval = null;
        cachedIntervalsForSegment = -1;
    }

    /** Base color for a segment label — used by other timeline classes for legend rendering. */
    public static Color segmentLabelColor(String label) {
        return segmentColors(label)[0];
    }

    // ---- segment drawing ---------------------------------------------------

    private void drawSegments(Graphics2D g, int w, int yMin, int yMax,
                               double maxDuration, double playheadSeconds) {
        g.setFont(segmentLabelFont);
        int dbZoneH = geometry.downbeatZoneHeight();
        List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            int yTop = geometry.rowTop(i);
            int yBot = geometry.rowBottom(i);
            if (yBot < yMin || yTop > yMax) continue;
            int cw = geometry.contentWidth(s, w, maxDuration);
            int hdrH = TimelineGeometry.HEADER_HEIGHT_PIXELS;
            int hdrBot = yTop + hdrH;
            int dbBot = hdrBot + dbZoneH;
            int bodyH = geometry.rowHeight() - hdrH;
            Color[] colors = segmentColors(s.getLabel());
            boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.SEGMENT, i);
            g.setColor(sel ? colors[2] : colors[1]);
            g.fillRect(0, yTop, cw, hdrH);
            g.setColor(sel ? colors[4] : colors[3]);
            g.fillRect(0, hdrBot, cw, bodyH);
            g.setColor(sel ? Color.WHITE : colors[5]);
            g.setStroke(sel ? SEGMENT_STROKE_SELECTED : SEGMENT_STROKE);
            g.drawRect(0, yTop, cw - 1, geometry.rowHeight() - 1);
            g.setColor(colors[6]);
            g.drawLine(0, hdrBot, cw, hdrBot);
            g.setColor(ZONE_SEPARATOR_COLOR);
            g.drawLine(0, dbBot, cw, dbBot);
            g.setColor(SEGMENT_EDGE_HANDLE_COLOR);
            g.fillRect(0, yTop, 2, geometry.rowHeight());
            g.fillRect(cw - 2, yTop, 2, geometry.rowHeight());
            g.setFont(segmentLabelFont);
            g.setColor(Color.WHITE);
            String headerText = cw > 150
                    ? String.format("%s (%.1fs) - %s", s.getLabel(), s.getDuration(),
                                    buildSegmentCounter(s, playheadSeconds))
                    : s.getLabel();
            headerText = fitTextToWidth(g, headerText, Math.max(0, cw - 12));
            g.drawString(headerText, 6, yTop + 15);
            drawDownbeatsInSegment(g, s, w, hdrBot, dbBot, maxDuration);
            drawBeatsInSegment(g, s, w, dbBot, yBot, maxDuration);
        }
    }

    private void drawDownbeatsInSegment(Graphics2D g, Segment s, int w,
                                        int zoneTop, int zoneBottom, double maxDuration) {
        if (zoneBottom <= zoneTop) return;
        int flatBarIndex = selectionLookup.firstBarIndex(s);
        for (Bar bar : s.getBars()) {
            if (!bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
                double t = bar.getBeats().get(0).getStart();
                int x = geometry.timeToX(t, s, w, maxDuration);
                boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.BAR, flatBarIndex);
                g.setColor(sel ? Color.WHITE : DOWNBEAT_COLOR);
                g.setStroke(sel ? DOWNBEAT_STROKE_SELECTED : DOWNBEAT_STROKE);
                g.drawLine(x, zoneTop, x, zoneBottom);
            }
            flatBarIndex++;
        }
    }

    private void drawBeatsInSegment(Graphics2D g, Segment s, int w,
                                    int zoneTop, int zoneBottom, double maxDuration) {
        if (zoneBottom <= zoneTop) return;
        int flatBeatIndex = 0;
        for (Segment seg : model.getSegments()) {
            if (seg == s) break;
            for (Bar bar : seg.getBars()) flatBeatIndex += bar.getBeats().size();
        }
        for (Bar bar : s.getBars()) {
            for (Beat b : bar.getBeats()) {
                int x = geometry.timeToX(b.getStart(), s, w, maxDuration);
                boolean sel = selection.isItemSelected(SelectionModel.SelectableItemType.BEAT, flatBeatIndex);
                boolean downbeat = b.isDownbeat();
                if (sel) {
                    g.setColor(Color.WHITE);
                    g.setStroke(BEAT_STROKE_SELECTED);
                } else {
                    g.setColor(downbeat ? BEAT_ONE_COLOR : BEAT_OTHER_COLOR);
                    g.setStroke(downbeat ? BEAT_STROKE_ONE : BEAT_STROKE);
                }
                g.drawLine(x, zoneTop, x, zoneBottom);
                if (zoneBottom - zoneTop > 18) {
                    g.setFont(beatLabelFont);
                    g.setColor(sel ? Color.WHITE : BEAT_LABEL_COLOR);
                    g.drawString(downbeat ? "1" : "•", x + 2, zoneBottom - 3);
                }
                flatBeatIndex++;
            }
        }
    }

    private String buildSegmentCounter(Segment s, double playheadSeconds) {
        int totalBars = s.getBars().size();
        int totalBeats = s.getBars().stream().mapToInt(bar -> bar.getBeats().size()).sum();
        boolean active = audio.isPlaying()
                && playheadSeconds >= s.getStart() && playheadSeconds <= s.getEnd();
        int playingBar = 0;
        int playingBeat = 0;
        if (active) {
            for (var bar : s.getBars()) {
                if (bar.getStartTime() <= playheadSeconds) {
                    playingBar++;
                    for (Beat b : bar.getBeats()) {
                        if (b.getStart() <= playheadSeconds) playingBeat++;
                    }
                }
            }
        }
        return String.format("%02d / %02d - %02d / %02d", playingBar, totalBars, playingBeat, totalBeats);
    }

    private static String fitTextToWidth(Graphics2D g, String text, int maxWidthPixels) {
        if (text == null || text.isEmpty() || maxWidthPixels <= 0) return "";
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidthPixels) return text;
        String suffix = "...";
        int suffixWidth = metrics.stringWidth(suffix);
        if (suffixWidth > maxWidthPixels) return "";
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (metrics.stringWidth(text.substring(0, mid)) + suffixWidth <= maxWidthPixels) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low) + suffix;
    }

    // ---- drag orphan marker ------------------------------------------------

    private void drawDraggedOrphanMarker(Graphics2D g, int w, int yMin, int yMax, double maxDuration,
                                          TimelineDragOperation dragOp, int dragItemIndex, int dragSegmentIndex) {
        if (dragSegmentIndex < 0 || dragItemIndex < 0) return;
        int yTop = geometry.rowTop(dragSegmentIndex);
        if (yTop + geometry.rowHeight() < yMin || yTop > yMax) return;
        Segment dragSeg = model.getSegments().get(dragSegmentIndex);
        int cw = geometry.contentWidth(dragSeg, w, maxDuration);
        if (dragOp == TimelineDragOperation.DRAGGING_BEAT_MARKER) {
            Beat b = selectionLookup.beatAt(dragItemIndex);
            if (b != null && !isTimeWithinAnySegment(b.getStart())) {
                int x = Math.max(0, Math.min(cw, geometry.timeToX(b.getStart(), dragSeg, w, maxDuration)));
                int zt = geometry.downbeatZoneBottom(dragSegmentIndex);
                int zb = geometry.rowBottom(dragSegmentIndex);
                g.setColor(Color.WHITE);
                g.setStroke(BEAT_STROKE_SELECTED);
                g.drawLine(x, zt, x, zb);
            }
        } else if (dragOp == TimelineDragOperation.DRAGGING_DOWNBEAT_MARKER) {
            Bar bar = selectionLookup.barAt(dragItemIndex);
            if (bar != null && !bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
                double t = bar.getBeats().get(0).getStart();
                if (!isTimeWithinAnySegment(t)) {
                    int x = Math.max(0, Math.min(cw, geometry.timeToX(t, dragSeg, w, maxDuration)));
                    int zt = geometry.headerBottom(dragSegmentIndex);
                    int zb = geometry.downbeatZoneBottom(dragSegmentIndex);
                    g.setColor(Color.WHITE);
                    g.setStroke(DOWNBEAT_STROKE_SELECTED);
                    g.drawLine(x, zt, x, zb);
                }
            }
        }
    }

    // ---- playhead drawing --------------------------------------------------

    private void drawPlayhead(Graphics2D g, int w, double playheadSeconds, double maxDuration) {
        int si = findSegmentContainingTime(playheadSeconds);
        if (si < 0) return;
        Segment s = model.getSegments().get(si);
        int x = geometry.timeToX(playheadSeconds, s, w, maxDuration);
        int yTop = geometry.rowTop(si);
        int yBot = geometry.rowBottom(si);
        int hdrBot = geometry.headerBottom(si);
        int dbBot = geometry.downbeatZoneBottom(si);

        if (si != cachedIntervalsForSegment) {
            cachedBeatInterval = null;
            cachedDownbeatInterval = null;
            cachedIntervalsForSegment = si;
        }
        if (cachedDownbeatInterval == null
                || playheadSeconds < cachedDownbeatInterval[0]
                || playheadSeconds >= cachedDownbeatInterval[1]) {
            cachedDownbeatInterval = computeDownbeatInterval(s, playheadSeconds);
        }
        if (cachedBeatInterval == null
                || playheadSeconds < cachedBeatInterval[0]
                || playheadSeconds >= cachedBeatInterval[1]) {
            cachedBeatInterval = computeBeatInterval(s, playheadSeconds);
        }

        if (cachedDownbeatInterval != null) {
            int x1 = geometry.timeToX(cachedDownbeatInterval[0], s, w, maxDuration);
            int x2 = geometry.timeToX(cachedDownbeatInterval[1], s, w, maxDuration);
            g.setColor(PLAYHEAD_BLOCK_COLOR);
            g.fillRect(x1, hdrBot, Math.max(1, x2 - x1), dbBot - hdrBot);
        }
        if (cachedBeatInterval != null) {
            int x1 = geometry.timeToX(cachedBeatInterval[0], s, w, maxDuration);
            int x2 = geometry.timeToX(cachedBeatInterval[1], s, w, maxDuration);
            g.setColor(PLAYHEAD_BLOCK_COLOR);
            g.fillRect(x1, dbBot, Math.max(1, x2 - x1), yBot - dbBot);
        }

        g.setColor(PLAYHEAD_COLOR);
        triangleX[0] = x - 5; triangleX[1] = x + 5; triangleX[2] = x;
        triangleY[0] = yTop;  triangleY[1] = yTop;  triangleY[2] = yTop + 8;
        g.fillPolygon(triangleX, triangleY, 3);
    }

    // ---- interval helpers (package-visible for hit-test reuse) -------------

    double[] computeBeatInterval(Segment s, double time) {
        Beat currentBeat = null;
        Beat nextBeat = null;
        outer:
        for (Bar bar : s.getBars()) {
            for (Beat b : bar.getBeats()) {
                if (b.getStart() <= time) currentBeat = b;
                else if (nextBeat == null) { nextBeat = b; break outer; }
            }
        }
        if (currentBeat == null) return null;
        return new double[]{currentBeat.getStart(), nextBeat != null ? nextBeat.getStart() : s.getEnd()};
    }

    double[] computeDownbeatInterval(Segment s, double time) {
        double currentT = -1;
        double nextT = s.getEnd();
        for (Bar bar : s.getBars()) {
            if (!bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
                double t = bar.getBeats().get(0).getStart();
                if (t <= time) currentT = t;
                else if (nextT == s.getEnd()) nextT = t;
            }
        }
        return currentT < 0 ? null : new double[]{currentT, nextT};
    }

    // ---- shared utilities --------------------------------------------------

    int findSegmentContainingTime(double time) {
        List<Segment> segments = model.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (time >= s.getStart() && time <= s.getEnd()) return i;
        }
        return -1;
    }

    private boolean isTimeWithinAnySegment(double time) {
        return findSegmentContainingTime(time) >= 0;
    }

    // ---- color helpers -----------------------------------------------------

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
}
