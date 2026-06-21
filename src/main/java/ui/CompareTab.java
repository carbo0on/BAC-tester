package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import db.*;
import engine.DiffUtil;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.Connection;
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
    private boolean leftFocused = true;
    private boolean syncScrollLock = false;
    private boolean normalizedMode = false;
    private List<Pattern> ignorePatterns = new ArrayList<>();
    private long currentResultId = -1; // id of the result currently in the RIGHT pane (for review)
    private boolean currentReviewed = false;

    // Diff navigation: visual line indices (in the rendered pane) where diffs start
    private final List<Integer> diffPositions = new ArrayList<>();
    private int diffNavIdx = 0;

    // ---- UI -----------------------------------------------------------

    private JPanel viewArea;        // swaps between placeholder and main view
    private JLabel navLabel;
    private JLabel tcNameLabel;
    private JComboBox<ResponseEntry> leftCombo, rightCombo;
    private JLabel summaryLabel;
    private JPanel sessionChipsPanel;
    private JTextPane leftPane, rightPane;
    private JScrollPane leftScroll, rightScroll;
    private JLabel diffCountLabel;
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

        loadIgnorePatterns();
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
                // quick check
                String sql = "SELECT reviewed FROM results WHERE id = ?";
                try (Connection c = dbManager.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, currentResultId);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) currentReviewed = rs.getInt(1) == 1;
                    }
                }
            } catch (Exception ignored) {}
        }

        DiffUtil.SideBySideDiff diff;
        if (normalizedMode) {
            String[] leftLines  = applyIgnore(leftRaw);
            String[] rightLines = applyIgnore(rightRaw);
            diff = DiffUtil.computeText(
                String.join("\n", leftLines),
                String.join("\n", rightLines));
        } else {
            diff = DiffUtil.compute(leftRaw, rightRaw);
        }

        updateSummary(left, right, diff);
        buildDiffPositions(diff.left());
        renderPane(leftPane,  diff.left(),  OLD_BG);
        renderPane(rightPane, diff.right(), NEW_BG);

        reviewedBtn.setEnabled(currentResultId > 0);
        reviewedBtn.setText(currentReviewed ? "✓ Reviewed" : "Mark Reviewed");
    }

    private String[] applyIgnore(byte[] raw) {
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        if (text.length() > 300_000) text = text.substring(0, 300_000);
        String[] lines = text.split("\n", -1);
        return DiffUtil.applyIgnorePatterns(lines, ignorePatterns);
    }

    private void renderPane(JTextPane pane, List<DiffUtil.DiffLine> lines, Color changedBg) {
        Font mono = api.userInterface().currentEditorFont();
        if (mono == null) mono = new Font("Monospaced", Font.PLAIN, 12);

        DefaultStyledDocument doc = new DefaultStyledDocument();
        Style base = doc.addStyle("base", null);
        StyleConstants.setFontFamily(base, mono.getFamily());
        StyleConstants.setFontSize(base, mono.getSize());

        Color tableBg = pane.getBackground();
        Color blendedOld = blend(OLD_BG, tableBg);
        Color blendedNew = blend(NEW_BG, tableBg);

        for (DiffUtil.DiffLine dl : lines) {
            boolean isIgnored = normalizedMode && DiffUtil.isIgnored(dl.content());
            String displayLine = isIgnored
                ? DiffUtil.stripIgnoredPrefix(dl.content())
                : dl.content();

            Style s = doc.addStyle(null, base);
            if (dl.type() == DiffUtil.LineType.OLD_ONLY) {
                StyleConstants.setBackground(s, blendedOld);
            } else if (dl.type() == DiffUtil.LineType.NEW_ONLY) {
                StyleConstants.setBackground(s, changedBg == OLD_BG ? blendedOld : blendedNew);
            } else if (dl.isBlank()) {
                Color blankBg = new Color(tableBg.getRed(), tableBg.getGreen(), tableBg.getBlue(),
                    tableBg instanceof Color bc ? bc.getAlpha() : 255);
                StyleConstants.setBackground(s, new Color(
                    Math.max(0, tableBg.getRed()   - 8),
                    Math.max(0, tableBg.getGreen() - 8),
                    Math.max(0, tableBg.getBlue()  - 8)));
            }
            if (isIgnored) {
                StyleConstants.setForeground(s, IGN_FG);
                StyleConstants.setStrikeThrough(s, true);
            }

            try {
                doc.insertString(doc.getLength(), displayLine + "\n", s);
            } catch (BadLocationException ignored) {}
        }

        pane.setDocument(doc);
        pane.setCaretPosition(0);
    }

    private void buildDiffPositions(List<DiffUtil.DiffLine> leftLines) {
        diffPositions.clear();
        for (int i = 0; i < leftLines.size(); i++) {
            if (leftLines.get(i).isChanged()) {
                // Add only the start of each changed block
                if (diffPositions.isEmpty() || diffPositions.get(diffPositions.size() - 1) < i - 1) {
                    diffPositions.add(i);
                }
            }
        }
        diffNavIdx = 0;
        diffCountLabel.setText(diffPositions.isEmpty() ? "No diffs" : "0/" + diffPositions.size() + " diffs");
    }

    private void jumpToDiff(boolean forward) {
        if (diffPositions.isEmpty()) return;
        diffNavIdx = forward
            ? (diffNavIdx + 1) % diffPositions.size()
            : (diffNavIdx - 1 + diffPositions.size()) % diffPositions.size();
        diffCountLabel.setText((diffNavIdx + 1) + "/" + diffPositions.size() + " diffs");
        scrollToLine(diffPositions.get(diffNavIdx));
    }

    private void scrollToLine(int lineIndex) {
        try {
            int pos = leftPane.getDocument().getDefaultRootElement()
                .getElement(Math.min(lineIndex, leftPane.getDocument().getDefaultRootElement().getElementCount() - 1))
                .getStartOffset();
            Rectangle r = leftPane.modelToView2D(pos).getBounds();
            leftPane.scrollRectToVisible(r);
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
        JLabel l = new JLabel("<html><center>No test cases loaded.<br>"
            + "<small>Select test cases in Library → Add to Working Set,<br>"
            + "or right-click a result in Test Run → Open in Compare.</small></center></html>");
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        p.add(l);
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
        Font mono = api.userInterface().currentEditorFont();
        if (mono == null) mono = new Font("Monospaced", Font.PLAIN, 12);

        leftPane  = createTextPane(mono);
        rightPane = createTextPane(mono);

        leftScroll  = new JScrollPane(leftPane);
        rightScroll = new JScrollPane(rightPane);

        configureFocusBorder(leftPane, rightPane, true);
        configureFocusBorder(rightPane, leftPane, false);

        wireScrollSync();
        wireKeyboardNavigation();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        api.userInterface().applyThemeToComponent(split);
        return split;
    }

    private JTextPane createTextPane(Font mono) {
        JTextPane pane = new JTextPane();
        pane.setFont(mono);
        pane.setEditable(false);
        pane.setCaret(new DefaultCaret() {
            @Override public void setSelectionVisible(boolean v) { super.setSelectionVisible(v); }
        });
        api.userInterface().applyThemeToComponent(pane);
        return pane;
    }

    private void configureFocusBorder(JTextPane pane, JTextPane other, boolean isLeft) {
        pane.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                leftFocused = isLeft;
                pane.setBorder(BorderFactory.createLineBorder(new Color(80, 130, 200), 1));
                other.setBorder(null);
            }
            @Override public void focusLost(FocusEvent e) { pane.setBorder(null); }
        });
    }

    private JPanel buildToolBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));

        // Diff navigation
        JButton prevDiff = new JButton("‹ Prev diff");
        diffCountLabel = new JLabel("No diffs");
        JButton nextDiff = new JButton("Next diff ›");
        prevDiff.addActionListener(e -> jumpToDiff(false));
        nextDiff.addActionListener(e -> jumpToDiff(true));

        bar.add(prevDiff);
        bar.add(diffCountLabel);
        bar.add(nextDiff);
        bar.add(new JSeparator(JSeparator.VERTICAL));

        // Swap sides
        JButton swapBtn = new JButton("⇄ Swap");
        swapBtn.addActionListener(e -> {
            int tmp = leftIdx; leftIdx = rightIdx; rightIdx = tmp;
            leftCombo.setSelectedIndex(leftIdx);
            rightCombo.setSelectedIndex(rightIdx);
            renderPanes();
        });
        bar.add(swapBtn);

        // Send to Burp Comparer
        JButton comparerBtn = new JButton("📋 Comparer");
        comparerBtn.setToolTipText("Send both responses to Burp Comparer");
        comparerBtn.addActionListener(e -> sendToComparer());
        bar.add(comparerBtn);

        bar.add(new JSeparator(JSeparator.VERTICAL));

        // Normalized toggle
        JToggleButton normBtn = new JToggleButton("Raw");
        normBtn.setToolTipText("Toggle normalized view (grey-out ignored patterns)");
        normBtn.addActionListener(e -> {
            normalizedMode = normBtn.isSelected();
            normBtn.setText(normalizedMode ? "Normalized" : "Raw");
            renderPanes();
        });
        bar.add(normBtn);

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

    // ---- Scroll sync --------------------------------------------------

    private void wireScrollSync() {
        BoundedRangeModel lm = leftScroll.getVerticalScrollBar().getModel();
        BoundedRangeModel rm = rightScroll.getVerticalScrollBar().getModel();

        lm.addChangeListener(e -> {
            if (!syncScrollLock) {
                syncScrollLock = true;
                rm.setValue(lm.getValue());
                syncScrollLock = false;
            }
        });
        rm.addChangeListener(e -> {
            if (!syncScrollLock) {
                syncScrollLock = true;
                lm.setValue(rm.getValue());
                syncScrollLock = false;
            }
        });
    }

    // ---- Keyboard navigation ------------------------------------------

    private void wireKeyboardNavigation() {
        // Override default arrow key actions in both text panes
        bindPane(leftPane,  true);
        bindPane(rightPane, false);
    }

    private void bindPane(JTextPane pane, boolean isLeft) {
        InputMap im = pane.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = pane.getActionMap();

        // ↑/↓ → navigate working set
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP,   0), "tc-prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "tc-next");
        am.put("tc-prev", action(e -> navigatePrev()));
        am.put("tc-next", action(e -> navigateNext()));

        // ←/→ → cycle responses in THIS pane
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0), "resp-back");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "resp-fwd");
        am.put("resp-back", action(e -> cycleResponse(isLeft, false)));
        am.put("resp-fwd",  action(e -> cycleResponse(isLeft, true)));
    }

    private AbstractAction action(Consumer<ActionEvent> fn) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { fn.accept(e); }
        };
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

    private void loadIgnorePatterns() {
        loader.submit(() -> {
            try {
                String sql = "SELECT value FROM settings WHERE key = 'ignore_patterns'";
                try (var st = dbManager.getConnection().createStatement();
                     var rs = st.executeQuery(sql)) {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        if (json != null) {
                            // Parse JSON array of strings: ["regex1","regex2"]
                            List<Pattern> pats = new ArrayList<>();
                            // Simple extraction (avoid pulling in Gson for this)
                            json = json.trim();
                            if (json.startsWith("[") && json.endsWith("]")) {
                                json = json.substring(1, json.length() - 1);
                                for (String part : json.split(",")) {
                                    String s = part.trim();
                                    if (s.startsWith("\"") && s.endsWith("\""))
                                        s = s.substring(1, s.length() - 1);
                                    if (!s.isBlank()) {
                                        try { pats.add(Pattern.compile(s)); } catch (Exception ignored) {}
                                    }
                                }
                            }
                            ignorePatterns = pats;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
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
