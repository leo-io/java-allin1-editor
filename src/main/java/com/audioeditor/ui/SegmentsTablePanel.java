package com.audioeditor.ui;

import com.audioeditor.audio.AudioEngine;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * CRUD table for segments (start / end / label). Label uses an editable combo
 * seeded from the model's label vocabulary; reordering rows reorders the JSON
 * segment array. Selection is synced to the timeline.
 */
public class SegmentsTablePanel extends JPanel implements ProjectModel.Listener, SelectionModel.Listener {

    private final ProjectModel model;
    private final AudioEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final SegTableModel tableModel;
    private boolean syncing = false;

    public SegmentsTablePanel(ProjectModel model, AudioEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new SegTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (syncing || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getSegments().size()) {
                selection.set(SelectionModel.Kind.SEGMENT, row);
                audio.seekSeconds(model.getSegments().get(row).getStart());
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        model.addListener(this);
        selection.addListener(this);
        refreshLabelEditor();
    }

    /** Rebuild the label combo editor from the (possibly extended) vocabulary. */
    private void refreshLabelEditor() {
        JComboBox<String> combo = new JComboBox<>(model.getLabelVocabulary().toArray(new String[0]));
        combo.setEditable(true);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(combo));
    }

    private JPanel buildButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton add = new JButton("Add");
        JButton dup = new JButton("Duplicate");
        JButton del = new JButton("Delete");
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");
        JButton sort = new JButton("Sort by start");

        add.addActionListener(a -> {
            double t = audio.isLoaded() ? audio.getPositionSeconds() : 0;
            model.addSegment(new Segment(t, t + 10, "verse"));
            refreshLabelEditor();
            selection.set(SelectionModel.Kind.SEGMENT, model.getSegments().size() - 1);
        });
        dup.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.getSegments().add(row + 1, model.getSegments().get(row).copy());
                model.fireChanged();
                selection.set(SelectionModel.Kind.SEGMENT, row + 1);
            }
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeSegment(row);
                selectRow(Math.min(row, model.getSegments().size() - 1));
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveSegment(row, row - 1);
                selection.set(SelectionModel.Kind.SEGMENT, row - 1);
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getSegments().size() - 1) {
                model.moveSegment(row, row + 1);
                selection.set(SelectionModel.Kind.SEGMENT, row + 1);
            }
        });
        sort.addActionListener(a -> model.sortSegments());

        p.add(add);
        p.add(dup);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private void selectRow(int row) {
        if (row >= 0 && row < model.getSegments().size()) {
            selection.set(SelectionModel.Kind.SEGMENT, row);
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
        if (selection.getKind() != SelectionModel.Kind.SEGMENT) {
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

    private class SegTableModel extends AbstractTableModel {
        private final String[] cols = {"Start (s)", "End (s)", "Label"};

        @Override
        public int getRowCount() {
            return model.getSegments().size();
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
            return c == 2 ? String.class : Double.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Segment s = model.getSegments().get(r);
            return switch (c) {
                case 0 -> s.getStart();
                case 1 -> s.getEnd();
                default -> s.getLabel();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            Segment s = model.getSegments().get(r);
            try {
                switch (c) {
                    case 0 -> s.setStart(Math.max(0, Double.parseDouble(v.toString())));
                    case 1 -> s.setEnd(Math.max(0, Double.parseDouble(v.toString())));
                    case 2 -> {
                        s.setLabel(v.toString());
                        model.rememberLabel(v.toString());
                        refreshLabelEditor();
                    }
                    default -> {
                    }
                }
                model.fireChanged();
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
