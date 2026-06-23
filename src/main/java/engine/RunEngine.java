package engine;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import db.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

    // Loaded from settings at the start of each run
    private volatile double greyLowerBound = 60.0;
    private volatile String scopeEnforcement = "WARN"; // WARN / BLOCK / OFF

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
        loadRunSettings();
        executor.submit(() -> {
            long runId = -1;
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

                // --- Iterate test cases ---
                int done = 0;
                for (long tcId : testCaseIds) {
                    processOne(runId, tcId, account, safeMode, matchThreshold);
                    done++;
                    fireProgress(done, testCaseIds.size());
                }

                runRepo.finishRun(runId);
                fireFinished(runId, null);

            } catch (Exception e) {
                api.logging().logToError("[BAC] Run error: " + e.getMessage());
                try { if (runId > 0) runRepo.finishRun(runId); } catch (Exception ignored) {}
                fireFinished(runId, "Run failed: " + e.getMessage());
            } finally {
                running = false;
            }
        });
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
            HttpRequest req = buildSwappedRequest(raw, account, service);
            if (req == null) return true;
            var resp = api.http().sendRequest(req);
            int status = resp.response().statusCode();
            // 2xx = good; 401/403/redirect (3xx) = session dead
            return status >= 200 && status < 300;
        } catch (Exception e) {
            api.logging().logToError("[BAC] Canary check error: " + e.getMessage());
            return false;
        }
    }

    // ---- Single test case ----------------------------------------------

    private void processOne(long runId, long tcId,
                            AccountRepository.AccountRecord account,
                            boolean safeMode, double matchThreshold) throws SQLException {
        TestCaseRepository.TestCaseRow tc = tcRepo.getById(tcId).orElse(null);
        if (tc == null) return;

        // Safe mode: skip DELETE requests only (the most destructive verb).
        // POST/PUT/PATCH are still replayed so access control on them can be tested.
        if (safeMode && "DELETE".equalsIgnoreCase(tc.method())) {
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
            HttpRequest req = buildSwappedRequest(requestRaw, account, service);
            if (req == null) {
                saveResult(runId, tc, account, baselineId > 0 ? baselineId : null,
                           0, 0, "", new byte[0], 0.0, ERROR);
                return;
            }

            var result = api.http().sendRequest(req);
            var response = result.response();
            int newStatus = response.statusCode();
            byte[] newResponseRaw = response.toByteArray().getBytes();
            byte[] newBody = response.body().getBytes();
            int newLength = newBody.length;
            String newHash = TestCaseRepository.sha256(newBody);

            // Extract baseline body for similarity
            byte[] baselineBody = extractBody(baselineRaw);
            double similarity = computeSimilarity(baselineBody, newBody);

            String expectedAccess = account.expectedAccess() != null ? account.expectedAccess() : "UNKNOWN";
            String verdict = computeVerdict(
                newStatus, tc.primaryBaselineStatus() != null ? tc.primaryBaselineStatus() : 0,
                similarity, matchThreshold, expectedAccess, greyLowerBound
            );

            saveResult(runId, tc, account,
                baselineId > 0 ? baselineId : null,
                newStatus, newLength, newHash, newResponseRaw, similarity, verdict);

        } catch (Exception e) {
            api.logging().logToError("[BAC] Error running test case " + tcId + ": " + e.getMessage());
            saveResult(runId, tc, account, null, 0, 0, "", new byte[0], 0.0, ERROR);
        }
    }

    // ---- Identity swap -------------------------------------------------

    /**
     * Reconstruct request from raw bytes with new auth material injected.
     * Removes existing Cookie and Authorization headers, injects from account.
     */
    private HttpRequest buildSwappedRequest(byte[] rawRequest, AccountRepository.AccountRecord account,
                                            HttpService service) {
        try {
            // Bind the request to its target service (host/port/TLS). Without this the
            // request has no destination and sendRequest fails with an empty response —
            // which is why responses never appeared for HTTPS targets.
            HttpRequest req = service != null
                ? HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest))
                : HttpRequest.httpRequest(ByteArray.byteArray(rawRequest));

            // Remove auth headers
            req = req.withRemovedHeader("Cookie");
            req = req.withRemovedHeader("Authorization");
            req = req.withRemovedHeader("X-Auth-Token");
            req = req.withRemovedHeader("X-Session-Token");
            req = req.withRemovedHeader("X-Access-Token");
            req = req.withRemovedHeader("X-Api-Key");

            // Inject new cookies
            Map<String, String> cookies = account.cookies();
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (var entry : cookies.entrySet()) {
                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                    cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
                }
                req = req.withAddedHeader("Cookie", cookieHeader.toString());
            }

            // Inject new session headers (Authorization etc.)
            for (var entry : account.headers().entrySet()) {
                req = req.withAddedHeader(entry.getKey(), entry.getValue());
            }

            return req;
        } catch (Exception e) {
            api.logging().logToError("[BAC] Failed to build swapped request: " + e.getMessage());
            return null;
        }
    }

    // ---- Similarity ----------------------------------------------------

    /**
     * Computes normalized similarity between two bodies (0.0–100.0).
     * Uses line-based Levenshtein-style diff with a fast approximation
     * suitable for typical HTTP response bodies up to 200 KB.
     */
    public static double computeSimilarity(byte[] body1, byte[] body2) {
        if (body1 == null) body1 = new byte[0];
        if (body2 == null) body2 = new byte[0];
        if (body1.length == 0 && body2.length == 0) return 100.0;
        if (body1.length == 0 || body2.length == 0) return 0.0;

        String s1 = new String(body1, StandardCharsets.UTF_8);
        String s2 = new String(body2, StandardCharsets.UTF_8);

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
