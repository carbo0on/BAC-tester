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
     * their current folder. Unlike {@link #organizeOnCapture}, this classifies
     * the WHOLE selection together in as few AI calls as possible (one call per
     * chunk of up to {@value #BATCH_SIZE} items, not one call per request) so the
     * model can see every item at once and group functionally related requests
     * into the SAME folder even when their URL paths differ. Reports a one-line
     * summary through {@code onDone}.
     */
    public void organizeSelection(List<Long> ids, Consumer<String> onDone) {
        AiConfig cfg = AiConfig.load(db);
        if (!cfg.isConfigured()) {
            if (onDone != null) onDone.accept("AI is not configured. Enable it and set an API key in Settings.");
            return;
        }
        worker.submit(() -> organizeBatch(cfg, ids, onDone));
    }

    private static final int BATCH_SIZE = 15;

    private void organizeBatch(AiConfig cfg, List<Long> ids, Consumer<String> onDone) {
        int cached = 0, classified = 0, fail = 0;
        String lastError = null;
        List<TestCaseRow> pending = new ArrayList<>();

        // Pass 1: anything already known for this exact endpoint costs nothing.
        for (long id : ids) {
            try {
                TestCaseRow row = tcRepo.getById(id).orElse(null);
                if (row == null) { fail++; continue; }
                if (applyCachedIfPresent(row)) {
                    cached++;
                    if (onChanged != null) onChanged.run();
                } else {
                    pending.add(row);
                }
            } catch (Exception e) {
                fail++;
                lastError = msg(e);
                api.logging().logToError("[BAC][AI] Cache lookup #" + id + ": " + lastError);
            }
        }

        // Pass 2: classify the rest together, in chunks, so related items land
        // in one shared folder instead of each spawning its own.
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            List<TestCaseRow> chunk = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
            status("AI grouping " + chunk.size() + " request(s)…");
            try {
                classified += classifyChunk(cfg, chunk);
                if (onChanged != null) onChanged.run();
            } catch (Exception e) {
                lastError = msg(e);
                api.logging().logToError("[BAC][AI] Batch classify failed (" + chunk.size()
                        + " items): " + lastError);
                // Don't leave the selection untouched — file each item by URL alone.
                for (TestCaseRow row : chunk) {
                    try { applyFallback(row); classified++; }
                    catch (Exception inner) { fail++; }
                }
                if (onChanged != null) onChanged.run();
            }
        }

        if (onDone != null) {
            StringBuilder summary = new StringBuilder("AI organized " + (cached + classified) + " request(s)");
            if (cached > 0) summary.append(" (").append(cached).append(" from cache)");
            if (fail > 0) summary.append(", ").append(fail).append(" failed — ").append(lastError);
            else summary.append('.');
            onDone.accept(summary.toString());
        }
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---- Core ------------------------------------------------------------

    private void organizeOne(AiConfig cfg, TestCaseRow row, boolean overrideFolder) throws Exception {
        if (applyCachedIfPresent(row)) return;

        String signature  = signature(row.method(), row.host(), row.url());
        String featureKey = featureKey(row.host(), row.url());
        String folderPath = resolveFolderPath(featureKey, row.url());
        Long folderId = folderPath == null ? null
                : folderRepo.findOrCreatePathCanonical(folderPath);

        Parsed p = null;
        if (cfg.isConfigured()) {
            try {
                p = describe(cfg, row);
            } catch (Exception e) {
                api.logging().logToError("[BAC][AI] Naming call failed for #" + row.id()
                        + " (filed by URL anyway): " + e.getMessage());
            }
        }
        String name, desc;
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

    /**
     * Applies a previously cached classification for this exact endpoint
     * signature, if one exists, at zero API cost. Returns {@code false} when
     * nothing is cached yet so the caller knows it still needs classifying.
     */
    private boolean applyCachedIfPresent(TestCaseRow row) throws Exception {
        String signature = signature(row.method(), row.host(), row.url());
        CacheEntry exact = cacheRepo.lookup(signature).orElse(null);
        if (exact == null || exact.name() == null || exact.name().isBlank()) return false;

        // Honor the folder chosen the last time this endpoint was classified
        // (which may be a semantic batch grouping) instead of recomputing it
        // from the URL — otherwise a smarter folder choice would never stick.
        Long folderId = exact.folderId();
        String folderPath = exact.folderPath();
        if (folderId == null) {
            folderPath = resolveFolderPath(featureKey(row.host(), row.url()), row.url());
            folderId = folderPath == null ? null : folderRepo.findOrCreatePathCanonical(folderPath);
        }
        apply(row.id(), exact.name(), exact.description(), folderId);
        api.logging().logToOutput("[BAC][AI] Grouped #" + row.id() + " → "
                + folderPath + " (cached, no API call).");
        status("AI ✓ grouped → " + folderPath + " (cached)");
        return true;
    }

    /**
     * Folder for a request that has no exact-signature cache entry yet. Reuses
     * the folder already chosen for any other endpoint sharing the same coarse
     * {@code featureKey} (zero-cost grouping across resource ids within one
     * area), falling back to the deterministic URL-mirrored path otherwise.
     */
    private String resolveFolderPath(String featureKey, String url) throws Exception {
        CacheEntry byFeature = cacheRepo.lookupByFeatureKey(featureKey).orElse(null);
        if (byFeature != null && byFeature.folderId() != null && byFeature.folderPath() != null) {
            return byFeature.folderPath();
        }
        return urlFolderPath(url);
    }

    /** Deterministic, AI-free placement — used when a batch classification call fails. */
    private void applyFallback(TestCaseRow row) throws Exception {
        String signature  = signature(row.method(), row.host(), row.url());
        String featureKey = featureKey(row.host(), row.url());
        String folderPath = resolveFolderPath(featureKey, row.url());
        Long folderId = folderPath == null ? null : folderRepo.findOrCreatePathCanonical(folderPath);
        String name = fallbackName(row);
        apply(row.id(), name, null, folderId);
        cacheRepo.put(signature, featureKey, folderId, folderPath != null ? folderPath : "", name, null);
    }

    /**
     * Classifies a whole chunk of requests in ONE model call so functionally
     * related items (e.g. several different "settings" endpoints) land in the
     * same folder instead of each spawning its own. Returns the number of items
     * successfully applied; throws when the call/parse fails entirely so the
     * caller can fall back per-item.
     */
    private int classifyChunk(AiConfig cfg, List<TestCaseRow> chunk) throws Exception {
        List<String> existingFolders = folderRepo.getAllFolderPaths();

        String system = """
            You organize a batch of captured HTTP requests for a security tester
            into a folder tree, grouped by FUNCTIONAL AREA — not by literal URL
            path. Requests that serve the same feature/page/purpose MUST share the
            SAME folder even when their URL paths differ (e.g. "/api/profile" and
            "/user/preferences" can both belong in "Settings"). Prefer reusing one
            of the EXISTING FOLDERS listed below when an item clearly belongs
            there. Otherwise invent a short folder path (use "/" to nest, max 3
            levels, e.g. "Checkout/Payment"). Keep distinct, unrelated items in
            different folders — don't over-merge. Reply with ONLY a JSON array (no
            markdown), one object per item, in the SAME ORDER given, each with
            exactly these keys:
              "idx": the item's number as given (integer),
              "folder": the folder path for this item,
              "name": a very short human-readable name for THIS request (max 6 words),
              "description": what the request does and its purpose (max 14 words).""";

        int perItemBudget = Math.max(80, cfg.maxChars() / Math.max(1, chunk.size()));
        int reqBudget  = Math.max(30, (int) (perItemBudget * 0.4));
        int respBudget = Math.max(40, perItemBudget - reqBudget);

        StringBuilder user = new StringBuilder();
        if (!existingFolders.isEmpty()) {
            user.append("EXISTING FOLDERS:\n");
            for (String f : existingFolders) user.append("- ").append(f).append('\n');
            user.append('\n');
        }
        user.append("ITEMS:\n");
        for (int i = 0; i < chunk.size(); i++) {
            TestCaseRow row = chunk.get(i);
            String reqText  = squeeze(decode(tcRepo.getRequestRaw(row.id())));
            String respText = squeeze(decode(tcRepo.getPrimaryBaselineResponse(row.id())));
            user.append('[').append(i + 1).append("] ").append(row.method()).append(' ')
                .append(pathOnly(row.url())).append('\n');
            user.append("  req: ").append(truncate(reqText, reqBudget)).append('\n');
            user.append("  resp: ").append(truncate(respText, respBudget)).append('\n');
        }

        int maxOutputTokens = Math.min(4096, Math.max(256, chunk.size() * 100 + 200));
        String raw = new AiClient(cfg).complete(system, user.toString(), maxOutputTokens);
        List<ChunkItem> items = parseChunk(raw);
        if (items == null || items.isEmpty()) {
            throw new Exception("Could not parse model reply: " + snippet(raw));
        }

        int applied = 0;
        for (ChunkItem item : items) {
            if (item.idx < 1 || item.idx > chunk.size()) continue;
            TestCaseRow row = chunk.get(item.idx - 1);
            String name = sanitizeName(item.name);
            if (name == null || name.isBlank()) name = fallbackName(row);
            String desc = sanitizeDescription(item.description);
            String folderPath = item.folder != null ? item.folder.trim() : null;
            if (folderPath != null && folderPath.isEmpty()) folderPath = null;
            Long folderId = folderPath == null ? null : folderRepo.findOrCreatePathCanonical(folderPath);

            apply(row.id(), name, desc, folderId);
            String signature  = signature(row.method(), row.host(), row.url());
            String featureKey = featureKey(row.host(), row.url());
            cacheRepo.put(signature, featureKey, folderId, folderPath != null ? folderPath : "", name, desc);
            applied++;
        }
        api.logging().logToOutput("[BAC][AI] Batch-grouped " + applied + "/" + chunk.size()
                + " request(s) in one call.");
        status("AI ✓ grouped " + applied + " request(s)");
        return applied;
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

    /** Collapses whitespace so a small per-item char budget covers more signal. */
    private static String squeeze(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
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

    private record ChunkItem(int idx, String folder, String name, String description) {}

    /** Parses the batch-classification reply: a JSON array of per-item objects. */
    private static List<ChunkItem> parseChunk(String raw) {
        if (raw == null) return null;
        String json = extractJsonArray(raw);
        if (json == null) return null;
        try {
            var arr = JsonParser.parseString(json).getAsJsonArray();
            List<ChunkItem> out = new ArrayList<>();
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                int idx = o.has("idx") && !o.get("idx").isJsonNull() ? o.get("idx").getAsInt() : -1;
                out.add(new ChunkItem(idx, str(o, "folder"), str(o, "name"), str(o, "description")));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts the first balanced [...] array from a possibly fenced reply. */
    static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
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
                else if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return raw.substring(start, i + 1);
                }
            }
        }
        return null;
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
