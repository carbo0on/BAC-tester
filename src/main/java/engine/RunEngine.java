package engine;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.params.HttpParameter;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import db.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Phase 4 run engine: canary check → identity swap → sendRequest → similarity → verdict → persist.
 *
 * All network I/O runs on a dedicated daemon thread pool, never on EDT.
 */
public class RunEngine {

    // Verdicts
    public static final String POTENTIAL_BAC    = "POTENTIAL_BAC";
    public static final String LIKELY_ENFORCED  = "LIKELY_ENFORCED";
    public static final String EXPECTED_OK      = "EXPECTED_OK";
    public static final String ANOMALY          = "ANOMALY";
    public static final String REVIEW           = "REVIEW";
    public static final String SKIPPED_SAFE     = "SKIPPED_SAFE";
    public static final String ERROR            = "ERROR";

    private final MontoyaApi api;
    private final TestCaseRepository tcRepo;
    private final AccountRepository accountRepo;
    private final RunRepository runRepo;
    private final DatabaseManager dbManager;
    private final ExecutorService executor;

    /** Progress callback: (completedCount, totalCount). Fires on background thread. */
    private volatile BiConsumer<Integer, Integer> onProgress;
    /** Result callback per result row. Fires on background thread. */
    private volatile Consumer<RunRepository.ResultRecord> onResult;
    /** Final callback: (runId, errorMessage or null). */
    private volatile BiConsumer<Long, String> onFinished;

    private volatile boolean running = false;
    /** Set by {@link #requestStop()}; the run loop checks it between requests. */
    private volatile boolean cancelRequested = false;

    // Loaded from settings at the start of each run
    private volatile double greyLowerBound = 60.0;
    private volatile String scopeEnforcement = "WARN"; // WARN / BLOCK / OFF
    private volatile String safeModeScope = "DELETE";  // DELETE (lenient) / ALL (strict)
    private volatile int    runThreads = 1;            // concurrent in-flight requests (1–16)
    private volatile long   runDelayMs = 0;            // throttle: delay before each request
    private volatile boolean detectLoginBody = true;   // treat a 200 login page as expired session
    private volatile List<Pattern> ignorePatterns = new ArrayList<>();

    /** Matches a login form in a 200 response body (strong "session expired" signal). */
    private static final Pattern LOGIN_BODY =
        Pattern.compile("(?i)<input[^>]+type=[\"']?password");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public RunEngine(MontoyaApi api, DatabaseManager dbManager) {
        this.api         = api;
        this.dbManager   = dbManager;
        this.tcRepo      = new TestCaseRepository(dbManager);
        this.accountRepo = new AccountRepository(dbManager);
        this.runRepo     = new RunRepository(dbManager);
        this.executor    = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bac-run-engine");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnProgress(BiConsumer<Integer, Integer> cb) { this.onProgress = cb; }
    public void setOnResult(Consumer<RunRepository.ResultRecord> cb) { this.onResult = cb; }
    public void setOnFinished(BiConsumer<Long, String> cb) { this.onFinished = cb; }

    public boolean isRunning() { return running; }

    /**
     * Requests cancellation of the in-flight run. The loop stops issuing new
     * requests after the current one returns; already-stored results are kept.
     */
    public void requestStop() { cancelRequested = true; }

    public boolean isStopRequested() { return cancelRequested; }

    /**
     * Start a run asynchronously.
     * @param accountId  account whose auth material replaces the original
     * @param testCaseIds ordered list of test case IDs to run
     * @param safeMode   skip state-changing requests when true
     * @param matchThreshold  similarity threshold 0–100 for "match"
     */
    public void startRun(long accountId, List<Long> testCaseIds,
                         boolean safeMode, double matchThreshold) {
        if (running) return;
        running = true;
        cancelRequested = false;
        loadRunSettings();
        executor.submit(() -> {
            long runId = -1;
            ExecutorService pool = null;
            try {
                AccountRepository.AccountRecord account = accountRepo.getById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

                // --- Canary check ---
                boolean canaryOk = checkCanary(account);
                runId = runRepo.createRun(accountId, testCaseIds.size(), canaryOk);

                if (!canaryOk) {
                    runRepo.finishRun(runId);
                    fireFinished(runId, "Canary check failed — auth material may be expired. Run aborted.");
                    return;
                }

                // --- Iterate test cases (sequentially or across a worker pool) ---
                final long fRunId = runId;
                final int total = testCaseIds.size();
                final java.util.concurrent.atomic.AtomicInteger done =
                    new java.util.concurrent.atomic.AtomicInteger();
                int threads = Math.max(1, Math.min(runThreads, 16));

                if (threads == 1) {
                    for (long tcId : testCaseIds) {
                        if (cancelRequested) break;
                        throttle();
                        processOne(fRunId, tcId, account, safeMode, matchThreshold);
                        fireProgress(done.incrementAndGet(), total);
                    }
                } else {
                    pool = Executors.newFixedThreadPool(threads, workerFactory());
                    for (long tcId : testCaseIds) {
                        final long id = tcId;
                        pool.submit(() -> {
                            if (cancelRequested) return;
                            try {
                                throttle();
                                processOne(fRunId, id, account, safeMode, matchThreshold);
                            } catch (Exception ex) {
                                api.logging().logToError("[BAC] Run worker TC " + id + ": " + ex.getMessage());
                            } finally {
                                fireProgress(done.incrementAndGet(), total);
                            }
                        });
                    }
                    pool.shutdown();
                    while (!pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (cancelRequested) { pool.shutdownNow(); break; }
                    }
                }

                runRepo.finishRun(runId);
                fireFinished(runId, null);

            } catch (Exception e) {
                api.logging().logToError("[BAC] Run error: " + e.getMessage());
                try { if (runId > 0) runRepo.finishRun(runId); } catch (Exception ignored) {}
                fireFinished(runId, "Run failed: " + e.getMessage());
            } finally {
                if (pool != null && !pool.isShutdown()) pool.shutdownNow();
                running = false;
            }
        });
    }

    /** Sleeps the configured throttle delay (no-op when 0). */
    private void throttle() {
        long d = runDelayMs;
        if (d > 0) {
            try { Thread.sleep(d); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private static java.util.concurrent.ThreadFactory workerFactory() {
        return r -> {
            Thread t = new Thread(r, "bac-run-worker");
            t.setDaemon(true);
            return t;
        };
    }

    private void loadRunSettings() {
        try {
            String lb = dbManager.getSetting("review_lower_bound");
            if (lb != null) greyLowerBound = Double.parseDouble(lb);
        } catch (Exception ignored) {}
        try {
            String se = dbManager.getSetting("scope_enforcement");
            if (se != null) scopeEnforcement = se;
        } catch (Exception ignored) {}
        try {
            String sms = dbManager.getSetting("safe_mode_scope");
            if (sms != null && !sms.isBlank()) safeModeScope = sms.trim().toUpperCase();
        } catch (Exception ignored) {}
        try {
            String t = dbManager.getSetting("run_threads");
            if (t != null && !t.isBlank()) runThreads = Integer.parseInt(t.trim());
        } catch (Exception ignored) {}
        try {
            String d = dbManager.getSetting("run_delay_ms");
            if (d != null && !d.isBlank()) runDelayMs = Long.parseLong(d.trim());
        } catch (Exception ignored) {}
        try {
            String lb = dbManager.getSetting("canary_detect_login_body");
            if (lb != null) detectLoginBody = !"false".equalsIgnoreCase(lb.trim());
        } catch (Exception ignored) {}
        ignorePatterns = loadIgnorePatterns(dbManager);
    }

    /** Compiles the user's ignore-pattern regexes from settings; invalid ones are skipped. */
    public static List<Pattern> loadIgnorePatterns(DatabaseManager db) {
        List<Pattern> compiled = new ArrayList<>();
        try {
            String json = db.getSetting("ignore_patterns");
            if (json != null && !json.isBlank()) {
                List<String> raw = new Gson().fromJson(json, new TypeToken<List<String>>() {}.getType());
                if (raw != null) {
                    for (String r : raw) {
                        if (r == null || r.isBlank()) continue;
                        try { compiled.add(Pattern.compile(r)); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return compiled;
    }

    // ---- Canary check --------------------------------------------------

    private boolean checkCanary(AccountRepository.AccountRecord account) {
        if (account.canaryRequestId() == null) return true; // no canary configured → assume ok
        try {
            long canaryId = account.canaryRequestId();
            byte[] raw = tcRepo.getRequestRaw(canaryId);
            if (raw == null) return true;
            TestCaseRepository.TestCaseRow canaryTc = tcRepo.getById(canaryId).orElse(null);
            if (canaryTc == null) return true;
            HttpService service = HttpService.httpService(canaryTc.host(), canaryTc.port(), canaryTc.isHttps());
            List<DynamicField> dyn = DynamicField.parse(tcRepo.getDynamicFields(canaryId));
            HttpRequest req = buildSwappedRequest(raw, account, service, dyn);
            if (req == null) return true;
            var resp = api.http().sendRequest(req);
            if (resp.response() == null) return false; // unreachable — don't emit false results
            return !isSessionDead(resp.response(), detectLoginBody);
        } catch (Exception e) {
            api.logging().logToError("[BAC] Canary check error: " + e.getMessage());
            return false;
        }
    }

    /**
     * A session is considered DEAD only on unambiguous auth-failure signals:
     * 401/403, or a redirect (3xx) whose Location points at a login/auth page.
     * Other statuses (2xx, 404, 5xx, non-login 3xx) are NOT treated as expired
     * sessions, so legitimate non-2xx canary endpoints don't abort the run.
     */
    public static boolean isSessionDead(HttpResponse response) {
        return isSessionDead(response, false);
    }

    /**
     * As above, but when {@code detectLoginBody} is set a {@code 200} response
     * whose body contains a login form (password input) is also treated as an
     * expired session — many apps serve a 200 login page instead of a 401/redirect.
     */
    public static boolean isSessionDead(HttpResponse response, boolean detectLoginBody) {
        int status = response.statusCode();
        if (status == 401 || status == 403) return true;
        if (status >= 300 && status < 400) {
            String loc = response.headerValue("Location");
            if (loc != null && Pattern.compile("(?i)(login|signin|sign-in|sign_in|auth|sso|account/login)")
                    .matcher(loc).find()) {
                return true;
            }
        }
        if (detectLoginBody && status == 200) {
            try {
                String body = response.bodyToString();
                if (body != null && LOGIN_BODY.matcher(body).find()) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ---- Single test case ----------------------------------------------

    private void processOne(long runId, long tcId,
                            AccountRepository.AccountRecord account,
                            boolean safeMode, double matchThreshold) throws SQLException {
        TestCaseRepository.TestCaseRow tc = tcRepo.getById(tcId).orElse(null);
        if (tc == null) return;

        // Safe mode: skip destructive requests so a replay never mutates target data.
        //   DELETE (lenient, default) → skip only DELETE; POST/PUT/PATCH still replayed.
        //   ALL   (strict)           → skip every state-changing request.
        if (safeMode && shouldSkipForSafeMode(tc)) {
            String expAccess = account.expectedAccess() != null ? account.expectedAccess() : "UNKNOWN";
            saveSkipped(runId, tc, account, expAccess);
            return;
        }

        // Get primary baseline for comparison
        byte[] baselineRaw = tcRepo.getPrimaryBaselineResponse(tcId);
        long baselineId = getPrimaryBaselineId(tcId);

        // Get raw request bytes and rebuild with new identity
        byte[] requestRaw = tcRepo.getRequestRaw(tcId);
        if (requestRaw == null || baselineRaw == null) {
            saveResult(runId, tc, account, null, 0, 0, "", new byte[0], 0.0, ERROR);
            return;
        }

        try {
            // Scope enforcement: OFF (ignore) / WARN (log) / BLOCK (skip)
            if (!"OFF".equalsIgnoreCase(scopeEnforcement) && !api.scope().isInScope(tc.url())) {
                if ("BLOCK".equalsIgnoreCase(scopeEnforcement)) {
                    api.logging().logToOutput("[BAC] Blocked (out of scope): " + tc.url());
                    saveResult(runId, tc, account, baselineId > 0 ? baselineId : null,
                               0, 0, "", new byte[0], 0.0, ERROR);
                    return;
                }
                api.logging().logToOutput("[BAC] Warning: " + tc.url() + " is out of scope. Proceeding anyway.");
            }

            HttpService service = HttpService.httpService(tc.host(), tc.port(), tc.isHttps());
            List<DynamicField> dyn = DynamicField.parse(tcRepo.getDynamicFields(tcId));
            HttpRequest req = buildSwappedRequest(requestRaw, account, service, dyn);
            if (req == null) {
                saveResult(runId, tc, account, baselineId > 0 ? baselineId : null,
                           0, 0, "", new byte[0], 0.0, ERROR);
                return;
            }

            var result = api.http().sendRequest(req);
            var response = result.response();
            if (response == null) {
                api.logging().logToError("[BAC] Run TC " + tcId + " (" + tc.method() + " "
                    + tc.host() + ":" + tc.port() + " https=" + tc.isHttps()
                    + "): no response (connection failed / unreachable / out of scope).");
                saveResult(runId, tc, account, baselineId > 0 ? baselineId : null,
                           0, 0, "", new byte[0], 0.0, ERROR);
                return;
            }
            int newStatus = response.statusCode();
            byte[] newResponseRaw = response.toByteArray().getBytes();
            byte[] newBody = response.body().getBytes();
            int newLength = newBody.length;
            String newHash = TestCaseRepository.sha256(newBody);
            api.logging().logToOutput("[BAC] Run TC " + tcId + ": status=" + newStatus
                + " responseBytes=" + newResponseRaw.length);

            // Extract baseline body for similarity. Ignore-patterns (timestamps,
            // nonces, CSRF tokens…) are stripped and JSON is pretty-printed first
            // so volatile noise doesn't depress the score (§3.2).
            byte[] baselineBody = extractBody(baselineRaw);
            double similarity = computeSimilarity(baselineBody, newBody, ignorePatterns);

            String expectedAccess = account.expectedAccess() != null ? account.expectedAccess() : "UNKNOWN";
            String verdict = computeVerdict(
                newStatus, tc.primaryBaselineStatus() != null ? tc.primaryBaselineStatus() : 0,
                similarity, matchThreshold, expectedAccess, greyLowerBound
            );

            saveResult(runId, tc, account,
                baselineId > 0 ? baselineId : null,
                newStatus, newLength, newHash, newResponseRaw, similarity, verdict);

        } catch (Exception e) {
            api.logging().logToError("[BAC] Error running test case " + tcId + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            saveResult(runId, tc, account, null, 0, 0, "", new byte[0], 0.0, ERROR);
        }
    }

    // ---- Identity swap -------------------------------------------------

    private boolean shouldSkipForSafeMode(TestCaseRepository.TestCaseRow tc) {
        if ("ALL".equalsIgnoreCase(safeModeScope)) return tc.isStateChanging();
        return "DELETE".equalsIgnoreCase(tc.method());
    }

    /**
     * Reconstruct a request from raw bytes with the account's identity injected,
     * then apply any dynamic-field rewrites.
     *
     * <p>Cookies are <b>merged</b>, not wiped: existing cookies (CSRF token,
     * load-balancer affinity, locale, …) are preserved and only the keys the
     * account defines are overridden. This avoids breaking the request for
     * reasons unrelated to access control (#4). Known session headers are still
     * replaced wholesale so the original identity can't leak through.</p>
     */
    public static HttpRequest buildSwappedRequestStatic(byte[] rawRequest,
                                                        AccountRepository.AccountRecord account,
                                                        HttpService service,
                                                        List<DynamicField> dynamicFields) {
        // Bind the request to its target service (host/port/TLS). Without this the
        // request has no destination and sendRequest fails with an empty response —
        // which is why responses never appeared for HTTPS targets.
        HttpRequest req = service != null
            ? HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest))
            : HttpRequest.httpRequest(ByteArray.byteArray(rawRequest));

        // Preserve the original cookie jar, overriding only the account's keys.
        String originalCookie = req.headerValue("Cookie");
        LinkedHashMap<String, String> mergedCookies = parseCookieHeader(originalCookie);
        mergedCookies.putAll(account.cookies());

        // Replace known session headers wholesale so the old identity can't leak.
        for (String h : new String[]{"Authorization", "X-Auth-Token", "X-Session-Token",
                                      "X-Access-Token", "X-Api-Key"}) {
            req = req.withRemovedHeader(h);
        }
        req = req.withRemovedHeader("Cookie");
        if (!mergedCookies.isEmpty()) {
            req = req.withAddedHeader("Cookie", buildCookieHeader(mergedCookies));
        }
        for (var entry : account.headers().entrySet()) {
            req = req.withAddedHeader(entry.getKey(), entry.getValue());
        }

        return applyDynamicFields(req, dynamicFields, account);
    }

    private HttpRequest buildSwappedRequest(byte[] rawRequest, AccountRepository.AccountRecord account,
                                            HttpService service, List<DynamicField> dynamicFields) {
        try {
            return buildSwappedRequestStatic(rawRequest, account, service, dynamicFields);
        } catch (Exception e) {
            api.logging().logToError("[BAC] Failed to build swapped request: " + e.getMessage());
            return null;
        }
    }

    // ---- Dynamic fields (#2 — CSRF / nonce handling) -------------------

    /** Rewrites dynamic fields (CSRF tokens, nonces, …) before replay. */
    public static HttpRequest applyDynamicFields(HttpRequest req, List<DynamicField> fields,
                                                 AccountRepository.AccountRecord account) {
        if (fields == null || fields.isEmpty()) return req;
        for (DynamicField f : fields) {
            if (f == null || f.name() == null || f.name().isBlank()) continue;
            String resolved = resolveDynamicValue(f, account);
            boolean remove = f.strategy() == DynamicField.Strategy.REMOVE || resolved == null;
            switch (f.location()) {
                case HEADER -> req = remove
                    ? req.withRemovedHeader(f.name())
                    : req.withUpdatedHeader(f.name(), resolved);
                case COOKIE -> {
                    LinkedHashMap<String, String> cookies = parseCookieHeader(req.headerValue("Cookie"));
                    if (remove) cookies.remove(f.name()); else cookies.put(f.name(), resolved);
                    req = req.withRemovedHeader("Cookie");
                    if (!cookies.isEmpty()) req = req.withAddedHeader("Cookie", buildCookieHeader(cookies));
                }
                case QUERY_PARAM -> req = setParameter(req, f.name(), resolved, remove, HttpParameterType.URL);
                case BODY_PARAM  -> req = setParameter(req, f.name(), resolved, remove, HttpParameterType.BODY);
            }
        }
        return req;
    }

    private static HttpRequest setParameter(HttpRequest req, String name, String value,
                                            boolean remove, HttpParameterType type) {
        boolean exists = false;
        for (ParsedHttpParameter p : req.parameters()) {
            if (p.type() == type && p.name().equals(name)) { exists = true; break; }
        }
        HttpParameter param = HttpParameter.parameter(name, value != null ? value : "", type);
        if (remove) {
            return exists ? req.withRemovedParameters(param) : req;
        }
        return exists ? req.withUpdatedParameters(param) : req.withAddedParameters(param);
    }

    private static String resolveDynamicValue(DynamicField f, AccountRepository.AccountRecord account) {
        return switch (f.strategy()) {
            case REMOVE      -> null;
            case STATIC      -> f.value() != null ? f.value() : "";
            case FROM_COOKIE -> account != null ? account.cookies().get(f.value()) : null;
            case FROM_HEADER -> {
                if (account == null) yield null;
                // header lookup is case-insensitive
                for (var e : account.headers().entrySet())
                    if (e.getKey().equalsIgnoreCase(f.value())) yield e.getValue();
                yield null;
            }
        };
    }

    // ---- Cookie helpers ------------------------------------------------

    public static LinkedHashMap<String, String> parseCookieHeader(String header) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (header == null || header.isBlank()) return map;
        for (String part : header.split(";")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq > 0) map.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return map;
    }

    public static String buildCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (var entry : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ---- Similarity ----------------------------------------------------

    /** Back-compat overload with no ignore patterns. */
    public static double computeSimilarity(byte[] body1, byte[] body2) {
        return computeSimilarity(body1, body2, null);
    }

    /**
     * Computes normalized similarity between two bodies (0.0–100.0).
     *
     * <p>Before diffing, each body is normalized: JSON is pretty-printed so a
     * minified single-line API response yields a real line-level gradient
     * (#3), and the user's ignore-patterns strip volatile noise such as
     * timestamps, nonces and CSRF tokens (#1). Uses a line-based LCS ratio,
     * suitable for typical HTTP bodies up to 200 KB.</p>
     */
    public static double computeSimilarity(byte[] body1, byte[] body2, List<Pattern> ignorePatterns) {
        if (body1 == null) body1 = new byte[0];
        if (body2 == null) body2 = new byte[0];
        if (body1.length == 0 && body2.length == 0) return 100.0;
        if (body1.length == 0 || body2.length == 0) return 0.0;

        String s1 = normalizeForSimilarity(new String(body1, StandardCharsets.UTF_8), ignorePatterns);
        String s2 = normalizeForSimilarity(new String(body2, StandardCharsets.UTF_8), ignorePatterns);

        // Truncate to 200 KB for performance
        if (s1.length() > 200_000) s1 = s1.substring(0, 200_000);
        if (s2.length() > 200_000) s2 = s2.substring(0, 200_000);

        if (s1.equals(s2)) return 100.0;

        // Line-based LCS ratio
        String[] lines1 = s1.split("\n", -1);
        String[] lines2 = s2.split("\n", -1);

        // Cap at 2000 lines each for performance
        if (lines1.length > 2000) lines1 = Arrays.copyOf(lines1, 2000);
        if (lines2.length > 2000) lines2 = Arrays.copyOf(lines2, 2000);

        int lcs = lcsCount(lines1, lines2);
        int total = lines1.length + lines2.length;
        if (total == 0) return 100.0;
        return Math.round((2.0 * lcs / total) * 10000.0) / 100.0;
    }

    /**
     * Pretty-prints JSON bodies (so minified JSON diffs line-by-line) and strips
     * ignore-pattern matches. Falls back to the raw text when not JSON.
     */
    public static String normalizeForSimilarity(String body, List<Pattern> ignorePatterns) {
        if (body == null) return "";
        String s = prettyPrintJsonIfPossible(body);
        if (ignorePatterns != null) {
            for (Pattern p : ignorePatterns) {
                try { s = p.matcher(s).replaceAll(""); } catch (Exception ignored) {}
            }
        }
        return s;
    }

    /** Returns pretty-printed JSON if {@code body} parses as JSON, else the original. */
    public static String prettyPrintJsonIfPossible(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return body;
        char c = trimmed.charAt(0);
        if (c != '{' && c != '[') return body;        // cheap pre-check
        if (trimmed.length() > 500_000) return body;   // don't re-serialize huge payloads
        try {
            JsonElement el = JsonParser.parseString(trimmed);
            if (el.isJsonObject() || el.isJsonArray()) return GSON.toJson(el);
        } catch (Exception ignored) {}
        return body;
    }

    private static int lcsCount(String[] a, String[] b) {
        int n = a.length, m = b.length;
        // DP with two rows to save memory
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] tmp = prev; prev = curr; curr = tmp;
            Arrays.fill(curr, 0);
        }
        return prev[m];
    }

    // ---- Verdict -------------------------------------------------------

    public static String computeVerdict(int newStatus, int origStatus,
                                        double similarity, double threshold,
                                        String expectedAccess) {
        return computeVerdict(newStatus, origStatus, similarity, threshold, expectedAccess, 60.0);
    }

    public static String computeVerdict(int newStatus, int origStatus,
                                        double similarity, double threshold,
                                        String expectedAccess, double greyLowerBound) {
        boolean isMatch = (newStatus == origStatus) && (similarity >= threshold);
        boolean isGrey  = similarity >= greyLowerBound && similarity < threshold; // ambiguous zone

        return switch (expectedAccess.toUpperCase()) {
            case "DENIED" -> {
                if (isGrey) yield REVIEW;
                yield isMatch ? POTENTIAL_BAC : LIKELY_ENFORCED;
            }
            case "ALLOWED" -> {
                if (isGrey) yield REVIEW;
                yield isMatch ? EXPECTED_OK : ANOMALY;
            }
            default -> REVIEW; // UNKNOWN
        };
    }

    // ---- DB helpers ----------------------------------------------------

    private void saveSkipped(long runId, TestCaseRepository.TestCaseRow tc,
                             AccountRepository.AccountRecord account,
                             String expectedAccess) throws SQLException {
        saveResult(runId, tc, account, null, 0, 0, "", new byte[0], 0.0, SKIPPED_SAFE);
    }

    private void saveResult(long runId, TestCaseRepository.TestCaseRow tc,
                            AccountRepository.AccountRecord account,
                            Long baselineId, int newStatus, int newLength,
                            String newHash, byte[] newResponseRaw,
                            double similarity, String verdict) throws SQLException {
        String expectedAccess = account.expectedAccess() != null ? account.expectedAccess() : "UNKNOWN";
        long resultId = runRepo.saveResult(
            runId, tc.id(), account.id(),
            baselineId, expectedAccess,
            newStatus, newLength, newHash, newResponseRaw,
            similarity, verdict
        );
        if (onResult != null) {
            try {
                List<RunRepository.ResultRecord> results = runRepo.getResultsForRun(runId);
                results.stream().filter(r -> r.id() == resultId).findFirst().ifPresent(r -> {
                    Consumer<RunRepository.ResultRecord> cb = onResult;
                    if (cb != null) cb.accept(r);
                });
            } catch (Exception ignored) {}
        }
    }

    private long getPrimaryBaselineId(long tcId) {
        try {
            return tcRepo.getPrimaryBaselineId(tcId).orElse(-1L);
        } catch (Exception ignored) {}
        return -1;
    }

    private byte[] extractBody(byte[] rawResponse) {
        if (rawResponse == null) return new byte[0];
        // Find header/body separator: \r\n\r\n or \n\n
        for (int i = 0; i < rawResponse.length - 3; i++) {
            if (rawResponse[i] == '\r' && rawResponse[i+1] == '\n'
                    && rawResponse[i+2] == '\r' && rawResponse[i+3] == '\n') {
                int bodyStart = i + 4;
                return Arrays.copyOfRange(rawResponse, bodyStart, rawResponse.length);
            }
        }
        for (int i = 0; i < rawResponse.length - 1; i++) {
            if (rawResponse[i] == '\n' && rawResponse[i+1] == '\n') {
                return Arrays.copyOfRange(rawResponse, i + 2, rawResponse.length);
            }
        }
        return rawResponse;
    }

    // ---- Callback helpers (fire on background thread) ------------------

    private void fireProgress(int done, int total) {
        BiConsumer<Integer, Integer> cb = onProgress;
        if (cb != null) cb.accept(done, total);
    }

    private void fireFinished(long runId, String error) {
        BiConsumer<Long, String> cb = onFinished;
        if (cb != null) cb.accept(runId, error);
    }

    public TestCaseRepository getTcRepo() { return tcRepo; }
}
