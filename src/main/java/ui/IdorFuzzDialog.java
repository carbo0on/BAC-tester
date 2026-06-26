package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import db.AccountRepository;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IDOR enumeration helper. Takes one captured request, lets the user mark an
 * object identifier and supply a set of values (explicit list or numeric range),
 * then replays each variant — optionally under a different identity — and reports
 * which values returned data. A strong signal for insecure direct object
 * references: identifiers you shouldn't be able to reach that still come back 200
 * with a body very similar to your own.
 *
 * <p>Self-contained: uses {@link RunEngine}'s static helpers (identity swap,
 * similarity, reflected-identity) so no run/DB plumbing is required.</p>
 */
public class IdorFuzzDialog extends JDialog {

    private final MontoyaApi api;
    private final byte[] requestRaw;
    private final HttpService service;

    private final JTextField markerField = new JTextField(14);
    private final JTextArea valuesArea = new JTextArea(6, 18);
    private final JTextField rangeFrom = new JTextField(6);
    private final JTextField rangeTo   = new JTextField(6);
    private final JTextField rangeStep = new JTextField(4);
    private final JComboBox<IdItem> identityCombo = new JComboBox<>();
    private final JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 60000, 50));

    private final ResultModel model = new ResultModel();
    private final JTable table = new JTable(model);
    private final JButton runBtn = new JButton("▶ Enumerate");
    private final JButton stopBtn = new JButton("■ Stop");
    private final JProgressBar progress = new JProgressBar();
    private final List<Row> rows = new ArrayList<>();

    private volatile boolean cancel = false;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-idor"); t.setDaemon(true); return t;
    });

    public IdorFuzzDialog(MontoyaApi api, Frame parent, byte[] requestRaw,
                          HttpService service, List<AccountRepository.AccountRecord> accounts) {
        super(parent, "IDOR enumeration", false);
        this.api = api;
        this.requestRaw = requestRaw;
        this.service = service;
        buildUI(accounts);
        setSize(720, 620);
        setLocationRelativeTo(parent);
        api.userInterface().applyThemeToComponent(getContentPane());
    }

    private void buildUI(List<AccountRepository.AccountRecord> accounts) {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        markerField.setText(guessIdentifier());
        form.add(labeled("Identifier to replace (exact substring in the request):", markerField,
            "e.g. the object id 1042 or a UUID. Every occurrence is swapped for each value below."));

        // Identity
        identityCombo.addItem(new IdItem(-1, "— Original captured session —", null));
        for (var a : accounts) identityCombo.addItem(new IdItem(a.id(), a.label(), a));
        form.add(labeled("Replay as identity:", identityCombo,
            "Send each variant under this identity. Use a low-priv or anonymous account to prove IDOR."));

        // Values
        JPanel valuesPanel = new JPanel(new BorderLayout(8, 0));
        valuesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        valuesPanel.add(new JScrollPane(valuesArea), BorderLayout.CENTER);

        JPanel rangePanel = new JPanel();
        rangePanel.setLayout(new BoxLayout(rangePanel, BoxLayout.Y_AXIS));
        rangePanel.add(new JLabel("Numeric range →"));
        rangePanel.add(rowOf(new JLabel("from"), rangeFrom));
        rangePanel.add(rowOf(new JLabel("to  "), rangeTo));
        rangePanel.add(rowOf(new JLabel("step"), rangeStep));
        JButton gen = new JButton("Fill list");
        gen.addActionListener(e -> fillRange());
        rangePanel.add(gen);
        valuesPanel.add(rangePanel, BorderLayout.EAST);

        JLabel vl = new JLabel("Values (one per line):");
        vl.setAlignmentX(Component.LEFT_ALIGNMENT);
        valuesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(gap());
        form.add(vl);
        form.add(valuesPanel);

        ((JComponent) delaySpinner.getEditor()).setMaximumSize(new Dimension(80, 26));
        form.add(labeled("Throttle delay (ms):", delaySpinner,
            "Delay between requests to avoid rate limits."));

        // Controls
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ctrl.add(runBtn);
        stopBtn.setEnabled(false);
        ctrl.add(stopBtn);
        progress.setStringPainted(true);
        ctrl.add(progress);
        ctrl.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(gap());
        form.add(ctrl);

        runBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> cancel = true);

        // Results
        table.setDefaultRenderer(Object.class, new SimRenderer());
        table.getColumnModel().getColumn(0).setMaxWidth(140); // value
        table.getColumnModel().getColumn(1).setMaxWidth(70);  // status
        table.getColumnModel().getColumn(2).setMaxWidth(90);  // length
        table.getColumnModel().getColumn(3).setMaxWidth(110); // similarity
        table.getColumnModel().getColumn(4).setMaxWidth(70);  // leak

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            form, new JScrollPane(table));
        split.setResizeWeight(0.62);
        add(split, BorderLayout.CENTER);
    }

    // ---- Enumeration ---------------------------------------------------

    private void start() {
        List<String> values = new ArrayList<>();
        for (String line : valuesArea.getText().split("\n")) {
            String v = line.trim();
            if (!v.isEmpty()) values.add(v);
        }
        String marker = markerField.getText();
        if (marker == null || marker.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter the identifier substring to replace.");
            return;
        }
        if (values.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Provide at least one value (or fill a numeric range).");
            return;
        }
        String reqStr = new String(requestRaw, StandardCharsets.ISO_8859_1);
        if (!reqStr.contains(marker)) {
            JOptionPane.showMessageDialog(this,
                "The identifier \"" + marker + "\" was not found in the request.");
            return;
        }

        IdItem id = (IdItem) identityCombo.getSelectedItem();
        AccountRepository.AccountRecord account = id != null ? id.account : null;
        long delay = ((Number) delaySpinner.getValue()).longValue();

        rows.clear();
        model.fireTableDataChanged();
        cancel = false;
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progress.setMaximum(values.size());
        progress.setValue(0);

        pool.submit(() -> {
            byte[] firstBody = null;
            int i = 0;
            for (String value : values) {
                if (cancel) break;
                try {
                    String variant = reqStr.replace(marker, value);
                    byte[] vbytes = variant.getBytes(StandardCharsets.ISO_8859_1);
                    HttpRequest req = account != null
                        ? RunEngine.buildSwappedRequestStatic(vbytes, account, service, java.util.List.of())
                        : HttpRequest.httpRequest(service, ByteArray.byteArray(vbytes));
                    var rr = api.http().sendRequest(req);
                    HttpResponse resp = rr.response();
                    Row row = new Row();
                    row.value = value;
                    if (resp == null) {
                        row.status = 0; row.length = 0; row.similarity = 0; row.respRaw = new byte[0];
                    } else {
                        byte[] body = resp.body().getBytes();
                        row.status = resp.statusCode();
                        row.length = body.length;
                        row.respRaw = resp.toByteArray().getBytes();
                        if (firstBody == null) firstBody = body;
                        row.similarity = RunEngine.computeSimilarity(firstBody, body, null);
                        row.leak = !RunEngine.detectReflectedIdentity(firstBody, body).isEmpty();
                    }
                    rows.add(row);
                } catch (Exception ex) {
                    api.logging().logToError("[BAC] IDOR fuzz error on " + value + ": " + ex.getMessage());
                }
                final int done = ++i;
                SwingUtilities.invokeLater(() -> { model.fireTableDataChanged(); progress.setValue(done); });
                if (delay > 0) { try { Thread.sleep(delay); } catch (InterruptedException ie) { break; } }
            }
            SwingUtilities.invokeLater(() -> {
                runBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            });
        });
    }

    private void fillRange() {
        try {
            long from = Long.parseLong(rangeFrom.getText().trim());
            long to   = Long.parseLong(rangeTo.getText().trim());
            long step = rangeStep.getText().isBlank() ? 1 : Long.parseLong(rangeStep.getText().trim());
            if (step == 0) step = 1;
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (long v = from; (step > 0 ? v <= to : v >= to) && count < 5000; v += step, count++)
                sb.append(v).append('\n');
            valuesArea.setText(sb.toString());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter valid numbers for from / to / step.");
        }
    }

    /** Prefills the marker with a likely identifier from the request line (long number or UUID). */
    private String guessIdentifier() {
        String s = new String(requestRaw, StandardCharsets.ISO_8859_1);
        int eol = s.indexOf('\n');
        String firstLine = eol > 0 ? s.substring(0, eol) : s;
        var uuid = java.util.regex.Pattern
            .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .matcher(firstLine);
        if (uuid.find()) return uuid.group();
        var num = java.util.regex.Pattern.compile("\\d{2,}").matcher(firstLine);
        String last = "";
        while (num.find()) last = num.group(); // last numeric token in the path is usually the id
        return last;
    }

    // ---- UI helpers ----------------------------------------------------

    private JComponent labeled(String label, JComponent field, String hint) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        fr.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(420, 28));
        fr.add(field);
        p.add(fr);
        if (hint != null) {
            JLabel h = new JLabel(hint);
            h.setFont(h.getFont().deriveFont(Font.ITALIC, 11f));
            h.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(h);
        }
        p.add(gap());
        return p;
    }

    private JPanel rowOf(Component a, Component b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        p.add(a); p.add(b);
        return p;
    }

    private Component gap() { return Box.createRigidArea(new Dimension(0, 8)); }

    // ---- Table ---------------------------------------------------------

    private static final class Row {
        String value; int status; int length; double similarity; boolean leak; byte[] respRaw = new byte[0];
    }

    private final class ResultModel extends AbstractTableModel {
        private final String[] cols = {"Value", "Status", "Length", "Similarity", "Leak", "Note"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.value;
                case 1 -> row.status == 0 ? "—" : String.valueOf(row.status);
                case 2 -> row.length;
                case 3 -> String.format("%.0f%%", row.similarity);
                case 4 -> row.leak ? "⚠" : "";
                case 5 -> note(row);
                default -> "";
            };
        }
        private String note(Row r) {
            if (r.status == 0) return "no response";
            if (r.status == 200 && r.similarity >= 90) return "🚩 reachable — looks like real data";
            if (r.status == 403 || r.status == 401) return "denied";
            if (r.status == 404) return "not found";
            return "";
        }
    }

    /** Tints rows where an id was reachable with a body similar to the first (likely IDOR). */
    private final class SimRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (!sel && row >= 0 && row < rows.size()) {
                Row r = rows.get(row);
                if (r.status == 200 && (r.similarity >= 90 || r.leak)) c.setBackground(VerdictStyle.POTENTIAL_BAC_BG);
                else if (r.status == 401 || r.status == 403) c.setBackground(VerdictStyle.LIKELY_ENFORCED_BG);
                else c.setBackground(t.getBackground());
            }
            return c;
        }
    }

    private record IdItem(long id, String label, AccountRepository.AccountRecord account) {
        @Override public String toString() { return label; }
    }
}
