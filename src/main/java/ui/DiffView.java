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

        center = new JPanel(cards);
        center.add(split, "diff");
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
        renderPane(leftPane, diff.left());
        renderPane(rightPane, diff.right());

        // Index changed rows for jump navigation.
        changedRows.clear();
        List<DiffLine> l = diff.left();
        List<DiffLine> r = diff.right();
        int n = Math.max(l.size(), r.size());
        for (int i = 0; i < n; i++) {
            boolean changed = (i < l.size() && l.get(i).isChanged())
                           || (i < r.size() && r.get(i).isChanged());
            if (changed) changedRows.add(i);
        }
        jumpCursor = -1;

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

    private void renderPane(JTextPane pane, List<DiffLine> lines) {
        pane.setHighlighter(new DefaultHighlighter());
        StyledDocument doc = new DefaultStyledDocument();

        SimpleAttributeSet normal = new SimpleAttributeSet();
        SimpleAttributeSet ignored = new SimpleAttributeSet();
        StyleConstants.setForeground(ignored, IGN_FG);
        StyleConstants.setStrikeThrough(ignored, true);

        try {
            List<int[]> bands = new ArrayList<>(); // {startOffset, type} for line backgrounds: 1=old,2=new
            for (DiffLine dl : lines) {
                String text = DiffUtil.stripIgnoredPrefix(dl.content());
                boolean isIgnored = DiffUtil.isIgnored(dl.content());
                int start = doc.getLength();
                doc.insertString(start, text + "\n", isIgnored ? ignored : normal);
                if (!isIgnored && dl.type() == LineType.OLD_ONLY) bands.add(new int[]{start, 1});
                else if (!isIgnored && dl.type() == LineType.NEW_ONLY) bands.add(new int[]{start, 2});
            }
            pane.setDocument(doc);

            Highlighter hl = pane.getHighlighter();
            Element root = doc.getDefaultRootElement();
            for (int[] band : bands) {
                int lineIndex = root.getElementIndex(band[0]);
                Element line = root.getElement(lineIndex);
                Color tint = band[1] == 1 ? OLD_TINT : NEW_TINT;
                hl.addHighlight(line.getStartOffset(), line.getEndOffset(),
                    new FullWidthLinePainter(tint));
            }
        } catch (BadLocationException e) {
            // Should not happen for sequential inserts; leave whatever rendered.
        }
        applyFont(pane, fontSize);
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
