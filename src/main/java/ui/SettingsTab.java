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
        form.add(sectionTitle("Triage / Comparison"));

        thresholdSpinner = new JSpinner(new SpinnerNumberModel(95, 1, 100, 1));
        thresholdSpinner.setPreferredSize(new Dimension(70, thresholdSpinner.getPreferredSize().height));
        form.add(row("Match threshold (%):", thresholdSpinner,
            "Similarity at/above this value counts as a 'match'. Default: 95"));

        reviewBoundSpinner = new JSpinner(new SpinnerNumberModel(60, 0, 100, 1));
        reviewBoundSpinner.setPreferredSize(new Dimension(70, reviewBoundSpinner.getPreferredSize().height));
        form.add(row("Review lower bound (%):", reviewBoundSpinner,
            "Similarity in [this, threshold) is flagged REVIEW (ambiguous). Default: 60"));

        defaultAccessCombo = new JComboBox<>(new String[]{"UNKNOWN", "ALLOWED", "DENIED"});
        form.add(row("Default expected access (new accounts):", defaultAccessCombo, null));

        form.add(gap(10));

        // ── Run safety ─────────────────────────────────────────────────────
        form.add(sectionTitle("Run Safety"));

        safeModeCheck = new JCheckBox(
            "Safe Mode — skip DELETE requests during runs (POST / PUT / PATCH are still replayed)");
        safeModeCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(safeModeCheck);

        confirmRunCheck = new JCheckBox("Ask for confirmation before starting a run");
        confirmRunCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(confirmRunCheck);

        scopeCombo = new JComboBox<>(new String[]{"WARN", "BLOCK", "OFF"});
        form.add(row("Out-of-scope handling:", scopeCombo,
            "WARN: log and continue · BLOCK: skip out-of-scope requests · OFF: ignore scope"));

        form.add(gap(10));

        // ── Capture ────────────────────────────────────────────────────────
        form.add(sectionTitle("Capture"));
        hotkeyField = new JTextField(14);
        form.add(row("Quick-save hotkey:", hotkeyField,
            "Combo used to quick-save the focused request to Inbox. Same format as Burp's "
            + "Settings (e.g. Alt+Meta for Alt+Windows, or Ctrl+Alt+A). Takes effect after "
            + "you reload the extension."));
        JLabel hotkeyHint = new JLabel("<html><small>Changing the hotkey requires reloading the "
            + "extension (Extensions ▸ toggle the Loaded checkbox).</small></html>");
        hotkeyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(hotkeyHint);

        form.add(gap(10));

        // ── Appearance ─────────────────────────────────────────────────────
        form.add(sectionTitle("Appearance"));

        coloringCombo = new JComboBox<>(new String[]{"AUTO", "MANUAL", "OFF"});
        form.add(row("Library row coloring:", coloringCombo,
            "AUTO: color by HTTP method · MANUAL: only your per-request colors · OFF: none"));

        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(12, 8, 24, 1));
        fontSizeSpinner.setPreferredSize(new Dimension(60, fontSizeSpinner.getPreferredSize().height));
        form.add(row("Response viewer font size (px):", fontSizeSpinner, null));

        autoExpandCheck = new JCheckBox("Auto-expand all folders in the Library tree");
        autoExpandCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(autoExpandCheck);

        form.add(gap(10));

        // ── Compare ─────────────────────────────────────────────────────────
        form.add(sectionTitle("Compare"));

        diffCapSpinner = new JSpinner(new SpinnerNumberModel(300, 50, 5000, 50));
        diffCapSpinner.setPreferredSize(new Dimension(80, diffCapSpinner.getPreferredSize().height));
        form.add(row("Max diff size (KB):", diffCapSpinner,
            "Responses larger than this are truncated in the Compare view for performance."));

        form.add(gap(10));

        // ── Ignore patterns ──────────────────────────────────────────────
        form.add(sectionTitle("Ignore Patterns"));
        JLabel lbl = new JLabel("Java regex — matching lines are greyed out and excluded from similarity:");
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(lbl);
        form.add(gap(4));

        patternModel = new DefaultListModel<>();
        JList<String> patternList = new JList<>(patternModel);
        patternList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        patternList.setVisibleRowCount(5);
        JScrollPane listScroll = new JScrollPane(patternList);
        listScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        listScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        form.add(listScroll);
        form.add(gap(4));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton addBtn = new JButton("+ Add");
        JButton remBtn = new JButton("- Remove selected");
        btnRow.add(addBtn);
        btnRow.add(remBtn);
        form.add(btnRow);

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

        form.add(gap(12));

        // ── DB path + Save ─────────────────────────────────────────────────
        form.add(sectionTitle("Storage"));
        dbPathLabel = new JLabel("—");
        dbPathLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        form.add(row("Database path:", dbPathLabel, null));

        form.add(gap(12));
        JButton saveBtn = new JButton("Save Settings");
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
        form.add(sectionTitle("Library Export / Import"));
        JLabel ioHint = new JLabel("<html><small>"
            + "Export saves folders + test cases + all versioned baselines to a portable .bac.json bundle.<br>"
            + "Import reads one or more .bac.json files, rebuilds the folder tree, and merges test cases<br>"
            + "(deduplicates by host + method + URL + request hash — old baselines are never deleted)."
            + "</small></html>");
        ioHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(ioHint);
        form.add(gap(6));

        dedupCheck = new JCheckBox("Skip duplicates on import (host + method + URL + request hash)");
        dedupCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(dedupCheck);
        form.add(gap(8));

        JButton exportBtn = new JButton("Export Library…");
        JButton importBtn = new JButton("Import Library…");
        exportBtn.addActionListener(e -> exportImport.showExportDialog(this));
        importBtn.addActionListener(e -> exportImport.showImportDialog(this, count -> {
            if (onSaved != null) onSaved.run(); // refresh library after import
        }));

        JPanel ioPan = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ioPan.setAlignmentX(Component.LEFT_ALIGNMENT);
        ioPan.add(exportBtn);
        ioPan.add(importBtn);
        form.add(ioPan);

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
                String fontSize    = db.getSetting("font_size");
                String diffCap     = db.getSetting("diff_size_cap_kb");
                String coloring    = db.getSetting("coloring_mode");
                String scope       = db.getSetting("scope_enforcement");
                String defAccess   = db.getSetting("default_expected_access");
                String autoExpand  = db.getSetting("auto_expand_folders");
                String confirmRun  = db.getSetting("confirm_before_run");
                String dedup       = db.getSetting("dedup_on_import");
                String patterns    = db.getSetting("ignore_patterns");
                String dbPath      = db.getSetting("db_path");
                String hotkey      = db.getSetting("hotkey_combo");

                SwingUtilities.invokeLater(() -> {
                    setSpinner(thresholdSpinner, threshold);
                    setSpinner(reviewBoundSpinner, reviewBound);
                    setSpinner(fontSizeSpinner, fontSize);
                    setSpinner(diffCapSpinner, diffCap);
                    safeModeCheck.setSelected(!"false".equalsIgnoreCase(safeMode));
                    autoExpandCheck.setSelected(!"false".equalsIgnoreCase(autoExpand));
                    confirmRunCheck.setSelected(!"false".equalsIgnoreCase(confirmRun));
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
                    hotkeyField.setText(hotkey != null && !hotkey.isBlank() ? hotkey : "Alt+Meta");
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
        int fontSize       = (Integer) fontSizeSpinner.getValue();
        int diffCap        = (Integer) diffCapSpinner.getValue();
        String coloring    = (String) coloringCombo.getSelectedItem();
        String scope       = (String) scopeCombo.getSelectedItem();
        String defAccess   = (String) defaultAccessCombo.getSelectedItem();
        boolean autoExpand = autoExpandCheck.isSelected();
        boolean confirmRun = confirmRunCheck.isSelected();
        boolean dedup      = dedupCheck != null && dedupCheck.isSelected();
        String hotkeyRaw   = hotkeyField != null ? hotkeyField.getText().trim() : "Alt+Meta";
        final String hotkeyToSave = hotkeyRaw.isBlank() ? "Alt+Meta" : hotkeyRaw;
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < patternModel.size(); i++) patterns.add(patternModel.get(i));
        String patternsJson = gson.toJson(patterns);

        loader.submit(() -> {
            try {
                db.setSetting("match_threshold", String.valueOf(threshold));
                db.setSetting("review_lower_bound", String.valueOf(reviewBound));
                db.setSetting("safe_mode", String.valueOf(safeMode));
                db.setSetting("font_size", String.valueOf(fontSize));
                db.setSetting("diff_size_cap_kb", String.valueOf(diffCap));
                db.setSetting("coloring_mode", coloring);
                db.setSetting("scope_enforcement", scope);
                db.setSetting("default_expected_access", defAccess);
                db.setSetting("auto_expand_folders", String.valueOf(autoExpand));
                db.setSetting("confirm_before_run", String.valueOf(confirmRun));
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

    private JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
        return l;
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
