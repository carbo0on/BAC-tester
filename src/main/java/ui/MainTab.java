package ui;

import burp.api.montoya.MontoyaApi;
import db.DatabaseManager;

import javax.swing.*;
import java.awt.*;

/**
 * Root Burp suite tab — "BAC Time-Machine".
 * Phase 1: placeholder panel confirming the extension and database are live.
 * Phases 2-7 will replace the center content with the full sub-tab UI
 * (Library / Accounts / Test Run / Compare / Settings).
 *
 * Registered via api.userInterface().registerSuiteTab(caption(), uiComponent())
 * because Montoya 2026.4 takes (String, Component) directly — no SuiteTab interface.
 */
public class MainTab {

    private final MontoyaApi api;
    private final DatabaseManager dbManager;
    private final JPanel root;

    public MainTab(MontoyaApi api, DatabaseManager dbManager) {
        this.api = api;
        this.dbManager = dbManager;
        this.root = buildPlaceholder();
    }

    private JPanel buildPlaceholder() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(32, 48, 32, 48));

        JLabel title = new JLabel("BAC Time-Machine");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Broken Access Control Testing Extension");
        subtitle.setFont(subtitle.getFont().deriveFont(13f));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(300, 2));
        sep.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel dbStatus = new JLabel("✓  Database initialised");
        dbStatus.setFont(dbStatus.getFont().deriveFont(Font.PLAIN, 12f));
        dbStatus.setForeground(new Color(0x2e7d32));
        dbStatus.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel phase = new JLabel("Phase 1 complete — UI implementation in progress");
        phase.setFont(phase.getFont().deriveFont(Font.ITALIC, 11f));
        phase.setAlignmentX(Component.CENTER_ALIGNMENT);

        card.add(title);
        card.add(Box.createVerticalStrut(6));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(16));
        card.add(sep);
        card.add(Box.createVerticalStrut(16));
        card.add(dbStatus);
        card.add(Box.createVerticalStrut(8));
        card.add(phase);

        panel.add(card);

        api.userInterface().applyThemeToComponent(panel);
        api.userInterface().applyThemeToComponent(card);
        return panel;
    }

    public String caption() {
        return "BAC Time-Machine";
    }

    public Component uiComponent() {
        return root;
    }
}
