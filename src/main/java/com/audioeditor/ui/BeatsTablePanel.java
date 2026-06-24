package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.logging.Logger;

/**
 * CRUD table for beats: time (s) and bar position. Supports add, delete,
 * duplicate, reorder (up/down) and sort, with selection synced to the timeline.
 */
public class BeatsTablePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(BeatsTablePanel.class.getName());

    private final ProjectModel model;
    private final PcmWavPlaybackEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final BeatMarkerTableModel tableModel;
    private boolean isSuppressingSelectionFeedback = false;

    public BeatsTablePanel(ProjectModel model, PcmWavPlaybackEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
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
            if (row >= 0 && row < model.getBeats().size()) {
                selection.selectItem(SelectionModel.SelectableItemType.BEAT, row);
                double t = model.getBeats().get(row).getTime();
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
        JButton add = new JButton("Add");
        JButton dup = new JButton("Duplicate");
        JButton del = new JButton("Delete");
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");
        JButton sort = new JButton("Sort by time");

        add.addActionListener(a -> {
            int row = table.getSelectedRow();
            double t = row >= 0 ? model.getBeats().get(row).getTime() + 0.5 : getCurrentPlaybackPositionOrZero();
            model.addBeat(new Beat(t, 1));
            selectTableRowAndBroadcastSelection(model.getBeats().size() - 1);
            LOG.fine("Beats table: added beat at " + t + "s (pos 1)");
        });
        dup.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Beat b = model.getBeats().get(row).copy();
                b.setTime(b.getTime() + 0.25);
                model.getBeats().add(row + 1, b);
                model.notifyAllProjectChangeListeners();
                selectTableRowAndBroadcastSelection(row + 1);
                LOG.fine("Beats table: duplicated beat row " + row);
            }
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeBeat(row);
                selectTableRowAndBroadcastSelection(Math.min(row, model.getBeats().size() - 1));
                LOG.fine("Beats table: deleted beat row " + row);
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveBeat(row, row - 1);
                selectTableRowAndBroadcastSelection(row - 1);
                LOG.fine("Beats table: moved beat row " + row + " up");
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getBeats().size() - 1) {
                model.moveBeat(row, row + 1);
                selectTableRowAndBroadcastSelection(row + 1);
                LOG.fine("Beats table: moved beat row " + row + " down");
            }
        });
        sort.addActionListener(a -> {
            model.sortBeats();
            LOG.fine("Beats table: sorted by time");
        });

        p.add(add);
        p.add(dup);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private double getCurrentPlaybackPositionOrZero() {
        return audio.isLoaded() ? audio.getPositionSeconds() : 0;
    }

    private void selectTableRowAndBroadcastSelection(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= model.getBeats().size()) {
            return;
        }
        selection.selectItem(SelectionModel.SelectableItemType.BEAT, rowIndex);
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
        private final String[] columnHeaderNames = {"#", "Time (s)", "Position"};

        @Override
        public int getRowCount() {
            return model.getBeats().size();
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
                case 0 -> Integer.class;
                case 1 -> Double.class;
                default -> Integer.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Beat b = model.getBeats().get(r);
            return switch (c) {
                case 0 -> r;
                case 1 -> b.getTime();
                default -> b.getPosition();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            Beat b = model.getBeats().get(r);
            try {
                if (c == 1) {
                    b.setTime(Math.max(0, Double.parseDouble(v.toString())));
                } else if (c == 2) {
                    b.setPosition(Math.max(1, Integer.parseInt(v.toString())));
                }
                model.notifyAllProjectChangeListeners();
                LOG.fine("Beats table: edited row " + r + " col " + c + " = " + v);
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
