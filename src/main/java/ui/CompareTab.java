package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import db.*;
import engine.DiffUtil;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.PreparedStatement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Phase 5 — Compare tab: side-by-side response comparison with keyboard navigation,
 * muted diff highlighting, synchronized scroll, normalized/raw toggle, and
 * Send-to-Comparer integration.
 */
public class CompareTab extends JPanel {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // Muted diff colors (blended with panel bg in renderPane)
    private static final Color OLD_BG  = new Color(0x7A, 0x1F, 0x1F, 160); // muted red
    private static final Color NEW_BG  = new Color(0x1A, 0x5A, 0x1A, 160); // muted green
    private static final Color IGN_FG  = new Color(0x88, 0x88, 0x88);       // grey for ignored lines

    // ---- State --------------------------------------------------------

    private final List<Long> workingSet = new ArrayList<>();
    private int workingSetIdx = -1;

    // Shared response list for both panes (baselines first, then results)
    private final List<ResponseEntry> responses = new ArrayList<>();
    private int leftIdx  = 0;
    private int rightIdx = 0;
    private long currentResultId = -1; // id of the result currently in the RIGHT pane (for review)
    private boolean currentReviewed = false;

    // ---- UI -----------------------------------------------------------

    private JPanel viewArea;        // swaps between placeholder and main view
    private JLabel navLabel;
    private JLabel tcNameLabel;
    private JComboBox<ResponseEntry> leftCombo, rightCombo;
    private JLabel summaryLabel;
    private JLabel leftTitle, rightTitle;
    private JPanel sessionChipsPanel;
    private HttpResponseEditor leftEditor, rightEditor;  // Burp-native: syntax coloring + right-click
    private JButton reviewedBtn;
    private JTextField noteField;

    // ---- Data ---------------------------------------------------------

    private final MontoyaApi api;
    private final DatabaseManager dbManager;
    private final TestCaseRepository tcRepo;
    private final RunRepository runRepo;
    private final AccountRepository accountRepo;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-compare-loader");
        t.setDaemon(true);
        return t;
    });

    // ---- Records -------------------------------------------------------

    record ResponseEntry(
        long id,           // baseline.id or result.id
        boolean isBaseline,
        String label,
        byte[] raw,
        Integer status,
        Integer length,
        String verdict,
        boolean isPrimary
    ) {
        @Override public String toString() { return label; }
    }

    // ---- Constructor --------------------------------------------------

    public CompareTab(MontoyaApi api, DatabaseManager dbManager,
                      TestCaseRepository tcRepo, RunRepository runRepo,
                      AccountRepository accountRepo) {
        super(new BorderLayout());
        this.api         = api;
        this.dbManager   = dbManager;
        this.tcRepo      = tcRepo;
        this.runRepo     = runRepo;
        this.accountRepo = accountRepo;

        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        viewArea = buildPlaceholder();
        add(viewArea, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(this);
    }

    // ---- Public API ---------------------------------------------------

    /** Adds test case IDs to the working set and jumps to the first new one. */
    public void addToWorkingSet(List<Long> ids) {
        int firstNew = workingSet.size();
        for (long id : ids) {
            if (!workingSet.contains(id)) workingSet.add(id);
        }
        if (workingSetIdx < 0 && !workingSet.isEmpty()) {
            workingSetIdx = 0;
        } else if (firstNew < workingSet.size() && firstNew >= 0) {
            workingSetIdx = firstNew;
        }
        loadCurrentTC();
    }

    /** Opens a single test case (replaces working set). */
    public void openTestCase(long id) {
        workingSet.clear();
        workingSet.add(id);
        workingSetIdx = 0;
        loadCurrentTC();
    }

    // ---- Navigation ---------------------------------------------------

    private void navigatePrev() {
        if (workingSet.isEmpty() || workingSetIdx <= 0) return;
        workingSetIdx--;
        loadCurrentTC();
    }

    private void navigateNext() {
        if (workingSet.isEmpty() || workingSetIdx >= workingSet.size() - 1) return;
        workingSetIdx++;
        loadCurrentTC();
    }

    private void cycleResponse(boolean isLeft, boolean forward) {
        if (responses.isEmpty()) return;
        if (isLeft) {
            leftIdx = wrap(leftIdx + (forward ? 1 : -1), responses.size());
            leftCombo.setSelectedIndex(leftIdx);
        } else {
            rightIdx = wrap(rightIdx + (forward ? 1 : -1), responses.size());
            rightCombo.setSelectedIndex(rightIdx);
        }
        renderPanes();
    }

    // ---- Data loading -------------------------------------------------

    private void loadCurrentTC() {
        if (workingSetIdx < 0 || workingSetIdx >= workingSet.size()) return;
        long tcId = workingSet.get(workingSetIdx);

        loader.submit(() -> {
            try {
                var tcOpt = tcRepo.getById(tcId);
                if (tcOpt.isEmpty()) return;
                var tc = tcOpt.get();

                var baselines = tcRepo.getBaselines(tcId);
                var results   = runRepo.getResultsForTestCase(tcId);
                var primaryId = tcRepo.getPrimaryBaselineId(tcId).orElse(-1L);

                List<ResponseEntry> entries = new ArrayList<>();
                for (var bl : baselines) {
                    boolean isPrimary = bl.id() == primaryId;
                    String lbl = "baseline: " + (bl.label() != null ? bl.label() : "original")
                        + " · " + FMT.format(Instant.ofEpochSecond(bl.capturedAt()))
                        + (isPrimary ? " ★" : "");
                    entries.add(new ResponseEntry(
                        bl.id(), true, lbl, bl.responseRaw(),
                        bl.status(), bl.length(), null, isPrimary));
                }
                for (var r : results) {
                    String acct = r.accountName() != null ? r.accountName() : "?";
                    String lbl = acct + " · " + verdictIcon(r.verdict())
                        + " · " + r.newStatus()
                        + " · " + String.format("%.0f%%", r.similarity())
                        + " · " + FMT.format(Instant.ofEpochSecond(r.createdAt()));
                    entries.add(new ResponseEntry(
                        r.id(), false, lbl, r.newResponseRaw(),
                        r.newStatus(), r.newLength(), r.verdict(), false));
                }

                // Determine starting indices
                int lIdx = entries.stream().filter(ResponseEntry::isPrimary)
                    .mapToInt(entries::indexOf).findFirst().orElse(0);
                int rIdx = entries.isEmpty() ? 0
                    : (entries.size() > baselines.size() ? baselines.size() : entries.size() - 1);

                final String tcName = tc.method() + " " + tc.host() + " — " + tc.name();
                final int li = lIdx, ri = rIdx;
                final List<ResponseEntry> ents = entries;
                final List<RunRepository.ResultRecord> resultList = results;

                SwingUtilities.invokeLater(() -> {
                    ensureMainView();
                    updateNavLabel();
                    tcNameLabel.setText(tcName);

                    responses.clear();
                    responses.addAll(ents);
                    leftIdx  = li;
                    rightIdx = ri;

                    rebuildCombos();
                    buildSessionChips(resultList);
                    renderPanes();
                });

            } catch (Exception e) {
                api.logging().logToError("[BAC] Compare load error: " + e.getMessage());
            }
        });
    }

    // ---- Rendering ----------------------------------------------------

    private void renderPanes() {
        if (responses.isEmpty()) return;
        leftIdx  = Math.min(leftIdx,  responses.size() - 1);
        rightIdx = Math.min(rightIdx, responses.size() - 1);

        ResponseEntry left  = responses.get(leftIdx);
        ResponseEntry right = responses.get(rightIdx);

        byte[] leftRaw  = left.raw()  != null ? left.raw()  : new byte[0];
        byte[] rightRaw = right.raw() != null ? right.raw() : new byte[0];

        // Determine result id for review button
        currentResultId = right.isBaseline() ? -1 : right.id();
        currentReviewed = false;
        if (currentResultId > 0) {
            try {
                synchronized (dbManager) {
                    String sql = "SELECT reviewed FROM results WHERE id = ?";
                    try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
                        ps.setLong(1, currentResultId);
                        try (var rs = ps.executeQuery()) {
                            if (rs.next()) currentReviewed = rs.getInt(1) == 1;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Show each response in a Burp-native read-only editor (syntax coloring,
        // search, and the full right-click context menu).
        setEditorResponse(leftEditor,  leftRaw);
        setEditorResponse(rightEditor, rightRaw);
        if (leftTitle  != null) leftTitle.setText("OLD: "  + left.label());
        if (rightTitle != null) rightTitle.setText("NEW: " + right.label());

        // Similarity for the summary still comes from the line diff.
        DiffUtil.SideBySideDiff diff = DiffUtil.compute(leftRaw, rightRaw);
        updateSummary(left, right, diff);

        reviewedBtn.setEnabled(currentResultId > 0);
        reviewedBtn.setText(currentReviewed ? "✓ Reviewed" : "Mark Reviewed");
    }

    private void setEditorResponse(HttpResponseEditor editor, byte[] raw) {
        try {
            editor.setResponse(HttpResponse.httpResponse(
                ByteArray.byteArray(raw != null && raw.length > 0 ? raw : new byte[0])));
        } catch (Exception ignored) {}
    }

    // ---- UI building --------------------------------------------------

    private void ensureMainView() {
        if (viewArea.getClientProperty("isMain") == Boolean.TRUE) return;
        remove(viewArea);
        viewArea = buildMainView();
        add(viewArea, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private JPanel buildPlaceholder() {
        JPanel p = new JPanel(new GridBagLayout());
        Box box = Box.createVerticalBox();
        for (String line : new String[]{
                "No test cases loaded.",
                "Select test cases in Library → Add to Working Set,",
                "or right-click a result in Test Run → Open in Compare."}) {
            JLabel l = new JLabel(line);
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
            l.setForeground(UIManager.getColor("Label.disabledForeground"));
            box.add(l);
        }
        p.add(box);
        api.userInterface().applyThemeToComponent(p);
        return p;
    }

    private JPanel buildMainView() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.putClientProperty("isMain", Boolean.TRUE);

        p.add(buildTopPanel(), BorderLayout.NORTH);
        p.add(buildEditorPanel(), BorderLayout.CENTER);
        p.add(buildToolBar(), BorderLayout.SOUTH);

        api.userInterface().applyThemeToComponent(p);
        return p;
    }

    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));

        // Nav bar
        JPanel nav = new JPanel(new BorderLayout(6, 0));
        JButton prevBtn = new JButton("◀");
        JButton nextBtn = new JButton("▶");
        prevBtn.setPreferredSize(new Dimension(36, 24));
        nextBtn.setPreferredSize(new Dimension(36, 24));
        navLabel    = new JLabel("0 / 0", SwingConstants.CENTER);
        tcNameLabel = new JLabel("", SwingConstants.LEFT);
        tcNameLabel.setFont(tcNameLabel.getFont().deriveFont(Font.BOLD, 12f));

        navLabel.setPreferredSize(new Dimension(80, 24));

        prevBtn.addActionListener(e -> navigatePrev());
        nextBtn.addActionListener(e -> navigateNext());

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navButtons.add(prevBtn);
        navButtons.add(navLabel);
        navButtons.add(nextBtn);
        navButtons.add(tcNameLabel);

        nav.add(navButtons, BorderLayout.WEST);
        top.add(nav, BorderLayout.NORTH);

        // Header row: OLD selector | summary | NEW selector
        JPanel header = new JPanel(new BorderLayout(8, 0));

        JPanel leftSel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftSel.add(new JLabel("OLD:"));
        leftCombo = new JComboBox<>();
        leftCombo.setPreferredSize(new Dimension(260, 24));
        leftCombo.addActionListener(e -> {
            leftIdx = leftCombo.getSelectedIndex();
            if (leftIdx >= 0) renderPanes();
        });
        leftSel.add(leftCombo);

        JPanel rightSel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightSel.add(new JLabel("NEW:"));
        rightCombo = new JComboBox<>();
        rightCombo.setPreferredSize(new Dimension(260, 24));
        rightCombo.addActionListener(e -> {
            rightIdx = rightCombo.getSelectedIndex();
            if (rightIdx >= 0) renderPanes();
        });
        rightSel.add(rightCombo);

        summaryLabel = new JLabel("", SwingConstants.CENTER);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(11f));

        header.add(leftSel,     BorderLayout.WEST);
        header.add(summaryLabel, BorderLayout.CENTER);
        header.add(rightSel,    BorderLayout.EAST);
        top.add(header, BorderLayout.CENTER);

        // Session chips
        sessionChipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        sessionChipsPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        top.add(sessionChipsPanel, BorderLayout.SOUTH);

        api.userInterface().applyThemeToComponent(top);
        api.userInterface().applyThemeToComponent(nav);
        api.userInterface().applyThemeToComponent(header);
        return top;
    }

    private JSplitPane buildEditorPanel() {
        leftEditor  = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        rightEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        leftTitle  = paneTitle("OLD");
        rightTitle = paneTitle("NEW");

        JPanel leftWrap  = new JPanel(new BorderLayout());
        leftWrap.add(leftTitle, BorderLayout.NORTH);
        leftWrap.add(leftEditor.uiComponent(), BorderLayout.CENTER);

        JPanel rightWrap = new JPanel(new BorderLayout());
        rightWrap.add(rightTitle, BorderLayout.NORTH);
        rightWrap.add(rightEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftWrap, rightWrap);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setContinuousLayout(true);
        api.userInterface().applyThemeToComponent(split);
        return split;
    }

    private JLabel paneTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return l;
    }

    private JPanel buildToolBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));

        // Swap sides
        JButton swapBtn = new JButton("⇄ Swap");
        swapBtn.addActionListener(e -> {
            int tmp = leftIdx; leftIdx = rightIdx; rightIdx = tmp;
            leftCombo.setSelectedIndex(leftIdx);
            rightCombo.setSelectedIndex(rightIdx);
            renderPanes();
        });
        bar.add(swapBtn);

        // Send to Burp Comparer (for a full word-level diff)
        JButton comparerBtn = new JButton("📋 Send to Comparer");
        comparerBtn.setToolTipText("Send both responses to Burp Comparer for a detailed diff");
        comparerBtn.addActionListener(e -> sendToComparer());
        bar.add(comparerBtn);

        bar.add(new JSeparator(JSeparator.VERTICAL));

        // Reviewed + note
        reviewedBtn = new JButton("Mark Reviewed");
        reviewedBtn.setEnabled(false);
        reviewedBtn.addActionListener(e -> toggleReviewed());
        bar.add(reviewedBtn);

        noteField = new JTextField(18);
        noteField.setToolTipText("Note for this result");
        noteField.addActionListener(e -> saveNote());
        bar.add(new JLabel("Note:"));
        bar.add(noteField);

        api.userInterface().applyThemeToComponent(bar);
        return bar;
    }

    // ---- Session chips ------------------------------------------------

    private void buildSessionChips(List<RunRepository.ResultRecord> results) {
        sessionChipsPanel.removeAll();
        if (results.isEmpty()) { sessionChipsPanel.revalidate(); return; }

        sessionChipsPanel.add(new JLabel("Sessions:"));
        // Latest result per account
        Map<Long, RunRepository.ResultRecord> latestPerAccount = new LinkedHashMap<>();
        for (var r : results) {
            latestPerAccount.putIfAbsent(r.accountId(), r);
        }
        for (var entry : latestPerAccount.entrySet()) {
            var r = entry.getValue();
            String label = (r.accountName() != null ? r.accountName() : "?")
                + " " + verdictIcon(r.verdict())
                + " " + r.newStatus()
                + " " + String.format("%.0f%%", r.similarity());

            JButton chip = new JButton(label);
            chip.setFont(chip.getFont().deriveFont(10.5f));
            chip.setMargin(new Insets(1, 6, 1, 6));
            Color chipColor = verdictColor(r.verdict());
            if (chipColor != null) {
                chip.setBackground(blend(chipColor, UIManager.getColor("Panel.background")));
            }
            // Find the index of this result in the responses list
            final long resultId = r.id();
            chip.addActionListener(e -> {
                for (int i = 0; i < responses.size(); i++) {
                    if (!responses.get(i).isBaseline() && responses.get(i).id() == resultId) {
                        rightIdx = i;
                        rightCombo.setSelectedIndex(rightIdx);
                        renderPanes();
                        break;
                    }
                }
            });
            sessionChipsPanel.add(chip);
        }
        sessionChipsPanel.revalidate();
        sessionChipsPanel.repaint();
    }

    // ---- Helpers ------------------------------------------------------

    private void rebuildCombos() {
        leftCombo.removeAllItems();
        rightCombo.removeAllItems();
        for (var r : responses) {
            leftCombo.addItem(r);
            rightCombo.addItem(r);
        }
        if (leftIdx  < responses.size()) leftCombo.setSelectedIndex(leftIdx);
        if (rightIdx < responses.size()) rightCombo.setSelectedIndex(rightIdx);
    }

    private void updateNavLabel() {
        if (navLabel != null)
            navLabel.setText((workingSetIdx + 1) + " / " + workingSet.size());
    }

    private void updateSummary(ResponseEntry left, ResponseEntry right, DiffUtil.SideBySideDiff diff) {
        int ls = left.status()  != null ? left.status()  : 0;
        int rs = right.status() != null ? right.status() : 0;
        int ll = left.length()  != null ? left.length()  : 0;
        int rl = right.length() != null ? right.length() : 0;
        double sim = diff.similarity();

        String statusPart  = ls + " → " + rs;
        String sizePart    = kbLabel(ll) + " → " + kbLabel(rl);
        String simPart     = String.format("Sim %.1f%%", sim);
        String verdictPart = right.verdict() != null ? verdictIcon(right.verdict()) : "";

        summaryLabel.setText(statusPart + "  ·  " + sizePart + "  ·  " + simPart
            + (verdictPart.isEmpty() ? "" : "  ·  " + verdictPart));

        // Colour summary by verdict
        Color vc = right.verdict() != null ? verdictColor(right.verdict()) : null;
        summaryLabel.setForeground(vc != null ? vc : UIManager.getColor("Label.foreground"));
    }

    private void sendToComparer() {
        if (responses.isEmpty()) return;
        byte[] leftRaw  = responses.get(leftIdx).raw();
        byte[] rightRaw = responses.get(rightIdx).raw();
        if (leftRaw == null || rightRaw == null) return;
        api.comparer().sendToComparer(ByteArray.byteArray(leftRaw), ByteArray.byteArray(rightRaw));
    }

    private void toggleReviewed() {
        if (currentResultId < 0) return;
        currentReviewed = !currentReviewed;
        String note = noteField.getText().trim();
        try {
            runRepo.markReviewed(currentResultId, currentReviewed, note.isEmpty() ? null : note);
            reviewedBtn.setText(currentReviewed ? "✓ Reviewed" : "Mark Reviewed");
            reviewedBtn.setForeground(currentReviewed ? new Color(0, 140, 0) : UIManager.getColor("Button.foreground"));
        } catch (Exception e) {
            api.logging().logToError("[BAC] markReviewed error: " + e.getMessage());
        }
    }

    private void saveNote() {
        if (currentResultId < 0) return;
        try {
            runRepo.markReviewed(currentResultId, currentReviewed, noteField.getText().trim());
        } catch (Exception e) {
            api.logging().logToError("[BAC] saveNote error: " + e.getMessage());
        }
    }

    // ---- Static helpers -----------------------------------------------

    private static int wrap(int i, int size) {
        if (size == 0) return 0;
        return ((i % size) + size) % size;
    }

    private static String kbLabel(int bytes) {
        if (bytes < 1024) return bytes + " B";
        return String.format("%.1f KB", bytes / 1024.0);
    }

    private static String verdictIcon(String v) {
        if (v == null) return "";
        return switch (v) {
            case RunEngine.POTENTIAL_BAC   -> "🚩";
            case RunEngine.LIKELY_ENFORCED -> "✅";
            case RunEngine.EXPECTED_OK     -> "⚪";
            case RunEngine.ANOMALY         -> "⚠";
            case RunEngine.REVIEW          -> "🔍";
            case RunEngine.SKIPPED_SAFE    -> "—";
            default -> "";
        };
    }

    private static Color verdictColor(String v) {
        if (v == null) return null;
        return switch (v) {
            case RunEngine.POTENTIAL_BAC   -> new Color(0xC8, 0x30, 0x30);
            case RunEngine.LIKELY_ENFORCED -> new Color(0x20, 0x90, 0x20);
            case RunEngine.ANOMALY         -> new Color(0xC8, 0x70, 0x00);
            case RunEngine.REVIEW          -> new Color(0x90, 0x80, 0x00);
            default -> null;
        };
    }

    private static Color blend(Color overlay, Color base) {
        if (base == null) base = Color.WHITE;
        float a = overlay.getAlpha() / 255f;
        int r = Math.min(255, (int)(overlay.getRed()   * a + base.getRed()   * (1 - a)));
        int g = Math.min(255, (int)(overlay.getGreen() * a + base.getGreen() * (1 - a)));
        int b = Math.min(255, (int)(overlay.getBlue()  * a + base.getBlue()  * (1 - a)));
        return new Color(r, g, b);
    }
}
