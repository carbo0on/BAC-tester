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
 * Reads a captured request/response with an LLM and files it intelligently:
 * a concise name, a one-line description (stored as notes) and a nested
 * "function" folder. Similar endpoints are grouped via a signature cache so a
 * given endpoint is classified at most once — keeping API usage low.
 *
 * All work runs on a dedicated single background thread; the UI is refreshed
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
                organizeOne(cfg, row, false);
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
                    organizeOne(cfg, row, true);
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

    private void organizeOne(AiConfig cfg, TestCaseRow row, boolean overrideFolder) throws Exception {
        String signature  = signature(row.method(), row.host(), row.url());
        String featureKey = featureKey(row.host(), row.url());

        // ── Folder = the URL path itself (deterministic). ──────────────────
        // Folders mirror the request's URL path segments (ids dropped); the same
        // area therefore ALWAYS resolves to the same folder, and findOrCreatePath-
        // Canonical reuses an existing folder instead of making a new one. This is
        // what guarantees requests of the same endpoint group together.
        String folderPath = urlFolderPath(row.url());
        Long folderId = folderPath == null ? null
                : folderRepo.findOrCreatePathCanonical(folderPath);

        // ── Name + description. ────────────────────────────────────────────
        // Reuse a cached name/description for this exact endpoint (no API call).
        // Otherwise ask the model once (when configured); if that fails or AI is
        // off, fall back to a readable name derived from the URL so the request
        // is still placed and named.
        CacheEntry exact = cacheRepo.lookup(signature).orElse(null);
        String name, desc;
        if (exact != null && exact.name() != null && !exact.name().isBlank()) {
            name = exact.name();
            desc = exact.description();
            apply(row.id(), name, desc, folderId);
            cacheRepo.put(signature, featureKey, folderId,
                    folderPath != null ? folderPath : "", name, desc);
            api.logging().logToOutput("[BAC][AI] Grouped #" + row.id() + " → "
                    + folderPath + " (cached name, no API call).");
            status("AI ✓ grouped → " + folderPath + " (cached)");
            return;
        }

        Parsed p = null;
        if (cfg.isConfigured()) {
            try {
                p = describe(cfg, row);
            } catch (Exception e) {
                api.logging().logToError("[BAC][AI] Naming call failed for #" + row.id()
                        + " (filed by URL anyway): " + e.getMessage());
            }
        }
        if (p != null && p.name() != null && !p.name().isBlank()) {
            name = sanitizeName(p.name());
            desc = sanitizeDescription(p.description());
        } else {
            name = fallbackName(row);
            desc = null;
        }

        apply(row.id(), name, desc, folderId);
        cacheRepo.put(signature, featureKey, folderId,
                folderPath != null ? folderPath : "", name, desc);
        api.logging().logToOutput("[BAC][AI] Organized #" + row.id() + " → " + folderPath
                + " as \"" + name + "\".");
        status("AI ✓ " + name + " → " + folderPath);
    }

    /** Asks the model for a concise name + description only (folder is URL-derived). */
    private Parsed describe(AiConfig cfg, TestCaseRow row) throws Exception {
        String reqText  = decode(tcRepo.getRequestRaw(row.id()));
        String respText = decode(tcRepo.getPrimaryBaselineResponse(row.id()));

        String system = """
            You label a captured HTTP request for a security tester.
            Reply with ONLY a JSON object (no markdown), with exactly these keys:
              "name": a very short human-readable name for THIS request (max 6 words),
              "description": what the request does and its purpose (max 14 words).
            Be concise and consistent.""";

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

    /** Readable name from method + last meaningful path segment (no API needed). */
    private static String fallbackName(TestCaseRow row) {
        String last = null;
        try {
            String path = new URI(row.url()).getPath();
            if (path != null) {
                for (String seg : path.split("/")) {
                    if (seg.isEmpty() || isIdLike(seg)) continue;
                    last = seg;
                }
            }
        } catch (Exception ignored) {}
        String method = row.method() != null ? row.method().toUpperCase() : "REQ";
        return last != null ? method + " " + last : method + " request";
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

    /** Generic API/versioning path prefixes that carry no functional meaning. */
    private static final Set<String> GENERIC_SEGMENTS = Set.of(
            "api", "rest", "graphql", "gql", "public", "internal", "external",
            "www", "app", "apps", "web", "services", "service", "ajax", "json", "rpc");

    private static boolean isVersionSegment(String s) {
        return s.matches("v\\d+") || s.matches("\\d+\\.\\d+");
    }

    /**
     * Coarse feature key: host + the first <em>meaningful</em> path segment
     * (skipping generic prefixes like "api"/"v1" and id-like segments). All
     * endpoints under the same base area (e.g. every /api/users/* call) share
     * this key, so they reuse one function folder with no extra API calls.
     */
    static String featureKey(String host, String url) {
        String path;
        try {
            path = new URI(url).getPath();
        } catch (Exception e) {
            int slash = url.indexOf('/', url.indexOf("//") + 2);
            path = slash >= 0 ? url.substring(slash) : "/";
        }
        if (path == null) path = "/";
        String feature = null;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            String low = seg.toLowerCase();
            if (GENERIC_SEGMENTS.contains(low) || isVersionSegment(low) || isIdLike(seg)) continue;
            feature = low;
            break;
        }
        String h = host == null ? "" : host.toLowerCase();
        return h + "|" + (feature != null ? feature : "/");
    }

    /**
     * Builds a folder path that mirrors the URL's path segments, with resource
     * ids dropped (e.g. {@code /api/users/123/posts → "api/users/posts"}).
     * Returns {@code null} when there is no meaningful segment, so such requests
     * stay in the Inbox instead of creating junk folders.
     *
     * <p>This is the deterministic grouping the user asked for: the same URL area
     * always maps to the same path, and {@code findOrCreatePathCanonical} reuses
     * an existing folder rather than spawning a new one per request.
     */
    static String urlFolderPath(String url) {
        String path;
        try {
            path = new URI(url).getPath();
        } catch (Exception e) {
            int slash = url.indexOf('/', url.indexOf("//") + 2);
            path = slash >= 0 ? url.substring(slash) : "/";
        }
        if (path == null) return null;
        List<String> segs = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || isIdLike(seg)) continue;     // drop empties + resource ids
            String s = seg.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) continue;
            if (s.length() > 40) s = s.substring(0, 40).trim();
            segs.add(s);
            if (segs.size() >= 6) break;                       // safety cap on depth
        }
        return segs.isEmpty() ? null : String.join("/", segs);
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

    private record Parsed(String name, String description, String folder) {}

    private static Parsed parse(String raw) {
        if (raw == null) return null;
        String json = extractJson(raw);
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = str(o, "name");
            String desc = str(o, "description");
            String folder = str(o, "folder");
            if (name == null && desc == null && folder == null) return null;
            return new Parsed(name, desc, folder);
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
