package db;

import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** CRUD for test_cases and their versioned baselines. */
public class TestCaseRepository {

    private final DatabaseManager db;

    public TestCaseRepository(DatabaseManager db) {
        this.db = db;
    }

    // ---- Records -------------------------------------------------------

    public record TestCaseRow(
        long id,
        String name,
        String method,
        String host,
        String url,
        int port,
        boolean isHttps,
        boolean isStateChanging,
        Long folderId,
        Long ownerAccountId,
        String ownerName,
        Integer primaryBaselineStatus,
        Integer primaryBaselineLength,
        long capturedAt,
        String notes,
        String colorTag
    ) {}

    public record BaselineRecord(
        long id,
        long testCaseId,
        Long accountId,
        String label,
        Integer status,
        Integer length,
        String bodyHash,
        byte[] responseRaw,
        long capturedAt
    ) {}

    public record SaveRequest(
        String name,
        String notes,
        Long folderId,
        Long ownerAccountId,
        String method,
        String url,
        String host,
        int port,
        boolean isHttps,
        byte[] requestRaw,
        byte[] responseRaw,
        int responseStatus,
        int responseLength
    ) {}

    // ---- Write ---------------------------------------------------------

    /**
     * Saves a test case and its initial "original" baseline atomically.
     * Returns the new test_case.id.
     */
    public long save(SaveRequest req) throws SQLException {
        synchronized (db) {
            Connection conn = db.getConnection();
            boolean prevAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                long now = Instant.now().getEpochSecond();
                boolean isStateChanging = isStateChangingMethod(req.method());
                byte[] responseRaw = req.responseRaw() != null ? req.responseRaw() : new byte[0];
                byte[] requestRaw  = req.requestRaw()  != null ? req.requestRaw()  : new byte[0];
                String baselineLabel = responseRaw.length == 0 ? "no-response" : "original";

                // 1. Insert test case
                String insertTc = """
                    INSERT INTO test_cases
                        (name, notes, folder_id, owner_acct_id, method, url, host, port,
                         is_https, request_raw, is_state_changing, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                long tcId;
                try (PreparedStatement ps = conn.prepareStatement(insertTc, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, req.name() != null ? req.name() : autoName(req));
                    ps.setString(2, req.notes());
                    if (req.folderId() != null) ps.setLong(3, req.folderId()); else ps.setNull(3, Types.INTEGER);
                    if (req.ownerAccountId() != null) ps.setLong(4, req.ownerAccountId()); else ps.setNull(4, Types.INTEGER);
                    ps.setString(5, req.method());
                    ps.setString(6, req.url());
                    ps.setString(7, req.host());
                    ps.setInt(8, req.port());
                    ps.setInt(9, req.isHttps() ? 1 : 0);
                    ps.setBytes(10, requestRaw);
                    ps.setInt(11, isStateChanging ? 1 : 0);
                    ps.setLong(12, now);
                    ps.setLong(13, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        tcId = rs.next() ? rs.getLong(1) : -1;
                    }
                }

                // 2. Insert initial baseline
                String insertBl = """
                    INSERT INTO baselines
                        (test_case_id, account_id, label, status, length, body_hash, response_raw, captured_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                long blId;
                try (PreparedStatement ps = conn.prepareStatement(insertBl, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, tcId);
                    if (req.ownerAccountId() != null) ps.setLong(2, req.ownerAccountId()); else ps.setNull(2, Types.INTEGER);
                    ps.setString(3, baselineLabel);
                    ps.setInt(4, req.responseStatus());
                    ps.setInt(5, req.responseLength());
                    ps.setString(6, sha256(responseRaw));
                    ps.setBytes(7, responseRaw);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        blId = rs.next() ? rs.getLong(1) : -1;
                    }
                }

                // 3. Link primary baseline
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE test_cases SET primary_baseline_id = ? WHERE id = ?")) {
                    ps.setLong(1, blId);
                    ps.setLong(2, tcId);
                    ps.executeUpdate();
                }

                conn.commit();
                return tcId;
            } catch (Exception e) {
                conn.rollback();
                throw (e instanceof SQLException se) ? se : new SQLException(e);
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        }
    }

    // ---- Read ----------------------------------------------------------

    public List<TestCaseRow> getByFolder(Long folderId) throws SQLException {
        synchronized (db) {
            if (folderId == null) {
                return query("tc.folder_id IS NULL", ps -> {});
            } else {
                return query("tc.folder_id = ?", ps -> ps.setLong(1, folderId));
            }
        }
    }

    public List<TestCaseRow> getAll() throws SQLException {
        synchronized (db) {
            return query("1=1", ps -> {});
        }
    }

    public Optional<TestCaseRow> getById(long id) throws SQLException {
        synchronized (db) {
            List<TestCaseRow> rows = query("tc.id = ?", ps -> ps.setLong(1, id));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    public Optional<Long> getPrimaryBaselineId(long tcId) throws SQLException {
        synchronized (db) {
            String sql = "SELECT primary_baseline_id FROM test_cases WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, tcId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? Optional.empty() : Optional.of(v);
                    }
                }
            }
            return Optional.empty();
        }
    }

    public byte[] getRequestRaw(long id) throws SQLException {
        synchronized (db) {
            String sql = "SELECT request_raw FROM test_cases WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes("request_raw") : null;
                }
            }
        }
    }

    public byte[] getPrimaryBaselineResponse(long testCaseId) throws SQLException {
        synchronized (db) {
            String sql = """
                SELECT b.response_raw FROM baselines b
                JOIN test_cases tc ON tc.primary_baseline_id = b.id
                WHERE tc.id = ?
                """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, testCaseId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes("response_raw") : null;
                }
            }
        }
    }

    public List<BaselineRecord> getBaselines(long testCaseId) throws SQLException {
        synchronized (db) {
            String sql = """
                SELECT id, test_case_id, account_id, label, status, length, body_hash, response_raw, captured_at
                FROM baselines WHERE test_case_id = ? ORDER BY captured_at
                """;
            List<BaselineRecord> result = new ArrayList<>();
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, testCaseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Long acctId = rs.getLong("account_id");
                        result.add(new BaselineRecord(
                            rs.getLong("id"),
                            rs.getLong("test_case_id"),
                            rs.wasNull() ? null : acctId,
                            rs.getString("label"),
                            rs.getInt("status"),
                            rs.getInt("length"),
                            rs.getString("body_hash"),
                            rs.getBytes("response_raw"),
                            rs.getLong("captured_at")
                        ));
                    }
                }
            }
            return result;
        }
    }

    // ---- Baseline write ------------------------------------------------

    /** Add a new versioned baseline for a test case. Returns new baseline id. */
    public long addBaseline(long tcId, Long accountId, String label,
                            int status, int length, byte[] responseRaw) throws SQLException {
        synchronized (db) {
            long now = Instant.now().getEpochSecond();
            String sql = """
                INSERT INTO baselines (test_case_id, account_id, label, status, length, body_hash, response_raw, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, tcId);
                if (accountId != null) ps.setLong(2, accountId); else ps.setNull(2, Types.INTEGER);
                ps.setString(3, label != null ? label : "baseline");
                ps.setInt(4, status);
                ps.setInt(5, length);
                ps.setString(6, sha256(responseRaw));
                ps.setBytes(7, responseRaw);
                ps.setLong(8, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    /** Set primary_baseline_id for a test case (does NOT delete old baselines). */
    public void setPrimaryBaseline(long tcId, long baselineId) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE test_cases SET primary_baseline_id = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, baselineId);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, tcId);
                ps.executeUpdate();
            }
        }
    }

    // ---- Update --------------------------------------------------------

    public void rename(long id, String name) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE test_cases SET name = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    public void setNotes(long id, String notes) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE test_cases SET notes = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, notes);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    /** Set (or clear with null) a manual color tag like "RED"/"ORANGE"/"GREEN"/"BLUE". */
    public void setColorTag(long id, String colorTag) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE test_cases SET color_tag = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, colorTag);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    public String getNotes(long id) throws SQLException {
        synchronized (db) {
            String sql = "SELECT notes FROM test_cases WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("notes") : null;
                }
            }
        }
    }

    public void moveToFolder(long id, Long folderId) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE test_cases SET folder_id = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                if (folderId != null) ps.setLong(1, folderId); else ps.setNull(1, Types.INTEGER);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    // ---- Delete --------------------------------------------------------

    public void delete(long id) throws SQLException {
        synchronized (db) {
            Connection conn = db.getConnection();
            boolean prev = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                exec(conn, "DELETE FROM results WHERE test_case_id = ?", id);
                exec(conn, "DELETE FROM baselines WHERE test_case_id = ?", id);
                exec(conn, "DELETE FROM test_cases WHERE id = ?", id);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<TestCaseRow> query(String where, Binder binder) throws SQLException {
        String sql = """
            SELECT tc.id, tc.name, tc.method, tc.host, tc.url, tc.port,
                   tc.is_https, tc.is_state_changing, tc.folder_id, tc.owner_acct_id,
                   a.name AS owner_name,
                   b.status AS bl_status, b.length AS bl_length,
                   tc.created_at, tc.notes, tc.color_tag
            FROM test_cases tc
            LEFT JOIN accounts a ON a.id = tc.owner_acct_id
            LEFT JOIN baselines b ON b.id = tc.primary_baseline_id
            WHERE %s
            ORDER BY tc.created_at DESC
            """.formatted(where);
        List<TestCaseRow> rows = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long fi = rs.getLong("folder_id");
                    Long folderId = rs.wasNull() ? null : fi;
                    long oi = rs.getLong("owner_acct_id");
                    Long ownerId = rs.wasNull() ? null : oi;
                    int blStatus = rs.getInt("bl_status");
                    Integer blStatusVal = rs.wasNull() ? null : blStatus;
                    int blLen = rs.getInt("bl_length");
                    Integer blLenVal = rs.wasNull() ? null : blLen;
                    rows.add(new TestCaseRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("method"),
                        rs.getString("host"),
                        rs.getString("url"),
                        rs.getInt("port"),
                        rs.getInt("is_https") == 1,
                        rs.getInt("is_state_changing") == 1,
                        folderId,
                        ownerId,
                        rs.getString("owner_name"),
                        blStatusVal,
                        blLenVal,
                        rs.getLong("created_at"),
                        rs.getString("notes"),
                        rs.getString("color_tag")
                    ));
                }
            }
        }
        return rows;
    }

    private static void exec(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static String autoName(SaveRequest req) {
        String path = req.url();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) path = path.substring(slash + 1);
        return req.method() + " " + (path.isEmpty() ? "/" : path);
    }

    public static boolean isStateChangingMethod(String method) {
        if (method == null) return false;
        return switch (method.toUpperCase()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    public static String sha256(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
