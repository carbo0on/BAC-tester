package ui;

import burp.api.montoya.MontoyaApi;
import db.RunRepository.ResultRecord;
import db.TestCaseRepository;
import engine.RunEngine;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Exports a self-contained HTML findings report from a set of run results.
 *
 * The report is meant to be handed off (bug-bounty / engagement write-up): it
 * lists each finding with its verdict, status, similarity and note, plus the
 * captured request and the replayed response (truncated for size). All dynamic
 * content is HTML-escaped.
 */
final class ReportExporter {

    private ReportExporter() {}

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final int BODY_CAP = 8 * 1024; // per request/response, in bytes

    /** Verdicts considered "interesting" enough to highlight at the top. */
    private static final Set<String> INTERESTING = Set.of(
        RunEngine.POTENTIAL_BAC, RunEngine.ANOMALY, RunEngine.REVIEW);

    static void export(Component parent, MontoyaApi api, TestCaseRepository tcRepo,
                       List<ResultRecord> results) {
        if (results == null || results.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No results to export.",
                "Export Report", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export findings report");
        chooser.setSelectedFile(new File("bac-findings-" + LocalDateTime.now().format(STAMP) + ".html"));
        chooser.setFileFilter(new FileNameExtensionFilter("HTML report (*.html)", "html"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File chosen = chooser.getSelectedFile();
        final File target = chosen.getName().toLowerCase().endsWith(".html")
            ? chosen : new File(chosen.getParentFile(), chosen.getName() + ".html");

        // Snapshot the results so the build runs safely off the EDT.
        final List<ResultRecord> snapshot = new ArrayList<>(results);
        Thread worker = new Thread(() -> {
            try {
                String html = build(snapshot, tcRepo);
                Files.write(target.toPath(), html.getBytes(StandardCharsets.UTF_8));
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent,
                    "Report saved:\n" + target.getAbsolutePath()
                        + "\n\n" + snapshot.size() + " findings exported.",
                    "Export Report", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Report export failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent,
                    "Export failed: " + ex.getMessage(), "Export Report", JOptionPane.ERROR_MESSAGE));
            }
        }, "bac-report-export");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- HTML building -------------------------------------------------

    private static String build(List<ResultRecord> results, TestCaseRepository tcRepo) {
        // Verdict counts for the summary.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ResultRecord r : results) counts.merge(r.verdict(), 1, Integer::sum);

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<title>BAC Time-Machine — Findings</title>")
          .append("<style>")
          .append("body{font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f7f9;color:#1c2230}")
          .append("header{background:#222a35;color:#fff;padding:18px 28px}")
          .append("header h1{margin:0;font-size:18px}header .sub{opacity:.7;font-size:12px}")
          .append("main{padding:22px 28px;max-width:1100px;margin:0 auto}")
          .append(".chips{margin:0 0 18px}.chip{display:inline-block;border-radius:12px;padding:3px 10px;margin:2px 6px 2px 0;font-size:12px;color:#fff}")
          .append(".f{background:#fff;border:1px solid #dfe3e8;border-radius:8px;margin:0 0 16px;overflow:hidden}")
          .append(".f>summary{cursor:pointer;padding:11px 14px;list-style:none;display:flex;align-items:center;gap:10px}")
          .append(".f>summary::-webkit-details-marker{display:none}")
          .append(".badge{font-weight:600;font-size:11px;padding:2px 8px;border-radius:10px;color:#fff;white-space:nowrap}")
          .append(".meta{color:#5a6472;font-size:12px}")
          .append(".body{padding:6px 14px 14px;border-top:1px solid #eef1f4}")
          .append(".kv{font-size:13px;margin:6px 0}.kv b{display:inline-block;min-width:120px;color:#41495a}")
          .append("pre{background:#0f1115;color:#d7dbe0;padding:12px;border-radius:6px;overflow:auto;font:12px/1.45 SFMono-Regular,Consolas,monospace;max-height:380px}")
          .append("h3{margin:14px 0 4px;font-size:13px;color:#41495a}")
          .append("</style></head><body>");

        sb.append("<header><h1>BAC Time-Machine — Findings Report</h1>")
          .append("<div class=\"sub\">Generated ")
          .append(esc(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
          .append(" · ").append(results.size()).append(" results</div></header><main>");

        // Summary chips.
        sb.append("<div class=\"chips\">");
        for (var e : counts.entrySet()) {
            sb.append("<span class=\"chip\" style=\"background:").append(hex(e.getKey())).append("\">")
              .append(esc(label(e.getKey()))).append(": ").append(e.getValue()).append("</span>");
        }
        sb.append("</div>");

        // Sort interesting verdicts first, then by similarity descending.
        List<ResultRecord> ordered = new ArrayList<>(results);
        ordered.sort(Comparator
            .comparing((ResultRecord r) -> INTERESTING.contains(r.verdict()) ? 0 : 1)
            .thenComparing(r -> -r.similarity()));

        for (ResultRecord r : ordered) {
            boolean open = INTERESTING.contains(r.verdict());
            sb.append("<details class=\"f\"").append(open ? " open" : "").append(">")
              .append("<summary>")
              .append("<span class=\"badge\" style=\"background:").append(hex(r.verdict())).append("\">")
              .append(esc(label(r.verdict()))).append("</span>")
              .append("<span><b>").append(esc(nz(r.method()))).append("</b> ")
              .append(esc(nz(r.host()))).append("</span>")
              .append("<span class=\"meta\">").append(esc(shortUrl(r.url()))).append("</span>")
              .append("</summary><div class=\"body\">");

            kv(sb, "Test case", nz(r.testCaseName()));
            kv(sb, "Account", nz(r.accountName()));
            kv(sb, "Expected access", nz(r.expectedAccess()));
            kv(sb, "Status", String.valueOf(r.newStatus()));
            kv(sb, "Response size", r.newLength() + " bytes");
            kv(sb, "Similarity", String.format("%.1f%%", r.similarity()));
            kv(sb, "Reviewed", r.reviewed() ? "yes" : "no");
            if (r.userNote() != null && !r.userNote().isBlank())
                kv(sb, "Note", r.userNote());

            // Captured request + replayed response (truncated, escaped).
            byte[] reqRaw = null;
            try { reqRaw = tcRepo.getRequestRaw(r.testCaseId()); } catch (Exception ignored) {}
            sb.append("<h3>Request</h3><pre>").append(esc(snippet(reqRaw))).append("</pre>");
            sb.append("<h3>Response</h3><pre>").append(esc(snippet(r.newResponseRaw()))).append("</pre>");

            sb.append("</div></details>");
        }

        sb.append("</main></body></html>");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("<div class=\"kv\"><b>").append(esc(k)).append("</b>").append(esc(v)).append("</div>");
    }

    // ---- Helpers -------------------------------------------------------

    private static String snippet(byte[] raw) {
        if (raw == null || raw.length == 0) return "(none)";
        int len = Math.min(raw.length, BODY_CAP);
        String s = new String(raw, 0, len, StandardCharsets.UTF_8);
        return raw.length > BODY_CAP ? s + "\n… [truncated, " + raw.length + " bytes total]" : s;
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 120 ? url.substring(0, 120) + "…" : url;
    }

    private static String label(String verdict) {
        if (verdict == null) return "—";
        return switch (verdict) {
            case RunEngine.POTENTIAL_BAC   -> "POTENTIAL BAC";
            case RunEngine.LIKELY_ENFORCED -> "LIKELY ENFORCED";
            case RunEngine.EXPECTED_OK     -> "EXPECTED OK";
            case RunEngine.ANOMALY         -> "ANOMALY";
            case RunEngine.REVIEW          -> "REVIEW";
            case RunEngine.SKIPPED_SAFE    -> "SKIPPED";
            case RunEngine.ERROR           -> "ERROR";
            default -> verdict;
        };
    }

    private static String hex(String verdict) {
        if (verdict == null) return "#777";
        return switch (verdict) {
            case RunEngine.POTENTIAL_BAC   -> "#c83030";
            case RunEngine.LIKELY_ENFORCED -> "#209020";
            case RunEngine.EXPECTED_OK     -> "#888888";
            case RunEngine.ANOMALY         -> "#c87000";
            case RunEngine.REVIEW          -> "#b09a00";
            case RunEngine.SKIPPED_SAFE    -> "#707070";
            case RunEngine.ERROR           -> "#903090";
            default -> "#777777";
        };
    }

    private static String nz(String s) { return s != null ? s : "—"; }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
