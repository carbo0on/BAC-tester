package ui;

import burp.api.montoya.MontoyaApi;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import db.DatabaseManager;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings tab: triage thresholds, coloring, scope, safe mode, Compare options,
 * ignore patterns, and Export/Import.
 */
public class SettingsTab extends JPanel {

    private final MontoyaApi api;
    private final DatabaseManager db;
    private final ExportImportManager exportImport;
    private final Gson gson = new Gson();

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-settings");
        t.setDaemon(true);
        return t;
    });

    // Controls
    private JSpinner thresholdSpinner;
    private JSpinner reviewBoundSpinner;
    private JCheckBox safeModeCheck;
    private JComboBox<String> safeModeScopeCombo;
    private JSpinner runThreadsSpinner;
    private JSpinner runDelaySpinner;
    private JSpinner canaryRecheckSpinner;
    private JCheckBox warnCanaryCheck;
    private JCheckBox detectLoginCheck;
    private JSpinner fontSizeSpinner;
    private JSpinner diffCapSpinner;
    private JComboBox<String> coloringCombo;
    private JComboBox<String> scopeCombo;
    private JComboBox<String> defaultAccessCombo;
    private JCheckBox autoExpandCheck;
    private JCheckBox confirmRunCheck;
    private JCheckBox dedupCheck;
    private JTextField hotkeyField;
    private DefaultListModel<String> patternModel;
    private JLabel dbPathLabel;

    // Fired after a successful save so the rest of the UI can react (e.g. recolor).
    private Runnable onSaved;

    public SettingsTab(MontoyaApi api, DatabaseManager db, ExportImportManager exportImport) {
        super(new BorderLayout(0, 8));
        this.api = api;
        this.db = db;
        this.exportImport = exportImport;
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        buildUI();
        loadSettings();
        api.userInterface().applyThemeToComponent(this);
    }

    public void setOnSaved(Runnable r) { this.onSaved = r; }

    private void buildUI() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        // ── Triage ───────────────────────────────────────────────────────
        CollapsibleSection triage = new CollapsibleSection("Triage / Comparison", "🔍");

        thresholdSpinner = new JSpinner(new SpinnerNumberModel(95, 1, 100, 1));
        thresholdSpinner.setPreferredSize(new Dimension(70, thresholdSpinner.getPreferredSize().height));
        triage.addContent(row("Match threshold (%):", thresholdSpinner,
            "Similarity at/above this value counts as a 'match'. Default: 95"));

        reviewBoundSpinner = new JSpinner(new SpinnerNumberModel(60, 0, 100, 1));
        reviewBoundSpinner.setPreferredSize(new Dimension(70, reviewBoundSpinner.getPreferredSize().height));
        triage.addContent(row("Review lower bound (%):", reviewBoundSpinner,
            "Similarity in [this, threshold) is flagged REVIEW (ambiguous). Default: 60"));

        defaultAccessCombo = new JComboBox<>(new String[]{"UNKNOWN", "ALLOWED", "DENIED"});
        triage.addContent(row("Default expected access (new accounts):", defaultAccessCombo, null));
        form.add(triage);
        form.add(gap(6));

        // ── Run safety ─────────────────────────────────────────────────────
        CollapsibleSection runSafety = new CollapsibleSection("Run Safety", "🛡");

        safeModeCheck = new JCheckBox(
            "Safe Mode — skip destructive requests during runs so a replay can't mutate target data");
        safeModeCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        runSafety.addContent(safeModeCheck);

        safeModeScopeCombo = new JComboBox<>(new String[]{"DELETE", "ALL"});
        runSafety.addContent(row("Safe Mode skips:", safeModeScopeCombo,
            "DELETE: skip only DELETE (POST/PUT/PATCH still replayed) · "
            + "ALL: skip every state-changing request (POST/PUT/PATCH/DELETE)"));

        confirmRunCheck = new JCheckBox("Ask for confirmation before starting a run");
        confirmRunCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        runSafety.addContent(confirmRunCheck);

        warnCanaryCheck = new JCheckBox("Warn before running an account that has no canary configured");
        warnCanaryCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        runSafety.addContent(warnCanaryCheck);

        detectLoginCheck = new JCheckBox("Treat a 200 login page as an expired session (canary body check)");
        detectLoginCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        runSafety.addContent(detectLoginCheck);

        scopeCombo = new JComboBox<>(new String[]{"WARN", "BLOCK", "OFF"});
        runSafety.addContent(row("Out-of-scope handling:", scopeCombo,
            "WARN: log and continue · BLOCK: skip out-of-scope requests · OFF: ignore scope"));

        runThreadsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
        runThreadsSpinner.setPreferredSize(new Dimension(60, runThreadsSpinner.getPreferredSize().height));
        runSafety.addContent(row("Concurrent requests:", runThreadsSpinner,
            "How many requests a run sends in parallel (1 = sequential). Higher is faster "
            + "but heavier on the target."));

        runDelaySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 60000, 50));
        runDelaySpinner.setPreferredSize(new Dimension(80, runDelaySpinner.getPreferredSize().height));
        runSafety.addContent(row("Throttle delay (ms):", runDelaySpinner,
            "Delay applied before each request during a run (0 = none). Use to avoid rate limits."));

        canaryRecheckSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
        canaryRecheckSpinner.setPreferredSize(new Dimension(70, canaryRecheckSpinner.getPreferredSize().height));
        runSafety.addContent(row("Re-check canary every N requests:", canaryRecheckSpinner,
            "Re-validate the session mid-run every N requests so an expiry doesn't cause false "
            + "negatives (0 = only at the start). Needs a canary set on the account."));
        form.add(runSafety);
        form.add(gap(6));

        // ── Capture ────────────────────────────────────────────────────────
        CollapsibleSection capture = new CollapsibleSection("Capture", "⌨");
        hotkeyField = new JTextField(14);
        capture.addContent(row("Quick-save hotkey:", hotkeyField,
            "Combo used to quick-save the focused request to Inbox. Same format as Burp's "
            + "Settings (e.g. Ctrl+Alt+B). Use modifiers plus a normal key; avoid the "
            + "Windows/Meta key (the OS reserves it). Takes effect after you reload the extension."));
        JLabel hotkeyHint = new JLabel("Changing the hotkey requires reloading the "
            + "extension (Extensions ▸ toggle the Loaded checkbox).");
        hotkeyHint.setFont(hotkeyHint.getFont().deriveFont(Font.ITALIC, 11f));
        hotkeyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        capture.addContent(hotkeyHint);
        form.add(capture);
        form.add(gap(6));

        // ── Appearance ─────────────────────────────────────────────────────
        CollapsibleSection appearance = new CollapsibleSection("Appearance", "🎨");

        coloringCombo = new JComboBox<>(new String[]{"AUTO", "MANUAL", "OFF"});
        appearance.addContent(row("Library row coloring:", coloringCombo,
            "AUTO: color by HTTP method · MANUAL: only your per-request colors · OFF: none"));

        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(12, 8, 24, 1));
        fontSizeSpinner.setPreferredSize(new Dimension(60, fontSizeSpinner.getPreferredSize().height));
        appearance.addContent(row("Response viewer font size (px):", fontSizeSpinner, null));

        autoExpandCheck = new JCheckBox("Auto-expand all folders in the Library tree");
        autoExpandCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        appearance.addContent(autoExpandCheck);
        form.add(appearance);
        form.add(gap(6));

        // ── Compare ─────────────────────────────────────────────────────────
        CollapsibleSection compare = new CollapsibleSection("Compare", "⇄");

        diffCapSpinner = new JSpinner(new SpinnerNumberModel(300, 50, 5000, 50));
        diffCapSpinner.setPreferredSize(new Dimension(80, diffCapSpinner.getPreferredSize().height));
        compare.addContent(row("Max diff size (KB):", diffCapSpinner,
            "Responses larger than this are truncated in the Compare view for performance."));
        form.add(compare);
        form.add(gap(6));

        // ── Ignore patterns ──────────────────────────────────────────────
        CollapsibleSection ignore = new CollapsibleSection("Ignore Patterns", "🚫");
        JLabel lbl = new JLabel("Java regex — matching lines are greyed out and excluded from similarity:");
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        ignore.addContent(lbl);
        ignore.addContent(gap(4));

        patternModel = new DefaultListModel<>();
        JList<String> patternList = new JList<>(patternModel);
        patternList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        patternList.setVisibleRowCount(5);
        JScrollPane listScroll = new JScrollPane(patternList);
        listScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        listScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        ignore.addContent(listScroll);
        ignore.addContent(gap(4));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton addBtn = new JButton("+ Add");
        JButton remBtn = new JButton("- Remove selected");
        btnRow.add(addBtn);
        btnRow.add(remBtn);
        ignore.addContent(btnRow);

        addBtn.addActionListener(e -> {
            String regex = JOptionPane.showInputDialog(this,
                "Enter Java regex to ignore (e.g. csrf[_-]?token=[^&\\s]+):",
                "Add Ignore Pattern", JOptionPane.PLAIN_MESSAGE);
            if (regex != null && !regex.isBlank()) {
                try {
                    java.util.regex.Pattern.compile(regex.trim()); // validate
                    patternModel.addElement(regex.trim());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Invalid regex: " + ex.getMessage(),
                        "Invalid Pattern", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        remBtn.addActionListener(e -> {
            int idx = patternList.getSelectedIndex();
            if (idx >= 0) patternModel.remove(idx);
        });
        form.add(ignore);
        form.add(gap(6));

        // ── DB path ────────────────────────────────────────────────────────
        CollapsibleSection storage = new CollapsibleSection("Storage", "💾");
        dbPathLabel = new JLabel("—");
        dbPathLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        storage.addContent(row("Database path:", dbPathLabel, null));
        form.add(storage);

        // ── Save (always visible, never inside a collapsible section) ───────
        form.add(gap(12));
        JButton saveBtn = new JButton("💾 Save Settings");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.addActionListener(e -> saveSettings());
        JPanel savePan = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        savePan.setAlignmentX(Component.LEFT_ALIGNMENT);
        savePan.add(saveBtn);
        form.add(savePan);

        form.add(gap(16));
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        form.add(sep);
        form.add(gap(12));

        // ── Export / Import ──────────────────────────────────────────────
        CollapsibleSection io = new CollapsibleSection("Library Export / Import", "📦");
        for (String line : new String[]{
                "Export saves folders + test cases + all versioned baselines to a portable .bac.json bundle.",
                "Import reads one or more .bac.json files, rebuilds the folder tree, and merges test cases",
                "(deduplicates by host + method + URL + request hash — old baselines are never deleted)."}) {
            JLabel ioHint = new JLabel(line);
            ioHint.setFont(ioHint.getFont().deriveFont(Font.ITALIC, 11f));
            ioHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            io.addContent(ioHint);
        }
        io.addContent(gap(6));

        dedupCheck = new JCheckBox("Skip duplicates on import (host + method + URL + request hash)");
        dedupCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        io.addContent(dedupCheck);
        io.addContent(gap(8));

        JButton exportBtn = new JButton("⤓ Export Library…");
        JButton importBtn = new JButton("⤒ Import Library…");
        exportBtn.addActionListener(e -> exportImport.showExportDialog(this));
        importBtn.addActionListener(e -> exportImport.showImportDialog(this, count -> {
            if (onSaved != null) onSaved.run(); // refresh library after import
        }));

        JPanel ioPan = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ioPan.setAlignmentX(Component.LEFT_ALIGNMENT);
        ioPan.add(exportBtn);
        ioPan.add(importBtn);
        io.addContent(ioPan);
        form.add(io);
        form.add(gap(6));

        // ── About ────────────────────────────────────────────────────────
        CollapsibleSection about = new CollapsibleSection("About", "ℹ");
        JLabel toolName = new JLabel("BAC Time-Machine");
        toolName.setFont(toolName.getFont().deriveFont(Font.BOLD, 13f));
        toolName.setAlignmentX(Component.LEFT_ALIGNMENT);
        about.addContent(toolName);
        for (String line : new String[]{
                "Automated Broken Access Control / IDOR testing for Burp Suite.",
                "Designed by Cataract."}) {
            JLabel l = new JLabel(line);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            about.addContent(l);
        }
        form.add(about);

        JScrollPane scroll = new JScrollPane(form);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    // ── Settings I/O ──────────────────────────────────────────────────────

    private void loadSettings() {
        loader.submit(() -> {
            try {
                String threshold   = db.getSetting("match_threshold");
                String reviewBound = db.getSetting("review_lower_bound");
                String safeMode    = db.getSetting("safe_mode");
                String safeScope   = db.getSetting("safe_mode_scope");
                String fontSize    = db.getSetting("font_size");
                String diffCap     = db.getSetting("diff_size_cap_kb");
                String coloring    = db.getSetting("coloring_mode");
                String scope       = db.getSetting("scope_enforcement");
                String defAccess   = db.getSetting("default_expected_access");
                String autoExpand  = db.getSetting("auto_expand_folders");
                String confirmRun  = db.getSetting("confirm_before_run");
                String dedup       = db.getSetting("dedup_on_import");
                String runThreads  = db.getSetting("run_threads");
                String runDelay    = db.getSetting("run_delay_ms");
                String canaryEvery = db.getSetting("canary_recheck_every");
                String warnCanary  = db.getSetting("warn_missing_canary");
                String detectLogin = db.getSetting("canary_detect_login_body");
                String patterns    = db.getSetting("ignore_patterns");
                String dbPath      = db.getSetting("db_path");
                String hotkey      = db.getSetting("hotkey_combo");

                SwingUtilities.invokeLater(() -> {
                    setSpinner(thresholdSpinner, threshold);
                    setSpinner(reviewBoundSpinner, reviewBound);
                    setSpinner(fontSizeSpinner, fontSize);
                    setSpinner(diffCapSpinner, diffCap);
                    safeModeCheck.setSelected(!"false".equalsIgnoreCase(safeMode));
                    if (safeScope != null) safeModeScopeCombo.setSelectedItem(safeScope.trim().toUpperCase());
                    autoExpandCheck.setSelected(!"false".equalsIgnoreCase(autoExpand));
                    confirmRunCheck.setSelected(!"false".equalsIgnoreCase(confirmRun));
                    warnCanaryCheck.setSelected(!"false".equalsIgnoreCase(warnCanary));
                    detectLoginCheck.setSelected(!"false".equalsIgnoreCase(detectLogin));
                    setSpinner(runThreadsSpinner, runThreads);
                    setSpinner(runDelaySpinner, runDelay);
                    setSpinner(canaryRecheckSpinner, canaryEvery);
                    dedupCheckSetSelected(dedup);
                    if (coloring != null) coloringCombo.setSelectedItem(coloring);
                    if (scope != null) scopeCombo.setSelectedItem(scope);
                    if (defAccess != null) defaultAccessCombo.setSelectedItem(defAccess);
                    if (patterns != null) {
                        try {
                            List<String> list = gson.fromJson(patterns, new TypeToken<List<String>>(){}.getType());
                            patternModel.clear();
                            if (list != null) list.forEach(patternModel::addElement);
                        } catch (Exception ignored) {}
                    }
                    hotkeyField.setText(hotkey != null && !hotkey.isBlank() ? hotkey : "Ctrl+Alt+B");
                    if (dbPath != null) dbPathLabel.setText(dbPath);
                    else dbPathLabel.setText(api.persistence().preferences().getString("bac_db_path"));
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Load settings failed: " + e.getMessage());
            }
        });
    }

    private void dedupCheckSetSelected(String v) {
        if (dedupCheck == null) return;
        dedupCheck.setSelected(!"false".equalsIgnoreCase(v));
    }

    private void saveSettings() {
        int threshold   = (Integer) thresholdSpinner.getValue();
        int reviewBound = (Integer) reviewBoundSpinner.getValue();
        if (reviewBound >= threshold) {
            JOptionPane.showMessageDialog(this,
                "Review lower bound must be less than the match threshold.",
                "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean safeMode   = safeModeCheck.isSelected();
        String safeScope   = (String) safeModeScopeCombo.getSelectedItem();
        int fontSize       = (Integer) fontSizeSpinner.getValue();
        int diffCap        = (Integer) diffCapSpinner.getValue();
        String coloring    = (String) coloringCombo.getSelectedItem();
        String scope       = (String) scopeCombo.getSelectedItem();
        String defAccess   = (String) defaultAccessCombo.getSelectedItem();
        boolean autoExpand = autoExpandCheck.isSelected();
        boolean confirmRun = confirmRunCheck.isSelected();
        boolean warnCanary = warnCanaryCheck.isSelected();
        boolean detectLogin = detectLoginCheck.isSelected();
        int runThreads     = (Integer) runThreadsSpinner.getValue();
        int runDelay       = (Integer) runDelaySpinner.getValue();
        int canaryEvery    = (Integer) canaryRecheckSpinner.getValue();
        boolean dedup      = dedupCheck != null && dedupCheck.isSelected();
        String hotkeyRaw   = hotkeyField != null ? hotkeyField.getText().trim() : "Ctrl+Alt+B";
        final String hotkeyToSave = hotkeyRaw.isBlank() ? "Ctrl+Alt+B" : hotkeyRaw;
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < patternModel.size(); i++) patterns.add(patternModel.get(i));
        String patternsJson = gson.toJson(patterns);

        loader.submit(() -> {
            try {
                db.setSetting("match_threshold", String.valueOf(threshold));
                db.setSetting("review_lower_bound", String.valueOf(reviewBound));
                db.setSetting("safe_mode", String.valueOf(safeMode));
                db.setSetting("safe_mode_scope", safeScope != null ? safeScope : "DELETE");
                db.setSetting("font_size", String.valueOf(fontSize));
                db.setSetting("diff_size_cap_kb", String.valueOf(diffCap));
                db.setSetting("coloring_mode", coloring);
                db.setSetting("scope_enforcement", scope);
                db.setSetting("default_expected_access", defAccess);
                db.setSetting("auto_expand_folders", String.valueOf(autoExpand));
                db.setSetting("confirm_before_run", String.valueOf(confirmRun));
                db.setSetting("warn_missing_canary", String.valueOf(warnCanary));
                db.setSetting("canary_detect_login_body", String.valueOf(detectLogin));
                db.setSetting("run_threads", String.valueOf(runThreads));
                db.setSetting("run_delay_ms", String.valueOf(runDelay));
                db.setSetting("canary_recheck_every", String.valueOf(canaryEvery));
                db.setSetting("dedup_on_import", String.valueOf(dedup));
                db.setSetting("hotkey_combo", hotkeyToSave);
                db.setSetting("ignore_patterns", patternsJson);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Settings saved.", "Saved", JOptionPane.INFORMATION_MESSAGE);
                    if (onSaved != null) onSaved.run();
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Save settings failed: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Error saving: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    // ── Layout helpers ────────────────────────────────────────────────────

    private static void setSpinner(JSpinner spinner, String val) {
        if (val == null) return;
        try { spinner.setValue(Integer.parseInt(val.trim())); } catch (Exception ignored) {}
    }

    private static JPanel row(String label, JComponent control, String tooltip) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        p.add(l);
        p.add(control);
        if (tooltip != null) { control.setToolTipText(tooltip); l.setToolTipText(tooltip); }
        return p;
    }

    private static Component gap(int h) { return Box.createVerticalStrut(h); }

}
