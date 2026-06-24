package com.audioeditor.ui;

import com.audioeditor.audio.AudioEngine;
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

/**
 * Zoomable, marker-only timeline. Renders a time ruler, segment blocks,
 * downbeat band and beat band, plus the playhead. All marker editing — seek,
 * select, drag-to-move, drag-to-resize segments, double-click-to-add,
 * right-click-to-delete — happens here and flows through {@link ProjectModel}
 * so the tables and playhead update live.
 */
public class TimelinePanel extends JPanel implements ProjectModel.Listener, SelectionModel.Listener {

    private static final int RULER_H = 22;
    private static final int SEG_TOP = 26;
    private static final int SEG_BOT = 84;
    private static final int DB_TOP = 88;   // downbeat band
    private static final int DB_BOT = 128;
    private static final int BEAT_TOP = 132; // beat band (to bottom)
    private static final int HEIGHT = 280;
    private static final int HIT = 5;        // px tolerance for hit-testing
    private static final int EDGE = 6;       // px for segment edge grab

    private final ProjectModel model;
    private final AudioEngine audio;
    private final SelectionModel selection;

    private double pps = 40.0;        // pixels per second (zoom)
    private double playhead = 0.0;

    // drag state
    private enum DragKind { NONE, BEAT, DOWNBEAT, SEG_MOVE, SEG_START, SEG_END }
    private DragKind drag = DragKind.NONE;
    private int dragIndex = -1;
    private double dragGrabOffset = 0; // for segment move: click time - seg.start

    public TimelinePanel(ProjectModel model, AudioEngine audio, SelectionModel selection) {
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        setBackground(new Color(0x1e1e1e));
        model.addListener(this);
        selection.addListener(this);
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        setToolTipText("");
    }

    public void setZoom(double pixelsPerSecond) {
        this.pps = pixelsPerSecond;
        revalidate();
        repaint();
    }

    public double getZoom() {
        return pps;
    }

    public void setPlayhead(double seconds) {
        int oldX = timeToX(playhead);
        this.playhead = seconds;
        int newX = timeToX(seconds);
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
        int w = (int) ((model.getMaxTime() + 3) * pps) + 20;
        return new Dimension(Math.max(w, 800), HEIGHT);
    }

    // ---- coordinate helpers ---------------------------------------------

    private int timeToX(double t) {
        return (int) Math.round(t * pps);
    }

    private double xToTime(int x) {
        return Math.max(0, x / pps);
    }

    // ---- painting --------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        int h = getHeight();
        int xMin = clip.x - 4;
        int xMax = clip.x + clip.width + 4;

        drawRuler(g, clip, h, xMin, xMax);
        drawSegments(g, h, xMin, xMax);
        drawDownbeats(g, h, clip, xMin, xMax);
        drawBeats(g, h, xMin, xMax);
        drawPlayhead(g, h);
    }

    private void drawRuler(Graphics2D g, Rectangle clip, int h, int xMin, int xMax) {
        g.setColor(new Color(0x2b2b2b));
        g.fillRect(clip.x, 0, clip.width, RULER_H);
        g.setColor(new Color(0x3c3c3c));
        g.drawLine(clip.x, RULER_H, clip.x + clip.width, RULER_H);

        // adaptive seconds-per-label so labels never crowd
        double minLabelPx = 60;
        double step = Math.max(1, Math.ceil(minLabelPx / pps));
        g.setFont(g.getFont().deriveFont(10f));
        double maxT = model.getMaxTime() + 3;
        double tStart = Math.max(0, Math.floor(xToTime(xMin) / step) * step);
        for (double t = tStart; t <= maxT; t += step) {
            int x = timeToX(t);
            if (x > xMax) {
                break;
            }
            g.setColor(new Color(0x555555));
            g.drawLine(x, RULER_H - 6, x, RULER_H);
            g.setColor(new Color(0xaaaaaa));
            g.drawString(formatTime(t), x + 2, 12);
        }
    }

    private void drawSegments(Graphics2D g, int h, int xMin, int xMax) {
        Font f = g.getFont().deriveFont(Font.BOLD, 11f);
        g.setFont(f);
        for (int i = 0; i < model.getSegments().size(); i++) {
            Segment s = model.getSegments().get(i);
            int x1 = timeToX(s.getStart());
            int x2 = timeToX(s.getEnd());
            if (x2 < xMin || x1 > xMax) {
                continue;
            }
            int w = Math.max(1, x2 - x1);
            Color base = labelColor(s.getLabel());
            boolean sel = selection.is(SelectionModel.Kind.SEGMENT, i);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), sel ? 235 : 170));
            g.fillRect(x1, SEG_TOP, w, SEG_BOT - SEG_TOP);
            g.setColor(sel ? Color.WHITE : base.darker());
            g.setStroke(new BasicStroke(sel ? 2f : 1f));
            g.drawRect(x1, SEG_TOP, w, SEG_BOT - SEG_TOP);
            // edge handles
            g.setColor(new Color(255, 255, 255, 120));
            g.fillRect(x1, SEG_TOP, 2, SEG_BOT - SEG_TOP);
            g.fillRect(x2 - 2, SEG_TOP, 2, SEG_BOT - SEG_TOP);
            // label
            g.setColor(Color.WHITE);
            if (w > 24) {
                g.drawString(s.getLabel(), x1 + 4, SEG_TOP + 16);
            }
        }
    }

    private void drawDownbeats(Graphics2D g, int h, Rectangle clip, int xMin, int xMax) {
        g.setColor(new Color(0x222222));
        g.fillRect(clip.x, DB_TOP, clip.width, DB_BOT - DB_TOP);
        for (int i = 0; i < model.getDownbeats().size(); i++) {
            int x = timeToX(model.getDownbeats().get(i));
            if (x < xMin || x > xMax) {
                continue;
            }
            boolean sel = selection.is(SelectionModel.Kind.DOWNBEAT, i);
            g.setColor(sel ? Color.WHITE : new Color(0xff6e6e));
            g.setStroke(new BasicStroke(sel ? 3f : 2f));
            g.drawLine(x, DB_TOP, x, DB_BOT);
        }
        g.setColor(new Color(0x777777));
        g.setFont(g.getFont().deriveFont(9f));
        g.drawString("downbeats", 4, DB_TOP + 11);
    }

    private void drawBeats(Graphics2D g, int h, int xMin, int xMax) {
        g.setFont(g.getFont().deriveFont(9f));
        boolean drawLabels = pps >= 14; // only show bar positions when readable
        for (int i = 0; i < model.getBeats().size(); i++) {
            Beat b = model.getBeats().get(i);
            int x = timeToX(b.getTime());
            if (x < xMin || x > xMax) {
                continue;
            }
            boolean sel = selection.is(SelectionModel.Kind.BEAT, i);
            boolean one = b.getPosition() == 1;
            if (sel) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2.5f));
            } else {
                g.setColor(one ? new Color(0x6ec1ff) : new Color(0x4a7a99));
                g.setStroke(new BasicStroke(one ? 1.6f : 1f));
            }
            g.drawLine(x, BEAT_TOP, x, h);
            if (drawLabels) {
                g.setColor(sel ? Color.WHITE : new Color(0x99c7e0));
                g.drawString(Integer.toString(b.getPosition()), x + 1, BEAT_TOP + 10);
            }
        }
        g.setColor(new Color(0x777777));
        g.drawString("beats", 4, BEAT_TOP + 10);
    }

    private void drawPlayhead(Graphics2D g, int h) {
        int x = timeToX(playhead);
        g.setColor(new Color(0xffd24a));
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(x, 0, x, h);
        g.fillPolygon(new int[]{x - 5, x + 5, x}, new int[]{0, 0, 8}, 3);
    }

    // ---- hit testing -----------------------------------------------------

    private int hitBeat(int mx) {
        int best = -1;
        int bestD = HIT + 1;
        for (int i = 0; i < model.getBeats().size(); i++) {
            int d = Math.abs(timeToX(model.getBeats().get(i).getTime()) - mx);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private int hitDownbeat(int mx) {
        int best = -1;
        int bestD = HIT + 1;
        for (int i = 0; i < model.getDownbeats().size(); i++) {
            int d = Math.abs(timeToX(model.getDownbeats().get(i)) - mx);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private int hitSegment(int mx) {
        for (int i = model.getSegments().size() - 1; i >= 0; i--) {
            Segment s = model.getSegments().get(i);
            if (mx >= timeToX(s.getStart()) - EDGE && mx <= timeToX(s.getEnd()) + EDGE) {
                return i;
            }
        }
        return -1;
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
                addAtDoubleClick(x, y);
                return;
            }

            // segment band
            if (y >= SEG_TOP && y <= SEG_BOT) {
                int si = hitSegment(x);
                if (si >= 0) {
                    Segment s = model.getSegments().get(si);
                    selection.set(SelectionModel.Kind.SEGMENT, si);
                    int xs = timeToX(s.getStart());
                    int xe = timeToX(s.getEnd());
                    if (Math.abs(x - xs) <= EDGE) {
                        drag = DragKind.SEG_START;
                    } else if (Math.abs(x - xe) <= EDGE) {
                        drag = DragKind.SEG_END;
                    } else {
                        drag = DragKind.SEG_MOVE;
                        dragGrabOffset = xToTime(x) - s.getStart();
                    }
                    dragIndex = si;
                    audio.seekSeconds(s.getStart());
                    setPlayhead(s.getStart());
                    return;
                }
            }
            // downbeat band
            if (y >= DB_TOP && y <= DB_BOT) {
                int di = hitDownbeat(x);
                if (di >= 0) {
                    selection.set(SelectionModel.Kind.DOWNBEAT, di);
                    drag = DragKind.DOWNBEAT;
                    dragIndex = di;
                    jumpTo(model.getDownbeats().get(di));
                    return;
                }
            }
            // beat band
            if (y >= BEAT_TOP) {
                int bi = hitBeat(x);
                if (bi >= 0) {
                    selection.set(SelectionModel.Kind.BEAT, bi);
                    drag = DragKind.BEAT;
                    dragIndex = bi;
                    jumpTo(model.getBeats().get(bi).getTime());
                    return;
                }
            }

            // empty space anywhere -> seek
            drag = DragKind.NONE;
            double t = xToTime(x);
            audio.seekSeconds(t);
            setPlayhead(t);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (drag == DragKind.NONE || dragIndex < 0) {
                // scrubbing the playhead over empty space
                double t = xToTime(e.getX());
                audio.seekSeconds(t);
                setPlayhead(t);
                return;
            }
            double t = xToTime(e.getX());
            switch (drag) {
                case BEAT -> model.getBeats().get(dragIndex).setTime(t);
                case DOWNBEAT -> model.getDownbeats().set(dragIndex, t);
                case SEG_START -> {
                    Segment s = model.getSegments().get(dragIndex);
                    s.setStart(Math.min(t, s.getEnd() - 0.05));
                }
                case SEG_END -> {
                    Segment s = model.getSegments().get(dragIndex);
                    s.setEnd(Math.max(t, s.getStart() + 0.05));
                }
                case SEG_MOVE -> {
                    Segment s = model.getSegments().get(dragIndex);
                    double dur = s.getDuration();
                    double ns = Math.max(0, t - dragGrabOffset);
                    s.setStart(ns);
                    s.setEnd(ns + dur);
                }
                default -> {
                }
            }
            model.fireChanged();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            // keep beat/downbeat lists time-ordered after a move
            if (drag == DragKind.BEAT) {
                model.sortBeats();
                reselect(SelectionModel.Kind.BEAT, e.getX());
            } else if (drag == DragKind.DOWNBEAT) {
                model.sortDownbeats();
                reselect(SelectionModel.Kind.DOWNBEAT, e.getX());
            }
            drag = DragKind.NONE;
            dragIndex = -1;
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();
            int cursor = Cursor.DEFAULT_CURSOR;
            if (y >= SEG_TOP && y <= SEG_BOT) {
                int si = hitSegment(x);
                if (si >= 0) {
                    Segment s = model.getSegments().get(si);
                    if (Math.abs(x - timeToX(s.getStart())) <= EDGE
                            || Math.abs(x - timeToX(s.getEnd())) <= EDGE) {
                        cursor = Cursor.E_RESIZE_CURSOR;
                    } else {
                        cursor = Cursor.MOVE_CURSOR;
                    }
                }
            } else if ((y >= DB_TOP && y <= DB_BOT && hitDownbeat(x) >= 0)
                    || (y >= BEAT_TOP && hitBeat(x) >= 0)) {
                cursor = Cursor.HAND_CURSOR;
            }
            setCursor(Cursor.getPredefinedCursor(cursor));
        }
    }

    private void reselect(SelectionModel.Kind kind, int mx) {
        if (kind == SelectionModel.Kind.BEAT) {
            selection.set(kind, hitBeat(mx));
        } else if (kind == SelectionModel.Kind.DOWNBEAT) {
            selection.set(kind, hitDownbeat(mx));
        }
    }

    private void jumpTo(double t) {
        audio.seekSeconds(t);
        setPlayhead(t);
    }

    private void addAtDoubleClick(int x, int y) {
        double t = xToTime(x);
        if (y >= SEG_TOP && y <= SEG_BOT) {
            double end = Math.min(model.getMaxTime() + 5, t + 5);
            Segment s = new Segment(t, Math.max(end, t + 1), "verse");
            model.addSegment(s);
            selection.set(SelectionModel.Kind.SEGMENT, model.getSegments().size() - 1);
        } else if (y >= DB_TOP && y <= DB_BOT) {
            model.addDownbeat(t);
            model.sortDownbeats();
            selection.set(SelectionModel.Kind.DOWNBEAT, hitDownbeat(x));
        } else {
            model.addBeat(new Beat(t, 1));
            model.sortBeats();
            selection.set(SelectionModel.Kind.BEAT, hitBeat(x));
        }
    }

    private void showContextMenu(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        JPopupMenu menu = new JPopupMenu();

        if (y >= SEG_TOP && y <= SEG_BOT) {
            int si = hitSegment(x);
            if (si >= 0) {
                selection.set(SelectionModel.Kind.SEGMENT, si);
                JMenuItem del = new JMenuItem("Delete segment");
                del.addActionListener(a -> model.removeSegment(si));
                menu.add(del);
            } else {
                JMenuItem add = new JMenuItem("Add segment here");
                add.addActionListener(a -> addAtDoubleClick(x, y));
                menu.add(add);
            }
        } else if (y >= DB_TOP && y <= DB_BOT) {
            int di = hitDownbeat(x);
            if (di >= 0) {
                selection.set(SelectionModel.Kind.DOWNBEAT, di);
                JMenuItem del = new JMenuItem("Delete downbeat");
                del.addActionListener(a -> model.removeDownbeat(di));
                menu.add(del);
            } else {
                JMenuItem add = new JMenuItem("Add downbeat here");
                add.addActionListener(a -> {
                    model.addDownbeat(xToTime(x));
                    model.sortDownbeats();
                });
                menu.add(add);
            }
        } else {
            int bi = hitBeat(x);
            if (bi >= 0) {
                selection.set(SelectionModel.Kind.BEAT, bi);
                JMenuItem del = new JMenuItem("Delete beat");
                del.addActionListener(a -> model.removeBeat(bi));
                menu.add(del);
            } else {
                JMenuItem add = new JMenuItem("Add beat here");
                add.addActionListener(a -> {
                    model.addBeat(new Beat(xToTime(x), 1));
                    model.sortBeats();
                });
                menu.add(add);
            }
        }
        menu.show(this, x, y);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        if (y >= SEG_TOP && y <= SEG_BOT) {
            int si = hitSegment(x);
            if (si >= 0) {
                Segment s = model.getSegments().get(si);
                return String.format("%s  %.2f–%.2f s", s.getLabel(), s.getStart(), s.getEnd());
            }
        } else if (y >= DB_TOP && y <= DB_BOT) {
            int di = hitDownbeat(x);
            if (di >= 0) {
                return String.format("downbeat %.3f s", model.getDownbeats().get(di));
            }
        } else if (y >= BEAT_TOP) {
            int bi = hitBeat(x);
            if (bi >= 0) {
                Beat b = model.getBeats().get(bi);
                return String.format("beat %.3f s  (pos %d)", b.getTime(), b.getPosition());
            }
        }
        return String.format("%.2f s", xToTime(x));
    }

    // ---- misc ------------------------------------------------------------

    static Color labelColor(String label) {
        if (label == null) {
            label = "";
        }
        int hue = Math.floorMod(label.toLowerCase().hashCode(), 360);
        return Color.getHSBColor(hue / 360f, 0.55f, 0.75f);
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }
}
