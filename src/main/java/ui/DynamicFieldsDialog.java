package ui;

import engine.DynamicField;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for a test case's dynamic fields (CSRF tokens, nonces, anti-forgery
 * headers…). Lets the tester declare how each volatile value should be rewritten
 * before the request is replayed under another identity, so the server doesn't
 * reject the replay for a stale token (#2 / spec §7).
 */
public class DynamicFieldsDialog extends JDialog {

    private final FieldTableModel model;
    private boolean confirmed = false;

    public DynamicFieldsDialog(Frame parent, String testCaseLabel, List<DynamicField> initial) {
        super(parent, "Dynamic Fields — " + testCaseLabel, true);
        this.model = new FieldTableModel(initial);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel intro = new JLabel("<html>Rewrite volatile values (CSRF tokens, nonces…) before each replay so "
            + "the request isn't rejected for a stale token.<br>"
            + "<b>REMOVE</b> strips it · <b>STATIC</b> sets a fixed value · "
            + "<b>FROM_COOKIE/HEADER</b> copies from the running account's session.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        root.add(intro, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Combo editors for the enum columns
        table.getColumnModel().getColumn(1).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(DynamicField.Location.values())));
        table.getColumnModel().getColumn(2).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(DynamicField.Strategy.values())));
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(600, 220));
        root.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = new JButton("+ Add");
        JButton remove = new JButton("- Remove");
        add.addActionListener(e -> model.add());
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                if (table.isEditing()) table.getCellEditor().stopCellEditing();
                model.remove(r);
            }
        });
        left.add(add);
        left.add(remove);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            confirmed = true;
            setVisible(false);
        });
        cancel.addActionListener(e -> setVisible(false));
        right.add(cancel);
        right.add(ok);

        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(parent);
    }

    public boolean isConfirmed() { return confirmed; }

    /** The edited list (rows with a blank name are dropped). */
    public List<DynamicField> getFields() {
        List<DynamicField> out = new ArrayList<>();
        for (DynamicField f : model.rows) {
            if (f.name() != null && !f.name().isBlank()) out.add(f);
        }
        return out;
    }

    // ---- table model ---------------------------------------------------

    private static final class FieldTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Field name", "Location", "Strategy", "Value / Source"};
        private final List<DynamicField> rows = new ArrayList<>();

        FieldTableModel(List<DynamicField> initial) {
            if (initial != null) rows.addAll(initial);
        }

        void add() {
            rows.add(new DynamicField("", DynamicField.Location.BODY_PARAM,
                DynamicField.Strategy.REMOVE, ""));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int i) {
            if (i < 0 || i >= rows.size()) return;
            rows.remove(i);
            fireTableRowsDeleted(i, i);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            DynamicField f = rows.get(r);
            return switch (c) {
                case 0 -> f.name();
                case 1 -> f.location();
                case 2 -> f.strategy();
                case 3 -> f.value();
                default -> "";
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            DynamicField f = rows.get(r);
            String name = f.name();
            DynamicField.Location loc = f.location();
            DynamicField.Strategy str = f.strategy();
            String val = f.value();
            switch (c) {
                case 0 -> name = v != null ? v.toString() : "";
                case 1 -> loc = (v instanceof DynamicField.Location l) ? l
                                : DynamicField.Location.valueOf(v.toString());
                case 2 -> str = (v instanceof DynamicField.Strategy s) ? s
                                : DynamicField.Strategy.valueOf(v.toString());
                case 3 -> val = v != null ? v.toString() : "";
            }
            rows.set(r, new DynamicField(name, loc, str, val));
            fireTableRowsUpdated(r, r);
        }
    }
}
