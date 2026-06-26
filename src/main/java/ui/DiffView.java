package ui;

import engine.DiffUtil;
import engine.DiffUtil.DiffLine;
import engine.DiffUtil.LineType;
import engine.DiffUtil.SideBySideDiff;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Side-by-side response diff renderer for the Compare tab.
 *
 * Two non-wrapping text panes show line-aligned content with muted, theme-
 * friendly diff highlights, vertical scroll synchronized between the panes,
 * prev/next jump between changes, a monospace font, and an "Identical" banner
 * when the two responses match. Lines matching ignore-patterns (when the
 * caller renders in normalized mode) are greyed and struck through.
 *
 * <p>This is a pure renderer — the caller computes the {@link SideBySideDiff}
 * (raw or normalized) and hands it over.</p>
 */
public class DiffView extends JPanel {

    // Muted diff tints, blended onto the current theme background in the painter.
    private static final Color OLD_TINT = new Color(0xC0, 0x39, 0x39);
    private static final Color NEW_TINT = new Color(0x33, 0x99, 0x33);
    private static final Color IGN_FG   = new Color(0x88, 0x88, 0x88);

    private final JTextPane leftPane  = new JTextPane();
    private final JTextPane rightPane = new JTextPane();
    private final JScrollPane leftScroll;
    private final JScrollPane rightScroll;

    private final JLabel banner = new JLabel("", SwingConstants.CENTER);
    private final JPanel center;     // CardLayout: banner vs diff
    private final CardLayout cards = new CardLayout();

    private final List<Integer> changedRows = new ArrayList<>();
    private int jumpCursor = -1;
    private int fontSize = 12;
    private final ChangeMinimap minimap = new ChangeMinimap();

    public DiffView() {
        super(new BorderLayout());

        leftScroll  = noWrapScroll(leftPane);
        rightScroll = noWrapScroll(rightPane);

        // Lock the two panes' vertical scrolling together. Both panes always have
        // the same number of aligned rows, so a shared model keeps them in step.
        rightScroll.getVerticalScrollBar().setModel(leftScroll.getVerticalScrollBar().getModel());

        // Distinct, subtle border on whichever pane holds focus.
        installFocusBorder(leftPane, leftScroll);
        installFocusBorder(rightPane, rightScroll);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setContinuousLayout(true);

        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 13f));
        banner.setForeground(NEW_TINT);

        // Diff stage = panes + a thin change minimap on the right edge. The
        // minimap marks every changed row and a viewport box; clicking jumps.
        minimap.setOnJump(this::scrollToFraction);
        JPanel diffPanel = new JPanel(new BorderLayout());
        diffPanel.add(split, BorderLayout.CENTER);
        diffPanel.add(minimap, BorderLayout.EAST);
        leftScroll.getVerticalScrollBar().addAdjustmentListener(e -> minimap.repaint());

        center = new JPanel(cards);
        center.add(diffPanel, "diff");
        center.add(banner, "banner");
        cards.show(center, "diff");

        add(center, BorderLayout.CENTER);

        applyFont(fontSize);
    }

    // ---- Public API ----------------------------------------------------

    /** Left (OLD) pane — exposed so the caller can track focus / request focus. */
    public JTextPane leftPane()  { return leftPane; }
    /** Right (NEW) pane. */
    public JTextPane rightPane() { return rightPane; }

    /**
     * Binds the spec navigation keys directly on both panes (WHEN_FOCUSED so they
     * win over the panes' default caret movement): ↑/↓ move test cases, ←/→ cycle
     * the focused pane's response, Tab switches the focused pane.
     */
    public void installKeyNavigation(Runnable prevTc, Runnable nextTc,
                                     Runnable cyclePrev, Runnable cycleNext,
                                     Runnable toggleFocus) {
        for (JTextPane p : new JTextPane[]{leftPane, rightPane}) {
            p.setFocusTraversalKeysEnabled(false);
            InputMap im = p.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap am = p.getActionMap();
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),    "bacPrevTc",      prevTc);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),  "bacNextTc",      nextTc);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),  "bacCyclePrev",   cyclePrev);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "bacCycleNext",   cycleNext);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),   "bacToggleFocus", toggleFocus);
        }
    }

    private static void bind(InputMap im, ActionMap am, KeyStroke ks, String key, Runnable r) {
        im.put(ks, key);
        am.put(key, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    public void setFontSize(int size) {
        if (size >= 8 && size <= 24) { fontSize = size; applyFont(size); }
    }

    /** Renders a precomputed side-by-side diff. */
    public void render(SideBySideDiff diff) {
        List<DiffLine> l = diff.left();
        List<DiffLine> r = diff.right();

        // Pair replace-blocks (a run of OLD_ONLY rows followed by a run of
        // NEW_ONLY rows) so we can highlight only the characters that actually
        // changed within otherwise-similar lines — e.g. a single id inside an
        // identical JSON line — instead of painting the whole line.
        java.util.Map<Integer, List<DiffUtil.Span>> leftSpans = new java.util.HashMap<>();
        java.util.Map<Integer, List<DiffUtil.Span>> rightSpans = new java.util.HashMap<>();
        computeWordSpans(l, r, leftSpans, rightSpans);

        renderPane(leftPane, l, leftSpans, OLD_TINT);
        renderPane(rightPane, r, rightSpans, NEW_TINT);

        // Index changed rows for jump navigation.
        changedRows.clear();
        int n = Math.max(l.size(), r.size());
        for (int i = 0; i < n; i++) {
            boolean changed = (i < l.size() && l.get(i).isChanged())
                           || (i < r.size() && r.get(i).isChanged());
            if (changed) changedRows.add(i);
        }
        jumpCursor = -1;
        minimap.setRows(n, changedRows);

        if (changedRows.isEmpty()) {
            banner.setText("✓  Responses are identical");
            cards.show(center, "banner");
        } else {
            cards.show(center, "diff");
        }

        SwingUtilities.invokeLater(() -> {
            leftScroll.getVerticalScrollBar().setValue(0);
            leftPane.setCaretPosition(0);
            rightPane.setCaretPosition(0);
        });
    }

    /** Pairs delete-runs with the following insert-runs and computes per-row word spans. */
    private static void computeWordSpans(List<DiffLine> left, List<DiffLine> right,
                                         java.util.Map<Integer, List<DiffUtil.Span>> leftSpans,
                                         java.util.Map<Integer, List<DiffUtil.Span>> rightSpans) {
        int n = Math.min(left.size(), right.size());
        int i = 0;
        while (i < n) {
            if (left.get(i).type() == LineType.OLD_ONLY) {
                int delStart = i;
                while (i < n && left.get(i).type() == LineType.OLD_ONLY) i++;
                int insStart = i;
                while (i < n && right.get(i).type() == LineType.NEW_ONLY) i++;
                int dels = insStart - delStart, ins = i - insStart;
                int pairs = Math.min(dels, ins);
                for (int t = 0; t < pairs; t++) {
                    int oldRow = delStart + t, newRow = insStart + t;
                    String a = DiffUtil.stripIgnoredPrefix(left.get(oldRow).content());
                    String b = DiffUtil.stripIgnoredPrefix(right.get(newRow).content());
                    leftSpans.put(oldRow, DiffUtil.wordDiffLeft(a, b));
                    rightSpans.put(newRow, DiffUtil.wordDiffRight(a, b));
                }
            } else {
                i++;
            }
        }
    }

    /** Number of changed rows in the current diff. */
    public int changeCount() { return changedRows.size(); }

    /** Scroll to the next change (wraps). */
    public void jumpNext() {
        if (changedRows.isEmpty()) return;
        jumpCursor = (jumpCursor + 1) % changedRows.size();
        scrollToRow(changedRows.get(jumpCursor));
    }

    /** Scroll to the previous change (wraps). */
    public void jumpPrev() {
        if (changedRows.isEmpty()) return;
        jumpCursor = (jumpCursor - 1 + changedRows.size()) % changedRows.size();
        scrollToRow(changedRows.get(jumpCursor));
    }

    // ---- Rendering -----------------------------------------------------

    private void renderPane(JTextPane pane, List<DiffLine> lines,
                            java.util.Map<Integer, List<DiffUtil.Span>> charSpans, Color tint) {
        pane.setHighlighter(new DefaultHighlighter());
        StyledDocument doc = new DefaultStyledDocument();

        SimpleAttributeSet normal = new SimpleAttributeSet();
        SimpleAttributeSet ignored = new SimpleAttributeSet();
        StyleConstants.setForeground(ignored, IGN_FG);
        StyleConstants.setStrikeThrough(ignored, true);

        try {
            List<int[]> bands = new ArrayList<>(); // {startOffset, type} for line backgrounds: 1=old,2=new
            List<int[]> wordMarks = new ArrayList<>(); // {absStart, absEnd} intra-line changed spans
            int rowIdx = 0;
            for (DiffLine dl : lines) {
                String text = DiffUtil.stripIgnoredPrefix(dl.content());
                boolean isIgnored = DiffUtil.isIgnored(dl.content());
                int start = doc.getLength();
                doc.insertString(start, text + "\n", isIgnored ? ignored : normal);
                if (!isIgnored && dl.type() == LineType.OLD_ONLY) bands.add(new int[]{start, 1});
                else if (!isIgnored && dl.type() == LineType.NEW_ONLY) bands.add(new int[]{start, 2});
                if (!isIgnored && charSpans != null) {
                    List<DiffUtil.Span> spans = charSpans.get(rowIdx);
                    if (spans != null) for (DiffUtil.Span s : spans) {
                        int a = start + Math.min(s.start(), text.length());
                        int b = start + Math.min(s.end(), text.length());
                        if (b > a) wordMarks.add(new int[]{a, b});
                    }
                }
                rowIdx++;
            }
            pane.setDocument(doc);

            Highlighter hl = pane.getHighlighter();
            Element root = doc.getDefaultRootElement();
            for (int[] band : bands) {
                int lineIndex = root.getElementIndex(band[0]);
                Element line = root.getElement(lineIndex);
                Color t = band[1] == 1 ? OLD_TINT : NEW_TINT;
                hl.addHighlight(line.getStartOffset(), line.getEndOffset(),
                    new FullWidthLinePainter(t));
            }
            // Stronger highlight on just the changed words, painted over the band.
            var wordPainter = new DefaultHighlighter.DefaultHighlightPainter(
                FullWidthLinePainter.blend(tint, pane.getBackground(), 0.45f));
            for (int[] m : wordMarks) hl.addHighlight(m[0], m[1], wordPainter);
        } catch (BadLocationException e) {
            // Should not happen for sequential inserts; leave whatever rendered.
        }
        applyFont(pane, fontSize);
    }

    /** Scrolls so the given 0..1 fraction of the document is centered (minimap clicks). */
    private void scrollToFraction(double frac) {
        var bar = leftScroll.getVerticalScrollBar();
        int max = bar.getMaximum() - bar.getVisibleAmount();
        bar.setValue((int) Math.max(0, Math.min(max, frac * max)));
    }

    private void scrollToRow(int row) {
        try {
            Element root = leftPane.getStyledDocument().getDefaultRootElement();
            if (row < 0 || row >= root.getElementCount()) return;
            Element line = root.getElement(row);
            Rectangle2D r = leftPane.modelToView2D(line.getStartOffset());
            if (r != null) {
                Rectangle rect = new Rectangle((int) r.getX(), (int) r.getY(),
                    (int) r.getWidth(), (int) r.getHeight() * 4); // bring some context into view
                leftPane.scrollRectToVisible(rect);
            }
        } catch (BadLocationException ignored) {
        }
    }

    // ---- Helpers -------------------------------------------------------

    private void applyFont(int size) {
        applyFont(leftPane, size);
        applyFont(rightPane, size);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, (float) Math.max(13, size + 1)));
    }

    private static void applyFont(JTextPane pane, int size) {
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size));
    }

    private static void installFocusBorder(JTextPane pane, JScrollPane scroll) {
        javax.swing.border.Border idle = BorderFactory.createEmptyBorder(1, 1, 1, 1);
        javax.swing.border.Border active = BorderFactory.createLineBorder(NEW_TINT, 1);
        scroll.setBorder(idle);
        pane.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { scroll.setBorder(active); }
            @Override public void focusLost(java.awt.event.FocusEvent e)   { scroll.setBorder(idle); }
        });
    }

    /** Wraps a text pane so it never line-wraps and is horizontally scrollable. */
    private static JScrollPane noWrapScroll(JTextPane pane) {
        pane.setEditable(false);
        JPanel noWrap = new JPanel(new BorderLayout());
        noWrap.add(pane, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(noWrap);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /**
     * A slim vertical overview of the diff: one tick per changed row mapped to
     * the document height, plus a translucent box showing the visible viewport.
     * Clicking (or dragging) scrolls both panes to that position.
     */
    private final class ChangeMinimap extends JComponent {
        private int totalRows = 0;
        private List<Integer> changed = List.of();
        private java.util.function.DoubleConsumer onJump;

        ChangeMinimap() {
            setPreferredSize(new Dimension(12, 10));
            setToolTipText("Diff overview — click to jump");
            java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { jump(e.getY()); }
                @Override public void mouseDragged(java.awt.event.MouseEvent e) { jump(e.getY()); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        void setOnJump(java.util.function.DoubleConsumer c) { this.onJump = c; }

        void setRows(int total, List<Integer> changedRows) {
            this.totalRows = Math.max(1, total);
            this.changed = new ArrayList<>(changedRows);
            repaint();
        }

        private void jump(int y) {
            if (onJump != null) onJump.accept(Math.max(0, Math.min(1.0, (double) y / Math.max(1, getHeight()))));
        }

        @Override protected void paintComponent(Graphics g) {
            int h = getHeight(), w = getWidth();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(blendBg());
            g2.fillRect(0, 0, w, h);
            // Change ticks.
            for (int row : changed) {
                int y = (int) ((double) row / totalRows * h);
                g2.setColor(NEW_TINT);
                g2.fillRect(1, y, w - 2, Math.max(1, h / totalRows + 1));
            }
            // Viewport box.
            var bar = leftScroll.getVerticalScrollBar();
            int max = Math.max(1, bar.getMaximum());
            int vy = (int) ((double) bar.getValue() / max * h);
            int vh = (int) ((double) bar.getVisibleAmount() / max * h);
            g2.setColor(new Color(128, 128, 128, 70));
            g2.fillRect(0, vy, w, Math.max(6, vh));
            g2.setColor(new Color(128, 128, 128, 130));
            g2.drawRect(0, vy, w - 1, Math.max(6, vh));
            g2.dispose();
        }

        private Color blendBg() {
            Color b = leftPane.getBackground();
            if (b == null) return new Color(0, 0, 0, 12);
            int d = (b.getRed() + b.getGreen() + b.getBlue()) / 3 > 128 ? -12 : 18;
            return new Color(clamp(b.getRed() + d), clamp(b.getGreen() + d), clamp(b.getBlue() + d));
        }
        private int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    }

    /**
     * Paints a muted, full-width background band behind a single line, blended
     * onto the pane's current background so it reads gently on light or dark themes.
     */
    private static final class FullWidthLinePainter implements Highlighter.HighlightPainter {
        private final Color tint;
        FullWidthLinePainter(Color tint) { this.tint = tint; }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle2D r0 = c.modelToView2D(p0);
                Rectangle2D r1 = c.modelToView2D(p1);
                if (r0 == null || r1 == null) return;
                int y = (int) Math.min(r0.getY(), r1.getY());
                int h = (int) Math.max(r0.getHeight(), Math.abs(r1.getMaxY() - r0.getY()));
                if (h <= 0) h = (int) r0.getHeight();
                int width = c.getWidth();
                Color base = c.getBackground();
                g.setColor(blend(tint, base, 0.22f));
                g.fillRect(0, y, width, h);
            } catch (BadLocationException ignored) {
            }
        }

        private static Color blend(Color overlay, Color base, float alpha) {
            if (base == null) base = Color.WHITE;
            int r = (int) (overlay.getRed()   * alpha + base.getRed()   * (1 - alpha));
            int g = (int) (overlay.getGreen() * alpha + base.getGreen() * (1 - alpha));
            int b = (int) (overlay.getBlue()  * alpha + base.getBlue()  * (1 - alpha));
            return new Color(clamp(r), clamp(g), clamp(b));
        }

        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    }
}
