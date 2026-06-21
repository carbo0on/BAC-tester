package ui;

import burp.api.montoya.MontoyaApi;
import capture.CaptureService;
import db.FolderRepository;
import db.FolderRepository.FolderRecord;
import db.TestCaseRepository;
import db.TestCaseRepository.TestCaseRow;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Library tab: left = folder tree, right = test cases table with danger-colored methods.
 * Supports: New Folder, Rename (F2/double-click), Move (right-click), Delete, multi-select.
 */
public class LibraryTab extends JPanel {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final MontoyaApi api;
    private final FolderRepository folderRepo;
    private final TestCaseRepository tcRepo;
    private final CaptureService captureService;

    // UI components
    private final JTree folderTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode treeRoot;
    private final TestCaseTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel;
    private final JPanel actionBar;

    // Background loader
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-library-loader");
        t.setDaemon(true);
        return t;
    });

    // Currently selected folder (null = Inbox, "ALL" sentinel = show all)
    private Long selectedFolderIdState = null; // null = Inbox
    private boolean showAll = false;

    // Callbacks wired by MainTab in Phase 5
    private Consumer<List<Long>> onAddToWorkingSet;
    private Consumer<Long>       onOpenInCompare;

    public void setOnAddToWorkingSet(Consumer<List<Long>> cb) { this.onAddToWorkingSet = cb; }
    public void setOnOpenInCompare(Consumer<Long> cb)         { this.onOpenInCompare = cb; }

    public LibraryTab(MontoyaApi api, FolderRepository folderRepo,
                      TestCaseRepository tcRepo, CaptureService captureService) {
        super(new BorderLayout());
        this.api = api;
        this.folderRepo = folderRepo;
        this.tcRepo = tcRepo;
        this.captureService = captureService;

        // --- Folder tree ---
        treeRoot = new DefaultMutableTreeNode("Library");
        treeModel = new DefaultTreeModel(treeRoot);
        folderTree = new JTree(treeModel);
        folderTree.setRootVisible(false);
        folderTree.setShowsRootHandles(true);
        folderTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        folderTree.setCellRenderer(new FolderTreeRenderer());

        // --- Test cases table ---
        tableModel = new TestCaseTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new MethodAwareRenderer());
        setupTableColumns();

        // --- Status label ---
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        // --- Action bar (shows on multi-select) ---
        actionBar = buildActionBar();

        // --- Layout ---
        JScrollPane treeScroll = new JScrollPane(folderTree);
        treeScroll.setMinimumSize(new Dimension(180, 0));
        treeScroll.setPreferredSize(new Dimension(200, 0));

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        rightPanel.add(buildRightToolbar(), BorderLayout.NORTH);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(actionBar, BorderLayout.CENTER);
        rightPanel.add(bottomBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightPanel);
        split.setDividerLocation(200);
        split.setDividerSize(4);

        add(split, BorderLayout.CENTER);

        // --- Wiring ---
        wireFolderTreeSelection();
        wireFolderTreeContextMenu();
        wireTableContextMenu();
        wireTableSelection();
        wireKeyBindings();
        wireInlineRename();

        // Register with capture service to refresh on new saves
        captureService.addOnSaveListener(this::refresh);

        // Load initial state
        refresh();
    }

    // ---- Public --------------------------------------------------------

    /** Reload folder tree + test case table from DB. */
    public void refresh() {
        loader.submit(() -> {
            try {
                List<FolderRecord> folders = folderRepo.getAllFolders();
                int inboxCount = folderRepo.countInbox();
                List<TestCaseRow> rows = loadRows();
                SwingUtilities.invokeLater(() -> {
                    rebuildTree(folders, inboxCount);
                    tableModel.setRows(rows);
                    updateStatus(rows.size());
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Library refresh failed: " + e.getMessage());
            }
        });
    }

    // ---- Tree ----------------------------------------------------------

    private void rebuildTree(List<FolderRecord> folders, int inboxCount) {
        treeRoot.removeAllChildren();

        DefaultMutableTreeNode inbox = new DefaultMutableTreeNode(
            new FolderNode(null, "Inbox (" + inboxCount + ")"));
        treeRoot.add(inbox);

        // Build hierarchy
        Map<Long, DefaultMutableTreeNode> nodeMap = new HashMap<>();
        for (FolderRecord fr : folders) {
            nodeMap.put(fr.id(), new DefaultMutableTreeNode(new FolderNode(fr.id(), fr.name())));
        }
        for (FolderRecord fr : folders) {
            DefaultMutableTreeNode node = nodeMap.get(fr.id());
            if (fr.parentId() == null) {
                treeRoot.add(node);
            } else {
                DefaultMutableTreeNode parent = nodeMap.get(fr.parentId());
                if (parent != null) parent.add(node);
                else treeRoot.add(node);
            }
        }

        treeModel.reload();
        // Re-expand all
        for (int i = 0; i < folderTree.getRowCount(); i++) folderTree.expandRow(i);

        // Re-select the current path
        selectFolderNode(selectedFolderIdState);
    }

    private void selectFolderNode(Long folderId) {
        for (int i = 0; i < folderTree.getRowCount(); i++) {
            TreePath path = folderTree.getPathForRow(i);
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node &&
                node.getUserObject() instanceof FolderNode fn &&
                Objects.equals(fn.id(), folderId)) {
                folderTree.setSelectionPath(path);
                return;
            }
        }
        // Default: select Inbox
        if (folderTree.getRowCount() > 0) folderTree.setSelectionRow(0);
    }

    private void wireFolderTreeSelection() {
        folderTree.addTreeSelectionListener(e -> {
            TreePath path = folderTree.getSelectionPath();
            if (path == null) return;
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node &&
                node.getUserObject() instanceof FolderNode fn) {
                selectedFolderIdState = fn.id();
                showAll = false;
                reloadTable();
            }
        });
    }

    private void wireFolderTreeContextMenu() {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem newFolder   = new JMenuItem("New Folder");
        JMenuItem renameFolder = new JMenuItem("Rename Folder");
        JMenuItem deleteFolder = new JMenuItem("Delete Folder");

        popup.add(newFolder);
        popup.add(renameFolder);
        popup.add(deleteFolder);

        folderTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = folderTree.getPathForLocation(e.getX(), e.getY());
                folderTree.setSelectionPath(path);
                FolderNode fn = selectedFolderNode();
                boolean isInbox = fn != null && fn.id() == null;
                renameFolder.setEnabled(!isInbox);
                deleteFolder.setEnabled(!isInbox);
                popup.show(folderTree, e.getX(), e.getY());
            }
        });

        newFolder.addActionListener(e -> {
            FolderNode parent = selectedFolderNode();
            Long parentId = parent != null ? parent.id() : null;
            String name = JOptionPane.showInputDialog(this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                loader.submit(() -> {
                    try {
                        folderRepo.createFolder(name.trim(), parentId);
                        SwingUtilities.invokeLater(this::refresh);
                    } catch (Exception ex) {
                        api.logging().logToError("[BAC] Create folder failed: " + ex.getMessage());
                    }
                });
            }
        });

        renameFolder.addActionListener(e -> {
            FolderNode fn = selectedFolderNode();
            if (fn == null || fn.id() == null) return;
            String newName = (String) JOptionPane.showInputDialog(this, "Rename folder:",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, fn.displayName());
            if (newName != null && !newName.isBlank()) {
                long id = fn.id();
                loader.submit(() -> {
                    try {
                        folderRepo.renameFolder(id, newName.trim());
                        SwingUtilities.invokeLater(this::refresh);
                    } catch (Exception ex) {
                        api.logging().logToError("[BAC] Rename folder failed: " + ex.getMessage());
                    }
                });
            }
        });

        deleteFolder.addActionListener(e -> {
            FolderNode fn = selectedFolderNode();
            if (fn == null || fn.id() == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete folder \"" + fn.displayName() + "\"?\nTest cases inside will be moved to Inbox.",
                "Delete Folder", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            long id = fn.id();
            loader.submit(() -> {
                try {
                    // Move test cases to Inbox first
                    for (TestCaseRow row : tcRepo.getByFolder(id)) {
                        tcRepo.moveToFolder(row.id(), null);
                    }
                    folderRepo.deleteFolder(id);
                    SwingUtilities.invokeLater(() -> {
                        selectedFolderIdState = null;
                        refresh();
                    });
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Delete folder failed: " + ex.getMessage());
                }
            });
        });
    }

    private FolderNode selectedFolderNode() {
        TreePath path = folderTree.getSelectionPath();
        if (path == null) return null;
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node &&
            node.getUserObject() instanceof FolderNode fn) {
            return fn;
        }
        return null;
    }

    // ---- Table ---------------------------------------------------------

    private void setupTableColumns() {
        var cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(30);  cm.getColumn(0).setMaxWidth(36);  // danger
        cm.getColumn(1).setPreferredWidth(70);  cm.getColumn(1).setMaxWidth(90);  // method
        cm.getColumn(2).setPreferredWidth(180);                                    // name
        cm.getColumn(3).setPreferredWidth(130);                                    // host
        cm.getColumn(4).setPreferredWidth(200);                                    // path
        cm.getColumn(5).setPreferredWidth(90);  cm.getColumn(5).setMaxWidth(110); // status
        cm.getColumn(6).setPreferredWidth(80);  cm.getColumn(6).setMaxWidth(90);  // size
        cm.getColumn(7).setPreferredWidth(130); cm.getColumn(7).setMaxWidth(150); // captured
    }

    private JPanel buildRightToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton newFolderBtn = new JButton("+ Folder");
        newFolderBtn.setToolTipText("Create a new top-level folder");
        newFolderBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                loader.submit(() -> {
                    try {
                        folderRepo.createFolder(name.trim(), null);
                        SwingUtilities.invokeLater(this::refresh);
                    } catch (Exception ex) {
                        api.logging().logToError("[BAC] Create folder failed: " + ex.getMessage());
                    }
                });
            }
        });
        bar.add(newFolderBtn);
        return bar;
    }

    private JPanel buildActionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        bar.setVisible(false); // hidden until rows are selected

        JLabel selLabel = new JLabel("0 selected");
        JButton addToWorkingSet = new JButton("Add to Working Set");
        addToWorkingSet.setToolTipText("Add selected test cases to Compare tab working set");
        addToWorkingSet.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0 || onAddToWorkingSet == null) return;
            List<Long> ids = new ArrayList<>();
            for (int r : rows) ids.add(tableModel.getRow(r).id());
            onAddToWorkingSet.accept(ids);
        });

        bar.add(selLabel);
        bar.add(addToWorkingSet);
        bar.putClientProperty("selLabel", selLabel);
        return bar;
    }

    private void wireTableSelection() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int count = table.getSelectedRowCount();
            JLabel lbl = (JLabel) actionBar.getClientProperty("selLabel");
            if (lbl != null) lbl.setText(count + " selected");
            actionBar.setVisible(count > 0);
        });
    }

    private void wireTableContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem renameItem  = new JMenuItem("Rename");
        JMenuItem moveItem    = new JMenuItem("Move to Folder…");
        JMenuItem deleteItem  = new JMenuItem("Delete");
        JMenuItem viewItem    = new JMenuItem("View Request / Response");
        JMenuItem compareItem = new JMenuItem("Open in Compare");

        popup.add(renameItem);
        popup.add(moveItem);
        popup.addSeparator();
        popup.add(viewItem);
        popup.add(compareItem);
        popup.addSeparator();
        popup.add(deleteItem);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                popup.show(table, e.getX(), e.getY());
            }
        });

        renameItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            TestCaseRow tc = tableModel.getRow(row);
            String newName = (String) JOptionPane.showInputDialog(this, "New name:",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, tc.name());
            if (newName != null && !newName.isBlank()) {
                loader.submit(() -> {
                    try {
                        tcRepo.rename(tc.id(), newName.trim());
                        SwingUtilities.invokeLater(this::reloadTable);
                    } catch (Exception ex) {
                        api.logging().logToError("[BAC] Rename failed: " + ex.getMessage());
                    }
                });
            }
        });

        moveItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            showMoveFolderDialog(rows);
        });

        deleteItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete " + rows.length + " test case(s)? This cannot be undone.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
            loader.submit(() -> {
                try {
                    for (long id : ids) tcRepo.delete(id);
                    SwingUtilities.invokeLater(this::refresh);
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Delete failed: " + ex.getMessage());
                }
            });
        });

        viewItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            showRequestResponseViewer(tableModel.getRow(row));
        });

        compareItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || onOpenInCompare == null) return;
            onOpenInCompare.accept(tableModel.getRow(row).id());
        });
    }

    private void showMoveFolderDialog(int[] selectedRows) {
        try {
            List<FolderRecord> folders = folderRepo.getAllFolders();
            List<String> options = new ArrayList<>();
            options.add("Inbox");
            folders.forEach(f -> options.add(f.name()));

            String chosen = (String) JOptionPane.showInputDialog(this,
                "Move to folder:", "Move Test Case",
                JOptionPane.PLAIN_MESSAGE, null,
                options.toArray(), options.get(0));
            if (chosen == null) return;

            Long targetFolderId;
            if ("Inbox".equals(chosen)) {
                targetFolderId = null;
            } else {
                targetFolderId = folders.stream()
                    .filter(f -> f.name().equals(chosen))
                    .map(FolderRecord::id)
                    .findFirst().orElse(null);
            }

            final Long fid = targetFolderId;
            long[] ids = Arrays.stream(selectedRows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
            loader.submit(() -> {
                try {
                    for (long id : ids) tcRepo.moveToFolder(id, fid);
                    SwingUtilities.invokeLater(this::refresh);
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Move failed: " + ex.getMessage());
                }
            });
        } catch (Exception ex) {
            api.logging().logToError("[BAC] Load folders for move dialog failed: " + ex.getMessage());
        }
    }

    private void showRequestResponseViewer(TestCaseRow tc) {
        loader.submit(() -> {
            try {
                byte[] reqBytes  = tcRepo.getRequestRaw(tc.id());
                byte[] respBytes = tcRepo.getPrimaryBaselineResponse(tc.id());
                SwingUtilities.invokeLater(() -> {
                    JDialog dlg = new JDialog(
                        (Frame) SwingUtilities.getWindowAncestor(this),
                        tc.method() + " " + tc.url(), false);
                    dlg.setSize(900, 600);
                    dlg.setLocationRelativeTo(this);

                    JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
                    sp.setDividerLocation(450);

                    JTextArea reqArea  = monospaceArea(reqBytes);
                    JTextArea respArea = monospaceArea(respBytes);
                    sp.setLeftComponent(new JScrollPane(reqArea));
                    sp.setRightComponent(new JScrollPane(respArea));

                    dlg.add(sp);
                    dlg.setVisible(true);
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] View request failed: " + ex.getMessage());
            }
        });
    }

    private JTextArea monospaceArea(byte[] data) {
        JTextArea area = new JTextArea(data != null ? new String(data) : "(empty)");
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    // ---- Inline rename (F2 / double-click) ----------------------------

    private void wireKeyBindings() {
        // F2 = rename selected test case
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "renameSelected");
        table.getActionMap().put("renameSelected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0) triggerRename(row);
            }
        });

        // DELETE key = delete selected
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSelected");
        table.getActionMap().put("deleteSelected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int[] rows = table.getSelectedRows();
                if (rows.length > 0) {
                    int confirm = JOptionPane.showConfirmDialog(LibraryTab.this,
                        "Delete " + rows.length + " test case(s)?", "Confirm Delete",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (confirm == JOptionPane.YES_OPTION) {
                        long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
                        loader.submit(() -> {
                            try {
                                for (long id : ids) tcRepo.delete(id);
                                SwingUtilities.invokeLater(LibraryTab.this::refresh);
                            } catch (Exception ex) {
                                api.logging().logToError("[BAC] Delete failed: " + ex.getMessage());
                            }
                        });
                    }
                }
            }
        });
    }

    private void wireInlineRename() {
        // Double-click on name column (col 2)
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    int col = table.columnAtPoint(e.getPoint());
                    if (row >= 0 && col == 2) triggerRename(row);
                }
            }
        });
    }

    private void triggerRename(int row) {
        TestCaseRow tc = tableModel.getRow(row);
        String newName = (String) JOptionPane.showInputDialog(this, "New name:",
            "Rename", JOptionPane.PLAIN_MESSAGE, null, null, tc.name());
        if (newName != null && !newName.isBlank()) {
            loader.submit(() -> {
                try {
                    tcRepo.rename(tc.id(), newName.trim());
                    SwingUtilities.invokeLater(this::reloadTable);
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Rename failed: " + ex.getMessage());
                }
            });
        }
    }

    // ---- Helpers -------------------------------------------------------

    private void reloadTable() {
        loader.submit(() -> {
            try {
                List<TestCaseRow> rows = loadRows();
                SwingUtilities.invokeLater(() -> {
                    tableModel.setRows(rows);
                    updateStatus(rows.size());
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Table reload failed: " + e.getMessage());
            }
        });
    }

    private List<TestCaseRow> loadRows() throws Exception {
        if (showAll) return tcRepo.getAll();
        return tcRepo.getByFolder(selectedFolderIdState);
    }

    private void updateStatus(int count) {
        statusLabel.setText("  " + count + " request" + (count == 1 ? "" : "s"));
    }

    // ---- Inner: folder node -------------------------------------------

    record FolderNode(Long id, String displayName) {
        @Override public String toString() { return displayName; }
    }

    // ---- Inner: table model -------------------------------------------

    private static class TestCaseTableModel extends javax.swing.table.AbstractTableModel {
        private static final String[] COLS =
            {"", "Method", "Name", "Host", "Path / URL", "Status", "Size", "Captured"};

        private List<TestCaseRow> rows = new ArrayList<>();

        void setRows(List<TestCaseRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        TestCaseRow getRow(int i) { return rows.get(i); }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Class<?> getColumnClass(int col) { return String.class; }

        @Override public Object getValueAt(int row, int col) {
            TestCaseRow r = rows.get(row);
            return switch (col) {
                case 0 -> dangerIcon(r.method());
                case 1 -> r.method();
                case 2 -> r.name() != null ? r.name() : autoLabel(r);
                case 3 -> r.host();
                case 4 -> extractPath(r.url());
                case 5 -> r.primaryBaselineStatus() != null ? String.valueOf(r.primaryBaselineStatus()) : "—";
                case 6 -> r.primaryBaselineLength() != null ? formatSize(r.primaryBaselineLength()) : "—";
                case 7 -> DATE_FMT.format(Instant.ofEpochSecond(r.capturedAt()));
                default -> "";
            };
        }

        private static String dangerIcon(String method) {
            if (method == null) return "";
            return switch (method.toUpperCase()) {
                case "DELETE"       -> "🔴";
                case "PUT", "PATCH" -> "🟠";
                case "POST"         -> "🟡";
                default             -> "";
            };
        }

        private static String autoLabel(TestCaseRow r) {
            return r.method() + " " + extractPath(r.url());
        }

        private static String extractPath(String url) {
            if (url == null) return "/";
            try {
                var uri = new java.net.URI(url);
                String path = uri.getPath();
                String query = uri.getQuery();
                if (path == null || path.isEmpty()) path = "/";
                return query != null ? path + "?" + query : path;
            } catch (Exception e) {
                // fallback: strip scheme+host
                int slash = url.indexOf('/', url.indexOf("//") + 2);
                return slash >= 0 ? url.substring(slash) : url;
            }
        }

        private static String formatSize(int bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    // ---- Inner: cell renderer with method danger coloring -------------

    private class MethodAwareRenderer extends javax.swing.table.DefaultTableCellRenderer {

        // Muted palette that works on both light and dark themes
        private static final Color RED_BG    = new Color(0x4D, 0x1F, 0x1F, 160);
        private static final Color ORANGE_BG = new Color(0x4D, 0x35, 0x0A, 160);
        private static final Color YELLOW_BG = new Color(0x44, 0x40, 0x00, 160);

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean selected, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            if (c instanceof JLabel lbl) {
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                if (!selected) {
                    TestCaseRow rowData = tableModel.getRow(row);
                    String method = rowData.method();
                    Color bg = methodBg(method);
                    if (bg != null) {
                        lbl.setBackground(blend(t.getBackground(), bg));
                        lbl.setOpaque(true);
                    } else {
                        lbl.setBackground(t.getBackground());
                        lbl.setOpaque(true);
                    }
                }
            }
            return c;
        }

        private Color methodBg(String method) {
            if (method == null) return null;
            return switch (method.toUpperCase()) {
                case "DELETE"       -> RED_BG;
                case "PUT", "PATCH" -> ORANGE_BG;
                case "POST"         -> YELLOW_BG;
                default             -> null;
            };
        }

        private Color blend(Color base, Color overlay) {
            int alpha = overlay.getAlpha();
            float a = alpha / 255f;
            int r = Math.min(255, (int)(base.getRed()   * (1 - a) + overlay.getRed()   * a));
            int g = Math.min(255, (int)(base.getGreen() * (1 - a) + overlay.getGreen() * a));
            int b = Math.min(255, (int)(base.getBlue()  * (1 - a) + overlay.getBlue()  * a));
            return new Color(r, g, b);
        }
    }

    // ---- Inner: folder tree renderer ----------------------------------

    private static class FolderTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (value instanceof DefaultMutableTreeNode node &&
                node.getUserObject() instanceof FolderNode fn) {
                setText(fn.displayName());
                // Use folder icon from UIManager for folders, file icon for leaves
                setIcon(fn.id() == null
                    ? UIManager.getIcon("FileView.computerIcon")
                    : (leaf ? UIManager.getIcon("FileView.directoryIcon")
                            : UIManager.getIcon("FileView.directoryIcon")));
            }
            return this;
        }
    }
}
