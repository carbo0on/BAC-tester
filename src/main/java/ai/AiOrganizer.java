package ai;

import burp.api.montoya.MontoyaApi;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import db.AiCacheRepository;
import db.AiCacheRepository.CacheEntry;
import db.DatabaseManager;
import db.FolderRepository;
import db.TestCaseRepository;
import db.TestCaseRepository.TestCaseRow;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads a captured request/response with an LLM and files it into a tidy,
 * consistent library. Folders are {@code [host]/Category/Feature} where the
 * model picks {@code Category} from a small fixed taxonomy (see {@link #CATEGORIES})
 * and {@code Feature} is the deterministic first meaningful path segment. The
 * category is decided ONCE per function and reused (feature-key cache), so every
 * endpoint of one function (e.g. all of {@code /orders/*}) lands in the SAME
 * folder — one API call per function, not per endpoint, and no fragmentation.
 * An exact-endpoint cache means a repeated request costs nothing.
 *
 * <p>When AI is unavailable the request is still filed under a cleaned,
 * URL-derived fallback folder (that fallback is never cached, so it is retried
 * once AI is reachable again).
 *
 * <p>All work runs on a dedicated single background thread; the UI is refreshed
 * through {@code onChanged} after each test case is organized.
 */
public class AiOrganizer {

    private final MontoyaApi api;
    private final DatabaseManager db;
    private final TestCaseRepository tcRepo;
    private final FolderRepository folderRepo;
    private final AiCacheRepository cacheRepo;
    private final Runnable onChanged;

    /** Optional sink for short, user-visible status lines (set by the UI). */
    private volatile Consumer<String> onStatus;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-ai-organizer");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * Fixed functional taxonomy the model must classify each endpoint into. A
     * small, stable list (instead of mirroring the URL) keeps the tree shallow
     * and consistent: the same feature always lands in the same top folder.
     */
    private static final List<String> CATEGORIES = List.of(
            "Authentication", "Users & Profiles", "Admin", "Billing & Payments",
            "Files & Media", "Messaging & Notifications", "Search",
            "Settings & Config", "API Keys & Tokens", "Products & Catalog", "Misc");

    /** Generic API/versioning path prefixes that carry no functional meaning. */
    private static final Set<String> GENERIC_SEGMENTS = Set.of(
            "api", "rest", "graphql", "gql", "public", "internal", "external",
            "www", "app", "apps", "web", "services", "service", "ajax", "json", "rpc");

    private static boolean isVersionSegment(String s) {
        return s.matches("v\\d+") || s.matches("\\d+\\.\\d+");
    }

    public AiOrganizer(MontoyaApi api, DatabaseManager db, TestCaseRepository tcRepo,
                       FolderRepository folderRepo, AiCacheRepository cacheRepo,
                       Runnable onChanged) {
        this.api = api;
        this.db = db;
        this.tcRepo = tcRepo;
        this.folderRepo = folderRepo;
        this.cacheRepo = cacheRepo;
        this.onChanged = onChanged;
    }

    /** True when AI is enabled and an API key is present. */
    public boolean isReady() {
        return AiConfig.load(db).isConfigured();
    }

    /** Registers a sink for short status lines (e.g. the Library status bar). */
    public void setStatusListener(Consumer<String> listener) {
        this.onStatus = listener;
    }

    private void status(String msg) {
        Consumer<String> l = onStatus;
        if (l != null) l.accept(msg);
    }

    /** Stops the background worker; call on extension unload to avoid thread leaks. */
    public void shutdown() {
        worker.shutdownNow();
    }

    // ---- Entry points ----------------------------------------------------

    /**
     * Called after a quick-save / hotkey capture. Only organizes requests that
     * are still in the Inbox (folder_id == null) so explicit "Send to BAC…"
     * folder choices are respected. No-op unless AI + auto-organize are on.
     */
    public void organizeOnCapture(long tcId) {
        AiConfig cfg = AiConfig.load(db);
        if (!cfg.isConfigured() || !cfg.autoOrganize()) return;
        worker.submit(() -> {
            try {
                TestCaseRow row = tcRepo.getById(tcId).orElse(null);
                if (row == null || row.folderId() != null) return; // already filed
                organizeOne(cfg, row);
                if (onChanged != null) onChanged.run();
            } catch (Exception e) {
                String m = e.getMessage();
                api.logging().logToError("[BAC][AI] Auto-organize failed for #" + tcId + ": " + m);
                status("AI ✗ organize failed: " + (m != null ? m : e.getClass().getSimpleName()));
            }
        });
    }

    /**
     * Manual "Organize with AI" on a selection. Moves requests regardless of
     * their current folder. Reports a one-line summary through {@code onDone}.
     */
    public void organizeSelection(List<Long> ids, Consumer<String> onDone) {
        AiConfig cfg = AiConfig.load(db);
        if (!cfg.isConfigured()) {
            if (onDone != null) onDone.accept("AI is not configured. Enable it and set an API key in Settings.");
            return;
        }
        worker.submit(() -> {
            int ok = 0, fail = 0;
            String lastError = null;
            for (long id : ids) {
                try {
                    TestCaseRow row = tcRepo.getById(id).orElse(null);
                    if (row == null) { fail++; continue; }
                    organizeOne(cfg, row);
                    ok++;
                    if (onChanged != null) onChanged.run();
                } catch (Exception e) {
                    fail++;
                    lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    api.logging().logToError("[BAC][AI] Organize #" + id + ": " + lastError);
                }
            }
            if (onDone != null) {
                String summary = "AI organized " + ok + " request(s)";
                if (fail > 0) summary += ", " + fail + " failed — " + lastError;
                else summary += ".";
                onDone.accept(summary);
            }
        });
    }

    // ---- Core ------------------------------------------------------------

    private void organizeOne(AiConfig cfg, TestCaseRow row) throws Exception {
        String signature  = signature(row.method(), row.host(), row.url());
        String featureKey = featureKey(row.host(), row.url());

        // 1) Exact endpoint already classified → reuse its folder + name + notes
        //    with NO API call. (Repeats of the same request cost nothing.)
        CacheEntry exact = cacheRepo.lookup(signature).orElse(null);
        if (exact != null && exact.name() != null && !exact.name().isBlank()) {
            Long folderId = pathToFolder(exact.folderPath());
            apply(row.id(), exact.name(), exact.description(), folderId);
            api.logging().logToOutput("[BAC][AI] Grouped #" + row.id() + " → "
                    + exact.folderPath() + " (cached endpoint, no API call).");
            status("AI ✓ grouped → " + exact.folderPath() + " (cached)");
            return;
        }

        // 2) A DIFFERENT endpoint of the SAME function was already classified →
        //    reuse its folder (same Category + Feature) with NO API call, so all
        //    of e.g. /orders, /orders/{id}, /orders/{id}/cancel land together.
        CacheEntry feat = cacheRepo.lookupByFeatureKey(featureKey).orElse(null);
        if (feat != null && feat.folderPath() != null && !feat.folderPath().isBlank()) {
            String folderPath = feat.folderPath();
            Long folderId = pathToFolder(folderPath);
            String name = fallbackName(row);   // deterministic; grouping is the priority
            apply(row.id(), name, null, folderId);
            cacheRepo.put(signature, featureKey, folderId, folderPath, name, null);
            api.logging().logToOutput("[BAC][AI] Grouped #" + row.id() + " → " + folderPath
                    + " (same function, no API call).");
            status("AI ✓ grouped → " + folderPath + " (function)");
            return;
        }

        // 3) First endpoint of a NEW function → ask the model once for the
        //    functional category (+ a name). Build a stable, shallow folder
        //    [host]/Category/Feature where Feature is the deterministic first
        //    meaningful path segment, so later siblings reuse it via step 2.
        //    On failure we file under a cleaned URL fallback and DON'T cache it,
        //    so the classification is retried once AI is reachable again.
        Parsed p = null;
        if (cfg.isConfigured()) {
            try {
                p = describe(cfg, row);
            } catch (Exception e) {
                api.logging().logToError("[BAC][AI] Classify call failed for #" + row.id()
                        + " (filed by URL anyway): " + e.getMessage());
            }
        }

        if (p != null && p.category() != null && !p.category().isBlank()) {
            String category = normalizeCategory(p.category());
            String feature  = featureSegment(row.url());
            List<String> parts = new ArrayList<>();
            if (cfg.folderByHost()) { String h = hostSegment(row.host()); if (h != null) parts.add(h); }
            parts.add(category);
            if (feature != null) parts.add(feature);
            String folderPath = String.join("/", parts);
            Long folderId = folderRepo.findOrCreatePathCanonical(folderPath);

            String aiName = sanitizeName(p.name());
            String name = (aiName != null && !aiName.isBlank())
                    ? nameWithMethod(row.method(), aiName) : fallbackName(row);
            String desc = sanitizeDescription(p.description());
            apply(row.id(), name, desc, folderId);
            cacheRepo.put(signature, featureKey, folderId, folderPath, name, desc);
            api.logging().logToOutput("[BAC][AI] Classified #" + row.id() + " → " + folderPath
                    + " as \"" + name + "\".");
            status("AI ✓ " + name + " → " + folderPath);
        } else {
            String folderPath = fallbackFolderPath(cfg, row);
            Long folderId = folderPath == null ? null
                    : folderRepo.findOrCreatePathCanonical(folderPath);
            String name = fallbackName(row);
            apply(row.id(), name, null, folderId);
            api.logging().logToOutput("[BAC][AI] Filed #" + row.id() + " → " + folderPath
                    + " as \"" + name + "\" (no AI classification; will retry next time).");
            status("AI ⚠ filed → " + folderPath + " (no AI)");
        }
    }

    /** Resolves a folder path to an id (creating as needed); null/blank → Inbox. */
    private Long pathToFolder(String path) throws java.sql.SQLException {
        return (path == null || path.isBlank()) ? null : folderRepo.findOrCreatePathCanonical(path);
    }

    /** Asks the model for a functional category + action name + description. */
    private Parsed describe(AiConfig cfg, TestCaseRow row) throws Exception {
        String reqText  = decode(tcRepo.getRequestRaw(row.id()));
        String respText = decode(tcRepo.getPrimaryBaselineResponse(row.id()));

        String system = """
            You classify a captured HTTP request for a security tester so it can be
            filed into a tidy, consistent library. Reply with ONLY a JSON object (no
            markdown), with exactly these keys:
              "category": pick EXACTLY ONE of: %s,
              "name": a very short ACTION name for THIS request as verb + object
                      (max 5 words, DO NOT include the HTTP method),
              "description": what the request does and its purpose (max 14 words).
            Be concise and consistent: pick the category by the request's business
            FUNCTION so related endpoints share it.""".formatted(String.join(", ", CATEGORIES));

        StringBuilder user = new StringBuilder();
        user.append("Endpoint: ").append(row.method()).append(' ')
            .append(pathOnly(row.url())).append('\n');
        int reqBudget  = Math.max(200, (int) (cfg.maxChars() * 0.45));
        int respBudget = Math.max(200, cfg.maxChars() - reqBudget);
        user.append("\n--- REQUEST ---\n").append(truncate(reqText, reqBudget));
        user.append("\n--- RESPONSE ---\n").append(truncate(respText, respBudget));

        String raw = new AiClient(cfg).complete(system, user.toString());
        Parsed p = parse(raw);
        if (p == null) {
            api.logging().logToError("[BAC][AI] Could not parse model reply for #" + row.id()
                    + ": " + snippet(raw));
        }
        return p;
    }

    /** Matches the model's free-text category to the fixed taxonomy; "Misc" if unknown. */
    private static String normalizeCategory(String c) {
        if (c == null || c.isBlank()) return "Misc";
        String t = c.trim();
        for (String cat : CATEGORIES) if (cat.equalsIgnoreCase(t)) return cat;
        String key = t.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String cat : CATEGORIES) {
            if (cat.toLowerCase().replaceAll("[^a-z0-9]", "").equals(key)) return cat;
        }
        // Loose keyword match on the category's first word (e.g. "user" → Users & Profiles).
        for (String cat : CATEGORIES) {
            String first = cat.toLowerCase().split("[ &]")[0];
            if (first.length() >= 4 && key.contains(first)) return cat;
        }
        return "Misc";
    }

    /** Consistent display name: "METHOD — action", avoiding a doubled method prefix. */
    private static String nameWithMethod(String method, String base) {
        String m = method != null ? method.toUpperCase() : "REQ";
        String b = base == null || base.isBlank() ? "request" : base.trim();
        String bu = b.toUpperCase();
        if (bu.startsWith(m + " ") || bu.startsWith(m + "—") || bu.startsWith(m + " —")) return b;
        return m + " — " + b;
    }

    /** Cleaned, shallow (depth ≤ 2) URL-derived folder used when AI is unavailable. */
    private String fallbackFolderPath(AiConfig cfg, TestCaseRow row) {
        List<String> parts = new ArrayList<>();
        if (cfg.folderByHost()) { String h = hostSegment(row.host()); if (h != null) parts.add(h); }
        parts.addAll(meaningfulSegments(row.url(), 2));
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    /** Readable name from method + last meaningful path segment (no API needed). */
    private static String fallbackName(TestCaseRow row) {
        List<String> segs = meaningfulSegments(row.url(), Integer.MAX_VALUE);
        String last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
        return nameWithMethod(row.method(), last);
    }

    /**
     * Coarse function key: host + the first meaningful path segment. Every
     * endpoint under the same base area (e.g. all of {@code /orders/*}) shares
     * this key, so they reuse one folder + the category already chosen for that
     * function — no extra API calls and no per-endpoint fragmentation.
     */
    static String featureKey(String host, String url) {
        String seg = featureSegment(url);
        return (host == null ? "" : host.toLowerCase()) + "|"
                + (seg != null ? seg.toLowerCase() : "/");
    }

    /** First meaningful path segment (the "function" folder), or null. */
    private static String featureSegment(String url) {
        List<String> segs = meaningfulSegments(url, 1);
        return segs.isEmpty() ? null : segs.get(0);
    }

    /** Meaningful path segments (generic prefixes, versions and ids dropped), capped. */
    private static List<String> meaningfulSegments(String url, int max) {
        String path;
        try {
            path = new URI(url).getPath();
        } catch (Exception e) {
            int slash = url.indexOf('/', url.indexOf("//") + 2);
            path = slash >= 0 ? url.substring(slash) : "/";
        }
        List<String> segs = new ArrayList<>();
        if (path == null) return segs;
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || isIdLike(seg)) continue;
            String low = seg.toLowerCase();
            if (GENERIC_SEGMENTS.contains(low) || isVersionSegment(low)) continue;
            String s = sanitizeSegment(seg);
            if (s == null) continue;
            segs.add(s);
            if (segs.size() >= max) break;
        }
        return segs;
    }

    /** Sanitizes a string into a safe folder segment; null if nothing meaningful remains. */
    private static String sanitizeSegment(String s) {
        if (s == null) return null;
        String x = s.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (x.length() > 40) x = x.substring(0, 40).trim();
        return x.isEmpty() ? null : x;
    }

    /** Host as a folder segment (illegal chars stripped, dots kept). */
    private static String hostSegment(String host) {
        if (host == null || host.isBlank()) return null;
        return sanitizeSegment(host.toLowerCase());
    }

    private void apply(long tcId, String name, String description, Long folderId) throws Exception {
        if (name != null && !name.isBlank()) tcRepo.rename(tcId, name);
        if (description != null && !description.isBlank()) {
            String existing = tcRepo.getNotes(tcId);
            // Don't clobber a user's existing notes; prepend the AI summary once.
            if (existing == null || existing.isBlank()) {
                tcRepo.setNotes(tcId, description);
            } else if (!existing.contains(description)) {
                tcRepo.setNotes(tcId, description + "\n" + existing);
            }
        }
        if (folderId != null) tcRepo.moveToFolder(tcId, folderId);
    }

    // ---- Helpers ---------------------------------------------------------

    /** Normalized signature so /users/12 and /users/98 group as one endpoint. */
    static String signature(String method, String host, String url) {
        String path;
        try {
            URI uri = new URI(url);
            path = uri.getPath();
        } catch (Exception e) {
            int slash = url.indexOf('/', url.indexOf("//") + 2);
            path = slash >= 0 ? url.substring(slash) : url;
        }
        if (path == null || path.isEmpty()) path = "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(isIdLike(seg) ? "{id}" : seg.toLowerCase());
        }
        String templated = sb.length() == 0 ? "/" : sb.toString();
        return (method == null ? "" : method.toUpperCase()) + " "
                + (host == null ? "" : host.toLowerCase()) + " " + templated;
    }

    /** Path (+ query) of a URL, without scheme/host — keeps the prompt host-free. */
    private static String pathOnly(String url) {
        try {
            URI u = new URI(url);
            String p = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
            return u.getQuery() != null ? p + "?" + u.getQuery() : p;
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean isIdLike(String seg) {
        if (DIGITS.matcher(seg).matches()) return true;
        if (UUID.matcher(seg).matches()) return true;
        // long mixed token (e.g. hashes / opaque ids)
        if (seg.length() >= 12) {
            int digits = 0;
            for (char c : seg.toCharArray()) if (Character.isDigit(c)) digits++;
            if (digits >= 2) return true;
        }
        return false;
    }

    private static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…[truncated]";
    }

    private static String snippet(String s) {
        if (s == null) return "null";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    // ---- Parsing / sanitizing -------------------------------------------

    private record Parsed(String name, String description, String category) {}

    private static Parsed parse(String raw) {
        if (raw == null) return null;
        String json = extractJson(raw);
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = str(o, "name");
            String desc = str(o, "description");
            String category = str(o, "category");
            if (name == null && desc == null && category == null) return null;
            return new Parsed(name, desc, category);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Extracts the first balanced {...} object from a possibly fenced reply. */
    static String extractJson(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String sanitizeName(String name) {
        if (name == null) return null;
        String s = name.replaceAll("\\s+", " ").trim();
        if (s.length() > 70) s = s.substring(0, 70).trim();
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeDescription(String desc) {
        if (desc == null) return null;
        String s = desc.replaceAll("\\s+", " ").trim();
        if (s.length() > 160) s = s.substring(0, 160).trim();
        return s.isEmpty() ? null : s;
    }

}
