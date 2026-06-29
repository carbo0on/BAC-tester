package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import capture.CaptureService;
import db.AccountRepository;
import db.AccountRepository.AccountRecord;
import db.DatabaseManager;
import db.FolderRepository;
import db.FolderRepository.FolderRecord;
import db.TestCaseRepository;
import db.TestCaseRepository.TestCaseRow;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Library tab — Site-map style layout.
 *
 * Left: folder tree with drag-and-drop (folders + test cases).
 * Right-top: test-case table with advanced filter bar.
 * Right-bottom: Burp-native request / response viewer (auto-populates on row selection).
 */
public class LibraryTab extends JPanel {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final DataFlavor TC_FLAVOR =
        new DataFlavor(long[].class, "BAC test case IDs");

    private final MontoyaApi api;
    private final FolderRepository folderRepo;
    private final TestCaseRepository tcRepo;
    private final CaptureService captureService;
    private final DatabaseManager db;
    private AccountRepository accountRepo;
    private ai.AiOrganizer aiOrganizer;

    private volatile String coloringMode   = "AUTO";
    private volatile boolean autoExpandFolders = true;

    // Filter state
    private JTextField   searchField;
    private JCheckBox    regexCheck;
    private JComboBox<String> methodFilter;
    private JTextField   statusFilter;
    private JCheckBox    searchInReqCheck;
    private JCheckBox    searchInRespCheck;
    private List<TestCaseRow> allRows = new ArrayList<>();

    static final String[] COLOR_TAGS = {"RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE"};

    // UI
    private final JTree folderTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode treeRoot;
    private final TestCaseTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel;
    private final JPanel actionBar;
    private JComboBox<String> sessionCombo;
    private final List<Long> sessionAccountIds = new ArrayList<>();

    // Burp native editors (non-null after construction)
    private HttpRequestEditor  reqViewer;
    private HttpResponseEditor respViewer;
    private JPanel viewerPanel;

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-library-loader");
        t.setDaemon(true);
        return t;
    });

    private Long selectedFolderIdState = null;
    private boolean showAll = false;

    private Consumer<List<Long>>     onAddToWorkingSet;
    private Consumer<Long>           onOpenInCompare;
    private BiConsumer<Long, List<Long>> onRunSelected;
    private BiConsumer<List<Long>, List<Long>> onRunPair;   // (accountIds, tcIds)

    public void setOnAddToWorkingSet(Consumer<List<Long>> cb) { this.onAddToWorkingSet = cb; }
    public void setOnOpenInCompare(Consumer<Long> cb)         { this.onOpenInCompare = cb; }
    public void setOnRunSelected(BiConsumer<Long, List<Long>> cb) { this.onRunSelected = cb; }
    public void setOnRunPair(BiConsumer<List<Long>, List<Long>> cb) { this.onRunPair = cb; }

    public void setAccountRepository(AccountRepository repo) {
        this.accountRepo = repo;
        refreshSessionCombo();
    }

    public void setAiOrganizer(ai.AiOrganizer organizer) {
        this.aiOrganizer = organizer;
        if (organizer != null) {
            // Surface AI activity (success / grouping / errors) in the status bar
            // so auto-organization is observable instead of silent.
            organizer.setStatusListener(msg ->
                SwingUtilities.invokeLater(() -> statusLabel.setText("  " + msg)));
        }
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

        // Burp native editors
        reqViewer  = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        respViewer = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        // Folder tree
        treeRoot = new DefaultMutableTreeNode("Library");
        treeModel = new DefaultTreeModel(treeRoot);
        folderTree = new JTree(treeModel);
        folderTree.setRootVisible(false);
        folderTree.setShowsRootHandles(true);
        folderTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        folderTree.setCellRenderer(new FolderTreeRenderer());
        setupTreeDragDrop();

        // Table
        tableModel = new TestCaseTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new MethodAwareRenderer());
        setupTableColumns();
        setupTableDragSource();

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        actionBar = buildActionBar();

        // Viewer panel (req left / resp right)
        viewerPanel = buildViewerPanel();

        // Layout: right side = vertical split (table + filter) / viewer
        JPanel tableArea = new JPanel(new BorderLayout(0, 0));
        tableArea.add(buildFilterToolbar(), BorderLayout.NORTH);
        tableArea.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(actionBar, BorderLayout.CENTER);
        tableArea.add(bottomBar, BorderLayout.SOUTH);

        tableArea.setMinimumSize(new Dimension(0, 180));
        viewerPanel.setMinimumSize(new Dimension(0, 160));

        JSplitPane vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableArea, viewerPanel);
        vertSplit.setDividerLocation(0.55);   // proportion — applied when component gets real size
        vertSplit.setResizeWeight(0.55);
        vertSplit.setDividerSize(5);
        vertSplit.setContinuousLayout(true);

        JScrollPane treeScroll = new JScrollPane(folderTree);
        treeScroll.setMinimumSize(new Dimension(170, 0));
        treeScroll.setPreferredSize(new Dimension(190, 0));

        JSplitPane hSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, vertSplit);
        hSplit.setDividerLocation(190);
        hSplit.setDividerSize(4);
        hSplit.setContinuousLayout(true);

        add(hSplit, BorderLayout.CENTER);

        wireFolderTreeSelection();
        wireFolderTreeContextMenu();
        wireTableContextMenu();
        wireTableSelection();
        wireKeyBindings();
        wireInlineRename();

        captureService.addOnSaveListener(this::refresh);
        refresh();
    }

    // ---- Public -----------------------------------------------------------

    public void refresh() {
        refreshSessionCombo();
        loadDisplaySettings();
        loader.submit(() -> {
            try {
                List<FolderRecord> folders = folderRepo.getAllFolders();
                int inboxCount = folderRepo.countInbox();
                List<TestCaseRow> rows = loadRows();
                Map<Long, String> verdicts;
                try { verdicts = new db.RunRepository(db).getLatestVerdictPerTestCase(); }
                catch (Exception ex) { verdicts = new HashMap<>(); }
                final Map<Long, String> fVerdicts = verdicts;
                SwingUtilities.invokeLater(() -> {
                    rebuildTree(folders, inboxCount);
                    allRows = rows;
                    tableModel.setVerdicts(fVerdicts);
                    applyFilter();
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Library refresh failed: " + e.getMessage());
            }
        });
    }

    private void loadDisplaySettings() {
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

    public void setColoringMode(String mode) {
        if (mode != null) { this.coloringMode = mode; SwingUtilities.invokeLater(table::repaint); }
    }

    // ---- Filter -----------------------------------------------------------

    private void applyFilter() {
        String q     = searchField != null ? searchField.getText().trim() : "";
        boolean regex = regexCheck != null && regexCheck.isSelected();
        String mf    = methodFilter != null ? (String) methodFilter.getSelectedItem() : "All";
        String sf    = statusFilter != null ? statusFilter.getText().trim() : "";

        Pattern pattern = null;
        if (regex && !q.isBlank()) {
            try { pattern = Pattern.compile(q, Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException ignored) { pattern = null; }
        }

        final Pattern finalPattern = pattern;
        final String finalQ = q;

        List<TestCaseRow> shown = new ArrayList<>();
        for (TestCaseRow r : allRows) {
            if (!"All".equals(mf) && !mf.equalsIgnoreCase(r.method())) continue;
            if (!sf.isBlank() && !matchesStatus(r, sf)) continue;
            if (!finalQ.isBlank()) {
                if (regex) {
                    if (finalPattern == null) continue;
                    if (!regexMatch(finalPattern, r)) continue;
                } else {
                    if (!containsQuery(r, finalQ.toLowerCase())) continue;
                }
            }
            shown.add(r);
        }
        tableModel.setRows(shown);
        updateStatus(shown.size());
    }

    private static boolean matchesStatus(TestCaseRow r, String sf) {
        Integer st = r.primaryBaselineStatus();
        if (st == null) return false;
        if (sf.endsWith("xx")) {
            try {
                int prefix = Integer.parseInt(sf.substring(0, sf.length() - 2));
                return st / 100 == prefix;
            } catch (NumberFormatException e) { return false; }
        }
        try { return Integer.parseInt(sf) == st; } catch (NumberFormatException e) { return false; }
    }

    private static boolean containsQuery(TestCaseRow r, String q) {
        return ci(r.name(), q) || ci(r.url(), q) || ci(r.host(), q)
            || ci(r.method(), q) || ci(r.notes(), q);
    }

    private static boolean regexMatch(Pattern p, TestCaseRow r) {
        return test(p, r.name()) || test(p, r.url()) || test(p, r.host())
            || test(p, r.method()) || test(p, r.notes());
    }

    private static boolean ci(String s, String q) { return s != null && s.toLowerCase().contains(q); }
    private static boolean test(Pattern p, String s) { return s != null && p.matcher(s).find(); }

    // ---- Toolbar ---------------------------------------------------------

    private JPanel buildFilterToolbar() {
        JPanel bar = new JPanel(new BorderLayout(0, 2));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        // Row 1: folder button + text search
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton newFolderBtn = new JButton("+ Folder");
        newFolderBtn.setToolTipText("Create a new top-level folder");
        newFolderBtn.addActionListener(e -> promptCreateFolder(null));
        row1.add(newFolderBtn);

        row1.add(new JLabel("  Search:"));
        searchField = new JTextField(20);
        searchField.setToolTipText("Filter by name, URL, host, method, or notes (supports regex)");
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        };
        searchField.getDocument().addDocumentListener(dl);
        row1.add(searchField);

        regexCheck = new JCheckBox("Regex");
        regexCheck.setToolTipText("Treat search text as a regular expression");
        regexCheck.addActionListener(e -> applyFilter());
        row1.add(regexCheck);

        JButton clearBtn = new JButton("✕");
        clearBtn.setMargin(new Insets(1, 5, 1, 5));
        clearBtn.setToolTipText("Clear search");
        clearBtn.addActionListener(e -> { searchField.setText(""); applyFilter(); });
        row1.add(clearBtn);

        // Row 2: method + status filters
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        row2.add(new JLabel("Method:"));
        methodFilter = new JComboBox<>(new String[]{"All","GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"});
        methodFilter.setPreferredSize(new Dimension(90, 22));
        methodFilter.addActionListener(e -> applyFilter());
        row2.add(methodFilter);

        row2.add(new JLabel("  Status:"));
        statusFilter = new JTextField(7);
        statusFilter.setToolTipText("Filter by baseline status (e.g. 200, 4xx, 403)");
        statusFilter.getDocument().addDocumentListener(dl);
        row2.add(statusFilter);

        // In-request / in-response search (async, triggered by button)
        searchInReqCheck  = new JCheckBox("in Request");
        searchInRespCheck = new JCheckBox("in Response");
        searchInReqCheck.setToolTipText("Also search inside raw request bytes (slower)");
        searchInRespCheck.setToolTipText("Also search inside raw response bytes (slower)");
        JButton deepSearch = new JButton("Deep Search");
        deepSearch.setToolTipText("Search inside request/response bodies (slower)");
        deepSearch.addActionListener(e -> runDeepSearch());
        row2.add(searchInReqCheck);
        row2.add(searchInRespCheck);
        row2.add(deepSearch);

        bar.add(row1, BorderLayout.NORTH);
        bar.add(row2, BorderLayout.CENTER);

        return bar;
    }

    private void runDeepSearch() {
        String q = searchField.getText().trim();
        if (q.isBlank()) { applyFilter(); return; }
        boolean inReq  = searchInReqCheck.isSelected();
        boolean inResp = searchInRespCheck.isSelected();
        if (!inReq && !inResp) { applyFilter(); return; }

        boolean regex = regexCheck.isSelected();
        Pattern pat = null;
        if (regex) {
            try { pat = Pattern.compile(q, Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException e) { return; }
        }
        final Pattern finalPat = pat;
        final String  finalQ   = q.toLowerCase();

        statusLabel.setText("  Searching…");
        final List<TestCaseRow> candidates = new ArrayList<>(allRows);
        final int totalToScan = candidates.size();
        loader.submit(() -> {
            List<TestCaseRow> matches = new ArrayList<>();
            int scanned = 0;
            for (TestCaseRow r : candidates) {
                scanned++;
                if (scanned % 25 == 0 || scanned == totalToScan) {
                    final int s = scanned;
                    SwingUtilities.invokeLater(() ->
                        statusLabel.setText("  Searching… " + s + " / " + totalToScan));
                }
                try {
                    boolean hit = false;
                    if (inReq) {
                        byte[] raw = tcRepo.getRequestRaw(r.id());
                        if (raw != null) hit = bodyContains(raw, finalQ, finalPat, regex);
                    }
                    if (!hit && inResp) {
                        byte[] raw = tcRepo.getPrimaryBaselineResponse(r.id());
                        if (raw != null) hit = bodyContains(raw, finalQ, finalPat, regex);
                    }
                    if (hit) matches.add(r);
                } catch (Exception ignored) {}
            }
            SwingUtilities.invokeLater(() -> {
                tableModel.setRows(matches);
                updateStatus(matches.size());
            });
        });
    }

    private static boolean bodyContains(byte[] raw, String q, Pattern pat, boolean regex) {
        String body = new String(raw);
        if (regex && pat != null) return pat.matcher(body).find();
        return body.toLowerCase().contains(q);
    }

    // ---- Viewer panel ----------------------------------------------------

    private JPanel buildViewerPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel header = new JLabel("  Request / Response  —  select a row above to preview");
        header.setFont(header.getFont().deriveFont(Font.ITALIC, 11f));
        header.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            UIManager.getColor("Separator.foreground")));
        panel.add(header, BorderLayout.NORTH);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            reqViewer.uiComponent(), respViewer.uiComponent());
        sp.setDividerLocation(0.5);
        sp.setResizeWeight(0.5);
        sp.setContinuousLayout(true);
        sp.setDividerSize(4);
        panel.add(sp, BorderLayout.CENTER);

        return panel;
    }

    private void populateViewer(TestCaseRow tc) {
        loader.submit(() -> {
            try {
                byte[] reqBytes  = tcRepo.getRequestRaw(tc.id());
                byte[] respBytes = tcRepo.getPrimaryBaselineResponse(tc.id());
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (reqBytes != null && reqBytes.length > 0) {
                            reqViewer.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(reqBytes)));
                        }
                        if (respBytes != null && respBytes.length > 0) {
                            respViewer.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(respBytes)));
                        }
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Viewer load failed: " + e.getMessage());
            }
        });
    }

    // ---- Tree ------------------------------------------------------------

    private void rebuildTree(List<FolderRecord> folders, int inboxCount) {
        treeRoot.removeAllChildren();

        // Virtual "All Requests" node — shows every test case across all folders.
        DefaultMutableTreeNode all =
            new DefaultMutableTreeNode(new FolderNode(ALL_ID, "All Requests", null));
        treeRoot.add(all);

        DefaultMutableTreeNode inbox =
            new DefaultMutableTreeNode(new FolderNode(null, "Inbox (" + inboxCount + ")", null));
        treeRoot.add(inbox);

        Map<Long, DefaultMutableTreeNode> nodeMap = new HashMap<>();
        for (FolderRecord fr : folders)
            nodeMap.put(fr.id(), new DefaultMutableTreeNode(new FolderNode(fr.id(), fr.name(), fr.color())));
        for (FolderRecord fr : folders) {
            DefaultMutableTreeNode node = nodeMap.get(fr.id());
            if (fr.parentId() == null) treeRoot.add(node);
            else {
                DefaultMutableTreeNode parent = nodeMap.get(fr.parentId());
                if (parent != null) parent.add(node); else treeRoot.add(node);
            }
        }
        treeModel.reload();
        if (autoExpandFolders)
            for (int i = 0; i < folderTree.getRowCount(); i++) folderTree.expandRow(i);
        if (showAll) selectFolderNode(ALL_ID); else selectFolderNode(selectedFolderIdState);
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
        if (folderTree.getRowCount() > 0) folderTree.setSelectionRow(0);
    }

    private void wireFolderTreeSelection() {
        folderTree.addTreeSelectionListener(e -> {
            TreePath path = folderTree.getSelectionPath();
            if (path == null) return;
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node &&
                node.getUserObject() instanceof FolderNode fn) {
                if (Objects.equals(fn.id(), ALL_ID)) {
                    showAll = true;
                } else {
                    showAll = false;
                    selectedFolderIdState = fn.id();
                }
                reloadTable();
            }
        });
    }

    private void wireFolderTreeContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem newFolder    = new JMenuItem("New Folder");
        JMenuItem newSub       = new JMenuItem("New Sub-folder");
        JMenuItem renameFolder = new JMenuItem("Rename Folder");
        JMenuItem deleteFolder = new JMenuItem("Delete Folder");

        JMenu colorMenu = new JMenu("Set Folder Color");
        for (String tag : COLOR_TAGS) {
            JMenuItem ci = new JMenuItem(tag.charAt(0) + tag.substring(1).toLowerCase());
            ci.addActionListener(e -> applyFolderColor(tag));
            colorMenu.add(ci);
        }
        colorMenu.addSeparator();
        JMenuItem clearFolderColor = new JMenuItem("Clear Color");
        clearFolderColor.addActionListener(e -> applyFolderColor(null));
        colorMenu.add(clearFolderColor);

        popup.add(newFolder);
        popup.add(newSub);
        popup.add(renameFolder);
        popup.add(colorMenu);
        popup.add(deleteFolder);

        folderTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = folderTree.getPathForLocation(e.getX(), e.getY());
                folderTree.setSelectionPath(path);
                FolderNode fn = selectedFolderNode();
                // Inbox (null) and All Requests (ALL_ID) are virtual — not editable.
                boolean special = fn == null || fn.id() == null || Objects.equals(fn.id(), ALL_ID);
                renameFolder.setEnabled(!special);
                deleteFolder.setEnabled(!special);
                newSub.setEnabled(!special);
                colorMenu.setEnabled(!special);
                popup.show(folderTree, e.getX(), e.getY());
            }
        });

        newFolder.addActionListener(e -> promptCreateFolder(null));
        newSub.addActionListener(e -> {
            FolderNode fn = selectedFolderNode();
            Long parentId = fn != null ? fn.id() : null;
            promptCreateFolder(parentId);
        });

        renameFolder.addActionListener(e -> {
            FolderNode fn = selectedFolderNode();
            if (fn == null || fn.id() == null) return;
            String newName = (String) JOptionPane.showInputDialog(this, "Rename folder:",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, fn.displayName());
            if (newName != null && !newName.isBlank()) {
                long id = fn.id();
                loader.submit(() -> {
                    try { folderRepo.renameFolder(id, newName.trim()); SwingUtilities.invokeLater(this::refresh); }
                    catch (Exception ex) { api.logging().logToError("[BAC] Rename folder: " + ex.getMessage()); }
                });
            }
        });

        deleteFolder.addActionListener(e -> {
            FolderNode fn = selectedFolderNode();
            if (fn == null || fn.id() == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete folder \"" + fn.displayName() + "\"?\nTest cases inside will move to Inbox.",
                "Delete Folder", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            long id = fn.id();
            loader.submit(() -> {
                try {
                    for (TestCaseRow row : tcRepo.getByFolder(id)) tcRepo.moveToFolder(row.id(), null);
                    folderRepo.deleteFolder(id);
                    SwingUtilities.invokeLater(() -> { selectedFolderIdState = null; refresh(); });
                } catch (Exception ex) { api.logging().logToError("[BAC] Delete folder: " + ex.getMessage()); }
            });
        });
    }

    private void promptCreateFolder(Long parentId) {
        String name = JOptionPane.showInputDialog(this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            loader.submit(() -> {
                try { folderRepo.createFolder(name.trim(), parentId); SwingUtilities.invokeLater(this::refresh); }
                catch (Exception ex) { api.logging().logToError("[BAC] Create folder: " + ex.getMessage()); }
            });
        }
    }

    private FolderNode selectedFolderNode() {
        TreePath path = folderTree.getSelectionPath();
        if (path == null) return null;
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node &&
            node.getUserObject() instanceof FolderNode fn) return fn;
        return null;
    }

    // ---- Tree drag-and-drop (folder rearrangement) -----------------------

    private void setupTreeDragDrop() {
        folderTree.setDragEnabled(true);
        folderTree.setDropMode(DropMode.ON);
        folderTree.setTransferHandler(new FolderTreeTransferHandler());
    }

    private class FolderTreeTransferHandler extends TransferHandler {

        private FolderNode draggedFolder;

        @Override
        protected Transferable createTransferable(JComponent c) {
            FolderNode fn = selectedFolderNode();
            if (fn == null || fn.id() == null) return null;
            draggedFolder = fn;
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.stringFlavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return f == DataFlavor.stringFlavor; }
                @Override public Object getTransferData(DataFlavor f) { return "folder:" + fn.id(); }
            };
        }

        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop()) return false;
            // Accept: folder-to-folder or test-cases from table
            return support.isDataFlavorSupported(DataFlavor.stringFlavor)
                || support.isDataFlavorSupported(TC_FLAVOR);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!support.isDrop()) return false;
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            TreePath targetPath = dl.getPath();
            if (targetPath == null) return false;
            Object last = targetPath.getLastPathComponent();
            if (!(last instanceof DefaultMutableTreeNode targetNode)) return false;
            if (!(targetNode.getUserObject() instanceof FolderNode targetFn)) return false;

            try {
                // Test-case transfer from table
                if (support.isDataFlavorSupported(TC_FLAVOR)) {
                    long[] ids = (long[]) support.getTransferable().getTransferData(TC_FLAVOR);
                    final Long folderId = targetFn.id();
                    loader.submit(() -> {
                        try {
                            for (long id : ids) tcRepo.moveToFolder(id, folderId);
                            SwingUtilities.invokeLater(LibraryTab.this::refresh);
                        } catch (Exception ex) { api.logging().logToError("[BAC] DnD move TC: " + ex.getMessage()); }
                    });
                    return true;
                }
                // Folder-to-folder
                if (draggedFolder != null && draggedFolder.id() != null) {
                    long srcId = draggedFolder.id();
                    Long dstId = targetFn.id(); // null = Inbox root
                    // Prevent dropping onto itself or creating a cycle
                    if (Objects.equals(srcId, dstId)) return false;
                    loader.submit(() -> {
                        try {
                            folderRepo.updateParent(srcId, dstId, 0);
                            SwingUtilities.invokeLater(LibraryTab.this::refresh);
                        } catch (Exception ex) { api.logging().logToError("[BAC] DnD move folder: " + ex.getMessage()); }
                    });
                    draggedFolder = null;
                    return true;
                }
            } catch (Exception e) {
                api.logging().logToError("[BAC] DnD import failed: " + e.getMessage());
            }
            return false;
        }
    }

    // ---- Table drag source -----------------------------------------------

    private void setupTableDragSource() {
        table.setDragEnabled(true);
        table.setTransferHandler(new TransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                int[] rows = table.getSelectedRows();
                if (rows.length == 0) return null;
                long[] ids = new long[rows.length];
                for (int i = 0; i < rows.length; i++) ids[i] = tableModel.getRow(rows[i]).id();
                return new Transferable() {
                    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{TC_FLAVOR}; }
                    @Override public boolean isDataFlavorSupported(DataFlavor f) { return TC_FLAVOR.equals(f); }
                    @Override public Object getTransferData(DataFlavor f) { return ids; }
                };
            }
            @Override public int getSourceActions(JComponent c) { return MOVE; }
        });
    }

    // ---- Table -----------------------------------------------------------

    private void setupTableColumns() {
        var cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(28);  cm.getColumn(0).setMaxWidth(34);   // danger icon
        cm.getColumn(1).setPreferredWidth(70);  cm.getColumn(1).setMaxWidth(90);   // method
        cm.getColumn(2).setPreferredWidth(160);                                     // name
        cm.getColumn(3).setPreferredWidth(120);                                     // host
        cm.getColumn(4).setPreferredWidth(190);                                     // path
        cm.getColumn(5).setPreferredWidth(60);  cm.getColumn(5).setMaxWidth(90);   // status
        cm.getColumn(6).setPreferredWidth(65);  cm.getColumn(6).setMaxWidth(85);   // size
        cm.getColumn(7).setPreferredWidth(85);  cm.getColumn(7).setMaxWidth(105);  // verdict
        cm.getColumn(8).setPreferredWidth(140);                                     // notes
        cm.getColumn(9).setPreferredWidth(125); cm.getColumn(9).setMaxWidth(145);  // captured
    }

    private JPanel buildActionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 3));
        bar.setVisible(false);

        JLabel selLabel = new JLabel("0 selected");

        JButton addToWorkingSet = new JButton("Add to Working Set");
        addToWorkingSet.setToolTipText("Open selected in Compare tab working set");
        addToWorkingSet.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0 || onAddToWorkingSet == null) return;
            List<Long> ids = new ArrayList<>();
            for (int r : rows) ids.add(tableModel.getRow(r).id());
            onAddToWorkingSet.accept(ids);
        });

        sessionCombo = new JComboBox<>();
        sessionCombo.setToolTipText("Account/session to run against");
        sessionCombo.setPreferredSize(new Dimension(155, sessionCombo.getPreferredSize().height));

        JButton runBtn = new JButton("Run on selected ▶");
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
            } catch (Exception e) { api.logging().logToError("[BAC] Load accounts: " + e.getMessage()); }
        });
    }

    private void wireTableSelection() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int count = table.getSelectedRowCount();
            JLabel lbl = (JLabel) actionBar.getClientProperty("selLabel");
            if (lbl != null) lbl.setText(count + " selected");
            actionBar.setVisible(count > 0);

            // Auto-populate viewer on single selection
            if (count == 1) {
                int row = table.getSelectedRow();
                if (row >= 0 && row < tableModel.getRowCount()) {
                    populateViewer(tableModel.getRow(row));
                }
            }
        });
    }

    private void wireTableContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem renameItem     = new JMenuItem("Rename");
        JMenuItem notesItem      = new JMenuItem("Edit Notes / Comment…");
        JMenuItem moveItem       = new JMenuItem("Move to Folder…");
        JMenuItem deleteItem     = new JMenuItem("Delete");
        JMenuItem viewItem       = new JMenuItem("View Request / Response");
        JMenuItem compareItem    = new JMenuItem("Open in Compare");
        JMenuItem aiOrganizeItem = new JMenuItem("Organize with AI ✨");
        JMenuItem runPairItem    = new JMenuItem("Run pair (A vs B) → Compare…");
        JMenuItem dynFieldsItem  = new JMenuItem("Edit Dynamic Fields (CSRF/nonce)…");
        JMenuItem rebaselineItem = new JMenuItem("Re-baseline (resend as owner)");

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

        popup.add(renameItem); popup.add(notesItem); popup.add(colorMenu); popup.add(moveItem);
        popup.addSeparator();
        popup.add(viewItem); popup.add(compareItem); popup.add(aiOrganizeItem); popup.add(runPairItem);
        popup.addSeparator();
        popup.add(dynFieldsItem);
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
            triggerRename(row);
        });

        notesItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) editNotes(row);
        });

        moveItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length > 0) showMoveFolderDialog(rows);
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
                try { for (long id : ids) tcRepo.delete(id); SwingUtilities.invokeLater(this::refresh); }
                catch (Exception ex) { api.logging().logToError("[BAC] Delete: " + ex.getMessage()); }
            });
        });

        viewItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) populateViewer(tableModel.getRow(row));
        });

        compareItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || onOpenInCompare == null) return;
            onOpenInCompare.accept(tableModel.getRow(row).id());
        });

        aiOrganizeItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            if (aiOrganizer == null || !aiOrganizer.isReady()) {
                JOptionPane.showMessageDialog(this,
                    "AI is not active yet.\n\n"
                    + "Go to Settings ▸ AI Organization, tick \"Enable\", choose a provider,\n"
                    + "paste your API key, then click \"Test connection\" (a successful test\n"
                    + "saves and activates it) — or click \"Save Settings\".",
                    "AI not configured", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<Long> tcIds = new ArrayList<>();
            for (int r : rows) tcIds.add(tableModel.getRow(r).id());
            statusLabel.setText("  Organizing " + tcIds.size() + " request(s) with AI…");
            aiOrganizer.organizeSelection(tcIds, msg ->
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("  " + msg);
                    refresh();
                    // Make failures impossible to miss for a user-initiated action.
                    if (msg != null && (msg.contains("failed") || msg.contains("not configured"))) {
                        JOptionPane.showMessageDialog(this, msg,
                            "Organize with AI", JOptionPane.WARNING_MESSAGE);
                    }
                }));
        });

        runPairItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            List<Long> tcIds = new ArrayList<>();
            for (int r : rows) tcIds.add(tableModel.getRow(r).id());
            promptRunPair(tcIds);
        });

        dynFieldsItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) editDynamicFields(tableModel.getRow(row));
        });

        rebaselineItem.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Re-send " + rows.length + " request(s) as owner and save a new baseline?\nExisting baselines will NOT be deleted.",
                "Re-baseline", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
            rebaselineAsync(ids);
        });
    }

    private void rebaselineAsync(long[] tcIds) {
        loader.submit(() -> {
            int done = 0, errors = 0;
            final int n = tcIds.length;
            for (int i = 0; i < n; i++) {
                long tcId = tcIds[i];
                final int idx = i + 1;
                SwingUtilities.invokeLater(() ->
                    statusLabel.setText("  Re-baselining " + idx + " / " + n + "…"));
                try {
                    TestCaseRow tc = tcRepo.getById(tcId).orElse(null);
                    if (tc == null || tc.ownerAccountId() == null || accountRepo == null) { errors++; continue; }
                    AccountRecord owner = accountRepo.getById(tc.ownerAccountId()).orElse(null);
                    if (owner == null) { errors++; continue; }
                    byte[] reqRaw = tcRepo.getRequestRaw(tcId);
                    if (reqRaw == null) { errors++; continue; }
                    HttpService service = HttpService.httpService(tc.host(), tc.port(), tc.isHttps());
                    java.util.List<engine.DynamicField> dyn =
                        engine.DynamicField.parse(tcRepo.getDynamicFields(tcId));
                    HttpRequest req = buildOwnerRequest(reqRaw, owner, service, dyn);
                    if (req == null) { errors++; continue; }
                    var resp = api.http().sendRequest(req);
                    // Guard against an unreachable / out-of-scope target: response()
                    // may be null, which would otherwise NPE inside this loop.
                    if (resp.response() == null) {
                        api.logging().logToError("[BAC] Re-baseline TC " + tcId
                            + ": no response (target unreachable / out of scope).");
                        errors++;
                        continue;
                    }
                    byte[] respRaw = resp.response().toByteArray().getBytes();
                    int status = resp.response().statusCode();
                    int length = resp.response().body().length();
                    String today = java.time.LocalDate.now().toString();
                    long newBlId = tcRepo.addBaseline(tcId, owner.id(), "rebaseline " + today, status, length, respRaw);
                    tcRepo.setPrimaryBaseline(tcId, newBlId);
                    done++;
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Re-baseline TC " + tcId + ": " + ex.getMessage());
                    errors++;
                }
            }
            final int d = done, er = errors;
            SwingUtilities.invokeLater(() -> {
                refresh();
                JOptionPane.showMessageDialog(this,
                    "Re-baseline complete: " + d + " succeeded" + (er > 0 ? ", " + er + " failed." : "."),
                    "Re-baseline", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private HttpRequest buildOwnerRequest(byte[] rawRequest, AccountRecord owner, HttpService service,
                                          java.util.List<engine.DynamicField> dynamicFields) {
        try {
            // Reuse the engine's identity-swap (cookie merge + dynamic fields) so a
            // re-baseline produces a response consistent with how runs replay.
            return engine.RunEngine.buildSwappedRequestStatic(rawRequest, owner, service, dynamicFields);
        } catch (Exception e) {
            api.logging().logToError("[BAC] Build owner request: " + e.getMessage());
            return null;
        }
    }

    private void showMoveFolderDialog(int[] selectedRows) {
        try {
            List<FolderRecord> folders = folderRepo.getAllFolders();
            List<String> options = new ArrayList<>();
            options.add("Inbox");
            folders.forEach(f -> options.add(f.name()));
            String chosen = (String) JOptionPane.showInputDialog(this, "Move to folder:",
                "Move Test Case", JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.get(0));
            if (chosen == null) return;
            Long targetFolderId = "Inbox".equals(chosen) ? null :
                folders.stream().filter(f -> f.name().equals(chosen)).map(FolderRecord::id).findFirst().orElse(null);
            final Long fid = targetFolderId;
            long[] ids = Arrays.stream(selectedRows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
            loader.submit(() -> {
                try { for (long id : ids) tcRepo.moveToFolder(id, fid); SwingUtilities.invokeLater(this::refresh); }
                catch (Exception ex) { api.logging().logToError("[BAC] Move: " + ex.getMessage()); }
            });
        } catch (Exception ex) { api.logging().logToError("[BAC] Load folders for move: " + ex.getMessage()); }
    }

    // ---- Inline rename / notes -------------------------------------------

    private void wireKeyBindings() {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "renameSelected");
        table.getActionMap().put("renameSelected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0) triggerRename(row);
            }
        });
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
                            try { for (long id : ids) tcRepo.delete(id); SwingUtilities.invokeLater(LibraryTab.this::refresh); }
                            catch (Exception ex) { api.logging().logToError("[BAC] Delete: " + ex.getMessage()); }
                        });
                    }
                }
            }
        });
    }

    private void wireInlineRename() {
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
        area.setLineWrap(true); area.setWrapStyleWord(true);
        int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(area),
            "Notes / Comment — " + (tc.name() != null ? tc.name() : tc.url()),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        loader.submit(() -> {
            try { tcRepo.setNotes(tc.id(), area.getText()); SwingUtilities.invokeLater(this::reloadTable); }
            catch (Exception ex) { api.logging().logToError("[BAC] Save notes: " + ex.getMessage()); }
        });
    }

    /**
     * Horizontal access-control test: pick two (or more) accounts to replay the
     * selected requests under, then send them all to the Compare working set so
     * their responses can be diffed directly (A vs B). (#9)
     */
    private void promptRunPair(List<Long> tcIds) {
        if (accountRepo == null || onRunPair == null) return;
        loader.submit(() -> {
            try {
                List<AccountRecord> accounts = accountRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    if (accounts.size() < 2) {
                        JOptionPane.showMessageDialog(this,
                            "Define at least two accounts in the Accounts tab first.",
                            "Need two accounts", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    DefaultListModel<String> model = new DefaultListModel<>();
                    for (AccountRecord a : accounts) model.addElement(a.label());
                    JList<String> list = new JList<>(model);
                    list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                    list.setVisibleRowCount(Math.min(8, accounts.size()));
                    if (accounts.size() >= 2) list.setSelectedIndices(new int[]{0, 1});
                    int ok = JOptionPane.showConfirmDialog(this,
                        new Object[]{
                            "Replay " + tcIds.size() + " request(s) as each selected account,",
                            "then compare their responses side-by-side:",
                            new JScrollPane(list)},
                        "Run pair (A vs B)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                    if (ok != JOptionPane.OK_OPTION) return;
                    int[] sel = list.getSelectedIndices();
                    if (sel.length < 2) {
                        JOptionPane.showMessageDialog(this, "Select at least two accounts.",
                            "Need two accounts", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    List<Long> acctIds = new ArrayList<>();
                    for (int i : sel) acctIds.add(accounts.get(i).id());
                    onRunPair.accept(acctIds, tcIds);
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Run pair: " + ex.getMessage());
            }
        });
    }

    private void editDynamicFields(TestCaseRow tc) {
        loader.submit(() -> {
            try {
                String json = tcRepo.getDynamicFields(tc.id());
                List<engine.DynamicField> fields = engine.DynamicField.parse(json);
                SwingUtilities.invokeLater(() -> {
                    Frame parent = api.userInterface().swingUtils().suiteFrame();
                    String label = tc.name() != null ? tc.name() : tc.url();
                    DynamicFieldsDialog dlg = new DynamicFieldsDialog(parent, label, fields);
                    api.userInterface().applyThemeToComponent(dlg.getContentPane());
                    dlg.setVisible(true);
                    if (!dlg.isConfirmed()) return;
                    List<engine.DynamicField> edited = dlg.getFields();
                    final String toSave = edited.isEmpty() ? null : engine.DynamicField.toJson(edited);
                    loader.submit(() -> {
                        try { tcRepo.setDynamicFields(tc.id(), toSave); }
                        catch (Exception ex) { api.logging().logToError("[BAC] Save dynamic fields: " + ex.getMessage()); }
                    });
                });
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Load dynamic fields: " + ex.getMessage());
            }
        });
    }

    private void triggerRename(int row) {
        TestCaseRow tc = tableModel.getRow(row);
        String newName = (String) JOptionPane.showInputDialog(this, "New name:",
            "Rename", JOptionPane.PLAIN_MESSAGE, null, null, tc.name());
        if (newName != null && !newName.isBlank()) {
            loader.submit(() -> {
                try { tcRepo.rename(tc.id(), newName.trim()); SwingUtilities.invokeLater(this::reloadTable); }
                catch (Exception ex) { api.logging().logToError("[BAC] Rename: " + ex.getMessage()); }
            });
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private void reloadTable() {
        loader.submit(() -> {
            try {
                List<TestCaseRow> rows = loadRows();
                SwingUtilities.invokeLater(() -> { allRows = rows; applyFilter(); });
            } catch (Exception e) { api.logging().logToError("[BAC] Table reload: " + e.getMessage()); }
        });
    }

    private void applyColorTag(String tag) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;
        long[] ids = Arrays.stream(rows).mapToLong(r -> tableModel.getRow(r).id()).toArray();
        loader.submit(() -> {
            try {
                for (long id : ids) tcRepo.setColorTag(id, tag);
                if (tag != null && !"MANUAL".equalsIgnoreCase(coloringMode)) {
                    db.setSetting("coloring_mode", "MANUAL");
                    coloringMode = "MANUAL";
                }
                SwingUtilities.invokeLater(this::reloadTable);
            } catch (Exception ex) { api.logging().logToError("[BAC] Set color: " + ex.getMessage()); }
        });
    }

    private void applyFolderColor(String tag) {
        FolderNode fn = selectedFolderNode();
        if (fn == null || fn.id() == null || Objects.equals(fn.id(), ALL_ID)) return;
        long id = fn.id();
        loader.submit(() -> {
            try { folderRepo.setColor(id, tag); SwingUtilities.invokeLater(this::refresh); }
            catch (Exception ex) { api.logging().logToError("[BAC] Set folder color: " + ex.getMessage()); }
        });
    }

    private List<TestCaseRow> loadRows() throws Exception {
        if (showAll) return tcRepo.getAll();
        return tcRepo.getByFolder(selectedFolderIdState);
    }

    private void updateStatus(int count) {
        int total = allRows.size();
        statusLabel.setText("  " + count + (count != total ? " / " + total : "") +
            " request" + (count == 1 ? "" : "s"));
    }

    // ---- Inner: folder node ----------------------------------------------

    /** id == null → Inbox, id == ALL_ID → All Requests, otherwise a real folder id. */
    record FolderNode(Long id, String displayName, String color) {
        @Override public String toString() { return displayName; }
    }

    private static final Long ALL_ID = -1L;

    // ---- Inner: table model ----------------------------------------------

    private static class TestCaseTableModel extends javax.swing.table.AbstractTableModel {
        private static final String[] COLS =
            {"", "Method", "Name", "Host", "Path / URL", "Status", "Size", "Verdict", "Notes", "Captured"};
        private List<TestCaseRow> rows = new ArrayList<>();
        private Map<Long, String> verdicts = new HashMap<>();

        void setRows(List<TestCaseRow> rows) { this.rows = rows; fireTableDataChanged(); }
        void setVerdicts(Map<Long, String> v) { this.verdicts = v != null ? v : new HashMap<>(); fireTableDataChanged(); }
        String verdictFor(long tcId) { return verdicts.get(tcId); }
        TestCaseRow getRow(int i) { return rows.get(i); }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Class<?> getColumnClass(int col) { return String.class; }

        @Override public Object getValueAt(int row, int col) {
            TestCaseRow r = rows.get(row);
            return switch (col) {
                case 0 -> "";   // danger badge drawn as a vector icon by the renderer
                case 1 -> r.method();
                case 2 -> r.name() != null ? r.name() : autoLabel(r);
                case 3 -> r.host();
                case 4 -> extractPath(r.url());
                case 5 -> r.primaryBaselineStatus() != null ? String.valueOf(r.primaryBaselineStatus()) : "—";
                case 6 -> r.primaryBaselineLength() != null ? formatSize(r.primaryBaselineLength()) : "—";
                case 7 -> { String v = verdicts.get(r.id()); yield v != null ? VerdictStyle.shortLabel(v) : "—"; }
                case 8 -> r.notes() != null ? r.notes().replaceAll("\\s+", " ").trim() : "";
                case 9 -> DATE_FMT.format(Instant.ofEpochSecond(r.capturedAt()));
                default -> "";
            };
        }

        private static String autoLabel(TestCaseRow r) { return r.method() + " " + extractPath(r.url()); }

        private static String extractPath(String url) {
            if (url == null) return "/";
            try {
                var uri = new java.net.URI(url);
                String path = uri.getPath();
                String query = uri.getQuery();
                if (path == null || path.isEmpty()) path = "/";
                return query != null ? path + "?" + query : path;
            } catch (Exception e) {
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

    // ---- Inner: cell renderer --------------------------------------------

    private class MethodAwareRenderer extends javax.swing.table.DefaultTableCellRenderer {

        // Light pastel highlights mirroring Burp's Proxy-history row colours.
        // These are *light* base tints (not saturated) blended at low alpha so
        // rows read as gentle highlights on both light and dark themes rather
        // than heavy, dark fills.
        private static final Color RED_BG    = new Color(255, 165, 165, 90);
        private static final Color ORANGE_BG = new Color(255, 205, 135, 90);
        private static final Color YELLOW_BG = new Color(250, 240, 150, 90);
        private static final Color GREEN_BG  = new Color(180, 235, 180, 90);
        private static final Color BLUE_BG   = new Color(170, 205, 255, 90);
        private static final Color PURPLE_BG = new Color(215, 180, 250, 90);

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean selected, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            if (c instanceof JLabel lbl) {
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                TestCaseRow rowData = tableModel.getRow(row);

                // Column 0 = danger badge: a crisp vector dot (red/orange/yellow)
                // for state-changing methods, nothing for safe ones.
                int modelCol = t.convertColumnIndexToModel(col);
                if (modelCol == 0) {
                    Icon d = BacIcons.dangerDot(rowData.method());
                    lbl.setIcon(d);
                    lbl.setText("");
                    lbl.setHorizontalAlignment(SwingConstants.CENTER);
                    if (d != null) lbl.setToolTipText("State-changing request (" + rowData.method() + ")");
                } else {
                    lbl.setIcon(null);
                    lbl.setHorizontalAlignment(SwingConstants.LEADING);
                    lbl.setToolTipText(null);
                }

                if (!selected) {
                    // The Verdict cell is tinted by its own triage color so flagged
                    // rows stand out; every other cell uses the method/tag row color.
                    String verdict = modelCol == 7 ? tableModel.verdictFor(rowData.id()) : null;
                    Color vbg = verdict != null ? VerdictStyle.color(verdict) : null;
                    Color bg = vbg != null ? vbg : rowBackground(rowData);
                    lbl.setBackground(bg != null ? blend(t.getBackground(), bg) : t.getBackground());
                    lbl.setOpaque(true);
                }
            }
            return c;
        }

        private Color rowBackground(TestCaseRow r) {
            return switch (coloringMode == null ? "AUTO" : coloringMode.toUpperCase()) {
                case "OFF"    -> null;
                case "MANUAL" -> tagBg(r.colorTag());
                default       -> methodBg(r.method());
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
            float a = overlay.getAlpha() / 255f;
            int r = Math.min(255, (int)(base.getRed()   * (1 - a) + overlay.getRed()   * a));
            int g = Math.min(255, (int)(base.getGreen() * (1 - a) + overlay.getGreen() * a));
            int b = Math.min(255, (int)(base.getBlue()  * (1 - a) + overlay.getBlue()  * a));
            return new Color(r, g, b);
        }
    }

    // ---- Inner: folder tree renderer -------------------------------------

    private static class FolderTreeRenderer extends DefaultTreeCellRenderer {

        // Light pastel folder tints (foreground text color) — gentle, theme-friendly.
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
            if (value instanceof DefaultMutableTreeNode node &&
                node.getUserObject() instanceof FolderNode fn) {
                setText(fn.displayName());
                Color c = tagColor(fn.color());
                // A colour-tinted folder icon makes the colour visible regardless of
                // how the active theme treats the label foreground.
                setIcon(BacIcons.folder(c));
                if (fn.id() == null) setIcon(UIManager.getIcon("FileView.computerIcon"));        // Inbox
                else if (Objects.equals(fn.id(), ALL_ID)) setIcon(UIManager.getIcon("FileView.hardDriveIcon")); // All
                // Tint the folder label by its color (only when not selected, to keep contrast).
                if (!selected && c != null) setForeground(c);
            }
            return this;
        }
    }
}
