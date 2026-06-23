package ui;

import burp.api.montoya.MontoyaApi;
import db.RunRepository;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Phase 6 — Overview Matrix: requests (rows) × sessions/accounts (columns).
 *
 * Each cell is a colored square showing the verdict for that (TC, account) pair,
 * with a tooltip displaying similarity + status. Clicking a cell opens the
 * Compare tab for that test case, with the NEW pane set to that account's result.
 */
public class OverviewMatrix extends JPanel {

    // Pastel verdict colors — same palette as TestRunTab
    private static final Color POTENTIAL_BAC_BG   = new Color(255, 80,  80,  65);
    private static final Color LIKELY_ENFORCED_BG = new Color(60,  200, 60,  55);
    private static final Color EXPECTED_OK_BG     = new Color(150, 150, 150, 40);
    private static final Color ANOMALY_BG         = new Color(255, 155, 40,  60);
    private static final Color REVIEW_BG          = new Color(245, 215, 40,  60);
    private static final Color SKIPPED_BG         = new Color(130, 130, 130, 35);
    private static final Color ERROR_BG           = new Color(200, 60,  200, 55);

    private final RunRepository runRepo;
    private final MontoyaApi api;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-matrix-loader");
        t.setDaemon(true);
        return t;
    });

    private JTable table;
    private MatrixTableModel tableModel;
    private JLabel statusLabel;
    private Consumer<Long> onOpenInCompare; // fires with testCaseId

    public OverviewMatrix(RunRepository runRepo, MontoyaApi api) {
        super(new BorderLayout(0, 4));
        this.runRepo = runRepo;
        this.api = api;

        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        statusLabel = new JLabel("No results yet. Run a test to populate the matrix.");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JButton refreshBtn = new JButton("↻ Refresh");
        refreshBtn.addActionListener(e -> refresh());

        JPanel top = new JPanel(new BorderLayout());
        top.add(statusLabel, BorderLayout.CENTER);
        top.add(refreshBtn, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        tableModel = new MatrixTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new MatrixCellRenderer());
        table.setDefaultRenderer(String.class, new MatrixCellRenderer());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Tooltip on hover
        table.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                table.setToolTipText(tableModel.getTooltip(row, col));
            }
        });

        // Click → open in Compare
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) handleCellClick(e);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);

        JLabel hint = new JLabel(
            "Click a cell to open it in the Compare tab.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(hint, BorderLayout.SOUTH);

        api.userInterface().applyThemeToComponent(this);
    }

    public void setOnOpenInCompare(Consumer<Long> cb) { this.onOpenInCompare = cb; }

    /** Reload the matrix from the database in background. */
    public void refresh() {
        loader.submit(() -> {
            try {
                List<RunRepository.MatrixCell> cells = runRepo.getLatestResultMatrix();
                SwingUtilities.invokeLater(() -> buildModel(cells));
            } catch (Exception e) {
                api.logging().logToError("[BAC] Matrix refresh error: " + e.getMessage());
            }
        });
    }

    // ---- Build model from flat list ------------------------------------

    private void buildModel(List<RunRepository.MatrixCell> cells) {
        // Collect unique ordered accounts and TCs
        List<String> accounts   = new ArrayList<>(); // account names (ordered)
        List<Long>   accountIds = new ArrayList<>();
        List<String> tcNames    = new ArrayList<>(); // "method host — name"
        List<Long>   tcIds      = new ArrayList<>();

        for (var c : cells) {
            String acct = c.accountName() != null ? c.accountName() : "Account #" + c.accountId();
            if (!accountIds.contains(c.accountId())) {
                accountIds.add(c.accountId());
                accounts.add(acct);
            }
            String tcLabel = c.method() + " " + c.host() + " — " + c.testCaseName();
            if (!tcIds.contains(c.testCaseId())) {
                tcIds.add(c.testCaseId());
                tcNames.add(tcLabel);
            }
        }

        // Build 2D lookup: key=tcId+"_"+accountId → cell
        Map<String, RunRepository.MatrixCell> lookup = new HashMap<>();
        for (var c : cells) lookup.put(c.testCaseId() + "_" + c.accountId(), c);

        tableModel.setData(tcIds, tcNames, accountIds, accounts, lookup);

        // Set column widths
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(280);
            for (int i = 1; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(90);
            }
        }

        int total = tcIds.size();
        int populated = cells.size();
        statusLabel.setText(total == 0
            ? "No results yet. Run a test to populate the matrix."
            : total + " test cases × " + accounts.size() + " accounts (" + populated + " results total)");
    }

    // ---- Cell click ---------------------------------------------------

    private void handleCellClick(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        int col = table.columnAtPoint(e.getPoint());
        if (row < 0 || col <= 0) return; // col 0 = TC name

        RunRepository.MatrixCell cell = tableModel.getCellData(row, col);
        if (cell == null) return;
        if (onOpenInCompare != null) onOpenInCompare.accept(cell.testCaseId());
    }

    // ---- Table model --------------------------------------------------

    private static class MatrixTableModel extends AbstractTableModel {
        private List<Long>   tcIds      = new ArrayList<>();
        private List<String> tcNames    = new ArrayList<>();
        private List<Long>   accountIds = new ArrayList<>();
        private List<String> accountNames = new ArrayList<>();
        private Map<String, RunRepository.MatrixCell> data = new HashMap<>();

        void setData(List<Long> tcIds, List<String> tcNames,
                     List<Long> accountIds, List<String> accountNames,
                     Map<String, RunRepository.MatrixCell> data) {
            this.tcIds        = tcIds;
            this.tcNames      = tcNames;
            this.accountIds   = accountIds;
            this.accountNames = accountNames;
            this.data         = data;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return tcIds.size(); }
        @Override public int getColumnCount() { return 1 + accountIds.size(); }
        @Override public String getColumnName(int col) {
            return col == 0 ? "Test Case" : accountNames.get(col - 1);
        }
        @Override public Class<?> getColumnClass(int col) { return String.class; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        @Override public Object getValueAt(int row, int col) {
            if (col == 0) return tcNames.get(row);
            RunRepository.MatrixCell cell = getCellData(row, col);
            if (cell == null) return "";
            return String.format("%.0f%%", cell.similarity());
        }

        RunRepository.MatrixCell getCellData(int row, int col) {
            if (col == 0 || row >= tcIds.size() || col > accountIds.size()) return null;
            String key = tcIds.get(row) + "_" + accountIds.get(col - 1);
            return data.get(key);
        }

        long getTcId(int row) {
            return (row >= 0 && row < tcIds.size()) ? tcIds.get(row) : -1;
        }

        String getTooltip(int row, int col) {
            if (col == 0) {
                return row >= 0 && row < tcNames.size() ? tcNames.get(row) : null;
            }
            RunRepository.MatrixCell cell = getCellData(row, col);
            if (cell == null) return "No result for this account";
            return cell.testCaseName()
                + "  ·  Account: " + cell.accountName()
                + "  ·  Status: " + cell.newStatus()
                + "  ·  Similarity: " + String.format("%.1f%%", cell.similarity())
                + "  ·  Verdict: " + verdictLabel(cell.verdict());
        }
    }

    // ---- Cell renderer ------------------------------------------------

    private static class MatrixCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(
                table, value, selected, hasFocus, row, col);

            if (c instanceof JLabel lbl) lbl.setHorizontalAlignment(SwingConstants.CENTER);

            if (!selected && col > 0) {
                MatrixTableModel model = (MatrixTableModel) table.getModel();
                RunRepository.MatrixCell cell = model.getCellData(row, col);
                if (cell != null) {
                    Color bg = verdictColor(cell.verdict());
                    if (bg != null) c.setBackground(blendWithTable(bg, table.getBackground()));
                    else c.setBackground(table.getBackground());
                } else {
                    // No result — subtly darker
                    Color base = table.getBackground();
                    c.setBackground(new Color(
                        Math.max(0, base.getRed() - 10),
                        Math.max(0, base.getGreen() - 10),
                        Math.max(0, base.getBlue() - 10)));
                    if (c instanceof JLabel lbl) { lbl.setText(""); lbl.setToolTipText("No result"); }
                }
            }
            return c;
        }

        private static Color blendWithTable(Color overlay, Color base) {
            if (base == null) base = Color.WHITE;
            float a = overlay.getAlpha() / 255f;
            int r = (int)(overlay.getRed()   * a + base.getRed()   * (1 - a));
            int g = (int)(overlay.getGreen() * a + base.getGreen() * (1 - a));
            int b = (int)(overlay.getBlue()  * a + base.getBlue()  * (1 - a));
            return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
        }
    }

    // ---- Static helpers -----------------------------------------------

    static Color verdictColor(String v) {
        if (v == null) return null;
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

    static String verdictLabel(String v) {
        if (v == null) return "—";
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
