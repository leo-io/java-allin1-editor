package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
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
import java.util.logging.Logger;

/**
 * CRUD table for segments (start / end / label). Label uses an editable combo
 * seeded from the model's label vocabulary; reordering rows reorders the JSON
 * segment array. Selection is synced to the timeline.
 */
public class SegmentsTablePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(SegmentsTablePanel.class.getName());

    private final ProjectModel model;
    private final PcmWavPlaybackEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final SegmentTableModel tableModel;
    private boolean isSuppressingSelectionFeedback = false;

    public SegmentsTablePanel(ProjectModel model, PcmWavPlaybackEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new SegmentTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (isSuppressingSelectionFeedback || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getSegments().size()) {
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, row);
                double t = model.getSegments().get(row).getStart();
                LOG.fine("Segments table: selected row " + row + " (segment at " + t + "s), seeking");
                audio.seekSeconds(t);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        model.addProjectChangeListener(this);
        selection.addSelectionChangeListener(this);
        rebuildSegmentLabelComboBoxEditor();
    }

    /** Rebuild the label combo editor from the (possibly extended) vocabulary. */
    private void rebuildSegmentLabelComboBoxEditor() {
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
            Segment seg = new Segment("verse");
            double start = model.getMaxTime();
            Bar bar = new Bar();
            bar.addBeat(new Beat(true, start, start + 10));
            seg.addBar(bar);
            model.addSegment(seg);
            rebuildSegmentLabelComboBoxEditor();
            selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, model.getSegments().size() - 1);
            LOG.fine("Segments table: added segment");
        });
        dup.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.getSegments().add(row + 1, model.getSegments().get(row).copy());
                model.notifyAllProjectChangeListeners();
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, row + 1);
                LOG.fine("Segments table: duplicated segment row " + row);
            }
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeSegment(row);
                selectTableRowAndBroadcastSelection(Math.min(row, model.getSegments().size() - 1));
                LOG.fine("Segments table: deleted segment row " + row);
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveSegment(row, row - 1);
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, row - 1);
                LOG.fine("Segments table: moved segment row " + row + " up");
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getSegments().size() - 1) {
                model.moveSegment(row, row + 1);
                selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, row + 1);
                LOG.fine("Segments table: moved segment row " + row + " down");
            }
        });
        sort.addActionListener(a -> {
            model.sortSegments();
            LOG.fine("Segments table: sorted by start");
        });

        p.add(add);
        p.add(dup);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private void selectTableRowAndBroadcastSelection(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < model.getSegments().size()) {
            selection.selectItem(SelectionModel.SelectableItemType.SEGMENT, rowIndex);
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
        if (selection.getSelectedItemType() != SelectionModel.SelectableItemType.SEGMENT) {
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

    private class SegmentTableModel extends AbstractTableModel {
        private final String[] columnHeaderNames = {"#", "Label", "# Bars", "# Beats", "Start (s)", "End (s)"};

        @Override
        public int getRowCount() {
            return model.getSegments().size();
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
                case 1 -> String.class;
                case 2, 3 -> Integer.class;
                default -> Double.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 1;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Segment s = model.getSegments().get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> s.getLabel();
                case 2 -> s.getBars().size();
                case 3 -> {
                    int count = 0;
                    for (var bar : s.getBars()) {
                        count += bar.getBeats().size();
                    }
                    yield count;
                }
                case 4 -> s.getStart();
                case 5 -> s.getEnd();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 1) {
                Segment s = model.getSegments().get(r);
                s.setLabel(v.toString());
                model.rememberLabel(v.toString());
                rebuildSegmentLabelComboBoxEditor();
                model.notifyAllProjectChangeListeners();
                LOG.fine("Segments table: edited row " + r + " label = " + v);
            }
        }
    }
}
