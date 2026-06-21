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
 * Phase 7 — Settings tab: match threshold, ignore patterns, safe mode,
 * font size, DB path display, and Export/Import buttons.
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

    private JSpinner thresholdSpinner;
    private JCheckBox safeModeCheck;
    private JSpinner fontSizeSpinner;
    private DefaultListModel<String> patternModel;
    private JLabel dbPathLabel;

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

    private void buildUI() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        // ── Match threshold ──────────────────────────────────────────────
        thresholdSpinner = new JSpinner(new SpinnerNumberModel(95, 50, 100, 1));
        thresholdSpinner.setPreferredSize(new Dimension(70, thresholdSpinner.getPreferredSize().height));
        form.add(row("Match threshold (%):", thresholdSpinner,
            "Similarity score at or above this value counts as a 'match'. Default: 95"));
        form.add(gap(6));

        // ── Safe mode ────────────────────────────────────────────────────
        safeModeCheck = new JCheckBox(
            "Safe Mode — skip state-changing requests (POST / PUT / PATCH / DELETE) during test runs");
        safeModeCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(safeModeCheck);
        form.add(gap(6));

        // ── Font size ────────────────────────────────────────────────────
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(12, 8, 24, 1));
        fontSizeSpinner.setPreferredSize(new Dimension(60, fontSizeSpinner.getPreferredSize().height));
        form.add(row("Response viewer font size (px):", fontSizeSpinner, null));
        form.add(gap(14));

        // ── Ignore patterns ──────────────────────────────────────────────
        JLabel lbl = new JLabel("Ignore patterns (Java regex — matching lines are greyed out and excluded from similarity):");
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
            if (regex != null && !regex.isBlank()) patternModel.addElement(regex.trim());
        });
        remBtn.addActionListener(e -> {
            int idx = patternList.getSelectedIndex();
            if (idx >= 0) patternModel.remove(idx);
        });

        form.add(gap(14));

        // ── DB path ──────────────────────────────────────────────────────
        dbPathLabel = new JLabel("—");
        dbPathLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        form.add(row("Database path:", dbPathLabel, null));
        form.add(gap(14));

        // ── Save ─────────────────────────────────────────────────────────
        JButton saveBtn = new JButton("Save Settings");
        saveBtn.addActionListener(e -> saveSettings());
        JPanel savePan = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        savePan.setAlignmentX(Component.LEFT_ALIGNMENT);
        savePan.add(saveBtn);
        form.add(savePan);
        form.add(gap(16));

        // ── Separator ────────────────────────────────────────────────────
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        form.add(sep);
        form.add(gap(12));

        // ── Export / Import ──────────────────────────────────────────────
        JLabel ioTitle = new JLabel("Library Export / Import");
        ioTitle.setFont(ioTitle.getFont().deriveFont(Font.BOLD, 13f));
        ioTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(ioTitle);
        form.add(gap(4));

        JLabel ioHint = new JLabel("<html><small>"
            + "Export saves folders + test cases + all versioned baselines to a portable .bac.json bundle.<br>"
            + "Import reads one or more .bac.json files, rebuilds the folder tree, and merges test cases<br>"
            + "(deduplicates by host + method + URL + request hash — old baselines are never deleted)."
            + "</small></html>");
        ioHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(ioHint);
        form.add(gap(8));

        JButton exportBtn = new JButton("Export Library…");
        JButton importBtn = new JButton("Import Library…");
        exportBtn.addActionListener(e -> exportImport.showExportDialog(this));
        importBtn.addActionListener(e -> exportImport.showImportDialog(this, count ->
            JOptionPane.showMessageDialog(this,
                "Import complete: " + count + " test case(s) imported.",
                "Import Done", JOptionPane.INFORMATION_MESSAGE)));

        JPanel ioPan = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ioPan.setAlignmentX(Component.LEFT_ALIGNMENT);
        ioPan.add(exportBtn);
        ioPan.add(importBtn);
        form.add(ioPan);

        add(new JScrollPane(form), BorderLayout.CENTER);
    }

    // ── Settings I/O ──────────────────────────────────────────────────────

    private void loadSettings() {
        loader.submit(() -> {
            try {
                String threshold = db.getSetting("match_threshold");
                String safeMode  = db.getSetting("safe_mode");
                String fontSize  = db.getSetting("font_size");
                String patterns  = db.getSetting("ignore_patterns");
                String dbPath    = db.getSetting("db_path");
                SwingUtilities.invokeLater(() -> {
                    if (threshold != null) {
                        try { thresholdSpinner.setValue(Integer.parseInt(threshold)); } catch (Exception ignored) {}
                    }
                    safeModeCheck.setSelected(!"false".equalsIgnoreCase(safeMode));
                    if (fontSize != null) {
                        try { fontSizeSpinner.setValue(Integer.parseInt(fontSize)); } catch (Exception ignored) {}
                    }
                    if (patterns != null) {
                        try {
                            List<String> list = gson.fromJson(patterns, new TypeToken<List<String>>(){}.getType());
                            patternModel.clear();
                            if (list != null) list.forEach(patternModel::addElement);
                        } catch (Exception ignored) {}
                    }
                    if (dbPath != null) dbPathLabel.setText(dbPath);
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Load settings failed: " + e.getMessage());
            }
        });
    }

    private void saveSettings() {
        int threshold = (Integer) thresholdSpinner.getValue();
        boolean safeMode = safeModeCheck.isSelected();
        int fontSize = (Integer) fontSizeSpinner.getValue();
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < patternModel.size(); i++) patterns.add(patternModel.get(i));
        String patternsJson = gson.toJson(patterns);

        loader.submit(() -> {
            try {
                db.setSetting("match_threshold", String.valueOf(threshold));
                db.setSetting("safe_mode", String.valueOf(safeMode));
                db.setSetting("font_size", String.valueOf(fontSize));
                db.setSetting("ignore_patterns", patternsJson);
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Settings saved.", "Saved", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e) {
                api.logging().logToError("[BAC] Save settings failed: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Error saving: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    // ── Layout helpers ────────────────────────────────────────────────────

    private static JPanel row(String label, JComponent control, String tooltip) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(new JLabel(label));
        p.add(control);
        if (tooltip != null) control.setToolTipText(tooltip);
        return p;
    }

    private static Component gap(int h) { return Box.createVerticalStrut(h); }
}
