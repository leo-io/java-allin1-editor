package com.audioeditor.ui;

import com.audioeditor.application.editing.ProjectEditor;
import com.audioeditor.model.Bar;
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
 * CRUD table for bars (which have downbeat times), with add / delete / reorder
 * and selection synced to the timeline.
 *
 * <p>Each row represents a Bar. The downbeat time is the start of the bar's
 * first beat (when {@code isDownbeat()} is true).
 */
public class DownbeatsTablePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(DownbeatsTablePanel.class.getName());

    private final ProjectModel model;
    private final ProjectEditor editor;
    private final AudioPlayer audio;
    private final SelectionModel selection;
    private final JTable table;
    private final BarsTableModel tableModel;
    private boolean isSuppressingSelectionFeedback = false;

    public DownbeatsTablePanel(ProjectModel model, ProjectEditor editor, AudioPlayer audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.editor = editor;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new BarsTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (isSuppressingSelectionFeedback || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            Bar bar = tableModel.getBarAt(row);
            if (bar != null && !bar.getBeats().isEmpty()) {
                selection.selectItem(SelectionModel.SelectableItemType.BAR, row);
                double t = bar.getBeats().get(0).getStart();
                LOG.fine("Bars table: selected row " + row + " (downbeat at " + t + "s), seeking");
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
            Bar bar = tableModel.getBarAt(row);
            if (bar != null) {
                tableModel.removeBar(bar);
                selectTableRowAndBroadcastSelection(Math.min(row, tableModel.getRowCount() - 1));
                LOG.fine("Bars table: deleted bar row " + row);
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            Bar bar = tableModel.getBarAt(row);
            if (bar != null && row > 0) {
                tableModel.moveBarUp(bar);
                selectTableRowAndBroadcastSelection(row - 1);
                LOG.fine("Bars table: moved bar row " + row + " up");
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            Bar bar = tableModel.getBarAt(row);
            if (bar != null && row < tableModel.getRowCount() - 1) {
                tableModel.moveBarDown(bar);
                selectTableRowAndBroadcastSelection(row + 1);
                LOG.fine("Bars table: moved bar row " + row + " down");
            }
        });

        p.add(del);
        p.add(up);
        p.add(down);
        return p;
    }

    private void selectTableRowAndBroadcastSelection(int rowIndex) {
        Bar bar = tableModel.getBarAt(rowIndex);
        if (bar != null) {
            selection.selectItem(SelectionModel.SelectableItemType.BAR, rowIndex);
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
        if (selection.getSelectedItemType() != SelectionModel.SelectableItemType.BAR) {
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

    private class BarsTableModel extends AbstractTableModel {
        private final String[] columnHeaderNames = {"#", "Downbeat (s)", "# Beats", "Duration (s)"};
        private final List<Bar> flatBarList = new ArrayList<>();

        @Override
        public int getRowCount() {
            return flatBarList.size();
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
                case 1, 3 -> Double.class;
                case 2 -> Integer.class;
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Bar bar = flatBarList.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> bar.getBeats().isEmpty() ? 0.0 : bar.getBeats().get(0).getStart();
                case 2 -> bar.getBeats().size();
                case 3 -> bar.getDuration();
                default -> "";
            };
        }

        Bar getBarAt(int row) {
            if (row >= 0 && row < flatBarList.size()) {
                return flatBarList.get(row);
            }
            return null;
        }

        void removeBar(Bar bar) {
            editor.removeBar(bar);
        }

        void moveBarUp(Bar bar) {
            for (Segment s : model.getSegments()) {
                int idx = s.getBars().indexOf(bar);
                if (idx > 0) {
                    editor.moveBar(bar, -1);
                    return;
                }
            }
        }

        void moveBarDown(Bar bar) {
            for (Segment s : model.getSegments()) {
                int idx = s.getBars().indexOf(bar);
                if (idx >= 0 && idx < s.getBars().size() - 1) {
                    editor.moveBar(bar, 1);
                    return;
                }
            }
        }

        @Override
        public void fireTableDataChanged() {
            flatBarList.clear();
            for (Segment s : model.getSegments()) {
                flatBarList.addAll(s.getBars());
            }
            super.fireTableDataChanged();
        }
    }
}
