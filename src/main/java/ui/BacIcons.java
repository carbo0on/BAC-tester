package ui;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.*;

/**
 * Small vector icons drawn with Graphics2D so the extension does not depend on
 * bundled image assets and always renders crisply on any Look-and-Feel / theme.
 *
 * Used by the Accounts and Library trees to put a person icon in front of
 * accounts and a (colour-tintable) folder icon in front of folders.
 */
final class BacIcons {

    private BacIcons() {}

    /** Maps a stored colour tag to a gentle, theme-friendly colour (or null). */
    static Color tagColor(String tag) {
        if (tag == null) return null;
        return switch (tag.toUpperCase()) {
            case "RED"    -> new Color(0xE0, 0x6C, 0x6C);
            case "ORANGE" -> new Color(0xE0, 0xA0, 0x55);
            case "YELLOW" -> new Color(0xC9, 0xB8, 0x3A);
            case "GREEN"  -> new Color(0x5F, 0xBF, 0x5F);
            case "BLUE"   -> new Color(0x5F, 0x96, 0xE0);
            case "PURPLE" -> new Color(0xB0, 0x7C, 0xE0);
            default       -> null;
        };
    }

    /** A person glyph for account leaves. */
    static Icon account() { return new AccountIcon(new Color(0x7E, 0xA8, 0xD8)); }

    /** A folder glyph, optionally tinted by the given colour (null = neutral manila). */
    static Icon folder(Color tint) { return new FolderIcon(tint); }

    /** A simple coloured swatch (rounded square) — handy as a colour legend. */
    static Icon swatch(Color color) { return new SwatchIcon(color); }

    /** A small filled status dot (null colour → a hollow neutral ring). */
    static Icon dot(Color color) { return new DotIcon(color); }

    // ---- Verdict / method palettes (solid, for icons) -----------------------

    /** Solid colour for a run verdict (null when the verdict has no badge). */
    static Color verdictColor(String verdict) {
        if (verdict == null) return null;
        return switch (verdict) {
            case "POTENTIAL_BAC"   -> new Color(0xC8, 0x30, 0x30); // red
            case "LIKELY_ENFORCED" -> new Color(0x20, 0x90, 0x20); // green
            case "EXPECTED_OK"     -> new Color(0x88, 0x88, 0x88); // grey
            case "ANOMALY"         -> new Color(0xC8, 0x70, 0x00); // orange
            case "REVIEW"          -> new Color(0xB0, 0x9A, 0x00); // amber
            case "SKIPPED_SAFE"    -> new Color(0x70, 0x70, 0x70); // dim grey
            case "ERROR"           -> new Color(0x90, 0x30, 0x90); // purple
            default -> null;
        };
    }

    /** A verdict status dot, or null when the verdict has no badge. */
    static Icon verdictDot(String verdict) {
        Color c = verdictColor(verdict);
        return c != null ? new DotIcon(c) : null;
    }

    /** Danger colour for a state-changing HTTP method (null = neutral/safe). */
    static Color dangerColor(String method) {
        if (method == null) return null;
        return switch (method.toUpperCase()) {
            case "DELETE"       -> new Color(0xC8, 0x30, 0x30); // red
            case "PUT", "PATCH" -> new Color(0xC8, 0x70, 0x00); // orange
            case "POST"         -> new Color(0xC9, 0xB8, 0x3A); // yellow
            default             -> null;
        };
    }

    /** A danger badge for a method, or null for safe methods (GET/HEAD/OPTIONS). */
    static Icon dangerDot(String method) {
        Color c = dangerColor(method);
        return c != null ? new DotIcon(c) : null;
    }

    // ---- implementations ----------------------------------------------------

    private static final class AccountIcon implements Icon {
        private final Color color;
        AccountIcon(Color color) { this.color = color; }
        @Override public int getIconWidth()  { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 5, y + 2, 6, 6);          // head
            g2.fillArc(x + 2, y + 9, 12, 12, 0, 180); // shoulders (top half of an oval)
            g2.dispose();
        }
    }

    private static final class FolderIcon implements Icon {
        private final Color tint;
        FolderIcon(Color tint) { this.tint = tint; }
        @Override public int getIconWidth()  { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = tint != null ? tint : new Color(0xCB, 0xA1, 0x5A); // manila
            g2.setColor(base.darker());
            g2.fillRoundRect(x + 1, y + 3, 7, 4, 2, 2);   // tab
            g2.setColor(base);
            g2.fillRoundRect(x + 1, y + 5, 14, 9, 3, 3);  // body
            g2.setColor(base.darker());
            g2.drawRoundRect(x + 1, y + 5, 13, 8, 3, 3);  // outline
            g2.dispose();
        }
    }

    private static final class DotIcon implements Icon {
        private final Color color;
        DotIcon(Color color) { this.color = color; }
        @Override public int getIconWidth()  { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (color != null) {
                g2.setColor(color);
                g2.fillOval(x + 1, y + 2, 9, 9);
                g2.setColor(color.darker());
                g2.drawOval(x + 1, y + 2, 9, 9);
            } else {
                g2.setColor(UIManager.getColor("Label.disabledForeground"));
                g2.drawOval(x + 1, y + 2, 8, 8);
            }
            g2.dispose();
        }
    }

    private static final class SwatchIcon implements Icon {
        private final Color color;
        SwatchIcon(Color color) { this.color = color; }
        @Override public int getIconWidth()  { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (color != null) {
                g2.setColor(color);
                g2.fillRoundRect(x, y + 1, 11, 10, 3, 3);
            } else {
                g2.setColor(UIManager.getColor("Label.disabledForeground"));
                g2.drawRoundRect(x, y + 1, 10, 9, 3, 3);
            }
            g2.dispose();
        }
    }
}
