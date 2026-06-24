package com.audioeditor.ui;

import com.audioeditor.audio.AudioEngine;
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
 * CRUD table for downbeat times, with add / delete / duplicate / reorder / sort
 * and selection synced to the timeline.
 */
public class DownbeatsTablePanel extends JPanel implements ProjectModel.Listener, SelectionModel.Listener {

    private final ProjectModel model;
    private final AudioEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final DbTableModel tableModel;
    private boolean syncing = false;

    public DownbeatsTablePanel(ProjectModel model, AudioEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new DbTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (syncing || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getDownbeats().size()) {
                selection.set(SelectionModel.Kind.DOWNBEAT, row);
                audio.seekSeconds(model.getDownbeats().get(row));
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
        JButton del = new JButton("Delete");
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");
        JButton sort = new JButton("Sort");

        add.addActionListener(a -> {
            int row = table.getSelectedRow();
            double t = row >= 0 ? model.getDownbeats().get(row) + 1.0
                    : (audio.isLoaded() ? audio.getPositionSeconds() : 0);
            model.addDownbeat(t);
            selection.set(SelectionModel.Kind.DOWNBEAT, model.getDownbeats().size() - 1);
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeDownbeat(row);
                selectRow(Math.min(row, model.getDownbeats().size() - 1));
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveDownbeat(row, row - 1);
                selectRow(row - 1);
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getDownbeats().size() - 1) {
                model.moveDownbeat(row, row + 1);
                selectRow(row + 1);
            }
        });
        sort.addActionListener(a -> model.sortDownbeats());

        p.add(add);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private void selectRow(int row) {
        if (row >= 0 && row < model.getDownbeats().size()) {
            selection.set(SelectionModel.Kind.DOWNBEAT, row);
        }
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
        if (selection.getKind() != SelectionModel.Kind.DOWNBEAT) {
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

    private class DbTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Time (s)"};

        @Override
        public int getRowCount() {
            return model.getDownbeats().size();
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
            return c == 0 ? Integer.class : Double.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 1;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return c == 0 ? r : model.getDownbeats().get(r);
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 1) {
                try {
                    model.getDownbeats().set(r, Math.max(0, Double.parseDouble(v.toString())));
                    model.fireChanged();
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
