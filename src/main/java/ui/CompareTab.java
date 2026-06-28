package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
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
 * Compare tab — side-by-side response comparison (the centrepiece, spec §5.4).
 *
 * Implemented here: a custom side-by-side {@link DiffView} with muted diff
 * highlighting, vertical scroll synchronized between the panes, prev/next jump
 * between changes, a normalized↔raw toggle (ignored lines greyed/struck and
 * excluded from similarity), an "Identical" banner, plus an optional toggle to
 * the Burp-native editors (syntax colouring, search, right-click). Keyboard
 * navigation: ↑/↓ move through the working set, ←/→ cycle the focused pane's
 * response, Tab/click switches the focused pane. A collapsible "Show request"
 * strip reveals the captured request, and Send-to-Comparer hands both responses
 * to Burp Comparer.
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
    /** Monotonic token so a slow background render can't overwrite a newer one. */
    private volatile int renderToken = 0;

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

    // Diff view + view-mode switching (diff highlight vs native editors)
    private DiffView diffView;
    private JPanel centerCards;
    private final CardLayout centerLayout = new CardLayout();
    private boolean diffMode = true;

    // Normalized (ignore-patterns) vs raw rendering
    private boolean normalized = false;
    private List<Pattern> ignorePatterns = new ArrayList<>();
    private int fontSize = 12;

    // Focused pane (for ←/→ response cycling)
    private boolean focusedLeft = true;

    // Collapsible request strip
    private HttpRequestEditor requestEditor;
    private JPanel requestStrip;
    private JButton showRequestBtn;
    private boolean requestShown = false;

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

        loadCompareSettings();

        viewArea = buildPlaceholder();
        add(viewArea, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(this);
    }

    /** Loads ignore-patterns and font size used by the diff view. */
    private void loadCompareSettings() {
        try {
            ignorePatterns = RunEngine.loadIgnorePatterns(dbManager);
        } catch (Exception ignored) {}
        try {
            String fs = dbManager.getSetting("font_size");
            if (fs != null) fontSize = Integer.parseInt(fs.trim());
        } catch (Exception ignored) {}
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
                // Pick up any ignore-pattern / font changes made in Settings.
                ignorePatterns = RunEngine.loadIgnorePatterns(dbManager);
                try {
                    String fs = dbManager.getSetting("font_size");
                    if (fs != null) fontSize = Integer.parseInt(fs.trim());
                } catch (Exception ignored) {}

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
                    // Focus a diff pane so ↑/↓ ←/→ work immediately.
                    if (diffMode && diffView != null) {
                        (focusedLeft ? diffView.leftPane() : diffView.rightPane()).requestFocusInWindow();
                    }
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

        final ResponseEntry left  = responses.get(leftIdx);
        final ResponseEntry right = responses.get(rightIdx);

        final byte[] leftRaw  = left.raw()  != null ? left.raw()  : new byte[0];
        final byte[] rightRaw = right.raw() != null ? right.raw() : new byte[0];
        final boolean norm = normalized;
        final List<Pattern> ip = ignorePatterns;
        final int fs = fontSize;
        final long resultId = right.isBaseline() ? -1 : right.id();
        final int token = ++renderToken;

        // The Burp-native editors and pane titles are cheap to update — do them
        // on the EDT immediately so the panes never appear stale. The review
        // target is also set now so a fast click can't act on a stale result.
        setEditorResponse(leftEditor,  leftRaw);
        setEditorResponse(rightEditor, rightRaw);
        updateTitles(left, right);
        currentResultId = resultId;
        reviewedBtn.setEnabled(resultId > 0);
        if (requestShown) loadRequestForCurrentTc();

        // The expensive work — the reviewed lookup (a DB round-trip), the
        // line-level diff and the similarity score over bodies up to 200 KB —
        // must NOT run on the EDT or it freezes the UI while paging with ↑/↓.
        loader.submit(() -> {
            boolean reviewed = false;
            if (resultId > 0) {
                try {
                    synchronized (dbManager) {
                        String sql = "SELECT reviewed FROM results WHERE id = ?";
                        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
                            ps.setLong(1, resultId);
                            try (var rs = ps.executeQuery()) {
                                if (rs.next()) reviewed = rs.getInt(1) == 1;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            final DiffUtil.SideBySideDiff diff = norm
                ? DiffUtil.computeNormalized(leftRaw, rightRaw, ip)
                : DiffUtil.compute(leftRaw, rightRaw);
            // Similarity is computed consistently with the engine: JSON-aware and,
            // in normalized mode, with ignore-patterns stripped.
            final double sim = RunEngine.computeSimilarity(leftRaw, rightRaw,
                norm ? ip : Collections.emptyList());
            final boolean fReviewed = reviewed;

            SwingUtilities.invokeLater(() -> {
                if (token != renderToken) return; // a newer render superseded this one
                currentReviewed = fReviewed;
                if (diffView != null) {
                    diffView.setFontSize(fs);
                    diffView.render(diff);
                }
                updateSummary(left, right, sim);
                reviewedBtn.setText(currentReviewed ? "✓ Reviewed" : "Mark Reviewed");
            });
        });
    }

    private void updateTitles(ResponseEntry left, ResponseEntry right) {
        if (leftTitle != null)
            leftTitle.setText((focusedLeft ? "▶ " : "") + "OLD: " + left.label());
        if (rightTitle != null)
            rightTitle.setText((!focusedLeft ? "▶ " : "") + "NEW: " + right.label());
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

        // South stack: collapsible request strip above the toolbar.
        JPanel south = new JPanel(new BorderLayout());
        south.add(buildRequestStrip(), BorderLayout.NORTH);
        south.add(buildToolBar(), BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);

        installNavKeys(p, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        api.userInterface().applyThemeToComponent(p);
        return p;
    }

    /** Collapsible "Show request" strip (collapsed by default). */
    private JPanel buildRequestStrip() {
        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        requestStrip = new JPanel(new BorderLayout());
        requestStrip.add(requestEditor.uiComponent(), BorderLayout.CENTER);
        requestStrip.setPreferredSize(new Dimension(10, 200));
        requestStrip.setVisible(false);
        return requestStrip;
    }

    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));

        // ── Row 1 (slim): nav · test-case name · summary badges ────────────
        JPanel nav = new JPanel(new BorderLayout(8, 0));
        JButton prevBtn = new JButton("◀");
        JButton nextBtn = new JButton("▶");
        prevBtn.setPreferredSize(new Dimension(36, 24));
        nextBtn.setPreferredSize(new Dimension(36, 24));
        prevBtn.setToolTipText("Previous test case (↑)");
        nextBtn.setToolTipText("Next test case (↓)");
        navLabel    = new JLabel("0 / 0", SwingConstants.CENTER);
        tcNameLabel = new JLabel("", SwingConstants.LEFT);
        tcNameLabel.setFont(tcNameLabel.getFont().deriveFont(Font.BOLD, 12f));
        navLabel.setPreferredSize(new Dimension(70, 24));

        prevBtn.addActionListener(e -> navigatePrev());
        nextBtn.addActionListener(e -> navigateNext());

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navButtons.add(prevBtn);
        navButtons.add(navLabel);
        navButtons.add(nextBtn);

        summaryLabel = new JLabel("", SwingConstants.RIGHT);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(11f));

        nav.add(navButtons,  BorderLayout.WEST);
        nav.add(tcNameLabel, BorderLayout.CENTER);
        nav.add(summaryLabel, BorderLayout.EAST);
        top.add(nav, BorderLayout.NORTH);

        // ── Row 2: OLD selector | session chips | NEW selector ─────────────
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        JPanel leftSel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftSel.add(new JLabel("OLD:"));
        leftCombo = new JComboBox<>();
        leftCombo.setPreferredSize(new Dimension(240, 24));
        leftCombo.addActionListener(e -> {
            leftIdx = leftCombo.getSelectedIndex();
            if (leftIdx >= 0) renderPanes();
        });
        leftSel.add(leftCombo);

        JPanel rightSel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightCombo = new JComboBox<>();
        rightCombo.setPreferredSize(new Dimension(240, 24));
        rightCombo.addActionListener(e -> {
            rightIdx = rightCombo.getSelectedIndex();
            if (rightIdx >= 0) renderPanes();
        });
        rightSel.add(new JLabel("NEW:"));
        rightSel.add(rightCombo);

        // Session chips live between the two selectors so they stay on one line.
        sessionChipsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));

        header.add(leftSel,  BorderLayout.WEST);
        header.add(sessionChipsPanel, BorderLayout.CENTER);
        header.add(rightSel, BorderLayout.EAST);
        // Fold the OLD/NEW selectors + session chips away to give the two
        // responses the whole screen; the slim nav row above stays visible.
        top.add(new FoldablePanel(api, "compare_selectors", "Compare options", header), BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(top);
        api.userInterface().applyThemeToComponent(nav);
        api.userInterface().applyThemeToComponent(header);
        return top;
    }

    private JComponent buildEditorPanel() {
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

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftWrap, rightWrap);
        editorSplit.setResizeWeight(0.5);
        editorSplit.setDividerSize(4);
        editorSplit.setContinuousLayout(true);

        // Custom diff view (default) — muted highlight, synced scroll, jump.
        diffView = new DiffView();
        diffView.setFontSize(fontSize);
        // Track which diff pane is focused for ←/→ cycling, and bind nav keys there.
        for (Component c : diffView.getComponents()) installFocusTracking(c);

        centerCards = new JPanel(centerLayout);
        centerCards.add(diffView, "diff");
        centerCards.add(editorSplit, "editors");
        centerLayout.show(centerCards, "diff");

        // Keyboard navigation bound on the focusable diff panes (wins over caret).
        diffView.installKeyNavigation(
            this::navigatePrev,
            this::navigateNext,
            () -> cycleResponse(focusedLeft, false),
            () -> cycleResponse(focusedLeft, true),
            () -> setFocusedLeft(!focusedLeft));
        diffView.leftPane().addFocusListener(focusSetter(true));
        diffView.rightPane().addFocusListener(focusSetter(false));

        api.userInterface().applyThemeToComponent(editorSplit);
        return centerCards;
    }

    private FocusAdapter focusSetter(boolean left) {
        return new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { setFocusedLeft(left); }
        };
    }

    private void setFocusedLeft(boolean left) {
        if (focusedLeft == left && leftTitle != null) return;
        focusedLeft = left;
        // Reflect focus in the pane titles without a full re-render.
        if (!responses.isEmpty()) {
            updateTitles(responses.get(Math.min(leftIdx, responses.size() - 1)),
                         responses.get(Math.min(rightIdx, responses.size() - 1)));
        }
    }

    /** Ancestor-level fallback bindings (when focus is on combos/chips, not a pane). */
    private void installNavKeys(JComponent comp, int condition) {
        InputMap im = comp.getInputMap(condition);
        ActionMap am = comp.getActionMap();
        bindKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),    "bacPrevTc",    this::navigatePrev);
        bindKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),  "bacNextTc",    this::navigateNext);
        bindKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),  "bacCyclePrev", () -> cycleResponse(focusedLeft, false));
        bindKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "bacCycleNext", () -> cycleResponse(focusedLeft, true));
    }

    private static void bindKey(InputMap im, ActionMap am, KeyStroke ks, String key, Runnable r) {
        im.put(ks, key);
        am.put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Recursively attach a mouse listener so clicking either side sets the focused pane. */
    private void installFocusTracking(Component c) {
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // Heuristic: left half of the diff view = OLD, right half = NEW.
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), diffView);
                setFocusedLeft(p.x < diffView.getWidth() / 2);
            }
        });
        if (c instanceof Container con) {
            for (Component child : con.getComponents()) installFocusTracking(child);
        }
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

        // Diff-jump prev/next
        JButton diffPrev = new JButton("‹ diff");
        JButton diffNext = new JButton("diff ›");
        diffPrev.setToolTipText("Jump to previous difference");
        diffNext.setToolTipText("Jump to next difference");
        diffPrev.addActionListener(e -> { if (diffView != null) diffView.jumpPrev(); });
        diffNext.addActionListener(e -> { if (diffView != null) diffView.jumpNext(); });
        bar.add(diffPrev);
        bar.add(diffNext);

        bar.add(new JSeparator(JSeparator.VERTICAL));

        // View mode: Diff highlight ↔ Burp-native editors
        JToggleButton viewToggle = new JToggleButton("Editors");
        viewToggle.setToolTipText("Switch between the highlighted diff and the Burp-native editors "
            + "(syntax colouring, search, right-click)");
        viewToggle.addActionListener(e -> {
            diffMode = !viewToggle.isSelected();
            centerLayout.show(centerCards, diffMode ? "diff" : "editors");
            viewToggle.setText(diffMode ? "Editors" : "Diff view");
            renderPanes();
        });
        bar.add(viewToggle);

        // Normalized ↔ raw
        JToggleButton normToggle = new JToggleButton("Normalized");
        normToggle.setToolTipText("Grey out and exclude ignore-pattern lines (timestamps, nonces, "
            + "CSRF tokens) from the diff and similarity");
        normToggle.addActionListener(e -> { normalized = normToggle.isSelected(); renderPanes(); });
        bar.add(normToggle);

        // Swap sides
        JButton swapBtn = new JButton("⇄ Swap");
        swapBtn.setToolTipText("Swap the OLD and NEW responses");
        swapBtn.addActionListener(e -> {
            int tmp = leftIdx; leftIdx = rightIdx; rightIdx = tmp;
            leftCombo.setSelectedIndex(leftIdx);
            rightCombo.setSelectedIndex(rightIdx);
            renderPanes();
        });
        bar.add(swapBtn);

        // Show / hide request
        showRequestBtn = new JButton("▸ Show request");
        showRequestBtn.setToolTipText("Show the captured request (collapsed by default)");
        showRequestBtn.addActionListener(e -> toggleRequest());
        bar.add(showRequestBtn);

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

        noteField = new JTextField(16);
        noteField.setToolTipText("Note for this result");
        noteField.addActionListener(e -> saveNote());
        bar.add(new JLabel("Note:"));
        bar.add(noteField);

        // Keyboard-navigation hint (muted) so the arrow-key workflow is discoverable.
        JLabel hint = new JLabel("   ↑/↓ test cases · ←/→ responses · Tab/click switches pane");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 10.5f));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        bar.add(hint);

        api.userInterface().applyThemeToComponent(bar);
        return bar;
    }

    private void toggleRequest() {
        requestShown = !requestShown;
        requestStrip.setVisible(requestShown);
        showRequestBtn.setText(requestShown ? "▾ Hide request" : "▸ Show request");
        if (requestShown) loadRequestForCurrentTc();
        revalidate();
        repaint();
    }

    private void loadRequestForCurrentTc() {
        if (workingSetIdx < 0 || workingSetIdx >= workingSet.size()) return;
        long tcId = workingSet.get(workingSetIdx);
        loader.submit(() -> {
            try {
                byte[] raw = tcRepo.getRequestRaw(tcId);
                if (raw == null || raw.length == 0) return;
                SwingUtilities.invokeLater(() -> {
                    try { requestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(raw))); }
                    catch (Exception ignored) {}
                });
            } catch (Exception e) {
                api.logging().logToError("[BAC] Compare request load: " + e.getMessage());
            }
        });
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

    private void updateSummary(ResponseEntry left, ResponseEntry right, double sim) {
        int ls = left.status()  != null ? left.status()  : 0;
        int rs = right.status() != null ? right.status() : 0;
        int ll = left.length()  != null ? left.length()  : 0;
        int rl = right.length() != null ? right.length() : 0;

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
