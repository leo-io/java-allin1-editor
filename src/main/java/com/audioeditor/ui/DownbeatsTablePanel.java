package com.audioeditor.ui;

import com.audioeditor.audio.PcmWavPlaybackEngine;
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
 * CRUD table for downbeat times, with add / delete / duplicate / reorder / sort
 * and selection synced to the timeline.
 */
public class DownbeatsTablePanel extends JPanel implements ProjectModel.ProjectChangeListener, SelectionModel.SelectionChangeListener {

    private static final Logger LOG = Logger.getLogger(DownbeatsTablePanel.class.getName());

    private final ProjectModel model;
    private final PcmWavPlaybackEngine audio;
    private final SelectionModel selection;
    private final JTable table;
    private final DownbeatMarkerTableModel tableModel;
    private boolean isSuppressingSelectionFeedback = false;

    public DownbeatsTablePanel(ProjectModel model, PcmWavPlaybackEngine audio, SelectionModel selection) {
        super(new BorderLayout());
        this.model = model;
        this.audio = audio;
        this.selection = selection;
        this.tableModel = new DownbeatMarkerTableModel();
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (isSuppressingSelectionFeedback || e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getDownbeats().size()) {
                selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, row);
                double t = model.getDownbeats().get(row);
                LOG.fine("Downbeats table: selected row " + row + " (downbeat at " + t + "s), seeking");
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
        JButton del = new JButton("Delete");
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");
        JButton sort = new JButton("Sort");

        add.addActionListener(a -> {
            int row = table.getSelectedRow();
            double t = row >= 0 ? model.getDownbeats().get(row) + 1.0
                    : (audio.isLoaded() ? audio.getPositionSeconds() : 0);
            model.addDownbeat(t);
            selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, model.getDownbeats().size() - 1);
            LOG.fine("Downbeats table: added downbeat at " + t + "s");
        });
        del.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeDownbeat(row);
                selectTableRowAndBroadcastSelection(Math.min(row, model.getDownbeats().size() - 1));
                LOG.fine("Downbeats table: deleted downbeat row " + row);
            }
        });
        up.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveDownbeat(row, row - 1);
                selectTableRowAndBroadcastSelection(row - 1);
                LOG.fine("Downbeats table: moved downbeat row " + row + " up");
            }
        });
        down.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < model.getDownbeats().size() - 1) {
                model.moveDownbeat(row, row + 1);
                selectTableRowAndBroadcastSelection(row + 1);
                LOG.fine("Downbeats table: moved downbeat row " + row + " down");
            }
        });
        sort.addActionListener(a -> {
            model.sortDownbeats();
            LOG.fine("Downbeats table: sorted");
        });

        p.add(add);
        p.add(del);
        p.add(up);
        p.add(down);
        p.add(sort);
        return p;
    }

    private void selectTableRowAndBroadcastSelection(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < model.getDownbeats().size()) {
            selection.selectItem(SelectionModel.SelectableItemType.DOWNBEAT, rowIndex);
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
        if (selection.getSelectedItemType() != SelectionModel.SelectableItemType.DOWNBEAT) {
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

    private class DownbeatMarkerTableModel extends AbstractTableModel {
        private final String[] columnHeaderNames = {"#", "Time (s)"};

        @Override
        public int getRowCount() {
            return model.getDownbeats().size();
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
                    model.notifyAllProjectChangeListeners();
                    LOG.fine("Downbeats table: edited row " + r + " time = " + v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
