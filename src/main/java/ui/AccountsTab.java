package ui;

import burp.api.montoya.MontoyaApi;
import db.AccountRepository;
import db.AccountRepository.AccountRecord;
import db.TestCaseRepository;
import db.TestCaseRepository.TestCaseRow;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accounts tab — manages user identities with auth material (cookies + headers).
 *
 * Layout: left = accounts list, right = inline editor.
 * Also supports pre-filling the editor from a captured HTTP request's session headers.
 */
public class AccountsTab extends JPanel {

    private final MontoyaApi api;
    private final AccountRepository accountRepo;
    private final TestCaseRepository tcRepo;

    // Accounts list (left side)
    private final DefaultListModel<AccountRecord> listModel = new DefaultListModel<>();
    private final JList<AccountRecord> accountList;

    // Editor fields (right side)
    private final JTextField nameField       = new JTextField(28);
    private final JTextField roleField       = new JTextField(28);
    private final JComboBox<String> accessCombo = new JComboBox<>(new String[]{"UNKNOWN","ALLOWED","DENIED"});
    private final DefaultTableModel cookieModel = editableTableModel("Cookie Name", "Value");
    private final DefaultTableModel headerModel = editableTableModel("Header Name", "Value");
    private final JTable cookieTable = new JTable(cookieModel);
    private final JTable headerTable = new JTable(headerModel);
    private final JLabel canaryLabel = new JLabel("Not set");
    private final JButton canarySelectBtn = new JButton("Select…");
    private final JButton canaryClearBtn  = new JButton("Clear");
    private final JButton saveBtn         = new JButton("Save");
    private final JButton cancelBtn       = new JButton("Cancel");
    private final JLabel editorTitle      = new JLabel("New Account");

    // State
    private Long editingAccountId = null; // null = creating new
    private Long selectedCanaryId = null;
    private List<AccountRecord> allAccounts = new ArrayList<>();
    private List<TestCaseRow>  allTestCases = new ArrayList<>();

    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-accounts");
        t.setDaemon(true);
        return t;
    });

    public AccountsTab(MontoyaApi api, AccountRepository accountRepo, TestCaseRepository tcRepo) {
        super(new BorderLayout());
        this.api = api;
        this.accountRepo = accountRepo;
        this.tcRepo = tcRepo;

        accountList = new JList<>(listModel);
        accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountList.setCellRenderer(new AccountListRenderer());
        accountList.setFixedCellHeight(32);

        buildLayout();
        wireEvents();
        clearEditor();
        loadAccounts();
    }

    // ---- Public API ----------------------------------------------------

    /** Pre-fill the editor from an extracted HTTP session (cookies + headers). */
    public void prefillFromSession(Map<String, String> cookies,
                                   Map<String, String> headers,
                                   String suggestedName) {
        SwingUtilities.invokeLater(() -> {
            clearEditor();
            editingAccountId = null;
            editorTitle.setText("New Account (from session)");
            nameField.setText(suggestedName != null ? suggestedName : "");
            populateCookieTable(cookies);
            populateHeaderTable(headers);
            nameField.requestFocusInWindow();
        });
    }

    /** Refresh the accounts list from DB. */
    public void refresh() {
        loadAccounts();
    }

    // ---- Layout --------------------------------------------------------

    private void buildLayout() {
        // --- Left panel: accounts list ---
        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        leftPanel.setMinimumSize(new Dimension(180, 0));
        leftPanel.setPreferredSize(new Dimension(200, 0));

        JLabel listTitle = bold("Accounts");
        leftPanel.add(listTitle, BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(accountList), BorderLayout.CENTER);

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton newBtn = new JButton("+ New");
        JButton delBtn = new JButton("Delete");
        newBtn.addActionListener(e -> { accountList.clearSelection(); clearEditor(); });
        delBtn.addActionListener(e -> deleteSelected());
        listButtons.add(newBtn);
        listButtons.add(delBtn);
        leftPanel.add(listButtons, BorderLayout.SOUTH);

        // --- Right panel: editor ---
        JPanel editorPanel = buildEditor();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, new JScrollPane(editorPanel));
        split.setDividerLocation(200);
        split.setDividerSize(4);

        add(split, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(this);
    }

    private JPanel buildEditor() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(6, 0, 2, 10);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0; fc.insets = new Insets(6, 0, 2, 0);

        GridBagConstraints fullRow = new GridBagConstraints();
        fullRow.gridx = 0; fullRow.gridwidth = 2;
        fullRow.fill = GridBagConstraints.BOTH; fullRow.weightx = 1.0;
        fullRow.insets = new Insets(6, 0, 2, 0);

        int row = 0;

        // Title
        editorTitle.setFont(editorTitle.getFont().deriveFont(Font.BOLD, 14f));
        fullRow.gridy = row++;
        p.add(editorTitle, fullRow);

        lc.gridy = row; p.add(new JLabel("Name:"), lc);
        fc.gridy = row++; p.add(nameField, fc);

        lc.gridy = row; p.add(new JLabel("Role / description:"), lc);
        fc.gridy = row++; p.add(roleField, fc);

        lc.gridy = row; p.add(new JLabel("Expected access:"), lc);
        fc.gridy = row++; p.add(accessCombo, fc);

        // Separator
        fullRow.gridy = row++; fullRow.insets = new Insets(12, 0, 4, 0);
        p.add(sectionLabel("Cookies (auth session)"), fullRow);
        fullRow.insets = new Insets(2, 0, 2, 0);

        // Cookie table
        fullRow.gridy = row++; fullRow.weighty = 0.4; fullRow.fill = GridBagConstraints.BOTH;
        p.add(tableWithButtons(cookieTable, cookieModel), fullRow);
        fullRow.weighty = 0;

        // Separator
        fullRow.gridy = row++; fullRow.insets = new Insets(10, 0, 4, 0);
        p.add(sectionLabel("Additional Session Headers"), fullRow);
        fullRow.insets = new Insets(2, 0, 2, 0);

        // Header table
        fullRow.gridy = row++; fullRow.weighty = 0.4; fullRow.fill = GridBagConstraints.BOTH;
        p.add(tableWithButtons(headerTable, headerModel), fullRow);
        fullRow.weighty = 0;

        // Canary
        fullRow.gridy = row++; fullRow.insets = new Insets(10, 0, 4, 0);
        p.add(sectionLabel("Canary Request"), fullRow);
        fullRow.insets = new Insets(2, 0, 2, 0);

        JPanel canaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        canaryLabel.setFont(canaryLabel.getFont().deriveFont(Font.ITALIC));
        canaryRow.add(new JLabel("Set to:"));
        canaryRow.add(canaryLabel);
        canaryRow.add(canarySelectBtn);
        canaryRow.add(canaryClearBtn);
        fullRow.gridy = row++;
        p.add(canaryRow, fullRow);

        // Note
        JLabel note = new JLabel("<html><small>The canary request is sent before each run to confirm the session is still valid.</small></html>");
        fullRow.gridy = row++;
        p.add(note, fullRow);

        // Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        btnRow.add(cancelBtn);
        btnRow.add(saveBtn);
        fullRow.gridy = row++; fullRow.insets = new Insets(12, 0, 0, 0);
        p.add(btnRow, fullRow);

        return p;
    }

    private JPanel tableWithButtons(JTable tbl, DefaultTableModel model) {
        tbl.setRowHeight(22);
        tbl.getTableHeader().setReorderingAllowed(false);
        tbl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tbl.getColumnModel().getColumn(0).setPreferredWidth(160);
        tbl.getColumnModel().getColumn(1).setPreferredWidth(300);

        JButton addRow = new JButton("+");
        addRow.setMargin(new Insets(1, 6, 1, 6));
        addRow.setToolTipText("Add row");
        addRow.addActionListener(e -> model.addRow(new Object[]{"", ""}));

        JButton delRow = new JButton("−");
        delRow.setMargin(new Insets(1, 6, 1, 6));
        delRow.setToolTipText("Remove selected row");
        delRow.addActionListener(e -> {
            int sel = tbl.getSelectedRow();
            if (sel >= 0) model.removeRow(sel);
        });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.add(addRow);
        btns.add(delRow);

        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(new JScrollPane(tbl), BorderLayout.CENTER);
        panel.add(btns, BorderLayout.SOUTH);
        return panel;
    }

    // ---- Events --------------------------------------------------------

    private void wireEvents() {
        // List selection → populate editor
        accountList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            AccountRecord sel = accountList.getSelectedValue();
            if (sel != null) populateEditor(sel);
        });

        // Save
        saveBtn.addActionListener(e -> saveCurrentAccount());

        // Cancel
        cancelBtn.addActionListener(e -> {
            AccountRecord sel = accountList.getSelectedValue();
            if (sel != null) populateEditor(sel); else clearEditor();
        });

        // Canary select
        canarySelectBtn.addActionListener(e -> selectCanary());

        // Canary clear
        canaryClearBtn.addActionListener(e -> {
            selectedCanaryId = null;
            canaryLabel.setText("Not set");
        });
    }

    // ---- DB operations -------------------------------------------------

    private void loadAccounts() {
        bg.submit(() -> {
            try {
                List<AccountRecord> accounts = accountRepo.getAll();
                List<TestCaseRow> tcs = tcRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    allAccounts = accounts;
                    allTestCases = tcs;
                    AccountRecord prev = accountList.getSelectedValue();
                    listModel.clear();
                    accounts.forEach(listModel::addElement);
                    // Re-select previously selected account
                    if (prev != null) {
                        for (int i = 0; i < listModel.size(); i++) {
                            if (listModel.get(i).id() == prev.id()) {
                                accountList.setSelectedIndex(i);
                                break;
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Load accounts failed: " + ex.getMessage());
            }
        });
    }

    private void saveCurrentAccount() {
        // Stop any in-progress cell edits
        if (cookieTable.isEditing()) cookieTable.getCellEditor().stopCellEditing();
        if (headerTable.isEditing()) headerTable.getCellEditor().stopCellEditing();

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Account name cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String roleDesc     = roleField.getText().trim();
        String expectedAccess = (String) accessCombo.getSelectedItem();
        Map<String, String> cookies = tableToMap(cookieModel);
        Map<String, String> headers = tableToMap(headerModel);
        Long canaryId = selectedCanaryId;
        Long acctId = editingAccountId;

        bg.submit(() -> {
            try {
                if (acctId == null) {
                    long newId = accountRepo.create(name, roleDesc, cookies, headers, expectedAccess);
                    if (canaryId != null) accountRepo.setCanary(newId, canaryId);
                } else {
                    accountRepo.update(acctId, name, roleDesc, cookies, headers, expectedAccess);
                    accountRepo.setCanary(acctId, canaryId);
                }
                SwingUtilities.invokeLater(this::loadAccounts);
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Save account failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(AccountsTab.this,
                        "Save failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void deleteSelected() {
        AccountRecord sel = accountList.getSelectedValue();
        if (sel == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete account \"" + sel.name() + "\"?",
            "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        long id = sel.id();
        bg.submit(() -> {
            try {
                accountRepo.delete(id);
                SwingUtilities.invokeLater(() -> { clearEditor(); loadAccounts(); });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Delete account failed: " + ex.getMessage());
            }
        });
    }

    private void selectCanary() {
        if (allTestCases.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No test cases available. Save some requests first.", "No Test Cases",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Build display strings
        String[] options = allTestCases.stream()
            .map(tc -> tc.method() + " " + tc.host() + extractPath(tc.url()))
            .toArray(String[]::new);
        String chosen = (String) JOptionPane.showInputDialog(this,
            "Select the canary test case:", "Set Canary",
            JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (chosen == null) return;
        int idx = java.util.Arrays.asList(options).indexOf(chosen);
        if (idx >= 0) {
            TestCaseRow tc = allTestCases.get(idx);
            selectedCanaryId = tc.id();
            canaryLabel.setText(tc.method() + " " + tc.host());
        }
    }

    // ---- Editor helpers ------------------------------------------------

    private void populateEditor(AccountRecord a) {
        editingAccountId = a.id();
        editorTitle.setText("Edit Account");
        nameField.setText(a.name());
        roleField.setText(a.roleDesc() != null ? a.roleDesc() : "");
        accessCombo.setSelectedItem(a.expectedAccess() != null ? a.expectedAccess() : "UNKNOWN");
        populateCookieTable(a.cookies());
        populateHeaderTable(a.headers());
        selectedCanaryId = a.canaryRequestId();
        if (selectedCanaryId != null) {
            allTestCases.stream().filter(tc -> tc.id() == selectedCanaryId).findFirst()
                .ifPresentOrElse(
                    tc -> canaryLabel.setText(tc.method() + " " + tc.host()),
                    () -> canaryLabel.setText("id=" + selectedCanaryId));
        } else {
            canaryLabel.setText("Not set");
        }
    }

    private void clearEditor() {
        editingAccountId = null;
        selectedCanaryId = null;
        editorTitle.setText("New Account");
        nameField.setText("");
        roleField.setText("");
        accessCombo.setSelectedItem("UNKNOWN");
        cookieModel.setRowCount(0);
        headerModel.setRowCount(0);
        canaryLabel.setText("Not set");
    }

    private void populateCookieTable(Map<String, String> cookies) {
        cookieModel.setRowCount(0);
        cookies.forEach((k, v) -> cookieModel.addRow(new Object[]{k, v}));
    }

    private void populateHeaderTable(Map<String, String> headers) {
        headerModel.setRowCount(0);
        headers.forEach((k, v) -> headerModel.addRow(new Object[]{k, v}));
    }

    private static Map<String, String> tableToMap(DefaultTableModel model) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object k = model.getValueAt(i, 0);
            Object v = model.getValueAt(i, 1);
            if (k != null && !k.toString().isBlank()) {
                map.put(k.toString().trim(), v != null ? v.toString() : "");
            }
        }
        return map;
    }

    private static DefaultTableModel editableTableModel(String col1, String col2) {
        return new DefaultTableModel(new String[]{col1, col2}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        return l;
    }

    private static String extractPath(String url) {
        if (url == null) return "/";
        try {
            var uri = new java.net.URI(url);
            String p = uri.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Exception e) {
            return url;
        }
    }

    // ---- Inner: list cell renderer ------------------------------------

    private static class AccountListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof AccountRecord a) {
                String access = a.expectedAccess();
                String badge = switch (access != null ? access : "UNKNOWN") {
                    case "DENIED"  -> " [DENIED]";
                    case "ALLOWED" -> " [ALLOWED]";
                    default        -> "";
                };
                setText(a.name() + badge);
                String role = a.roleDesc();
                if (role != null && !role.isBlank()) setToolTipText(role);
            }
            return this;
        }
    }
}
