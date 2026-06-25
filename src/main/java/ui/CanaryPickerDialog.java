package ui;

import db.TestCaseRepository.TestCaseRow;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A searchable picker for choosing an account's canary test case.
 *
 * The old picker was a single combo of "METHOD host path" strings — unworkable
 * once there are many requests or several with near-identical URLs. This dialog
 * shows a filterable table (Method · Host · Path · Name) with a live search box,
 * so the right request is easy to find and disambiguate.
 */
final class CanaryPickerDialog extends JDialog {

    private final List<TestCaseRow> all;
    private final List<TestCaseRow> filtered = new ArrayList<>();
    private final PickerModel model = new PickerModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(28);

    private TestCaseRow result; // null = cancelled

    private CanaryPickerDialog(Window owner, List<TestCaseRow> testCases, Long preselectedId) {
        super(owner, "Select Canary Request", ModalityType.APPLICATION_MODAL);
        this.all = testCases;
        this.filtered.addAll(testCases);

        buildUI();
        applyFilter("");
        if (preselectedId != null) selectById(preselectedId);

        setSize(640, 420);
        setLocationRelativeTo(owner);
    }

    /** Opens the dialog and returns the chosen test case, or null if cancelled. */
    static TestCaseRow choose(Component parent, List<TestCaseRow> testCases, Long preselectedId) {
        Window w = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        CanaryPickerDialog dlg = new CanaryPickerDialog(w, testCases, preselectedId);
        dlg.setVisible(true);
        return dlg.result;
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // --- Search bar ---
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("🔍"), BorderLayout.WEST);
        search.setToolTipText("Type to filter by method, host, path or name");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(search.getText()); }
        });
        // Enter on the search box confirms the current selection.
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) confirm();
                else if (e.getKeyCode() == KeyEvent.VK_DOWN) table.requestFocusInWindow();
            }
        });
        top.add(search, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        // --- Table ---
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);   // method
        table.getColumnModel().getColumn(1).setPreferredWidth(150);  // host
        table.getColumnModel().getColumn(2).setPreferredWidth(230);  // path
        table.getColumnModel().getColumn(3).setPreferredWidth(150);  // name
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) confirm();
            }
        });
        root.add(new JScrollPane(table), BorderLayout.CENTER);

        // --- Buttons ---
        JButton ok = new JButton("Set Canary");
        ok.setFont(ok.getFont().deriveFont(Font.BOLD));
        ok.addActionListener(e -> confirm());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> { result = null; dispose(); });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.add(cancel);
        btns.add(ok);
        root.add(btns, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        SwingUtilities.invokeLater(search::requestFocusInWindow);
    }

    private void confirm() {
        int row = table.getSelectedRow();
        if (row < 0 && !filtered.isEmpty()) row = 0; // default to the top match
        if (row >= 0 && row < filtered.size()) {
            result = filtered.get(row);
            dispose();
        }
    }

    private void applyFilter(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        filtered.clear();
        for (TestCaseRow tc : all) {
            if (needle.isEmpty() || haystack(tc).contains(needle)) filtered.add(tc);
        }
        model.fireTableDataChanged();
        if (!filtered.isEmpty()) table.setRowSelectionInterval(0, 0);
    }

    private void selectById(long id) {
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id() == id) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private static String haystack(TestCaseRow tc) {
        return (nz(tc.method()) + " " + nz(tc.host()) + " " + nz(path(tc.url()))
              + " " + nz(tc.name())).toLowerCase();
    }

    private static String path(String url) {
        if (url == null) return "/";
        try {
            var uri = new java.net.URI(url);
            String p = uri.getPath();
            String query = uri.getQuery();
            if (p == null || p.isEmpty()) p = "/";
            return query != null ? p + "?" + query : p;
        } catch (Exception e) {
            return url;
        }
    }

    private static String nz(String s) { return s != null ? s : ""; }

    private final class PickerModel extends AbstractTableModel {
        private final String[] cols = {"Method", "Host", "Path", "Name"};
        @Override public int getRowCount() { return filtered.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            TestCaseRow tc = filtered.get(r);
            return switch (c) {
                case 0 -> tc.method();
                case 1 -> tc.host();
                case 2 -> path(tc.url());
                case 3 -> tc.name() != null ? tc.name() : "";
                default -> "";
            };
        }
        @Override public boolean isCellEditable(int r, int c) { return false; }
    }
}
