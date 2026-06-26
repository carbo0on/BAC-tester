package ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A reusable titled section with a clickable header that folds its body away.
 *
 * <p>Shared across all BAC tabs so collapsible behaviour is consistent. Fixes
 * the previous Settings-only implementation where only the header text (not the
 * whole row) was clickable and where toggling could leave stale layout: here the
 * <em>entire</em> header bar toggles, and a collapse revalidates/repaints the
 * whole ancestor chain so sibling sections reflow immediately under a
 * {@link BoxLayout} inside a {@link JScrollPane}.</p>
 */
public final class CollapsibleSection extends JPanel {

    private final JPanel content = new JPanel();
    private final JLabel arrow = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JPanel header = new JPanel(new BorderLayout(6, 0));
    private final String title;
    private boolean expanded = true;
    private Runnable onToggle;

    public CollapsibleSection(String title) { this(title, null); }

    public CollapsibleSection(String title, String emoji) {
        this.title = (emoji != null && !emoji.isBlank() ? emoji + "  " : "") + title;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setOpaque(false);

        // Header bar: arrow + title, clickable across its full width.
        arrow.setFont(arrow.getFont().deriveFont(Font.PLAIN, 12f));
        titleLabel.setText(this.title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        header.add(arrow, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 4));
        header.setOpaque(false);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setToolTipText("Click to collapse / expand this section");
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setExpanded(!expanded); }
        });

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(4, 18, 6, 0));

        add(header);
        add(content);
        updateHeader();
    }

    /** Add a control (or a gap component) to this section's body. */
    public void addContent(Component c) {
        if (c instanceof JComponent jc) jc.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(c);
    }

    /** Fired (on the EDT) whenever the section is expanded or collapsed. */
    public void setOnToggle(Runnable r) { this.onToggle = r; }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean e) {
        if (expanded == e) return;
        expanded = e;
        content.setVisible(e);
        updateHeader();
        // Revalidate the whole ancestor chain so siblings under a BoxLayout in a
        // JScrollPane reflow immediately instead of leaving a stale gap.
        revalidate();
        Container p = getParent();
        while (p != null) { p.revalidate(); p.repaint(); p = p.getParent(); }
        repaint();
        if (onToggle != null) onToggle.run();
    }

    private void updateHeader() {
        arrow.setText(expanded ? "▾" : "▸");
    }

    // Keep the section compact under BoxLayout (don't stretch vertically), while
    // filling the available width so the header bar is fully clickable.
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
