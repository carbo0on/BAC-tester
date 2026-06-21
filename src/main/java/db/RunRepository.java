package db;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** CRUD for the runs and results tables. */
public class RunRepository {

    private final DatabaseManager db;

    public RunRepository(DatabaseManager db) {
        this.db = db;
    }

    // ---- Records -------------------------------------------------------

    public record RunRecord(
        long id,
        long accountId,
        String accountName,
        long startedAt,
        Long finishedAt,
        int totalCases,
        boolean canaryOk
    ) {}

    public record ResultRecord(
        long id,
        long runId,
        long testCaseId,
        String testCaseName,
        String method,
        String host,
        String url,
        long accountId,
        String accountName,
        Long comparedBaselineId,
        String expectedAccess,
        int newStatus,
        int newLength,
        String newBodyHash,
        byte[] newResponseRaw,
        double similarity,
        String verdict,
        boolean reviewed,
        String userNote,
        long createdAt
    ) {}

    // ---- Runs ----------------------------------------------------------

    public synchronized long createRun(long accountId, int totalCases, boolean canaryOk) throws SQLException {
        long now = Instant.now().getEpochSecond();
        String sql = """
            INSERT INTO runs (account_id, started_at, total_cases, canary_ok)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, accountId);
            ps.setLong(2, now);
            ps.setInt(3, totalCases);
            ps.setInt(4, canaryOk ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized void finishRun(long runId) throws SQLException {
        String sql = "UPDATE runs SET finished_at = ? WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    public synchronized List<RunRecord> getAllRuns() throws SQLException {
        String sql = """
            SELECT r.id, r.account_id, a.name AS account_name,
                   r.started_at, r.finished_at, r.total_cases, r.canary_ok
            FROM runs r
            LEFT JOIN accounts a ON a.id = r.account_id
            ORDER BY r.started_at DESC
            """;
        List<RunRecord> result = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                long fi = rs.getLong("finished_at");
                result.add(new RunRecord(
                    rs.getLong("id"),
                    rs.getLong("account_id"),
                    rs.getString("account_name"),
                    rs.getLong("started_at"),
                    rs.wasNull() ? null : fi,
                    rs.getInt("total_cases"),
                    rs.getInt("canary_ok") == 1
                ));
            }
        }
        return result;
    }

    // ---- Results -------------------------------------------------------

    public synchronized long saveResult(
            long runId, long testCaseId, long accountId,
            Long comparedBaselineId, String expectedAccess,
            int newStatus, int newLength, String newBodyHash,
            byte[] newResponseRaw, double similarity, String verdict) throws SQLException {
        long now = Instant.now().getEpochSecond();
        String sql = """
            INSERT INTO results
                (run_id, test_case_id, account_id, compared_baseline_id,
                 expected_access, new_status, new_length, new_body_hash,
                 new_response_raw, similarity, verdict, reviewed, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, runId);
            ps.setLong(2, testCaseId);
            ps.setLong(3, accountId);
            if (comparedBaselineId != null) ps.setLong(4, comparedBaselineId); else ps.setNull(4, Types.INTEGER);
            ps.setString(5, expectedAccess);
            ps.setInt(6, newStatus);
            ps.setInt(7, newLength);
            ps.setString(8, newBodyHash);
            ps.setBytes(9, newResponseRaw);
            ps.setDouble(10, similarity);
            ps.setString(11, verdict);
            ps.setLong(12, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized List<ResultRecord> getResultsForTestCase(long testCaseId) throws SQLException {
        String sql = """
            SELECT r.id, r.run_id, r.test_case_id, tc.name AS tc_name,
                   tc.method, tc.host, tc.url,
                   r.account_id, a.name AS account_name,
                   r.compared_baseline_id, r.expected_access,
                   r.new_status, r.new_length, r.new_body_hash, r.new_response_raw,
                   r.similarity, r.verdict, r.reviewed, r.user_note, r.created_at
            FROM results r
            LEFT JOIN test_cases tc ON tc.id = r.test_case_id
            LEFT JOIN accounts a ON a.id = r.account_id
            WHERE r.test_case_id = ?
            ORDER BY r.created_at DESC
            """;
        List<ResultRecord> result = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, testCaseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long blId = rs.getLong("compared_baseline_id");
                    result.add(new ResultRecord(
                        rs.getLong("id"),
                        rs.getLong("run_id"),
                        rs.getLong("test_case_id"),
                        rs.getString("tc_name"),
                        rs.getString("method"),
                        rs.getString("host"),
                        rs.getString("url"),
                        rs.getLong("account_id"),
                        rs.getString("account_name"),
                        rs.wasNull() ? null : blId,
                        rs.getString("expected_access"),
                        rs.getInt("new_status"),
                        rs.getInt("new_length"),
                        rs.getString("new_body_hash"),
                        rs.getBytes("new_response_raw"),
                        rs.getDouble("similarity"),
                        rs.getString("verdict"),
                        rs.getInt("reviewed") == 1,
                        rs.getString("user_note"),
                        rs.getLong("created_at")
                    ));
                }
            }
        }
        return result;
    }

    public synchronized void markReviewed(long resultId, boolean reviewed, String note) throws SQLException {
        String sql = "UPDATE results SET reviewed = ?, user_note = ? WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, reviewed ? 1 : 0);
            ps.setString(2, note);
            ps.setLong(3, resultId);
            ps.executeUpdate();
        }
    }

    public synchronized List<ResultRecord> getResultsForRun(long runId) throws SQLException {
        String sql = """
            SELECT r.id, r.run_id, r.test_case_id, tc.name AS tc_name,
                   tc.method, tc.host, tc.url,
                   r.account_id, a.name AS account_name,
                   r.compared_baseline_id, r.expected_access,
                   r.new_status, r.new_length, r.new_body_hash, r.new_response_raw,
                   r.similarity, r.verdict, r.reviewed, r.user_note, r.created_at
            FROM results r
            LEFT JOIN test_cases tc ON tc.id = r.test_case_id
            LEFT JOIN accounts a ON a.id = r.account_id
            WHERE r.run_id = ?
            ORDER BY r.created_at
            """;
        List<ResultRecord> result = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long blId = rs.getLong("compared_baseline_id");
                    result.add(new ResultRecord(
                        rs.getLong("id"),
                        rs.getLong("run_id"),
                        rs.getLong("test_case_id"),
                        rs.getString("tc_name"),
                        rs.getString("method"),
                        rs.getString("host"),
                        rs.getString("url"),
                        rs.getLong("account_id"),
                        rs.getString("account_name"),
                        rs.wasNull() ? null : blId,
                        rs.getString("expected_access"),
                        rs.getInt("new_status"),
                        rs.getInt("new_length"),
                        rs.getString("new_body_hash"),
                        rs.getBytes("new_response_raw"),
                        rs.getDouble("similarity"),
                        rs.getString("verdict"),
                        rs.getInt("reviewed") == 1,
                        rs.getString("user_note"),
                        rs.getLong("created_at")
                    ));
                }
            }
        }
        return result;
    }
}
