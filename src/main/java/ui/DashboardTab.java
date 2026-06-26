package ui;

import burp.api.montoya.MontoyaApi;
import db.RunRepository;
import db.TestCaseRepository;
import engine.RunEngine;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Project-wide triage dashboard: at-a-glance counts of every verdict across the
 * whole library, plus library size. Each card is clickable to jump to the
 * filtered results. Gives a bird's-eye sense of progress on a large target.
 */
public class DashboardTab extends JPanel {

    private final MontoyaApi api;
    private final RunRepository runRepo;
    private final TestCaseRepository tcRepo;

    private final JPanel cards = new JPanel(new GridLayout(0, 4, 10, 10));
    private final JLabel summary = new JLabel();
    private Consumer<String> onVerdictClicked; // fires with verdict key

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-dashboard");
        t.setDaemon(true);
        return t;
    });

    private static final String[] ORDER = {
        RunEngine.POTENTIAL_BAC, RunEngine.ANOMALY, RunEngine.REVIEW,
        RunEngine.LIKELY_ENFORCED, RunEngine.EXPECTED_OK,
        RunEngine.SKIPPED_SAFE, RunEngine.ERROR
    };

    public DashboardTab(MontoyaApi api, RunRepository runRepo, TestCaseRepository tcRepo) {
        super(new BorderLayout(0, 10));
        this.api = api;
        this.runRepo = runRepo;
        this.tcRepo = tcRepo;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel title = new JLabel("Triage Dashboard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

        JButton refresh = new JButton("↻ Refresh");
        refresh.addActionListener(e -> refresh());

        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        top.add(refresh, BorderLayout.EAST);

        cards.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        summary.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.add(top, BorderLayout.NORTH);
        north.add(summary, BorderLayout.SOUTH);

        add(north, BorderLayout.NORTH);

        // Keep cards pinned to the top instead of stretching to fill height.
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(cards, BorderLayout.NORTH);
        add(new JScrollPane(wrap), BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(this);
        refresh();
    }

    public void setOnVerdictClicked(Consumer<String> c) { this.onVerdictClicked = c; }

    public void refresh() {
        loader.submit(() -> {
            Map<String, Integer> counts;
            int libSize;
            try {
                counts = runRepo.getVerdictCounts();
                libSize = tcRepo.countAll();
            } catch (Exception e) {
                api.logging().logToError("[BAC] Dashboard load failed: " + e.getMessage());
                return;
            }
            SwingUtilities.invokeLater(() -> render(counts, libSize));
        });
    }

    private void render(Map<String, Integer> counts, int libSize) {
        cards.removeAll();
        int totalResults = counts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String v : ORDER) ordered.put(v, counts.getOrDefault(v, 0));

        for (var e : ordered.entrySet()) {
            cards.add(card(e.getKey(), e.getValue()));
        }

        int flagged = ordered.getOrDefault(RunEngine.POTENTIAL_BAC, 0)
            + ordered.getOrDefault(RunEngine.ANOMALY, 0)
            + ordered.getOrDefault(RunEngine.REVIEW, 0);
        summary.setText(libSize + " test cases · " + totalResults + " tested · "
            + flagged + " need attention (BAC / Anomaly / Review)");
        cards.revalidate();
        cards.repaint();
    }

    private JComponent card(String verdict, int count) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(VerdictStyle.solid(verdict).darker(), 1, true),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        p.setBackground(VerdictStyle.color(verdict));
        p.setOpaque(true);

        JLabel num = new JLabel(String.valueOf(count));
        num.setFont(num.getFont().deriveFont(Font.BOLD, 26f));
        JLabel lbl = new JLabel(VerdictStyle.label(verdict));
        lbl.setFont(lbl.getFont().deriveFont(11f));

        p.add(num, BorderLayout.CENTER);
        p.add(lbl, BorderLayout.SOUTH);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        p.setToolTipText("Open " + VerdictStyle.shortLabel(verdict) + " results");
        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                if (onVerdictClicked != null) onVerdictClicked.accept(verdict);
            }
        });
        return p;
    }
}
