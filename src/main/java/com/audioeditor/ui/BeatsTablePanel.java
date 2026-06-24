package com.audioeditor.ui;

import com.audioeditor.audio.AudioEngine;
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

/**
 * CRUD table for beats: time (s) and bar position. Supports add, delete,
 * duplicate, reorder (up/down) and sort, with selection synced to the timeline.
 */
public class BeatsTablePanel extends JPanel implements ProjectModel.Listener, SelectionModel.Listener {

    private final ProjectModel model;
    private final AudioEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final BeatTableModel tableModel;
    private boolean syncing = false;

    public BeatsTablePanel(ProjectModel model, AudioEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new BeatTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (syncing || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getBeats().size()) {
                selection.set(SelectionModel.Kind.BEAT, row);
                audio.seekSeconds(model.getBeats().get(row).getTime());
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        model.addListener(this);
        selection.addListener(this);
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
            double t = row >= 0 ? model.getBeats().get(row).getTime() + 0.5 : audioPos();
            model.addBeat(new Beat(t, 1));
            selectRow(model.getBeats().size() - 1);
        });
        dup.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Beat b = model.getBeats().get(row).copy();
                b.setTime(b.getTime() + 0.25);
                model.getBeats().add(row + 1, b);
                model.fireChanged();
                selectRow(row + 1);
            }
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeBeat(row);
                selectRow(Math.min(row, model.getBeats().size() - 1));
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveBeat(row, row - 1);
                selectRow(row - 1);
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getBeats().size() - 1) {
                model.moveBeat(row, row + 1);
                selectRow(row + 1);
            }
        });
        sort.addActionListener(a -> model.sortBeats());

        p.add(add);
        p.add(dup);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private double audioPos() {
        return audio.isLoaded() ? audio.getPositionSeconds() : 0;
    }

    private void selectRow(int row) {
        if (row < 0 || row >= model.getBeats().size()) {
            return;
        }
        selection.set(SelectionModel.Kind.BEAT, row);
    }

    @Override
    public void modelChanged() {
        int sel = table.getSelectedRow();
        tableModel.fireTableDataChanged();
        if (sel >= 0 && sel < tableModel.getRowCount()) {
            syncing = true;
            table.setRowSelectionInterval(sel, sel);
            syncing = false;
        }
    }

    @Override
    public void selectionChanged() {
        if (selection.getKind() != SelectionModel.Kind.BEAT) {
            return;
        }
        int i = selection.getIndex();
        if (i >= 0 && i < tableModel.getRowCount() && table.getSelectedRow() != i) {
            syncing = true;
            table.setRowSelectionInterval(i, i);
            table.scrollRectToVisible(table.getCellRect(i, 0, true));
            syncing = false;
        }
    }

    private class BeatTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Time (s)", "Position"};

        @Override
        public int getRowCount() {
            return model.getBeats().size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
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
                model.fireChanged();
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
