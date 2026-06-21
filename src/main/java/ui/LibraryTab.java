package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import capture.CaptureService;
import db.AccountRepository;
import db.AccountRepository.AccountRecord;
import db.DatabaseManager;
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
import java.util.function.BiConsumer;
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
    private final DatabaseManager db;
    private AccountRepository accountRepo;

    // Coloring mode: AUTO (by method) / MANUAL (by user tag) / OFF
    private volatile String coloringMode = "AUTO";
    private volatile boolean autoExpandFolders = true;

    // Search/filter
    private JTextField searchField;
    private List<TestCaseRow> allRows = new ArrayList<>();

    // Manual color tag palette (muted, theme-friendly)
    static final String[] COLOR_TAGS = {"RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE"};

    // UI components
    private final JTree folderTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode treeRoot;
    private final TestCaseTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel;
    private final JPanel actionBar;
    private JComboBox<String> sessionCombo;
    private final List<Long> sessionAccountIds = new ArrayList<>();

    // Background loader
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-library-loader");
        t.setDaemon(true);
        return t;
    });

    // Currently selected folder (null = Inbox, "ALL" sentinel = show all)
    private Long selectedFolderIdState = null; // null = Inbox
    private boolean showAll = false;

    // Callbacks wired by MainTab
    private Consumer<List<Long>> onAddToWorkingSet;
    private Consumer<Long>       onOpenInCompare;
    private BiConsumer<Long, List<Long>> onRunSelected; // (accountId, tcIds)

    public void setOnAddToWorkingSet(Consumer<List<Long>> cb) { this.onAddToWorkingSet = cb; }
    public void setOnOpenInCompare(Consumer<Long> cb)         { this.onOpenInCompare = cb; }
    public void setOnRunSelected(BiConsumer<Long, List<Long>> cb) { this.onRunSelected = cb; }

    public void setAccountRepository(AccountRepository repo) {
        this.accountRepo = repo;
        refreshSessionCombo();
    }

    public LibraryTab(MontoyaApi api, FolderRepository folderRepo,
                      TestCaseRepository tcRepo, CaptureService captureService,
                      DatabaseManager db) {
        super(new BorderLayout());
        this.api = api;
        this.folderRepo = folderRepo;
        this.tcRepo = tcRepo;
        this.captureService = captureService;
        this.db = db;

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
        refreshSessionCombo();
        loadColoringMode();
        loader.submit(() -> {
            try {
                List<FolderRecord> folders = folderRepo.getAllFolders();
                int inboxCount = folderRepo.countInbox();
                List<TestCaseRow> rows = loadRows();
                SwingUtilities.invokeLater(() -> {
                    rebuildTree(folders, inboxCount);
                    allRows = rows;
                    applyFilter();
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Library refresh failed: " + e.getMessage());
            }
        });
    }

    /** Reload display-related settings from the DB (cheap, runs off-EDT). */
    private void loadColoringMode() {
        loader.submit(() -> {
            try {
                String mode = db.getSetting("coloring_mode");
                if (mode != null) coloringMode = mode;
                String exp = db.getSetting("auto_expand_folders");
                if (exp != null) autoExpandFolders = !"false".equalsIgnoreCase(exp);
                SwingUtilities.invokeLater(table::repaint);
            } catch (Exception ignored) {}
        });
    }

    /** Public hook so the Settings tab can push a live coloring-mode change. */
    public void setColoringMode(String mode) {
        if (mode != null) {
            this.coloringMode = mode;
            SwingUtilities.invokeLater(table::repaint);
        }
    }

    /** Filter the in-memory rows by the search box text and push to the model. */
    private void applyFilter() {
        String q = searchField != null ? searchField.getText().trim().toLowerCase() : "";
        List<TestCaseRow> shown;
        if (q.isEmpty()) {
            shown = allRows;
        } else {
            shown = new ArrayList<>();
            for (TestCaseRow r : allRows) {
                if (matchesQuery(r, q)) shown.add(r);
            }
        }
        tableModel.setRows(shown);
        updateStatus(shown.size());
    }

    private static boolean matchesQuery(TestCaseRow r, String q) {
        return contains(r.name(), q)
            || contains(r.url(), q)
            || contains(r.host(), q)
            || contains(r.method(), q)
            || contains(r.notes(), q);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase().contains(q);
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
        // Re-expand all (when enabled)
        if (autoExpandFolders) {
            for (int i = 0; i < folderTree.getRowCount(); i++) folderTree.expandRow(i);
        }

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
        cm.getColumn(2).setPreferredWidth(170);                                    // name
        cm.getColumn(3).setPreferredWidth(120);                                    // host
        cm.getColumn(4).setPreferredWidth(190);                                    // path
        cm.getColumn(5).setPreferredWidth(70);  cm.getColumn(5).setMaxWidth(100); // status
        cm.getColumn(6).setPreferredWidth(70);  cm.getColumn(6).setMaxWidth(90);  // size
        cm.getColumn(7).setPreferredWidth(160);                                    // notes
        cm.getColumn(8).setPreferredWidth(130); cm.getColumn(8).setMaxWidth(150); // captured
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

        // --- Search / filter box ---
        bar.add(new JLabel("   🔎 Search:"));
        searchField = new JTextField(22);
        searchField.setToolTipText("Filter by name, URL, host, method, or notes");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        bar.add(searchField);

        JButton clearBtn = new JButton("✕");
        clearBtn.setMargin(new Insets(1, 6, 1, 6));
        clearBtn.setToolTipText("Clear search");
        clearBtn.addActionListener(e -> { searchField.setText(""); applyFilter(); });
        bar.add(clearBtn);

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

        sessionCombo = new JComboBox<>();
        sessionCombo.setToolTipText("Select account/session to run against");
        sessionCombo.setPreferredSize(new Dimension(160, sessionCombo.getPreferredSize().height));

        JButton runBtn = new JButton("Run on selected ▶");
        runBtn.setToolTipText("Run the chosen session against all selected test cases");
        runBtn.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            int comboIdx = sessionCombo.getSelectedIndex();
            if (comboIdx < 0 || comboIdx >= sessionAccountIds.size()) {
                JOptionPane.showMessageDialog(this, "Select an account/session first.",
                    "No Account Selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            long accountId = sessionAccountIds.get(comboIdx);
            List<Long> tcIds = new ArrayList<>();
            for (int r : rows) tcIds.add(tableModel.getRow(r).id());
            if (onRunSelected != null) onRunSelected.accept(accountId, tcIds);
        });

        bar.add(selLabel);
        bar.add(new JLabel("Session:"));
        bar.add(sessionCombo);
        bar.add(runBtn);
        bar.add(addToWorkingSet);
        bar.putClientProperty("selLabel", selLabel);
        return bar;
    }

    private void refreshSessionCombo() {
        if (accountRepo == null || sessionCombo == null) return;
        loader.submit(() -> {
            try {
                List<AccountRecord> accounts = accountRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    sessionCombo.removeAllItems();
                    sessionAccountIds.clear();
                    for (AccountRecord a : accounts) {
                        sessionCombo.addItem(a.name());
                        sessionAccountIds.add(a.id());
                    }
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Load accounts for session picker failed: " + e.getMessage());
            }
        });
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
        JMenuItem renameItem    = new JMenuItem("Rename");
        JMenuItem notesItem     = new JMenuItem("Edit Notes / Comment…");
        JMenuItem moveItem      = new JMenuItem("Move to Folder…");
        JMenuItem deleteItem    = new JMenuItem("Delete");
        JMenuItem viewItem      = new JMenuItem("View Request / Response");
        JMenuItem compareItem   = new JMenuItem("Open in Compare");
        JMenuItem rebaselineItem = new JMenuItem("Re-baseline (resend as owner)");

        // Manual color submenu
        JMenu colorMenu = new JMenu("Set Color");
        for (String tag : COLOR_TAGS) {
            JMenuItem ci = new JMenuItem(tag.charAt(0) + tag.substring(1).toLowerCase());
            ci.addActionListener(e -> applyColorTag(tag));
            colorMenu.add(ci);
        }
        colorMenu.addSeparator();
        JMenuItem clearColor = new JMenuItem("Clear Color");
        clearColor.addActionListener(e -> applyColorTag(null));
        colorMenu.add(clearColor);

        popup.add(renameItem);
        popup.add(notesItem);
        popup.add(colorMenu);
        popup.add(moveItem);
        popup.addSeparator();
        popup.add(viewItem);
        popup.add(compareItem);
        popup.addSeparator();
        popup.add(rebaselineItem);
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

        notesItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) editNotes(row);
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

        rebaselineItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Re-send " + rows.length + " request(s) using the owner account and save a new baseline version?\n" +
                "Existing baselines will NOT be deleted.",
                "Re-baseline", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
            rebaselineAsync(ids);
        });
    }

    private void rebaselineAsync(long[] tcIds) {
        loader.submit(() -> {
            int done = 0;
            int errors = 0;
            for (long tcId : tcIds) {
                try {
                    TestCaseRow tc = tcRepo.getById(tcId).orElse(null);
                    if (tc == null || tc.ownerAccountId() == null) { errors++; continue; }
                    if (accountRepo == null) { errors++; continue; }
                    AccountRecord owner = accountRepo.getById(tc.ownerAccountId()).orElse(null);
                    if (owner == null) { errors++; continue; }

                    byte[] reqRaw = tcRepo.getRequestRaw(tcId);
                    if (reqRaw == null) { errors++; continue; }

                    // Swap identity to owner
                    HttpRequest req = buildOwnerRequest(reqRaw, owner);
                    if (req == null) { errors++; continue; }

                    var resp = api.http().sendRequest(req);
                    byte[] respRaw = resp.response().toByteArray().getBytes();
                    int status = resp.response().statusCode();
                    int length = resp.response().body().length();
                    String today = java.time.LocalDate.now().toString();

                    long newBlId = tcRepo.addBaseline(tcId, owner.id(),
                        "rebaseline " + today, status, length, respRaw);
                    tcRepo.setPrimaryBaseline(tcId, newBlId);
                    done++;
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Re-baseline error for TC " + tcId + ": " + ex.getMessage());
                    errors++;
                }
            }
            final int d = done, er = errors;
            SwingUtilities.invokeLater(() -> {
                refresh();
                String msg = "Re-baseline complete: " + d + " succeeded" + (er > 0 ? ", " + er + " failed." : ".");
                JOptionPane.showMessageDialog(this, msg, "Re-baseline", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private HttpRequest buildOwnerRequest(byte[] rawRequest, AccountRecord owner) {
        try {
            HttpRequest req = HttpRequest.httpRequest(ByteArray.byteArray(rawRequest));
            req = req.withRemovedHeader("Cookie");
            req = req.withRemovedHeader("Authorization");
            req = req.withRemovedHeader("X-Auth-Token");
            req = req.withRemovedHeader("X-Session-Token");
            req = req.withRemovedHeader("X-Access-Token");
            req = req.withRemovedHeader("X-Api-Key");
            Map<String, String> cookies = owner.cookies();
            if (!cookies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (var entry : cookies.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                }
                req = req.withAddedHeader("Cookie", sb.toString());
            }
            for (var entry : owner.headers().entrySet()) {
                req = req.withAddedHeader(entry.getKey(), entry.getValue());
            }
            return req;
        } catch (Exception e) {
            api.logging().logToError("[BAC] Build owner request failed: " + e.getMessage());
            return null;
        }
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
        // Double-click: name column (2) → rename; notes column (7) → edit notes
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    int col = table.columnAtPoint(e.getPoint());
                    if (row < 0) return;
                    if (col == 2) triggerRename(row);
                    else if (col == 7) editNotes(row);
                }
            }
        });
    }

    private void editNotes(int row) {
        TestCaseRow tc = tableModel.getRow(row);
        JTextArea area = new JTextArea(tc.notes() != null ? tc.notes() : "", 8, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(area),
            "Notes / Comment — " + (tc.name() != null ? tc.name() : tc.url()),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String notes = area.getText();
        loader.submit(() -> {
            try {
                tcRepo.setNotes(tc.id(), notes);
                SwingUtilities.invokeLater(this::reloadTable);
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Save notes failed: " + ex.getMessage());
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
                    allRows = rows;
                    applyFilter();
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Table reload failed: " + e.getMessage());
            }
        });
    }

    /** Apply a manual color tag (or null to clear) to all selected test cases. */
    private void applyColorTag(String tag) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;
        long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
        loader.submit(() -> {
            try {
                for (long id : ids) tcRepo.setColorTag(id, tag);
                // If user is tagging colors, gently switch to MANUAL mode so it shows.
                if (tag != null && !"MANUAL".equalsIgnoreCase(coloringMode)) {
                    db.setSetting("coloring_mode", "MANUAL");
                    coloringMode = "MANUAL";
                }
                SwingUtilities.invokeLater(this::reloadTable);
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Set color failed: " + ex.getMessage());
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
            {"", "Method", "Name", "Host", "Path / URL", "Status", "Size", "Notes", "Captured"};

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
                case 7 -> r.notes() != null ? r.notes().replaceAll("\\s+", " ").trim() : "";
                case 8 -> DATE_FMT.format(Instant.ofEpochSecond(r.capturedAt()));
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
        private static final Color GREEN_BG  = new Color(0x1F, 0x44, 0x1F, 160);
        private static final Color BLUE_BG   = new Color(0x1A, 0x33, 0x52, 160);
        private static final Color PURPLE_BG = new Color(0x3A, 0x1F, 0x4D, 160);

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean selected, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            if (c instanceof JLabel lbl) {
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                if (!selected) {
                    TestCaseRow rowData = tableModel.getRow(row);
                    Color bg = rowBackground(rowData);
                    lbl.setBackground(bg != null ? blend(t.getBackground(), bg) : t.getBackground());
                    lbl.setOpaque(true);
                }
            }
            return c;
        }

        /** Resolve the row background according to the active coloring mode. */
        private Color rowBackground(TestCaseRow r) {
            return switch (coloringMode == null ? "AUTO" : coloringMode.toUpperCase()) {
                case "OFF"    -> null;
                case "MANUAL" -> tagBg(r.colorTag());
                default       -> methodBg(r.method()); // AUTO
            };
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

        private Color tagBg(String tag) {
            if (tag == null) return null;
            return switch (tag.toUpperCase()) {
                case "RED"    -> RED_BG;
                case "ORANGE" -> ORANGE_BG;
                case "YELLOW" -> YELLOW_BG;
                case "GREEN"  -> GREEN_BG;
                case "BLUE"   -> BLUE_BG;
                case "PURPLE" -> PURPLE_BG;
                default       -> null;
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
