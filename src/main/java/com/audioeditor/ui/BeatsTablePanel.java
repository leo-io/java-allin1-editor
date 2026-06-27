package com.audioeditor.ui;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.audioeditor.port.audio.AudioPlayer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * CRUD table for beats: start/end times and downbeat flag. Supports add, delete,
 * duplicate, reorder (up/down) and sort, with selection synced to the timeline.
 */
public class BeatsTablePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(BeatsTablePanel.class.getName());

    private final ProjectModel model;
    private final ProjectEditor editor;
    private final AudioPlayer audio;
    private final SelectionModel selection;
    private final JTable table;
    private final BeatMarkerTableModel tableModel;
    private boolean isSuppressingSelectionFeedback = false;

    public BeatsTablePanel(ProjectModel model, ProjectEditor editor, AudioPlayer audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.editor = editor;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new BeatMarkerTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (isSuppressingSelectionFeedback || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            Beat b = tableModel.getBeatAt(row);
            if (b != null) {
                selection.selectItem(SelectionModel.SelectableItemType.BEAT, row);
                double t = b.getStart();
                LOG.fine("Beats table: selected row " + row + " (beat at " + t + "s), seeking");
                audio.seekSeconds(t);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        model.addProjectChangeListener(this);
        selection.addSelectionChangeListener(this);
    }

    private JPanel buildButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton del = new JButton("Delete");
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");

        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            Beat b = tableModel.getBeatAt(row);
            if (b != null) {
                tableModel.removeBeat(b);
                selectTableRowAndBroadcastSelection(Math.min(row, tableModel.getRowCount() - 1));
                LOG.fine("Beats table: deleted beat row " + row);
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            Beat b = tableModel.getBeatAt(row);
            if (b != null && row > 0) {
                tableModel.moveBeatUp(b);
                selectTableRowAndBroadcastSelection(row - 1);
                LOG.fine("Beats table: moved beat row " + row + " up");
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            Beat b = tableModel.getBeatAt(row);
            if (b != null && row < tableModel.getRowCount() - 1) {
                tableModel.moveBeatDown(b);
                selectTableRowAndBroadcastSelection(row + 1);
                LOG.fine("Beats table: moved beat row " + row + " down");
            }
        });

        p.add(del);
        p.add(up);
        p.add(down);
        return p;
    }

    private void selectTableRowAndBroadcastSelection(int rowIndex) {
        Beat b = tableModel.getBeatAt(rowIndex);
        if (b != null) {
            selection.selectItem(SelectionModel.SelectableItemType.BEAT, rowIndex);
        }
    }

    @Override
    public void modelChanged() {
        int sel = table.getSelectedRow();
        tableModel.fireTableDataChanged();
        if (sel >= 0 && sel < tableModel.getRowCount()) {
            isSuppressingSelectionFeedback = true;
            table.setRowSelectionInterval(sel, sel);
            isSuppressingSelectionFeedback = false;
        }
    }

    @Override
    public void selectionChanged() {
        if (selection.getSelectedItemType() != SelectionModel.SelectableItemType.BEAT) {
            return;
        }
        int i = selection.getSelectedItemIndex();
        if (i >= 0 && i < tableModel.getRowCount() && table.getSelectedRow() != i) {
            isSuppressingSelectionFeedback = true;
            table.setRowSelectionInterval(i, i);
            table.scrollRectToVisible(table.getCellRect(i, 0, true));
            isSuppressingSelectionFeedback = false;
        }
    }

    private class BeatMarkerTableModel extends AbstractTableModel {
        private final String[] columnHeaderNames = {"#", "Bar #", "Start (s)", "End (s)", "Downbeat"};
        private final List<Beat> flatBeatList = new ArrayList<>();

        @Override
        public int getRowCount() {
            return flatBeatList.size();
        }

        @Override
        public int getColumnCount() {
            return columnHeaderNames.length;
        }

        @Override
        public String getColumnName(int c) {
            return columnHeaderNames[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case 0, 1 -> Integer.class;
                case 2, 3 -> Double.class;
                case 4 -> Boolean.class;
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2 || c == 3 || c == 4;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Beat b = flatBeatList.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> getBarNumberForBeat(b);
                case 2 -> b.getStart();
                case 3 -> b.getEnd();
                case 4 -> b.isDownbeat();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            Beat b = flatBeatList.get(r);
            try {
                switch (c) {
                    case 2 -> editor.updateBeat(b, Double.parseDouble(v.toString()), b.getEnd(), b.isDownbeat());
                    case 3 -> editor.updateBeat(b, b.getStart(), Double.parseDouble(v.toString()), b.isDownbeat());
                    case 4 -> editor.updateBeat(b, b.getStart(), b.getEnd(), (Boolean) v);
                    default -> {
                        return;
                    }
                }
                LOG.fine("Beats table: edited row " + r + " col " + c + " = " + v);
            } catch (NumberFormatException ignored) {
            }
        }

        Beat getBeatAt(int row) {
            if (row >= 0 && row < flatBeatList.size()) {
                return flatBeatList.get(row);
            }
            return null;
        }

        void removeBeat(Beat beat) {
            editor.removeBeat(beat);
        }

        void moveBeatUp(Beat beat) {
            for (Segment s : model.getSegments()) {
                for (Bar bar : s.getBars()) {
                    int idx = bar.getBeats().indexOf(beat);
                    if (idx > 0) {
                        editor.moveBeat(beat, -1);
                        return;
                    }
                }
            }
        }

        void moveBeatDown(Beat beat) {
            for (Segment s : model.getSegments()) {
                for (Bar bar : s.getBars()) {
                    int idx = bar.getBeats().indexOf(beat);
                    if (idx >= 0 && idx < bar.getBeats().size() - 1) {
                        editor.moveBeat(beat, 1);
                        return;
                    }
                }
            }
        }

        private int getBarNumberForBeat(Beat beat) {
            int barNum = 1;
            for (Segment s : model.getSegments()) {
                for (Bar bar : s.getBars()) {
                    if (bar.getBeats().contains(beat)) {
                        return barNum;
                    }
                    barNum++;
                }
            }
            return 0;
        }

        @Override
        public void fireTableDataChanged() {
            flatBeatList.clear();
            for (Segment s : model.getSegments()) {
                for (Bar bar : s.getBars()) {
                    flatBeatList.addAll(bar.getBeats());
                }
            }
            super.fireTableDataChanged();
        }
    }
}
