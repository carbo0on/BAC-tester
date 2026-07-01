package db;

import burp.api.montoya.logging.Logging;

import java.io.File;
import java.sql.*;

/**
 * Manages the SQLite connection and full schema for BAC Time-Machine.
 * All public methods are synchronized — background threads call them via ExecutorService
 * and UI thread calls are always marshalled through SwingUtilities.invokeLater before
 * touching Swing, but DB calls may come from any thread.
 */
public class DatabaseManager {

    private final String dbPath;
    private final Logging logging;
    private Connection connection;

    /** Cache the validity probe so we don't fire a round-trip query on every JDBC call. */
    private long lastValidatedAtMs = 0L;
    private static final long VALIDATION_INTERVAL_MS = 2000L;

    public DatabaseManager(String dbPath, Logging logging) {
        this.dbPath = dbPath;
        this.logging = logging;
    }

    public synchronized void initialize() throws Exception {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Cannot create database directory: " + parent.getAbsolutePath());
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new Exception("SQLite JDBC driver not found on classpath", e);
        }

        connection = openConnection();

        createSchema();
        migrateSchema();
        insertDefaultSettings();
    }

    /**
     * Opens a fresh JDBC connection and applies the standard pragmas. A short
     * busy timeout lets concurrent writers wait for the WAL lock instead of
     * failing immediately with SQLITE_BUSY, which is the most common cause of
     * the "database keeps disconnecting" symptom.
     */
    private Connection openConnection() throws SQLException {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setBusyTimeout(15000);
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setEncoding(org.sqlite.SQLiteConfig.Encoding.UTF8);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=15000");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        lastValidatedAtMs = System.currentTimeMillis();
        return conn;
    }

    /**
     * Applies additive, idempotent schema migrations for databases created by
     * earlier versions of the extension. SQLite lacks "ADD COLUMN IF NOT EXISTS",
     * so each column is checked via pragma table_info before being added.
     */
    private void migrateSchema() throws SQLException {
        addColumnIfMissing("test_cases", "color_tag", "TEXT");
        // notes already exists in the base schema, but guard for very old DBs
        addColumnIfMissing("test_cases", "notes", "TEXT");
        // Folder coloring (Library tree) and account folders/coloring.
        addColumnIfMissing("folders", "color", "TEXT");
        addColumnIfMissing("accounts", "folder_id", "INTEGER");
        // Unify the quick-save hotkey default on Ctrl+Alt+B across the codebase.
        // Only rewrite known former defaults so a user's custom combo is preserved.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE settings SET value = 'Ctrl+Alt+B' WHERE key = 'hotkey_combo' "
                + "AND value IN ('Ctrl+Alt+A', 'Alt+Meta', 'Alt+Q')")) {
            int n = ps.executeUpdate();
            if (n > 0) logging.logToOutput("[BAC] Migration: quick-save hotkey default updated to Ctrl+Alt+B");
        }
    }

    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
            }
        }
        if (!exists) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                logging.logToOutput("[BAC] Migration: added column " + table + "." + column);
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {

            // Hierarchical folder tree for organising test cases
            st.execute("""
                CREATE TABLE IF NOT EXISTS folders (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL,
                    parent_id  INTEGER REFERENCES folders(id),
                    sort_order INTEGER DEFAULT 0,
                    created_at INTEGER
                )
            """);

            // User identities — auth_material is JSON {"cookies":{...},"headers":{...}}
            // canary_request_id forward-references test_cases; SQLite resolves FK lazily so ordering is fine
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    name              TEXT NOT NULL,
                    role_desc         TEXT,
                    auth_material     TEXT NOT NULL,
                    expected_access   TEXT DEFAULT 'UNKNOWN',
                    canary_request_id INTEGER REFERENCES test_cases(id),
                    created_at        INTEGER,
                    updated_at        INTEGER
                )
            """);

            // Captured HTTP requests — the test library
            // primary_baseline_id is a soft FK to baselines (circular); managed in application layer
            st.execute("""
                CREATE TABLE IF NOT EXISTS test_cases (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    name                TEXT,
                    notes               TEXT,
                    folder_id           INTEGER REFERENCES folders(id),
                    owner_acct_id       INTEGER REFERENCES accounts(id),
                    method              TEXT NOT NULL,
                    url                 TEXT NOT NULL,
                    host                TEXT NOT NULL,
                    port                INTEGER NOT NULL,
                    is_https            INTEGER NOT NULL,
                    request_raw         BLOB NOT NULL,
                    is_state_changing   INTEGER NOT NULL DEFAULT 0,
                    dynamic_fields      TEXT,
                    primary_baseline_id INTEGER,
                    created_at          INTEGER,
                    updated_at          INTEGER
                )
            """);

            // Versioned response snapshots — old baselines are NEVER deleted (versioning invariant)
            st.execute("""
                CREATE TABLE IF NOT EXISTS baselines (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    test_case_id INTEGER NOT NULL REFERENCES test_cases(id),
                    account_id   INTEGER REFERENCES accounts(id),
                    label        TEXT,
                    status       INTEGER,
                    length       INTEGER,
                    body_hash    TEXT,
                    response_raw BLOB NOT NULL,
                    captured_at  INTEGER
                )
            """);

            // A single test-run session (one account replayed against N test cases)
            st.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id  INTEGER REFERENCES accounts(id),
                    started_at  INTEGER,
                    finished_at INTEGER,
                    total_cases INTEGER,
                    canary_ok   INTEGER
                )
            """);

            // Per-request result of a run — stores the new response and triage verdict
            st.execute("""
                CREATE TABLE IF NOT EXISTS results (
                    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id               INTEGER NOT NULL REFERENCES runs(id),
                    test_case_id         INTEGER REFERENCES test_cases(id),
                    account_id           INTEGER REFERENCES accounts(id),
                    compared_baseline_id INTEGER REFERENCES baselines(id),
                    expected_access      TEXT,
                    new_status           INTEGER,
                    new_length           INTEGER,
                    new_body_hash        TEXT,
                    new_response_raw     BLOB,
                    similarity           REAL,
                    verdict              TEXT,
                    reviewed             INTEGER DEFAULT 0,
                    user_note            TEXT,
                    created_at           INTEGER
                )
            """);

            // Folders for organising accounts (flat list; NULL folder = Uncategorized)
            st.execute("""
                CREATE TABLE IF NOT EXISTS account_folders (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL,
                    color      TEXT,
                    created_at INTEGER
                )
            """);

            // Extension-wide key/value configuration store
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT
                )
            """);

            // AI auto-organization cache: maps a normalized endpoint signature
            // (method + host + templated path) to a previously chosen folder +
            // name + description so similar requests are grouped WITHOUT spending
            // another API call. This is the main lever that keeps AI usage cheap.
            st.execute("""
                CREATE TABLE IF NOT EXISTS ai_endpoint_cache (
                    signature   TEXT PRIMARY KEY,
                    folder_id   INTEGER,
                    folder_path TEXT,
                    name        TEXT,
                    description TEXT,
                    created_at  INTEGER
                )
            """);
        }
    }

    private void insertDefaultSettings() throws SQLException {
        // INSERT OR IGNORE — never overwrite user-edited settings on reload
        String sql = "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            String[][] defaults = {
                {"match_threshold",  "95"},
                // JSON array of Java regex strings: timestamps, CSRF tokens, nonces
                {"ignore_patterns",  "[\"\\\\d{10,13}\",\"csrf[_-]?token=[^&\\\\s]+\",\"nonce=[^&\\\\s]+\"]"},
                {"safe_mode",        "true"},
                {"safe_mode_scope",  "DELETE"},   // DELETE (lenient) / ALL (strict: skip all state-changing)
                {"font_size",        "12"},
                {"hotkey_combo",     "Ctrl+Alt+B"},   // quick-save (configurable in Settings)
                // Run engine performance / safety
                {"run_threads",      "1"},        // concurrent in-flight requests during a run (1–16)
                {"run_delay_ms",     "0"},        // throttle: delay before each request (ms)
                {"warn_missing_canary", "true"},  // warn before running an account that has no canary
                {"canary_detect_login_body", "true"}, // treat a 200 login page as an expired session
                // Phase 7.1 additions
                {"coloring_mode",    "AUTO"},   // AUTO (by method) / MANUAL / OFF
                {"review_lower_bound", "60"},    // grey-zone lower bound for REVIEW verdict
                {"diff_size_cap_kb", "300"},     // max KB compared in Compare tab
                {"default_expected_access", "UNKNOWN"},
                {"auto_expand_folders", "true"},
                {"confirm_before_run", "true"},
                {"scope_enforcement", "WARN"},   // WARN / BLOCK / OFF
                {"dedup_on_import",  "true"},
                // ── AI auto-organization (Phase 8) ──────────────────────────
                // Off by default; the user must opt in and supply an API key.
                {"ai_enabled",        "false"},          // master switch
                {"ai_auto_organize",  "true"},           // organize on capture when enabled
                {"ai_provider",       "GEMINI"},         // GEMINI / GROQ / OPENROUTER
                {"ai_api_key",        ""},               // provider API key (stored locally)
                {"ai_model",          ""},               // blank = provider default
                {"ai_max_chars",      "1800"},           // truncation budget per request+response (token control)
                {"ai_folder_by_host", "true"}            // prefix AI folders with the request host (multi-target)
            };
            for (String[] kv : defaults) {
                ps.setString(1, kv[0]);
                ps.setString(2, kv[1]);
                ps.executeUpdate();
            }
        }
    }

    // ---- public API -------------------------------------------------------

    /**
     * Returns the live connection, auto-reconnecting if the underlying socket
     * has been closed or invalidated (e.g., after a long idle period or an
     * OS-level file-handle reclaim).
     *
     * All callers MUST hold the lock on this DatabaseManager before calling
     * any JDBC method on the returned Connection.  Repositories achieve this
     * by using  synchronized (db) { … }  blocks instead of instance-level
     * synchronized methods, so every JDBC call across every repository goes
     * through a single monitor.
     */
    public synchronized Connection getConnection() {
        if (connection != null && !isConnectionBroken()) {
            return connection;
        }
        // Connection is null or broken — reconnect with a few retries to ride out
        // transient file-lock / handle-reclaim situations instead of giving up.
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                logging.logToOutput("[BAC] (Re)opening database connection at: " + dbPath
                    + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                try { if (connection != null) connection.close(); } catch (Exception ignored) {}
                connection = openConnection();
                return connection;
            } catch (Exception e) {
                last = e;
                try { Thread.sleep(150L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logging.logToError("[BAC] Database reconnect failed after retries: "
            + (last != null ? last.getMessage() : "unknown"));
        return connection; // may be null/stale; callers handle SQLExceptions
    }

    private boolean isConnectionBroken() {
        try {
            if (connection.isClosed()) return true;
            // Probe with isValid() only periodically — calling it on every JDBC
            // access adds a round-trip and was itself a source of churn.
            long now = System.currentTimeMillis();
            if (now - lastValidatedAtMs < VALIDATION_INTERVAL_MS) return false;
            boolean ok = connection.isValid(2);
            if (ok) lastValidatedAtMs = now;
            return !ok;
        } catch (Exception e) {
            return true;
        }
    }

    public synchronized String getSetting(String key) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        }
    }

    public synchronized void setSetting(String key, String value) throws SQLException {
        String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logging.logToOutput("[BAC] Database connection closed.");
            }
        } catch (SQLException e) {
            logging.logToError("[BAC] Error closing database: " + e.getMessage());
        }
    }
}
