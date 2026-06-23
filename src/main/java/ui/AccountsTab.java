package ui;

import burp.api.montoya.MontoyaApi;
import db.AccountRepository;
import db.AccountRepository.AccountRecord;
import db.TestCaseRepository;
import db.TestCaseRepository.TestCaseRow;

import com.google.gson.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    // Accounts tree (left side): folders + account leaves
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Accounts");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree accountTree = new JTree(treeModel);
    private static final DataFlavor ACCT_FLAVOR = new DataFlavor(long[].class, "BAC account IDs");
    static final String[] FOLDER_COLORS = {"RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE"};
    private List<AccountRepository.AccountFolder> allFolders = new ArrayList<>();

    /** id == null → Uncategorized, otherwise a real account-folder id. */
    private record FolderNode(Long id, String name, String color) {
        @Override public String toString() { return name; }
    }

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

        accountTree.setRootVisible(false);
        accountTree.setShowsRootHandles(true);
        accountTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        accountTree.setCellRenderer(new AccountTreeRenderer());
        accountTree.setRowHeight(24);
        setupTreeDragDrop();

        buildLayout();
        wireEvents();
        wireTreeContextMenu();
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
        leftPanel.add(new JScrollPane(accountTree), BorderLayout.CENTER);

        JPanel listButtons = new JPanel(new GridLayout(2, 1, 0, 4));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton newBtn = new JButton("+ Account");
        JButton folderBtn = new JButton("+ Folder");
        JButton delBtn = new JButton("Delete");
        newBtn.addActionListener(e -> { accountTree.clearSelection(); clearEditor(); });
        folderBtn.addActionListener(e -> promptCreateFolder());
        delBtn.addActionListener(e -> deleteSelected());
        row1.add(newBtn); row1.add(folderBtn); row1.add(delBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton exportBtn = new JButton("Export…");
        JButton importBtn = new JButton("Import…");
        exportBtn.addActionListener(e -> exportAccounts());
        importBtn.addActionListener(e -> importAccounts());
        row2.add(exportBtn); row2.add(importBtn);

        listButtons.add(row1);
        listButtons.add(row2);
        leftPanel.add(listButtons, BorderLayout.SOUTH);

        // --- Right panel: scrollable editor + fixed action bar ---
        JPanel editorPanel = buildEditor();
        JScrollPane editorScroll = new JScrollPane(editorPanel);
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel rightSide = new JPanel(new BorderLayout());
        rightSide.add(editorScroll, BorderLayout.CENTER);
        rightSide.add(buildActionBar(), BorderLayout.SOUTH); // always visible, never scrolled away

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSide);
        split.setDividerLocation(200);
        split.setDividerSize(4);

        add(split, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(this);
    }

    /** Fixed bottom bar holding Save/Cancel so they are never scrolled out of view. */
    private JPanel buildActionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            UIManager.getColor("Separator.foreground")));
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        bar.add(cancelBtn);
        bar.add(saveBtn);
        return bar;
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
        JLabel note = new JLabel("The canary request is sent before each run to confirm the session is still valid.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        fullRow.gridy = row++;
        p.add(note, fullRow);

        // Filler so content stays top-aligned within the scroll viewport
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0; filler.gridy = row; filler.gridwidth = 2;
        filler.weighty = 1.0; filler.fill = GridBagConstraints.BOTH;
        p.add(Box.createGlue(), filler);

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

        JScrollPane tblScroll = new JScrollPane(tbl);
        tblScroll.setPreferredSize(new Dimension(420, 120));

        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(tblScroll, BorderLayout.CENTER);
        panel.add(btns, BorderLayout.SOUTH);
        return panel;
    }

    // ---- Events --------------------------------------------------------

    private void wireEvents() {
        // Tree selection → populate editor when an account leaf is selected
        accountTree.addTreeSelectionListener(e -> {
            AccountRecord sel = selectedAccount();
            if (sel != null) populateEditor(sel);
        });

        // Save
        saveBtn.addActionListener(e -> saveCurrentAccount());

        // Cancel
        cancelBtn.addActionListener(e -> {
            AccountRecord sel = selectedAccount();
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
                List<AccountRepository.AccountFolder> folders = accountRepo.getFolders();
                List<TestCaseRow> tcs = tcRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    allAccounts = accounts;
                    allFolders = folders;
                    allTestCases = tcs;
                    AccountRecord prev = selectedAccount();
                    rebuildTree(accounts, folders);
                    if (prev != null) selectAccountById(prev.id());
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Load accounts failed: " + ex.getMessage());
            }
        });
    }

    private void rebuildTree(List<AccountRecord> accounts,
                             List<AccountRepository.AccountFolder> folders) {
        treeRoot.removeAllChildren();

        // Folder id → node (plus a null-keyed "Uncategorized" node)
        Map<Long, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();
        DefaultMutableTreeNode uncategorized =
            new DefaultMutableTreeNode(new FolderNode(null, "Uncategorized", null));
        treeRoot.add(uncategorized);
        folderNodes.put(null, uncategorized);

        for (var f : folders) {
            DefaultMutableTreeNode n =
                new DefaultMutableTreeNode(new FolderNode(f.id(), f.name(), f.color()));
            folderNodes.put(f.id(), n);
            treeRoot.add(n);
        }
        for (var a : accounts) {
            DefaultMutableTreeNode parent = folderNodes.getOrDefault(a.folderId(), uncategorized);
            parent.add(new DefaultMutableTreeNode(a));
        }
        treeModel.reload();
        for (int i = 0; i < accountTree.getRowCount(); i++) accountTree.expandRow(i);
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
        // New accounts inherit the currently selected folder (if any).
        Long targetFolderId = (acctId == null) ? selectedFolderId() : null;

        bg.submit(() -> {
            try {
                final long savedId;
                if (acctId == null) {
                    savedId = accountRepo.create(name, roleDesc, cookies, headers, expectedAccess);
                    if (canaryId != null) accountRepo.setCanary(savedId, canaryId);
                    if (targetFolderId != null) accountRepo.moveToFolder(savedId, targetFolderId);
                } else {
                    accountRepo.update(acctId, name, roleDesc, cookies, headers, expectedAccess);
                    accountRepo.setCanary(acctId, canaryId);
                    savedId = acctId;
                }
                List<AccountRecord> accounts = accountRepo.getAll();
                List<AccountRepository.AccountFolder> folders = accountRepo.getFolders();
                SwingUtilities.invokeLater(() -> {
                    allAccounts = accounts;
                    allFolders = folders;
                    rebuildTree(accounts, folders);
                    selectAccountById(savedId);
                    editorTitle.setText("Saved ✓  —  " + name);
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Save account failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(AccountsTab.this,
                        "Save failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void deleteSelected() {
        // Delete the selected account, or the selected folder (accounts move to Uncategorized).
        AccountRecord acct = selectedAccount();
        if (acct != null) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete account \"" + acct.name() + "\"?",
                "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            long id = acct.id();
            bg.submit(() -> {
                try {
                    accountRepo.delete(id);
                    SwingUtilities.invokeLater(() -> { clearEditor(); loadAccounts(); });
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Delete account failed: " + ex.getMessage());
                }
            });
            return;
        }
        FolderNode fn = selectedFolderNode();
        if (fn != null && fn.id() != null) deleteFolder(fn);
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

    // ---- Tree helpers --------------------------------------------------

    private AccountRecord selectedAccount() {
        TreePath p = accountTree.getSelectionPath();
        if (p == null) return null;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof AccountRecord a ? a : null;
    }

    private FolderNode selectedFolderNode() {
        TreePath p = accountTree.getSelectionPath();
        if (p == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getLastPathComponent();
        Object o = node.getUserObject();
        if (o instanceof FolderNode fn) return fn;
        // If an account leaf is selected, treat its parent folder as context.
        if (o instanceof AccountRecord) {
            Object parent = ((DefaultMutableTreeNode) node.getParent()).getUserObject();
            if (parent instanceof FolderNode fn) return fn;
        }
        return null;
    }

    /** Folder id implied by the current selection (folder node or an account's parent). */
    private Long selectedFolderId() {
        FolderNode fn = selectedFolderNode();
        return fn != null ? fn.id() : null;
    }

    private void selectAccountById(long id) {
        for (int i = 0; i < accountTree.getRowCount(); i++) {
            TreePath path = accountTree.getPathForRow(i);
            Object o = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (o instanceof AccountRecord a && a.id() == id) {
                accountTree.setSelectionPath(path);
                accountTree.scrollPathToVisible(path);
                return;
            }
        }
    }

    // ---- Folder operations ---------------------------------------------

    private void promptCreateFolder() {
        String name = JOptionPane.showInputDialog(this, "Folder name:", "New Account Folder",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        bg.submit(() -> {
            try { accountRepo.createFolder(name.trim()); SwingUtilities.invokeLater(this::loadAccounts); }
            catch (Exception ex) { api.logging().logToError("[BAC] Create account folder: " + ex.getMessage()); }
        });
    }

    private void deleteFolder(FolderNode fn) {
        if (fn.id() == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete folder \"" + fn.name() + "\"?\nAccounts inside move to Uncategorized.",
            "Delete Folder", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        long id = fn.id();
        bg.submit(() -> {
            try { accountRepo.deleteFolder(id); SwingUtilities.invokeLater(this::loadAccounts); }
            catch (Exception ex) { api.logging().logToError("[BAC] Delete account folder: " + ex.getMessage()); }
        });
    }

    private void renameFolder(FolderNode fn) {
        if (fn.id() == null) return;
        String name = (String) JOptionPane.showInputDialog(this, "Rename folder:", "Rename",
            JOptionPane.PLAIN_MESSAGE, null, null, fn.name());
        if (name == null || name.isBlank()) return;
        long id = fn.id();
        bg.submit(() -> {
            try { accountRepo.renameFolder(id, name.trim()); SwingUtilities.invokeLater(this::loadAccounts); }
            catch (Exception ex) { api.logging().logToError("[BAC] Rename account folder: " + ex.getMessage()); }
        });
    }

    private void setFolderColor(FolderNode fn, String color) {
        if (fn.id() == null) return;
        long id = fn.id();
        bg.submit(() -> {
            try { accountRepo.setFolderColor(id, color); SwingUtilities.invokeLater(this::loadAccounts); }
            catch (Exception ex) { api.logging().logToError("[BAC] Set account folder color: " + ex.getMessage()); }
        });
    }

    private void moveAccountToFolder(long accountId, Long folderId) {
        bg.submit(() -> {
            try { accountRepo.moveToFolder(accountId, folderId); SwingUtilities.invokeLater(this::loadAccounts); }
            catch (Exception ex) { api.logging().logToError("[BAC] Move account: " + ex.getMessage()); }
        });
    }

    private void wireTreeContextMenu() {
        accountTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = accountTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) accountTree.setSelectionPath(path);

                JPopupMenu popup = new JPopupMenu();
                AccountRecord acct = selectedAccount();
                FolderNode fn = selectedFolderNode();

                if (acct != null) {
                    JMenu moveMenu = new JMenu("Move to Folder");
                    JMenuItem unc = new JMenuItem("Uncategorized");
                    unc.addActionListener(a -> moveAccountToFolder(acct.id(), null));
                    moveMenu.add(unc);
                    for (var f : allFolders) {
                        JMenuItem mi = new JMenuItem(f.name());
                        mi.addActionListener(a -> moveAccountToFolder(acct.id(), f.id()));
                        moveMenu.add(mi);
                    }
                    popup.add(moveMenu);
                    JMenuItem del = new JMenuItem("Delete Account");
                    del.addActionListener(a -> deleteSelected());
                    popup.add(del);
                    popup.addSeparator();
                }

                JMenuItem newFolder = new JMenuItem("New Folder");
                newFolder.addActionListener(a -> promptCreateFolder());
                popup.add(newFolder);

                if (fn != null && fn.id() != null) {
                    JMenuItem rename = new JMenuItem("Rename Folder");
                    rename.addActionListener(a -> renameFolder(fn));
                    popup.add(rename);

                    JMenu colorMenu = new JMenu("Set Folder Color");
                    for (String tag : FOLDER_COLORS) {
                        JMenuItem ci = new JMenuItem(tag.charAt(0) + tag.substring(1).toLowerCase());
                        ci.addActionListener(a -> setFolderColor(fn, tag));
                        colorMenu.add(ci);
                    }
                    colorMenu.addSeparator();
                    JMenuItem clear = new JMenuItem("Clear Color");
                    clear.addActionListener(a -> setFolderColor(fn, null));
                    colorMenu.add(clear);
                    popup.add(colorMenu);

                    JMenuItem delF = new JMenuItem("Delete Folder");
                    delF.addActionListener(a -> deleteFolder(fn));
                    popup.add(delF);
                }
                popup.show(accountTree, e.getX(), e.getY());
            }
        });
    }

    // ---- Drag-and-drop: move account leaves onto folder nodes ----------

    private void setupTreeDragDrop() {
        accountTree.setDragEnabled(true);
        accountTree.setDropMode(DropMode.ON);
        accountTree.setTransferHandler(new TransferHandler() {
            @Override protected Transferable createTransferable(JComponent c) {
                AccountRecord a = selectedAccount();
                if (a == null) return null;
                long[] ids = { a.id() };
                return new Transferable() {
                    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{ACCT_FLAVOR}; }
                    @Override public boolean isDataFlavorSupported(DataFlavor f) { return ACCT_FLAVOR.equals(f); }
                    @Override public Object getTransferData(DataFlavor f) { return ids; }
                };
            }
            @Override public int getSourceActions(JComponent c) { return MOVE; }
            @Override public boolean canImport(TransferSupport s) {
                return s.isDrop() && s.isDataFlavorSupported(ACCT_FLAVOR);
            }
            @Override public boolean importData(TransferSupport s) {
                if (!s.isDrop()) return false;
                JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
                TreePath target = dl.getPath();
                if (target == null) return false;
                Object o = ((DefaultMutableTreeNode) target.getLastPathComponent()).getUserObject();
                // Allow dropping on a folder node, or on an account (→ its parent folder).
                Long folderId;
                if (o instanceof FolderNode fn) folderId = fn.id();
                else if (o instanceof AccountRecord) {
                    Object parent = ((DefaultMutableTreeNode) ((DefaultMutableTreeNode)
                        target.getLastPathComponent()).getParent()).getUserObject();
                    folderId = (parent instanceof FolderNode pf) ? pf.id() : null;
                } else return false;
                try {
                    long[] ids = (long[]) s.getTransferable().getTransferData(ACCT_FLAVOR);
                    for (long id : ids) moveAccountToFolder(id, folderId);
                    return true;
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Account DnD: " + ex.getMessage());
                    return false;
                }
            }
        });
    }

    // ---- Export / Import -----------------------------------------------

    private void exportAccounts() {
        int ok = JOptionPane.showConfirmDialog(this,
            "Export includes auth material (cookies/tokens) in plain text.\nContinue?",
            "Export Accounts", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("accounts.bacaccounts.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        bg.submit(() -> {
            try {
                List<AccountRecord> accounts = accountRepo.getAll();
                List<AccountRepository.AccountFolder> folders = accountRepo.getFolders();
                JsonObject root = new JsonObject();
                JsonArray fArr = new JsonArray();
                for (var f : folders) {
                    JsonObject fo = new JsonObject();
                    fo.addProperty("id", f.id());
                    fo.addProperty("name", f.name());
                    if (f.color() != null) fo.addProperty("color", f.color());
                    fArr.add(fo);
                }
                root.add("folders", fArr);
                JsonArray aArr = new JsonArray();
                for (var a : accounts) {
                    JsonObject ao = new JsonObject();
                    ao.addProperty("name", a.name());
                    if (a.roleDesc() != null) ao.addProperty("role", a.roleDesc());
                    ao.addProperty("expectedAccess", a.expectedAccess());
                    if (a.folderId() != null) ao.addProperty("folderId", a.folderId());
                    ao.add("cookies", mapToJson(a.cookies()));
                    ao.add("headers", mapToJson(a.headers()));
                    aArr.add(ao);
                }
                root.add("accounts", aArr);
                String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
                Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Exported " + accounts.size() + " account(s).", "Export", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Export accounts failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void importAccounts() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] files = fc.getSelectedFiles();
        bg.submit(() -> {
            int imported = 0;
            try {
                for (File file : files) {
                    String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                    // Rebuild folders, remapping old ids → new ids by name.
                    Map<Long, Long> folderIdMap = new HashMap<>();
                    Map<String, Long> existingByName = new HashMap<>();
                    for (var f : accountRepo.getFolders()) existingByName.put(f.name(), f.id());
                    if (root.has("folders")) {
                        for (JsonElement fe : root.getAsJsonArray("folders")) {
                            JsonObject fo = fe.getAsJsonObject();
                            String name = fo.get("name").getAsString();
                            long newId = existingByName.containsKey(name)
                                ? existingByName.get(name)
                                : accountRepo.createFolder(name);
                            existingByName.put(name, newId);
                            if (fo.has("color"))
                                accountRepo.setFolderColor(newId, fo.get("color").getAsString());
                            if (fo.has("id")) folderIdMap.put(fo.get("id").getAsLong(), newId);
                        }
                    }
                    if (root.has("accounts")) {
                        for (JsonElement ae : root.getAsJsonArray("accounts")) {
                            JsonObject ao = ae.getAsJsonObject();
                            String name = ao.has("name") ? ao.get("name").getAsString() : "imported";
                            String role = ao.has("role") ? ao.get("role").getAsString() : null;
                            String access = ao.has("expectedAccess") ? ao.get("expectedAccess").getAsString() : "UNKNOWN";
                            Long folderId = ao.has("folderId") ? folderIdMap.get(ao.get("folderId").getAsLong()) : null;
                            Map<String, String> cookies = jsonToMap(ao.getAsJsonObject("cookies"));
                            Map<String, String> headers = jsonToMap(ao.getAsJsonObject("headers"));
                            accountRepo.createInFolder(name, role, cookies, headers, access, folderId);
                            imported++;
                        }
                    }
                }
                final int n = imported;
                SwingUtilities.invokeLater(() -> {
                    loadAccounts();
                    JOptionPane.showMessageDialog(this, "Imported " + n + " account(s).",
                        "Import", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Import accounts failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Import failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private static JsonObject mapToJson(Map<String, String> map) {
        JsonObject o = new JsonObject();
        if (map != null) map.forEach(o::addProperty);
        return o;
    }

    private static Map<String, String> jsonToMap(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o != null) for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
        return m;
    }

    // ---- Inner: tree cell renderer ------------------------------------

    private static class AccountTreeRenderer extends DefaultTreeCellRenderer {
        private static Color tagColor(String tag) {
            if (tag == null) return null;
            return switch (tag.toUpperCase()) {
                case "RED"    -> new Color(0xE0, 0x6C, 0x6C);
                case "ORANGE" -> new Color(0xE0, 0xA0, 0x55);
                case "YELLOW" -> new Color(0xC9, 0xB8, 0x3A);
                case "GREEN"  -> new Color(0x5F, 0xBF, 0x5F);
                case "BLUE"   -> new Color(0x5F, 0x96, 0xE0);
                case "PURPLE" -> new Color(0xB0, 0x7C, 0xE0);
                default       -> null;
            };
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Object o = (value instanceof DefaultMutableTreeNode n) ? n.getUserObject() : null;
            if (o instanceof AccountRecord a) {
                setText(a.id() + ":" + a.name());
                setIcon(UIManager.getIcon("FileView.fileIcon"));
                String access = a.expectedAccess() != null ? a.expectedAccess() : "UNKNOWN";
                String role = a.roleDesc();
                StringBuilder tip = new StringBuilder();
                tip.append("Account #").append(a.id()).append("  ·  ").append(a.name());
                if (role != null && !role.isBlank()) tip.append("  ·  Role: ").append(role.trim());
                tip.append("  ·  Expected access: ").append(access);
                setToolTipText(tip.toString());
            } else if (o instanceof FolderNode fn) {
                setText(fn.name());
                setIcon(UIManager.getIcon("FileView.directoryIcon"));
                setToolTipText(null);
                if (!selected) {
                    Color c = tagColor(fn.color());
                    if (c != null) setForeground(c);
                }
            }
            return this;
        }
    }
}
