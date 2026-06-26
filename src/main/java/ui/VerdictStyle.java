package ui;

import engine.RunEngine;

import java.awt.Color;

/**
 * Shared verdict palette and labels so every surface (Overview Matrix, Library
 * badge column, Dashboard, Live mode) renders triage verdicts identically.
 */
public final class VerdictStyle {

    private VerdictStyle() {}

    // Pastel backgrounds (low alpha so they sit calmly on any theme).
    public static final Color POTENTIAL_BAC_BG   = new Color(255, 80,  80,  65);
    public static final Color LIKELY_ENFORCED_BG = new Color(60,  200, 60,  55);
    public static final Color EXPECTED_OK_BG     = new Color(150, 150, 150, 40);
    public static final Color ANOMALY_BG         = new Color(255, 155, 40,  60);
    public static final Color REVIEW_BG          = new Color(245, 215, 40,  60);
    public static final Color SKIPPED_BG         = new Color(130, 130, 130, 35);
    public static final Color ERROR_BG           = new Color(200, 60,  200, 55);

    public static Color color(String v) {
        if (v == null) return null;
        return switch (v) {
            case RunEngine.POTENTIAL_BAC   -> POTENTIAL_BAC_BG;
            case RunEngine.LIKELY_ENFORCED -> LIKELY_ENFORCED_BG;
            case RunEngine.EXPECTED_OK     -> EXPECTED_OK_BG;
            case RunEngine.ANOMALY         -> ANOMALY_BG;
            case RunEngine.REVIEW          -> REVIEW_BG;
            case RunEngine.SKIPPED_SAFE    -> SKIPPED_BG;
            case RunEngine.ERROR           -> ERROR_BG;
            default -> null;
        };
    }

    /** A fully-opaque variant for swatches/dots where translucency reads as washed-out. */
    public static Color solid(String v) {
        Color c = color(v);
        return c == null ? new Color(120, 120, 120) : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    public static String label(String v) {
        if (v == null) return "—";
        return switch (v) {
            case RunEngine.POTENTIAL_BAC   -> "🚩 POTENTIAL_BAC";
            case RunEngine.LIKELY_ENFORCED -> "✅ LIKELY_ENFORCED";
            case RunEngine.EXPECTED_OK     -> "⚪ EXPECTED_OK";
            case RunEngine.ANOMALY         -> "⚠ ANOMALY";
            case RunEngine.REVIEW          -> "🔍 REVIEW";
            case RunEngine.SKIPPED_SAFE    -> "— SKIPPED";
            case RunEngine.ERROR           -> "✗ ERROR";
            default -> v;
        };
    }

    /** Short label (no emoji) for tight table cells. */
    public static String shortLabel(String v) {
        if (v == null) return "—";
        return switch (v) {
            case RunEngine.POTENTIAL_BAC   -> "BAC?";
            case RunEngine.LIKELY_ENFORCED -> "Enforced";
            case RunEngine.EXPECTED_OK     -> "OK";
            case RunEngine.ANOMALY         -> "Anomaly";
            case RunEngine.REVIEW          -> "Review";
            case RunEngine.SKIPPED_SAFE    -> "Skipped";
            case RunEngine.ERROR           -> "Error";
            default -> v;
        };
    }
}
