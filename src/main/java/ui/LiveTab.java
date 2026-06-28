package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import capture.CaptureService;
import db.AccountRepository;
import db.DatabaseManager;
import engine.DynamicField;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Passive "Live" mode — an Autorize-style auto-replay tab.
 *
 * <p>While you browse the target as a high-privilege user, every in-scope Proxy
 * response is automatically replayed under a chosen low-privilege (or anonymous)
 * identity and triaged in real time, so broken access control surfaces the
 * moment you touch the endpoint — no save-and-run cycle needed.</p>
 *
 * <p>The proxy thread only hands us already-captured bytes; the actual replay +
 * comparison runs on a background pool via {@link RunEngine#replayOnce}.</p>
 */
public class LiveTab extends JPanel {

    private final MontoyaApi api;
    private final RunEngine engine;
    private final AccountRepository accountRepo;
    private final CaptureService capture;
    private final DatabaseManager db;

    private final JComboBox<AccountItem> accountCombo = new JComboBox<>();
    private final JCheckBox enableCheck = new JCheckBox("Enable live testing");
    private final JCheckBox scopeOnly   = new JCheckBox("In-scope only", true);
    private final JCheckBox skipStatic  = new JCheckBox("Skip static assets", true);
    private final JCheckBox verifyAnon  = new JCheckBox("Verify vs anonymous");
    private final JCheckBox flaggedOnly = new JCheckBox("Show flagged only");
    private final JLabel statusLabel = new JLabel("Disabled.");

    private final List<Finding> findings = new ArrayList<>();
    private final FindingTableModel model = new FindingTableModel();
    private final JTable table = new JTable(model);
    private HttpResponseEditor origEditor;
    private HttpResponseEditor replayedEditor;

    private final Set<String> seen = new HashSet<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "bac-live"); t.setDaemon(true); return t;
    });

    private volatile long selectedAccountId = -1;
    private int processed = 0, flaggedCount = 0;

    public LiveTab(MontoyaApi api, RunEngine engine, AccountRepository accountRepo,
                   CaptureService capture, DatabaseManager db) {
        super(new BorderLayout(0, 6));
        this.api = api;
        this.engine = engine;
        this.accountRepo = accountRepo;
        this.capture = capture;
        this.db = db;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        buildUI();
        refreshAccounts();
        api.userInterface().applyThemeToComponent(this);
    }

    // ---- UI ------------------------------------------------------------

    private void buildUI() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controls.add(new JLabel("Replay identity:"));
        accountCombo.setToolTipText("The low-privilege (or anonymous) account each browsed request is replayed under");
        accountCombo.addActionListener(e -> {
            AccountItem it = (AccountItem) accountCombo.getSelectedItem();
            selectedAccountId = it != null ? it.id : -1;
        });
        controls.add(accountCombo);
        JButton reload = new JButton("↻");
        reload.setToolTipText("Reload accounts");
        reload.addActionListener(e -> refreshAccounts());
        controls.add(reload);

        enableCheck.setToolTipText("When on, in-scope Proxy traffic is auto-replayed under the chosen identity");
        enableCheck.addActionListener(e -> updateStatus());
        controls.add(enableCheck);
        controls.add(scopeOnly);
        controls.add(skipStatic);
        verifyAnon.setToolTipText("Also replay anonymously; if the result matches anonymous, "
            + "the endpoint is public (not auth-gated) and won't be flagged as BAC");
        controls.add(verifyAnon);
        flaggedOnly.addActionListener(e -> model.fireTableDataChanged());
        controls.add(flaggedOnly);

        JButton sendBurp = new JButton("Send flagged → Burp");
        sendBurp.setToolTipText("Add flagged findings to Burp's site map as audit issues (Dashboard)");
        sendBurp.addActionListener(e -> sendFlaggedToBurp());
        controls.add(sendBurp);

        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            findings.clear(); seen.clear(); processed = 0; flaggedCount = 0;
            model.fireTableDataChanged(); updateStatus();
        });
        controls.add(clear);

        JPanel north = new JPanel(new BorderLayout());
        north.add(new FoldablePanel(api, "live_controls", "Live options", controls), BorderLayout.CENTER);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        north.add(statusLabel, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        // Findings table
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(70);   // time
        table.getColumnModel().getColumn(1).setMaxWidth(90);   // verdict
        table.getColumnModel().getColumn(2).setMaxWidth(70);   // method
        table.getColumnModel().getColumn(4).setMaxWidth(110);  // status
        table.getColumnModel().getColumn(5).setMaxWidth(80);   // sim
        table.getColumnModel().getColumn(6).setMaxWidth(60);   // leak
        table.setDefaultRenderer(Object.class, new VerdictRowRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        table.setComponentPopupMenu(buildPopup());

        origEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        replayedEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        JPanel origWrap = titled("Original (your session)", origEditor.uiComponent());
        JPanel repWrap  = titled("Replayed (test identity)", replayedEditor.uiComponent());
        JSplitPane editors = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, origWrap, repWrap);
        editors.setResizeWeight(0.5);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(table), editors);
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);
        updateStatus();
    }

    private JPanel titled(String title, Component c) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = new JLabel(title);
        l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem save = new JMenuItem("Save request to Library");
        save.addActionListener(e -> {
            Finding f = selected();
            if (f != null && f.original != null) capture.quickSaveToInbox(f.original);
        });
        JMenuItem comparer = new JMenuItem("Send both responses to Burp Comparer");
        comparer.addActionListener(e -> {
            Finding f = selected();
            if (f != null) api.comparer().sendToComparer(
                ByteArray.byteArray(f.origRespRaw), ByteArray.byteArray(f.newRespRaw));
        });
        menu.add(save);
        menu.add(comparer);
        return menu;
    }

    public void refreshAccounts() {
        pool.submit(() -> {
            List<AccountItem> items = new ArrayList<>();
            try {
                for (var a : accountRepo.getAll()) items.add(new AccountItem(a.id(), a.label()));
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Live: load accounts failed: " + ex.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                long keep = selectedAccountId;
                accountCombo.removeAllItems();
                for (AccountItem it : items) accountCombo.addItem(it);
                for (int i = 0; i < accountCombo.getItemCount(); i++) {
                    if (accountCombo.getItemAt(i).id == keep) { accountCombo.setSelectedIndex(i); break; }
                }
                AccountItem sel = (AccountItem) accountCombo.getSelectedItem();
                selectedAccountId = sel != null ? sel.id : -1;
            });
        });
    }

    // ---- Proxy hook (called from Extension's HttpHandler) --------------

    /**
     * Entry point invoked for every Proxy response. Returns quickly; the replay
     * itself is dispatched to a background pool. {@code inScope} is precomputed
     * by the caller to avoid scope lookups on hot static traffic.
     */
    public void onProxyResponse(HttpRequest request, HttpResponse response, boolean inScope) {
        if (!enableCheck.isSelected() || selectedAccountId < 0) return;
        if (request == null || response == null) return;
        if (scopeOnly.isSelected() && !inScope) return;
        if (skipStatic.isSelected() && isStaticAsset(request.path())) return;

        // Snapshot bytes on the proxy thread; everything else is async.
        final byte[] reqRaw = request.toByteArray().getBytes();
        final byte[] origRespRaw = response.toByteArray().getBytes();
        final byte[] origBody = response.body().getBytes();
        final int origStatus = response.statusCode();
        final String method = request.method();
        final String host = request.httpService().host();
        final int port = request.httpService().port();
        final boolean https = request.httpService().secure();
        final String path = request.path();

        String key = method + " " + host + ":" + port + path + " #" + sha8(reqRaw);
        synchronized (seen) { if (!seen.add(key)) return; }

        long acctId = selectedAccountId;
        pool.submit(() -> {
            try {
                AccountRepository.AccountRecord acct = accountRepo.getById(acctId).orElse(null);
                if (acct == null) return;
                engine.loadRunSettings();
                double threshold = readThreshold();
                HttpService svc = HttpService.httpService(host, port, https);
                List<DynamicField> dyn = List.of();
                String expected = acct.expectedAccess() != null ? acct.expectedAccess() : "DENIED";
                RunEngine.ReplayOutcome out = engine.replayOnce(
                    reqRaw, svc, acct, dyn, origBody, origStatus, expected, threshold);

                Finding f = new Finding();
                f.time = System.currentTimeMillis();
                f.method = method; f.host = host; f.port = port; f.https = https; f.path = path;
                f.origStatus = origStatus; f.newStatus = out.status();
                f.similarity = out.similarity(); f.verdict = out.verdict();
                f.leak = out.leaksIdentity();
                f.origRespRaw = origRespRaw; f.newRespRaw = out.responseRaw();
                f.reqRaw = reqRaw;
                f.headerDiff = RunEngine.notableHeaderDiffs(origRespRaw, out.responseRaw());

                // Three-leg check: if the result matches an anonymous replay, the
                // endpoint is public, so demote any BAC flag to avoid false positives.
                if (verifyAnon.isSelected() && out.status() != 0) {
                    var anon = new AccountRepository.AccountRecord(
                        -1, "anonymous", null, java.util.Map.of(), java.util.Map.of(),
                        "DENIED", null, null, 0, 0);
                    RunEngine.ReplayOutcome anonOut = engine.replayOnce(
                        reqRaw, svc, anon, dyn, origBody, origStatus, "DENIED", threshold);
                    if (RunEngine.isLikelyPublic(out.body(), anonOut.body(), threshold)) {
                        f.publicContent = true;
                        if (RunEngine.POTENTIAL_BAC.equals(f.verdict)) f.verdict = RunEngine.EXPECTED_OK;
                    }
                }
                f.original = burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(
                    HttpRequest.httpRequest(svc, ByteArray.byteArray(reqRaw)),
                    HttpResponse.httpResponse(ByteArray.byteArray(origRespRaw)));

                SwingUtilities.invokeLater(() -> addFinding(f));
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Live replay error: " + ex.getMessage());
            }
        });
    }

    private void addFinding(Finding f) {
        findings.add(0, f); // newest first
        processed++;
        if (isFlagged(f.verdict) || f.leak) flaggedCount++;
        if (findings.size() > 500) findings.remove(findings.size() - 1);
        model.fireTableDataChanged();
        updateStatus();
    }

    /** Pushes every flagged finding into Burp's site map as a triage audit issue. */
    private void sendFlaggedToBurp() {
        int added = 0;
        for (Finding f : findings) {
            if (!(isFlagged(f.verdict) || f.leak) || f.original == null) continue;
            try {
                String name = "[BAC] Possible broken access control — " + VerdictStyle.shortLabel(f.verdict);
                String detail = "Replayed under a lower-privilege identity and the response was "
                    + String.format("%.0f%%", f.similarity) + " similar to the owner's "
                    + "(status " + f.origStatus + " → " + f.newStatus + ")."
                    + (f.leak ? " Victim-specific identifiers were reflected in the response." : "")
                    + (f.headerDiff != null && !f.headerDiff.isEmpty() ? " Header changes: " + f.headerDiff : "")
                    + " Flagged by BAC Time-Machine for manual review.";
                var sev = RunEngine.POTENTIAL_BAC.equals(f.verdict)
                    ? burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.HIGH
                    : burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.INFORMATION;
                var issue = burp.api.montoya.scanner.audit.issues.AuditIssue.auditIssue(
                    name, detail, "Confirm manually whether the lower-privilege identity should "
                        + "have access to this resource.",
                    f.https ? "https://" + f.host + f.path : "http://" + f.host + f.path,
                    sev, burp.api.montoya.scanner.audit.issues.AuditIssueConfidence.TENTATIVE,
                    "Detected by replaying captured traffic under a different identity.",
                    null, sev, f.original);
                api.siteMap().add(issue);
                added++;
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Send-to-Burp failed: " + ex.getMessage());
            }
        }
        JOptionPane.showMessageDialog(this, added + " finding(s) added to Burp's site map / Dashboard.",
            "Send to Burp", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateStatus() {
        if (!enableCheck.isSelected()) {
            statusLabel.setText("Disabled — tick \"Enable live testing\" and browse the target as your high-priv user.");
        } else if (selectedAccountId < 0) {
            statusLabel.setText("Enabled, but no replay identity selected. Pick an account above.");
        } else {
            statusLabel.setText("Live: " + processed + " replayed · " + flaggedCount + " flagged. "
                + "Browse the target; in-scope requests are auto-tested.");
        }
    }

    // ---- Selection / rendering ----------------------------------------

    private Finding selected() {
        int r = table.getSelectedRow();
        List<Finding> view = visibleFindings();
        return (r >= 0 && r < view.size()) ? view.get(r) : null;
    }

    private void showSelected() {
        Finding f = selected();
        byte[] o = f != null ? f.origRespRaw : new byte[0];
        byte[] n = f != null ? f.newRespRaw : new byte[0];
        origEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(o != null && o.length > 0 ? o : new byte[0])));
        replayedEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(n != null && n.length > 0 ? n : new byte[0])));
    }

    private List<Finding> visibleFindings() {
        if (!flaggedOnly.isSelected()) return findings;
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) if (isFlagged(f.verdict) || f.leak) out.add(f);
        return out;
    }

    private static boolean isFlagged(String v) {
        return RunEngine.POTENTIAL_BAC.equals(v) || RunEngine.ANOMALY.equals(v) || RunEngine.REVIEW.equals(v);
    }

    // ---- helpers -------------------------------------------------------

    /** Reads the user's match threshold from settings (defaults to the documented 95%). */
    private double readThreshold() {
        try {
            String t = db.getSetting("match_threshold");
            if (t != null && !t.isBlank()) return Double.parseDouble(t.trim());
        } catch (Exception ignored) {}
        return 95.0;
    }

    private static boolean isStaticAsset(String path) {
        if (path == null) return false;
        int q = path.indexOf('?');
        String p = (q >= 0 ? path.substring(0, q) : path).toLowerCase();
        return p.matches(".*\\.(png|jpe?g|gif|svg|ico|css|js|woff2?|ttf|eot|map|webp|mp4|avif)$");
    }

    private static String sha8(byte[] b) {
        return Integer.toHexString(java.util.Arrays.hashCode(b));
    }

    private record AccountItem(long id, String label) {
        @Override public String toString() { return label; }
    }

    private static final class Finding {
        long time;
        String method, host, path; int port; boolean https;
        int origStatus, newStatus;
        double similarity; String verdict; boolean leak;
        boolean publicContent; String headerDiff = "";
        byte[] origRespRaw, newRespRaw, reqRaw;
        burp.api.montoya.http.message.HttpRequestResponse original;
    }

    private final class FindingTableModel extends AbstractTableModel {
        private final String[] cols = {"Time", "Verdict", "Method", "Host / Path", "Status", "Similarity", "Leak", "Detail"};
        @Override public int getRowCount() { return visibleFindings().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Finding f = visibleFindings().get(r);
            return switch (c) {
                case 0 -> timeStr(f.time);
                case 1 -> VerdictStyle.shortLabel(f.verdict);
                case 2 -> f.method;
                case 3 -> f.host + f.path;
                case 4 -> f.origStatus + " → " + f.newStatus;
                case 5 -> String.format("%.0f%%", f.similarity);
                case 6 -> f.leak ? "⚠ yes" : "";
                case 7 -> detail(f);
                default -> "";
            };
        }
        private String detail(Finding f) {
            StringBuilder sb = new StringBuilder();
            if (f.publicContent) sb.append("public");
            if (f.headerDiff != null && !f.headerDiff.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(f.headerDiff);
            }
            return sb.toString();
        }
        private String timeStr(long ms) {
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(ms));
        }
    }

    /** Tints each row by its verdict color so flagged findings pop. */
    private final class VerdictRowRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            List<Finding> view = visibleFindings();
            if (!sel && row >= 0 && row < view.size()) {
                Color bg = VerdictStyle.color(view.get(row).verdict);
                c.setBackground(bg != null ? bg : t.getBackground());
            }
            return c;
        }
    }
}
