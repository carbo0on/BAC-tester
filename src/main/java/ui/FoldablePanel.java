package ui;

import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Wraps any "options / config" component behind a slim, fully-clickable header
 * so it can be folded away to free vertical space for the table/viewer below.
 *
 * <p>Unlike {@link CollapsibleSection} (a stacked settings group), this is meant
 * for the control bar that sits at the top of a tab: collapsing it lets the main
 * content breathe. The expanded/collapsed state is persisted per {@code prefKey}
 * via Burp's extension preferences, so the layout the user prefers survives a
 * reload.</p>
 */
public final class FoldablePanel extends JPanel {

    private final JLabel arrow = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JComponent body;
    private final MontoyaApi api;
    private final String prefKey;
    private final String baseTitle;
    private boolean expanded = true;

    public FoldablePanel(MontoyaApi api, String prefKey, String title, JComponent body) {
        super(new BorderLayout());
        this.api = api;
        this.prefKey = "bac_fold_" + prefKey;
        this.baseTitle = title;
        this.body = body;
        setOpaque(false);

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Click to fold / unfold these options");
        arrow.setFont(arrow.getFont().deriveFont(Font.PLAIN, 11f));
        titleLabel.setText(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
        titleLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.add(arrow, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setExpanded(!expanded); }
        });

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        // Restore persisted state (default: expanded).
        boolean restore = true;
        try {
            Boolean v = api.persistence().preferences().getBoolean(this.prefKey);
            if (v != null) restore = v;
        } catch (Exception ignored) {}
        applyState(restore);
    }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean e) {
        if (expanded == e) return;
        applyState(e);
        try { api.persistence().preferences().setBoolean(prefKey, e); } catch (Exception ignored) {}
    }

    private void applyState(boolean e) {
        expanded = e;
        body.setVisible(e);
        arrow.setText(e ? "▾" : "▸");
        titleLabel.setText(e ? baseTitle : baseTitle + "  (folded)");
        revalidate();
        repaint();
    }
}
