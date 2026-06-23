package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import db.AccountRepository;
import db.DatabaseManager;
import db.FolderRepository;
import db.RunRepository;
import db.TestCaseRepository;
import engine.RunEngine;

import java.util.HashMap;
import java.util.Map;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4 — Test Run tab.
 *
 * Layout:
 *   ┌─ Config panel ─────────────────────────────────────────────────────┐
 *   │  Account ▾   [Scope: All / Folder / Selection ▾]   [Run ▶]  [Stop]│
 *   └────────────────────────────────────────────────────────────────────┘
 *   ┌─ Progress ─────────────────────────────────────────────────────────┐
 *   │  [=====>         ]  12 / 45     ● RUNNING / ✓ DONE               │
 *   └────────────────────────────────────────────────────────────────────┘
 *   ┌─ Results table ────────────────────────────────────────────────────┐
 *   │  verdict | method | host | name | status | similarity | expectedAcc│
 *   └────────────────────────────────────────────────────────────────────┘
 *   [Filter: All | 🚩 BAC | ⚠ ANOMALY | 🔍 REVIEW | ✅ OK]
 */
public class TestRunTab extends JPanel {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // Pastel tints — same low-alpha approach as Burp Proxy history colours.
    private static final Color POTENTIAL_BAC_BG   = new Color(255, 80,  80,  65);
    private static final Color LIKELY_ENFORCED_BG = new Color(60,  200, 60,  55);
    private static final Color EXPECTED_OK_BG     = new Color(150, 150, 150, 40);
    private static final Color ANOMALY_BG         = new Color(255, 155, 40,  60);
    private static final Color REVIEW_BG          = new Color(245, 215, 40,  60);
    private static final Color SKIPPED_BG         = new Color(130, 130, 130, 35);
    private static final Color ERROR_BG           = new Color(200, 60,  200, 55);

    // Verdict label text
    private static final String[] VERDICT_LABELS = {
        "All", "🚩 POTENTIAL_BAC", "⚠ ANOMALY", "🔍 REVIEW",
        "✅ LIKELY_ENFORCED", "⚪ EXPECTED_OK", "SKIPPED"
    };
    private static final String[] VERDICT_FILTERS = {
        null, RunEngine.POTENTIAL_BAC, RunEngine.ANOMALY, RunEngine.REVIEW,
        RunEngine.LIKELY_ENFORCED, RunEngine.EXPECTED_OK, RunEngine.SKIPPED_SAFE
    };

    private final MontoyaApi api;
    private final RunEngine engine;
    private final AccountRepository accountRepo;
    private final TestCaseRepository tcRepo;
    private final FolderRepository folderRepo;
    private final DatabaseManager dbManager;

    // Config
    private JList<AccountItem> accountList;          // multi-select: run one or more accounts
    private JComboBox<ScopeItem> scopeCombo;
    private JCheckBox safeModeCheck;
    private JButton runBtn;
    private JButton stopBtn;

    // Sequential multi-account run queue
    private final java.util.ArrayDeque<Long> accountQueue = new java.util.ArrayDeque<>();
    private List<Long> queuedTcIds = new ArrayList<>();
    private boolean queuedSafeMode;
    private double  queuedThreshold;
    private int     queueTotalAccounts;

    // Progress
    private JProgressBar progressBar;
    private JLabel statusLabel;

    // Results
    private ResultsTableModel tableModel;
    private JTable resultsTable;
    private String activeVerdictFilter = null;
    private JTextField resultsSearch;
    private JComboBox<String> statusFilterCombo;
    private JComboBox<String> sizeFilterCombo;
    private ColumnManager columnManager;

    // Inline request/response viewer (Burp-native, read-only)
    private HttpRequestEditor  reqViewer;
    private HttpResponseEditor respViewer;

    // Verdict summary counts (updated as results stream in)
    private final Map<String, Integer> verdictCounts = new HashMap<>();
    private final Map<String, JLabel> verdictCountLabels = new HashMap<>();

    // Overview matrix
    private OverviewMatrix overviewMatrix;

    // Async loader for UI data
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-testrun-loader");
        t.setDaemon(true);
        return t;
    });

    private Consumer<Long> onOpenInCompare;

    public TestRunTab(MontoyaApi api, RunEngine engine,
                      AccountRepository accountRepo,
                      TestCaseRepository tcRepo,
                      FolderRepository folderRepo,
                      DatabaseManager dbManager) {
        super(new BorderLayout(0, 6));
        this.api = api;
        this.engine = engine;
        this.accountRepo = accountRepo;
        this.tcRepo = tcRepo;
        this.folderRepo = folderRepo;
        this.dbManager = dbManager;

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildConfigPanel(), BorderLayout.NORTH);
        add(buildMainArea(), BorderLayout.CENTER);

        wireEngine();
        refreshAccounts();
        refreshScope();
        api.userInterface().applyThemeToComponent(this);
    }

    // ---- Build UI ------------------------------------------------------

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));

        // Top row: account + scope + run/stop
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        top.add(new JLabel("Accounts:"));
        accountList = new JList<>(new DefaultListModel<>());
        accountList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        accountList.setVisibleRowCount(3);
        accountList.setToolTipText("Select one or more accounts — each is run in turn (Ctrl/Shift-click for multiple)");
        JScrollPane accScroll = new JScrollPane(accountList);
        accScroll.setPreferredSize(new Dimension(220, 62));
        top.add(accScroll);

        top.add(new JLabel("Scope:"));
        scopeCombo = new JComboBox<>();
        scopeCombo.setPreferredSize(new Dimension(220, 26));
        top.add(scopeCombo);

        safeModeCheck = new JCheckBox("Safe Mode", !"false".equalsIgnoreCase(readSetting("safe_mode")));
        safeModeCheck.setToolTipText("Skip DELETE requests during runs (other methods are still replayed)");
        top.add(safeModeCheck);

        runBtn = new JButton("Run ▶");
        runBtn.setForeground(new Color(0, 150, 60));
        runBtn.addActionListener(e -> startRun());
        top.add(runBtn);

        stopBtn = new JButton("Stop ■");
        stopBtn.setForeground(new Color(180, 0, 0));
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> {
            // Engine doesn't have a stop signal; just disable the button
            // (the current test case finishes then the run ends naturally — see engine TODO)
            statusLabel.setText("Stopping after current request…");
            stopBtn.setEnabled(false);
        });
        top.add(stopBtn);

        panel.add(top, BorderLayout.NORTH);

        // Progress row
        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("No run yet");
        statusLabel = new JLabel("Idle");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(statusLabel, BorderLayout.EAST);
        progressRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        panel.add(progressRow, BorderLayout.SOUTH);

        api.userInterface().applyThemeToComponent(panel);
        api.userInterface().applyThemeToComponent(top);
        api.userInterface().applyThemeToComponent(progressRow);
        return panel;
    }

    /** Builds the CENTER area: verdict summary + JTabbedPane(Results, Matrix). */
    private JPanel buildMainArea() {
        JPanel area = new JPanel(new BorderLayout(0, 4));
        area.add(buildVerdictSummary(), BorderLayout.NORTH);

        // Results sub-panel
        JPanel resultsPanel = buildResultsPanel();

        // Matrix sub-panel
        overviewMatrix = new OverviewMatrix(new db.RunRepository(dbManager), api);
        overviewMatrix.setOnOpenInCompare(tcId -> {
            if (onOpenInCompare != null) onOpenInCompare.accept(tcId);
        });

        JTabbedPane subTabs = new JTabbedPane();
        subTabs.addTab("Results", resultsPanel);
        subTabs.addTab("Matrix",  overviewMatrix);
        // Refresh matrix when switching to its tab
        subTabs.addChangeListener(e -> {
            if (subTabs.getSelectedIndex() == 1) overviewMatrix.refresh();
        });
        api.userInterface().applyThemeToComponent(subTabs);

        area.add(subTabs, BorderLayout.CENTER);
        api.userInterface().applyThemeToComponent(area);
        return area;
    }

    private JPanel buildVerdictSummary() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));

        // Initialize counts and create one chip per verdict
        String[][] verdictDefs = {
            {RunEngine.POTENTIAL_BAC,   "🚩 BAC",     "CC2222"},
            {RunEngine.ANOMALY,         "⚠ ANOMALY",  "CC7700"},
            {RunEngine.REVIEW,          "🔍 REVIEW",  "AA9900"},
            {RunEngine.LIKELY_ENFORCED, "✅ ENFORCED","227722"},
            {RunEngine.EXPECTED_OK,     "⚪ OK",       "777777"},
            {RunEngine.SKIPPED_SAFE,    "— SKIPPED",  "555555"},
            {RunEngine.ERROR,           "✗ ERROR",    "882288"},
        };
        for (String[] def : verdictDefs) {
            verdictCounts.put(def[0], 0);
            JLabel chip = new JLabel(def[1] + ": 0");
            chip.setFont(chip.getFont().deriveFont(11f));
            chip.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
            chip.setForeground(new Color(Integer.parseInt(def[2], 16)));
            verdictCountLabels.put(def[0], chip);
            bar.add(chip);
        }
        api.userInterface().applyThemeToComponent(bar);
        return bar;
    }

    private void incrementVerdictCount(String verdict) {
        verdictCounts.merge(verdict, 1, Integer::sum);
        JLabel lbl = verdictCountLabels.get(verdict);
        if (lbl == null) return;
        String display = switch (verdict) {
            case RunEngine.POTENTIAL_BAC   -> "🚩 BAC";
            case RunEngine.ANOMALY         -> "⚠ ANOMALY";
            case RunEngine.REVIEW          -> "🔍 REVIEW";
            case RunEngine.LIKELY_ENFORCED -> "✅ ENFORCED";
            case RunEngine.EXPECTED_OK     -> "⚪ OK";
            case RunEngine.SKIPPED_SAFE    -> "— SKIPPED";
            case RunEngine.ERROR           -> "✗ ERROR";
            default -> verdict;
        };
        lbl.setText(display + ": " + verdictCounts.get(verdict));
    }

    private void resetVerdictCounts() {
        verdictCounts.replaceAll((k, v) -> 0);
        for (var entry : verdictCountLabels.entrySet()) {
            String display = switch (entry.getKey()) {
                case RunEngine.POTENTIAL_BAC   -> "🚩 BAC";
                case RunEngine.ANOMALY         -> "⚠ ANOMALY";
                case RunEngine.REVIEW          -> "🔍 REVIEW";
                case RunEngine.LIKELY_ENFORCED -> "✅ ENFORCED";
                case RunEngine.EXPECTED_OK     -> "⚪ OK";
                case RunEngine.SKIPPED_SAFE    -> "— SKIPPED";
                case RunEngine.ERROR           -> "✗ ERROR";
                default -> entry.getKey();
            };
            entry.getValue().setText(display + ": 0");
        }
    }

    private void applyResultsFilter() {
        String search = resultsSearch != null ? resultsSearch.getText().trim().toLowerCase() : "";
        String statusSel = statusFilterCombo != null ? (String) statusFilterCombo.getSelectedItem() : "All Status";
        String sizeSel = sizeFilterCombo != null ? (String) sizeFilterCombo.getSelectedItem() : "Any size";
        tableModel.applyFilter(activeVerdictFilter, search, statusSel, sizeSel);
    }

    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        // Filter bar row 1: verdict toggles
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        filterBar.add(new JLabel("Verdict: "));
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < VERDICT_LABELS.length; i++) {
            final String filter = VERDICT_FILTERS[i];
            JToggleButton btn = new JToggleButton(VERDICT_LABELS[i]);
            btn.setFont(btn.getFont().deriveFont(11f));
            bg.add(btn);
            if (i == 0) btn.setSelected(true);
            btn.addActionListener(e -> {
                activeVerdictFilter = filter;
                applyResultsFilter();
            });
            filterBar.add(btn);
        }

        // Filter bar row 2: search + status
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        searchBar.add(new JLabel("Search:"));
        resultsSearch = new JTextField(18);
        resultsSearch.setToolTipText("Filter by name, host, or URL");
        resultsSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyResultsFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyResultsFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyResultsFilter(); }
        });
        searchBar.add(resultsSearch);

        searchBar.add(new JLabel("  Status:"));
        statusFilterCombo = new JComboBox<>(new String[]{
            "All Status", "2xx (OK)", "3xx (Redirect)", "4xx (Client err)", "5xx (Server err)",
            "200", "301", "302", "401", "403", "404", "500"
        });
        statusFilterCombo.setPreferredSize(new Dimension(140, 24));
        statusFilterCombo.addActionListener(e -> applyResultsFilter());
        searchBar.add(statusFilterCombo);

        searchBar.add(new JLabel("  Size:"));
        sizeFilterCombo = new JComboBox<>(new String[]{
            "Any size", "< 1 KB", "1–10 KB", "10–100 KB", "> 100 KB"
        });
        sizeFilterCombo.setPreferredSize(new Dimension(110, 24));
        sizeFilterCombo.addActionListener(e -> applyResultsFilter());
        searchBar.add(sizeFilterCombo);

        JButton clearBtn = new JButton("✕ Clear");
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.addActionListener(e -> {
            resultsSearch.setText("");
            statusFilterCombo.setSelectedIndex(0);
            sizeFilterCombo.setSelectedIndex(0);
            applyResultsFilter();
        });
        searchBar.add(clearBtn);

        JButton colsBtn = new JButton("Columns ▾");
        colsBtn.setFont(colsBtn.getFont().deriveFont(11f));
        colsBtn.setToolTipText("Show / hide columns and restore the default layout");
        searchBar.add(colsBtn);

        JButton clearHistoryBtn = new JButton("🗑 Clear history");
        clearHistoryBtn.setFont(clearHistoryBtn.getFont().deriveFont(11f));
        clearHistoryBtn.setToolTipText("Remove all rows from this results view (kept across runs otherwise)");
        clearHistoryBtn.addActionListener(e -> {
            tableModel.clear();
            resetVerdictCounts();
        });
        searchBar.add(clearHistoryBtn);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(filterBar, BorderLayout.NORTH);
        northPanel.add(searchBar, BorderLayout.SOUTH);
        panel.add(northPanel, BorderLayout.NORTH);

        // Table
        tableModel = new ResultsTableModel();
        resultsTable = new JTable(tableModel);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setRowHeight(22);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // honour per-column widths
        resultsTable.getTableHeader().setReorderingAllowed(true);
        resultsTable.setDefaultRenderer(Object.class, new VerdictCellRenderer());

        // Show/hide columns + restore default layout
        columnManager = new ColumnManager(resultsTable);
        colsBtn.addActionListener(e -> columnManager.showMenu(colsBtn));
        // Header right-click also opens the column chooser
        resultsTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(MouseEvent e) { maybe(e); }
            private void maybe(MouseEvent e) {
                if (e.isPopupTrigger()) columnManager.showMenu(e.getComponent(), e.getX(), e.getY());
            }
        });

        // Double-click → open in Compare
        resultsTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && onOpenInCompare != null) {
                    int row = resultsTable.rowAtPoint(e.getPoint());
                    if (row < 0) return;
                    RunRepository.ResultRecord r = tableModel.getRow(row);
                    if (r != null) onOpenInCompare.accept(r.testCaseId());
                }
            }
        });

        // Single-click → populate the inline request/response viewer
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = resultsTable.getSelectedRow();
            if (row >= 0) populateViewer(tableModel.getRow(row));
        });

        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setMinimumSize(new Dimension(0, 150));

        // Inline viewer (request left / response right)
        reqViewer  = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        respViewer = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        JPanel viewer = buildViewerPanel();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, viewer);
        split.setResizeWeight(0.55);
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        // Proportional divider locations are ignored until the split has a real
        // size; apply it once on first layout so the viewer is never collapsed.
        split.addComponentListener(new ComponentAdapter() {
            private boolean applied = false;
            @Override public void componentResized(ComponentEvent e) {
                if (!applied && split.getHeight() > 0) {
                    applied = true;
                    split.setDividerLocation(0.55);
                }
            }
        });

        panel.add(split, BorderLayout.CENTER);
        api.userInterface().applyThemeToComponent(panel);
        return panel;
    }

    private JPanel buildViewerPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel header = new JLabel("  Request / Response  —  select a result row to preview");
        header.setFont(header.getFont().deriveFont(Font.ITALIC, 11f));
        header.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            UIManager.getColor("Separator.foreground")));
        panel.add(header, BorderLayout.NORTH);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            reqViewer.uiComponent(), respViewer.uiComponent());
        sp.setResizeWeight(0.5);
        sp.setDividerLocation(0.5);
        sp.setDividerSize(4);
        sp.setContinuousLayout(true);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    /** Loads the test-case request and the result's stored response into the viewer. */
    private void populateViewer(RunRepository.ResultRecord rec) {
        if (rec == null) return;
        final long tcId = rec.testCaseId();
        final byte[] respBytes = rec.newResponseRaw();
        loader.submit(() -> {
            byte[] reqBytes = null;
            try { reqBytes = tcRepo.getRequestRaw(tcId); }
            catch (Exception ex) { api.logging().logToError("[BAC] Viewer request load: " + ex.getMessage()); }
            final byte[] req = reqBytes;
            SwingUtilities.invokeLater(() -> {
                try {
                    if (req != null && req.length > 0)
                        reqViewer.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(req)));
                    if (respBytes != null && respBytes.length > 0) {
                        respViewer.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(respBytes)));
                    } else {
                        // No stored response (SKIPPED / ERROR / unreachable target).
                        respViewer.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(
                            "HTTP/1.1 000 No response stored\r\nContent-Type: text/plain\r\n\r\n"
                          + "This result has no captured response.\r\n"
                          + "Cause: the request was skipped (Safe Mode), errored, or the target was "
                          + "unreachable/out of scope. Check the extension Output log for details, "
                          + "then re-run.")));
                    }
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] Viewer render failed: " + ex.getMessage());
                }
            });
        });
    }

    // ---- Engine wiring -------------------------------------------------

    private void wireEngine() {
        engine.setOnProgress((done, total) ->
            SwingUtilities.invokeLater(() -> {
                progressBar.setMaximum(total);
                progressBar.setValue(done);
                progressBar.setString(done + " / " + total);
                statusLabel.setText("Running…");
            })
        );

        engine.setOnResult(result ->
            SwingUtilities.invokeLater(() -> {
                tableModel.addResult(result);
                incrementVerdictCount(result.verdict());
            })
        );

        engine.setOnFinished((runId, error) ->
            SwingUtilities.invokeLater(() -> {
                if (error != null) {
                    // Abort the whole queue on a hard failure (e.g. canary).
                    accountQueue.clear();
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    statusLabel.setText("⚠ " + error);
                    statusLabel.setForeground(new Color(200, 60, 0));
                    JOptionPane.showMessageDialog(this, error,
                        "Run Failed", JOptionPane.WARNING_MESSAGE);
                } else if (!accountQueue.isEmpty()) {
                    // More accounts queued — keep going (history is preserved).
                    if (overviewMatrix != null) overviewMatrix.refresh();
                    runNextAccount();
                } else {
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    statusLabel.setText(queueTotalAccounts > 1
                        ? "✓ Done — " + queueTotalAccounts + " accounts"
                        : "✓ Done — run #" + runId);
                    statusLabel.setForeground(new Color(0, 140, 0));
                    if (resultsTable.getRowCount() > 0) resultsTable.setRowSelectionInterval(0, 0);
                    if (overviewMatrix != null) overviewMatrix.refresh();
                }
            })
        );
    }

    // ---- Run -----------------------------------------------------------

    /** Called from Library's "Run on selected ▶" to start a run without the confirm dialog. */
    public void startRunDirectly(long accountId, List<Long> tcIds) {
        if (engine.isRunning()) {
            JOptionPane.showMessageDialog(this, "A run is already in progress.",
                "Busy", JOptionPane.WARNING_MESSAGE);
            return;
        }
        startQueue(List.of(accountId), tcIds);
    }

    private void startRun() {
        List<AccountItem> selected = accountList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select one or more accounts first.",
                "No Account", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ScopeItem scopeItem = (ScopeItem) scopeCombo.getSelectedItem();
        if (scopeItem == null) return;

        List<Long> ids = scopeItem.testCaseIds;
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No test cases in scope.", "Empty Scope", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean confirmBeforeRun = !"false".equalsIgnoreCase(readSetting("confirm_before_run"));
        if (confirmBeforeRun) {
            String who = selected.size() == 1 ? "account \"" + selected.get(0).name + "\""
                                              : selected.size() + " accounts";
            int confirm = JOptionPane.showConfirmDialog(this,
                "Run " + ids.size() + " test case(s) as " + who + "?",
                "Start Run", JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        }

        List<Long> acctIds = selected.stream().map(a -> a.id).toList();
        startQueue(acctIds, ids);
    }

    /** Queue one or more accounts to run sequentially over the same test cases. */
    private void startQueue(List<Long> accountIds, List<Long> tcIds) {
        if (engine.isRunning()) {
            JOptionPane.showMessageDialog(this, "A run is already in progress.",
                "Busy", JOptionPane.WARNING_MESSAGE);
            return;
        }
        accountQueue.clear();
        accountQueue.addAll(accountIds);
        queuedTcIds       = tcIds;
        queuedSafeMode    = safeModeCheck.isSelected();
        queuedThreshold   = readMatchThreshold();
        queueTotalAccounts = accountIds.size();

        // History is preserved across runs — do NOT clear the results table.
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        runNextAccount();
    }

    /** Start the next queued account, or finish if the queue is empty. */
    private void runNextAccount() {
        Long accountId = accountQueue.poll();
        if (accountId == null) {
            runBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            statusLabel.setText("✓ Done — all accounts");
            statusLabel.setForeground(new Color(0, 140, 0));
            if (resultsTable.getRowCount() > 0) resultsTable.setRowSelectionInterval(0, 0);
            if (overviewMatrix != null) overviewMatrix.refresh();
            return;
        }
        int idx = queueTotalAccounts - accountQueue.size(); // 1-based index of current account
        progressBar.setValue(0);
        progressBar.setMaximum(queuedTcIds.size());
        progressBar.setString("0 / " + queuedTcIds.size());
        statusLabel.setText("Running account " + idx + " / " + queueTotalAccounts + "…");
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        engine.startRun(accountId, queuedTcIds, queuedSafeMode, queuedThreshold);
    }

    private double readMatchThreshold() {
        try {
            String val = readSetting("match_threshold");
            if (val != null) return Double.parseDouble(val);
        } catch (Exception ignored) {}
        return 95.0;
    }

    private String readSetting(String key) {
        try {
            return dbManager.getSetting(key);
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Refresh helpers -----------------------------------------------

    public void refreshAccounts() {
        loader.submit(() -> {
            try {
                List<AccountRepository.AccountRecord> accounts = accountRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    // Preserve current selection by id
                    java.util.Set<Long> selectedIds = new java.util.HashSet<>();
                    for (AccountItem it : accountList.getSelectedValuesList()) selectedIds.add(it.id);

                    DefaultListModel<AccountItem> model = new DefaultListModel<>();
                    for (var a : accounts) model.addElement(new AccountItem(a.id(), a.name(), a.label()));
                    accountList.setModel(model);

                    java.util.List<Integer> restore = new ArrayList<>();
                    for (int i = 0; i < model.size(); i++)
                        if (selectedIds.contains(model.get(i).id)) restore.add(i);
                    if (!restore.isEmpty()) {
                        int[] idx = restore.stream().mapToInt(Integer::intValue).toArray();
                        accountList.setSelectedIndices(idx);
                    } else if (model.size() == 1) {
                        accountList.setSelectedIndex(0);
                    }
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] TestRunTab account load: " + e.getMessage());
            }
        });
    }

    public void setOnOpenInCompare(Consumer<Long> cb) { this.onOpenInCompare = cb; }

    public void refreshScope() {
        loader.submit(() -> {
            try {
                List<TestCaseRepository.TestCaseRow> all = tcRepo.getAll();
                List<FolderRepository.FolderRecord> folders = folderRepo.getAllFolders();

                List<ScopeItem> items = new ArrayList<>();
                // All test cases
                List<Long> allIds = all.stream().map(TestCaseRepository.TestCaseRow::id).toList();
                items.add(new ScopeItem("All (" + all.size() + ")", allIds));

                // Inbox
                List<Long> inbox = all.stream()
                    .filter(tc -> tc.folderId() == null)
                    .map(TestCaseRepository.TestCaseRow::id).toList();
                items.add(new ScopeItem("Inbox (" + inbox.size() + ")", inbox));

                // Per folder
                for (var folder : folders) {
                    List<Long> ids = all.stream()
                        .filter(tc -> tc.folderId() != null && tc.folderId() == folder.id())
                        .map(TestCaseRepository.TestCaseRow::id).toList();
                    items.add(new ScopeItem(folder.name() + " (" + ids.size() + ")", ids));
                }

                SwingUtilities.invokeLater(() -> {
                    int selIdx = scopeCombo.getSelectedIndex();
                    scopeCombo.removeAllItems();
                    for (var item : items) scopeCombo.addItem(item);
                    if (selIdx >= 0 && selIdx < scopeCombo.getItemCount())
                        scopeCombo.setSelectedIndex(selIdx);
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] TestRunTab scope load: " + e.getMessage());
            }
        });
    }

    // ---- Table model ---------------------------------------------------

    /** A result row plus values derived once from the stored response/URL. */
    private static final class Row {
        final RunRepository.ResultRecord rec;
        final String path;
        final int params;
        final String mime;
        final String title;
        Row(RunRepository.ResultRecord rec) {
            this.rec    = rec;
            this.path   = ResultsTableModel.extractPath(rec.url());
            this.params = ResultsTableModel.paramCount(rec.url());
            this.mime   = ResultsTableModel.mimeType(rec.newResponseRaw());
            this.title  = ResultsTableModel.pageTitle(rec.newResponseRaw());
        }
    }

    static final String[] RESULT_COLS = {
        "Verdict", "Host", "Account", "Method", "URL", "Params", "Status",
        "Length", "MIME type", "Title", "Notes", "Time requested", "Similarity", "Reviewed"
    };

    private static class ResultsTableModel extends AbstractTableModel {
        private final List<Row> allRows = new ArrayList<>();
        private final List<Row> rows = new ArrayList<>();

        void addResult(RunRepository.ResultRecord r) {
            Row row = new Row(r);
            // Newest on top so repeated runs (incl. other accounts) stack above the old.
            allRows.add(0, row);
            rows.add(0, row);
            fireTableRowsInserted(0, 0);
        }

        void clear() {
            allRows.clear();
            rows.clear();
            fireTableDataChanged();
        }

        void applyFilter(String verdict, String search, String statusSel, String sizeSel) {
            rows.clear();
            for (Row row : allRows) {
                var r = row.rec;
                if (verdict != null && !verdict.equals(r.verdict())) continue;
                if (!matchesSearch(row, search)) continue;
                if (!matchesStatus(r, statusSel)) continue;
                if (!matchesSize(r, sizeSel)) continue;
                rows.add(row);
            }
            fireTableDataChanged();
        }

        private static boolean matchesSearch(Row row, String q) {
            if (q == null || q.isBlank()) return true;
            var r = row.rec;
            return ci(r.testCaseName(), q) || ci(r.host(), q) || ci(r.url(), q)
                || ci(r.method(), q) || ci(r.accountName(), q) || ci(row.title, q);
        }

        private static boolean ci(String s, String q) {
            return s != null && s.toLowerCase().contains(q);
        }

        private static boolean matchesStatus(RunRepository.ResultRecord r, String sel) {
            if (sel == null || sel.startsWith("All")) return true;
            int st = r.newStatus();
            return switch (sel) {
                case "2xx (OK)"          -> st >= 200 && st < 300;
                case "3xx (Redirect)"    -> st >= 300 && st < 400;
                case "4xx (Client err)"  -> st >= 400 && st < 500;
                case "5xx (Server err)"  -> st >= 500 && st < 600;
                default -> {
                    try { yield Integer.parseInt(sel.split(" ")[0]) == st; }
                    catch (NumberFormatException e) { yield true; }
                }
            };
        }

        private static boolean matchesSize(RunRepository.ResultRecord r, String sel) {
            if (sel == null || sel.startsWith("Any")) return true;
            int n = r.newLength();
            return switch (sel) {
                case "< 1 KB"     -> n < 1024;
                case "1–10 KB"    -> n >= 1024 && n < 10 * 1024;
                case "10–100 KB"  -> n >= 10 * 1024 && n < 100 * 1024;
                case "> 100 KB"   -> n >= 100 * 1024;
                default -> true;
            };
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return RESULT_COLS.length; }
        @Override public String getColumnName(int col) { return RESULT_COLS[col]; }
        @Override public Class<?> getColumnClass(int col) { return String.class; }

        @Override public Object getValueAt(int rowIdx, int col) {
            Row row = rows.get(rowIdx);
            var r = row.rec;
            boolean noBody = r.verdict().equals(RunEngine.SKIPPED_SAFE) || r.verdict().equals(RunEngine.ERROR);
            return switch (col) {
                case 0  -> verdictLabel(r.verdict());
                case 1  -> r.host();
                case 2  -> r.accountName() != null ? r.accountName() : "—";
                case 3  -> r.method();
                case 4  -> row.path;
                case 5  -> row.params >= 0 ? String.valueOf(row.params) : "—";
                case 6  -> r.newStatus() > 0 ? String.valueOf(r.newStatus()) : "—";
                case 7  -> formatSize(r.newLength());
                case 8  -> row.mime != null ? row.mime : "—";
                case 9  -> row.title != null ? row.title : "";
                case 10 -> r.userNote() != null ? r.userNote().replaceAll("\\s+", " ").trim()
                                                : (r.testCaseName() != null ? r.testCaseName() : "");
                case 11 -> FMT.format(Instant.ofEpochSecond(r.createdAt()));
                case 12 -> noBody ? "—" : String.format("%.1f%%", r.similarity());
                case 13 -> r.reviewed() ? "✓" : "";
                default -> "";
            };
        }

        RunRepository.ResultRecord getRow(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row).rec : null;
        }

        private static String verdictLabel(String v) {
            return switch (v) {
                case RunEngine.POTENTIAL_BAC   -> "🚩 POTENTIAL_BAC";
                case RunEngine.LIKELY_ENFORCED -> "✅ LIKELY_ENFORCED";
                case RunEngine.EXPECTED_OK     -> "⚪ EXPECTED_OK";
                case RunEngine.ANOMALY         -> "⚠ ANOMALY";
                case RunEngine.REVIEW          -> "🔍 REVIEW";
                case RunEngine.SKIPPED_SAFE    -> "— SKIPPED";
                case RunEngine.ERROR           -> "✗ ERROR";
                default -> v;
            };
        }

        // ---- Derived-value helpers (raw byte parsing — no MontoyaApi needed) ----

        private static String formatSize(int bytes) {
            if (bytes <= 0) return "—";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }

        private static String extractPath(String url) {
            if (url == null) return "/";
            try {
                var uri = new java.net.URI(url);
                String p = uri.getPath();
                String q = uri.getQuery();
                if (p == null || p.isEmpty()) p = "/";
                return q != null ? p + "?" + q : p;
            } catch (Exception e) {
                int slash = url.indexOf('/', url.indexOf("//") + 2);
                return slash >= 0 ? url.substring(slash) : url;
            }
        }

        private static int paramCount(String url) {
            if (url == null) return 0;
            int q = url.indexOf('?');
            if (q < 0 || q == url.length() - 1) return 0;
            String query = url.substring(q + 1);
            int count = 0;
            for (String part : query.split("&")) if (!part.isBlank()) count++;
            return count;
        }

        private static String mimeType(byte[] resp) {
            if (resp == null || resp.length == 0) return null;
            String head = new String(resp, 0, Math.min(resp.length, 4096));
            Matcher m = Pattern.compile("(?im)^Content-Type:\\s*([^;\\r\\n]+)").matcher(head);
            if (m.find()) {
                String ct = m.group(1).trim();
                int slash = ct.indexOf('/');
                return slash >= 0 ? ct.substring(slash + 1) : ct; // short form, e.g. "html", "json"
            }
            return null;
        }

        private static String pageTitle(byte[] resp) {
            if (resp == null || resp.length == 0) return null;
            String body = new String(resp, 0, Math.min(resp.length, 65536));
            Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
            if (m.find()) {
                String t = m.group(1).replaceAll("\\s+", " ").trim();
                return t.isEmpty() ? null : t;
            }
            return null;
        }
    }

    // ---- Column show/hide manager --------------------------------------

    /** Manages column visibility, default widths, and "restore default layout". */
    private final class ColumnManager {
        private final JTable t;
        private final TableColumn[] all;        // indexed by model column index
        private final boolean[] visible;
        private final int[] defaultWidths = {130, 150, 120, 70, 240, 70, 70, 80, 110, 200, 160, 140, 90, 90};

        ColumnManager(JTable table) {
            this.t = table;
            int n = table.getColumnModel().getColumnCount();
            all = new TableColumn[n];
            visible = new boolean[n];
            for (int i = 0; i < n; i++) {
                TableColumn tc = table.getColumnModel().getColumn(i);
                tc.setIdentifier(RESULT_COLS[i]);
                all[i] = tc;
                visible[i] = true;
            }
            restoreDefault();
        }

        void showMenu(Component invoker)                 { buildMenu().show(invoker, 0, invoker.getHeight()); }
        void showMenu(Component invoker, int x, int y)   { buildMenu().show(invoker, x, y); }

        private JPopupMenu buildMenu() {
            JPopupMenu menu = new JPopupMenu();
            for (int i = 0; i < all.length; i++) {
                final int idx = i;
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(RESULT_COLS[i], visible[i]);
                item.addActionListener(e -> setVisible(idx, item.isSelected()));
                menu.add(item);
            }
            menu.addSeparator();
            JMenuItem restore = new JMenuItem("Restore default layout");
            restore.addActionListener(e -> restoreDefault());
            menu.add(restore);
            return menu;
        }

        private void setVisible(int modelIdx, boolean show) {
            if (visible[modelIdx] == show) return;
            // Keep at least one column visible
            if (!show) {
                int shown = 0;
                for (boolean b : visible) if (b) shown++;
                if (shown <= 1) return;
            }
            visible[modelIdx] = show;
            rebuild();
        }

        private void restoreDefault() {
            for (int i = 0; i < visible.length; i++) {
                visible[i] = true;
                all[i].setPreferredWidth(i < defaultWidths.length ? defaultWidths[i] : 100);
            }
            rebuild();
        }

        /** Rebuild the column model from scratch in model-index order. */
        private void rebuild() {
            TableColumnModel cm = t.getColumnModel();
            while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
            for (int i = 0; i < all.length; i++) if (visible[i]) cm.addColumn(all[i]);
        }
    }

    // ---- Cell renderer -------------------------------------------------

    private static class VerdictCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);

            if (!isSelected) {
                ResultsTableModel model = (ResultsTableModel) table.getModel();
                RunRepository.ResultRecord rec = model.getRow(row);
                if (rec != null) {
                    Color bg = verdictColor(rec.verdict());
                    if (bg != null) {
                        Color base = table.getBackground();
                        c.setBackground(blend(bg, base));
                    } else {
                        c.setBackground(table.getBackground());
                    }
                }
            }
            return c;
        }

        private static Color verdictColor(String v) {
            return switch (v) {
                case RunEngine.POTENTIAL_BAC   -> POTENTIAL_BAC_BG;
                case RunEngine.LIKELY_ENFORCED -> LIKELY_ENFORCED_BG;
                case RunEngine.EXPECTED_OK     -> EXPECTED_OK_BG;
                case RunEngine.ANOMALY         -> ANOMALY_BG;
                case RunEngine.REVIEW          -> REVIEW_BG;
                case RunEngine.SKIPPED_SAFE    -> SKIPPED_BG;
                case RunEngine.ERROR           -> ERROR_BG;
                default -> null;
            };
        }

        private static Color blend(Color overlay, Color base) {
            float a = overlay.getAlpha() / 255f;
            int r = (int)(overlay.getRed() * a + base.getRed() * (1 - a));
            int g = (int)(overlay.getGreen() * a + base.getGreen() * (1 - a));
            int b = (int)(overlay.getBlue() * a + base.getBlue() * (1 - a));
            return new Color(
                Math.min(255, Math.max(0, r)),
                Math.min(255, Math.max(0, g)),
                Math.min(255, Math.max(0, b))
            );
        }
    }

    // ---- Helper types --------------------------------------------------

    private static class AccountItem {
        final long id;
        final String name;
        final String label;
        AccountItem(long id, String name, String label) {
            this.id = id; this.name = name; this.label = label;
        }
        @Override public String toString() { return label; }
    }

    private static class ScopeItem {
        final String label;
        final List<Long> testCaseIds;
        ScopeItem(String label, List<Long> ids) {
            this.label = label;
            this.testCaseIds = ids;
        }
        @Override public String toString() { return label; }
    }
}
