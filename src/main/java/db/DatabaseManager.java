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

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }

        createSchema();
        migrateSchema();
        insertDefaultSettings();
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

            // Extension-wide key/value configuration store
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT
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
                {"font_size",        "12"},
                {"hotkey_combo",     "Ctrl+Alt+A"},
                // Phase 7.1 additions
                {"coloring_mode",    "AUTO"},   // AUTO (by method) / MANUAL / OFF
                {"review_lower_bound", "60"},    // grey-zone lower bound for REVIEW verdict
                {"diff_size_cap_kb", "300"},     // max KB compared in Compare tab
                {"default_expected_access", "UNKNOWN"},
                {"auto_expand_folders", "true"},
                {"confirm_before_run", "true"},
                {"scope_enforcement", "WARN"},   // WARN / BLOCK / OFF
                {"dedup_on_import",  "true"}
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
        try {
            if (connection == null || isConnectionBroken()) {
                logging.logToOutput("[BAC] Re-opening database connection at: " + dbPath);
                try { if (connection != null) connection.close(); } catch (Exception ignored) {}
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA foreign_keys=ON");
                    st.execute("PRAGMA busy_timeout=5000");  // wait up to 5 s instead of failing immediately
                }
            }
        } catch (Exception e) {
            logging.logToError("[BAC] Database reconnect failed: " + e.getMessage());
        }
        return connection;
    }

    private boolean isConnectionBroken() {
        try {
            // isValid() sends a lightweight probe query; timeout=1 s
            return connection.isClosed() || !connection.isValid(1);
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
