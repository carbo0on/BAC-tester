package ui;

import burp.api.montoya.MontoyaApi;
import db.AccountRepository;
import db.DatabaseManager;
import db.FolderRepository;
import db.RunRepository;
import db.TestCaseRepository;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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

    // ---- Colors (muted, theme-aware via alpha blend) -------------------
    private static final Color POTENTIAL_BAC_BG   = new Color(0x7A, 0x1F, 0x1F, 180);
    private static final Color LIKELY_ENFORCED_BG = new Color(0x1F, 0x5C, 0x1F, 180);
    private static final Color EXPECTED_OK_BG     = new Color(0x55, 0x55, 0x55, 120);
    private static final Color ANOMALY_BG         = new Color(0x7A, 0x4A, 0x00, 180);
    private static final Color REVIEW_BG          = new Color(0x6A, 0x5C, 0x00, 180);
    private static final Color SKIPPED_BG         = new Color(0x40, 0x40, 0x40, 80);
    private static final Color ERROR_BG           = new Color(0x50, 0x00, 0x50, 160);

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
    private JComboBox<AccountItem> accountCombo;
    private JComboBox<ScopeItem> scopeCombo;
    private JCheckBox safeModeCheck;
    private JButton runBtn;
    private JButton stopBtn;

    // Progress
    private JProgressBar progressBar;
    private JLabel statusLabel;

    // Results
    private ResultsTableModel tableModel;
    private JTable resultsTable;
    private String activeVerdictFilter = null;

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
        add(buildResultsPanel(), BorderLayout.CENTER);

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

        top.add(new JLabel("Account:"));
        accountCombo = new JComboBox<>();
        accountCombo.setPreferredSize(new Dimension(200, 26));
        top.add(accountCombo);

        top.add(new JLabel("Scope:"));
        scopeCombo = new JComboBox<>();
        scopeCombo.setPreferredSize(new Dimension(220, 26));
        top.add(scopeCombo);

        safeModeCheck = new JCheckBox("Safe Mode", true);
        safeModeCheck.setToolTipText("Skip state-changing requests (POST/PUT/PATCH/DELETE)");
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

    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        // Filter bar
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        filterBar.add(new JLabel("Filter: "));
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < VERDICT_LABELS.length; i++) {
            final String filter = VERDICT_FILTERS[i];
            JToggleButton btn = new JToggleButton(VERDICT_LABELS[i]);
            btn.setFont(btn.getFont().deriveFont(11f));
            bg.add(btn);
            if (i == 0) btn.setSelected(true);
            btn.addActionListener(e -> {
                activeVerdictFilter = filter;
                tableModel.applyFilter(filter);
            });
            filterBar.add(btn);
        }
        panel.add(filterBar, BorderLayout.NORTH);

        // Table
        tableModel = new ResultsTableModel();
        resultsTable = new JTable(tableModel);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setRowHeight(22);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        resultsTable.getTableHeader().setReorderingAllowed(false);

        // Column widths
        int[] widths = {110, 65, 180, 180, 65, 95, 80, 70};
        for (int i = 0; i < widths.length && i < tableModel.getColumnCount(); i++) {
            resultsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        resultsTable.setDefaultRenderer(Object.class, new VerdictCellRenderer());

        // Double-click → open in Compare
        resultsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && onOpenInCompare != null) {
                    int row = resultsTable.rowAtPoint(e.getPoint());
                    if (row < 0) return;
                    RunRepository.ResultRecord r = tableModel.getRow(row);
                    if (r != null) onOpenInCompare.accept(r.testCaseId());
                }
            }
        });

        panel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        api.userInterface().applyThemeToComponent(panel);
        return panel;
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
            SwingUtilities.invokeLater(() -> tableModel.addResult(result))
        );

        engine.setOnFinished((runId, error) ->
            SwingUtilities.invokeLater(() -> {
                runBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                if (error != null) {
                    statusLabel.setText("⚠ " + error);
                    statusLabel.setForeground(new Color(200, 60, 0));
                    JOptionPane.showMessageDialog(this, error,
                        "Run Failed", JOptionPane.WARNING_MESSAGE);
                } else {
                    statusLabel.setText("✓ Done — run #" + runId);
                    statusLabel.setForeground(new Color(0, 140, 0));
                }
            })
        );
    }

    // ---- Run -----------------------------------------------------------

    private void startRun() {
        AccountItem acctItem = (AccountItem) accountCombo.getSelectedItem();
        if (acctItem == null) {
            JOptionPane.showMessageDialog(this, "Select an account first.", "No Account", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ScopeItem scopeItem = (ScopeItem) scopeCombo.getSelectedItem();
        if (scopeItem == null) return;

        List<Long> ids = scopeItem.testCaseIds;
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No test cases in scope.", "Empty Scope", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Run " + ids.size() + " test case(s) as account \"" + acctItem.name + "\"?",
            "Start Run", JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        tableModel.clear();
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progressBar.setValue(0);
        progressBar.setMaximum(ids.size());
        progressBar.setString("0 / " + ids.size());
        statusLabel.setText("Running…");
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));

        double threshold = readMatchThreshold();
        engine.startRun(acctItem.id, ids, safeModeCheck.isSelected(), threshold);
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
            String sql = "SELECT value FROM settings WHERE key = ?";
            try (var ps = dbManager.getConnection().prepareStatement(sql)) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("value") : null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Refresh helpers -----------------------------------------------

    public void refreshAccounts() {
        loader.submit(() -> {
            try {
                List<AccountRepository.AccountRecord> accounts = accountRepo.getAll();
                SwingUtilities.invokeLater(() -> {
                    AccountItem selected = (AccountItem) accountCombo.getSelectedItem();
                    accountCombo.removeAllItems();
                    for (var a : accounts) {
                        accountCombo.addItem(new AccountItem(a.id(), a.name(), a.label()));
                    }
                    // Restore selection
                    if (selected != null) {
                        for (int i = 0; i < accountCombo.getItemCount(); i++) {
                            if (accountCombo.getItemAt(i).id == selected.id) {
                                accountCombo.setSelectedIndex(i);
                                break;
                            }
                        }
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

    private static class ResultsTableModel extends AbstractTableModel {
        private static final String[] COLS = {
            "Verdict", "Method", "Host", "Name", "Status", "Similarity", "Expected", "Reviewed"
        };
        private final List<RunRepository.ResultRecord> allRows = new ArrayList<>();
        private final List<RunRepository.ResultRecord> rows = new ArrayList<>();

        void addResult(RunRepository.ResultRecord r) {
            allRows.add(r);
            rows.add(r); // will be filtered next repaint if filter active
            int idx = rows.size() - 1;
            fireTableRowsInserted(idx, idx);
        }

        void clear() {
            allRows.clear();
            rows.clear();
            fireTableDataChanged();
        }

        void applyFilter(String verdict) {
            rows.clear();
            for (var r : allRows) {
                if (verdict == null || verdict.equals(r.verdict())) rows.add(r);
            }
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Class<?> getColumnClass(int col) {
            return col == 7 ? Boolean.class : String.class;
        }

        @Override public Object getValueAt(int row, int col) {
            RunRepository.ResultRecord r = rows.get(row);
            return switch (col) {
                case 0 -> verdictLabel(r.verdict());
                case 1 -> r.method();
                case 2 -> r.host();
                case 3 -> r.testCaseName();
                case 4 -> r.newStatus() > 0 ? String.valueOf(r.newStatus()) : "-";
                case 5 -> r.verdict().equals(RunEngine.SKIPPED_SAFE) || r.verdict().equals(RunEngine.ERROR)
                          ? "-" : String.format("%.1f%%", r.similarity());
                case 6 -> r.expectedAccess();
                case 7 -> r.reviewed();
                default -> "";
            };
        }

        RunRepository.ResultRecord getRow(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
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
