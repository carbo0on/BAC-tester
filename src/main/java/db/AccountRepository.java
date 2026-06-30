package db;

import com.google.gson.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * CRUD for the accounts table.
 * Auth material is stored as JSON: {"cookies":{...},"headers":{...}}
 */
public class AccountRepository {

    private final DatabaseManager db;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public AccountRepository(DatabaseManager db) {
        this.db = db;
    }

    // ---- Records -------------------------------------------------------

    public record AccountRecord(
        long id,
        String name,
        String roleDesc,
        Map<String, String> cookies,
        Map<String, String> headers,
        String expectedAccess,      // ALLOWED / DENIED / UNKNOWN
        Long canaryRequestId,
        Long folderId,              // NULL = Uncategorized
        long createdAt,
        long updatedAt
    ) {
        /** Display label for dropdowns. */
        public String label() {
            String role = roleDesc != null && !roleDesc.isBlank() ? " (" + roleDesc + ")" : "";
            return name + role;
        }
    }

    public record AccountFolder(long id, String name, String color) {}

    // ---- Write ---------------------------------------------------------

    public long create(String name, String roleDesc,
                       Map<String, String> cookies,
                       Map<String, String> headers,
                       String expectedAccess) throws SQLException {
        synchronized (db) {
            long now = Instant.now().getEpochSecond();
            String sql = """
                INSERT INTO accounts (name, role_desc, auth_material, expected_access, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, roleDesc);
                ps.setString(3, serialize(cookies, headers));
                ps.setString(4, expectedAccess != null ? expectedAccess : "UNKNOWN");
                ps.setLong(5, now);
                ps.setLong(6, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    public void update(long id, String name, String roleDesc,
                       Map<String, String> cookies,
                       Map<String, String> headers,
                       String expectedAccess) throws SQLException {
        synchronized (db) {
            String sql = """
                UPDATE accounts SET name=?, role_desc=?, auth_material=?,
                                    expected_access=?, updated_at=?
                WHERE id=?
                """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, roleDesc);
                ps.setString(3, serialize(cookies, headers));
                ps.setString(4, expectedAccess != null ? expectedAccess : "UNKNOWN");
                ps.setLong(5, Instant.now().getEpochSecond());
                ps.setLong(6, id);
                ps.executeUpdate();
            }
        }
    }

    public void setCanary(long accountId, Long testCaseId) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE accounts SET canary_request_id=?, updated_at=? WHERE id=?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                if (testCaseId != null) ps.setLong(1, testCaseId); else ps.setNull(1, Types.INTEGER);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, accountId);
                ps.executeUpdate();
            }
        }
    }

    public void delete(long id) throws SQLException {
        synchronized (db) {
            // foreign_keys=ON means an account that is still referenced (it owns
            // test cases, or has baselines / runs / results) cannot be deleted
            // directly — that was why "certain accounts" refused to delete. Null
            // out every reference first, in one transaction, then remove the row.
            Connection conn = db.getConnection();
            boolean prev = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                String[] clears = {
                    "UPDATE test_cases SET owner_acct_id = NULL WHERE owner_acct_id = ?",
                    "UPDATE baselines  SET account_id    = NULL WHERE account_id    = ?",
                    "UPDATE runs       SET account_id    = NULL WHERE account_id    = ?",
                    "UPDATE results    SET account_id    = NULL WHERE account_id    = ?"
                };
                for (String sql : clears) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, id); ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE id=?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        }
    }

    // ---- Read ----------------------------------------------------------

    public List<AccountRecord> getAll() throws SQLException {
        synchronized (db) {
            String sql = """
                SELECT id, name, role_desc, auth_material, expected_access,
                       canary_request_id, folder_id, created_at, updated_at
                FROM accounts ORDER BY name
                """;
            List<AccountRecord> result = new ArrayList<>();
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
            return result;
        }
    }

    public Optional<AccountRecord> getById(long id) throws SQLException {
        synchronized (db) {
            String sql = """
                SELECT id, name, role_desc, auth_material, expected_access,
                       canary_request_id, folder_id, created_at, updated_at
                FROM accounts WHERE id=?
                """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        }
    }

    public void moveToFolder(long accountId, Long folderId) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE accounts SET folder_id=?, updated_at=? WHERE id=?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                if (folderId != null) ps.setLong(1, folderId); else ps.setNull(1, Types.INTEGER);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.setLong(3, accountId);
                ps.executeUpdate();
            }
        }
    }

    // ---- Account folders -----------------------------------------------

    public long createFolder(String name) throws SQLException {
        synchronized (db) {
            String sql = "INSERT INTO account_folders (name, created_at) VALUES (?, ?)";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
            }
        }
    }

    public List<AccountFolder> getFolders() throws SQLException {
        synchronized (db) {
            String sql = "SELECT id, name, color FROM account_folders ORDER BY name";
            List<AccountFolder> result = new ArrayList<>();
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next())
                    result.add(new AccountFolder(rs.getLong("id"), rs.getString("name"), rs.getString("color")));
            }
            return result;
        }
    }

    public void renameFolder(long id, String name) throws SQLException {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE account_folders SET name=? WHERE id=?")) {
                ps.setString(1, name); ps.setLong(2, id); ps.executeUpdate();
            }
        }
    }

    public void setFolderColor(long id, String color) throws SQLException {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE account_folders SET color=? WHERE id=?")) {
                ps.setString(1, color); ps.setLong(2, id); ps.executeUpdate();
            }
        }
    }

    /** Deletes a folder and moves its accounts back to Uncategorized. */
    public void deleteFolder(long id) throws SQLException {
        synchronized (db) {
            Connection conn = db.getConnection();
            boolean prev = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET folder_id=NULL WHERE folder_id=?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM account_folders WHERE id=?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        }
    }

    /** Creates an account with an explicit folder (used by import). Returns new id. */
    public long createInFolder(String name, String roleDesc,
                               Map<String, String> cookies, Map<String, String> headers,
                               String expectedAccess, Long folderId) throws SQLException {
        long id = create(name, roleDesc, cookies, headers, expectedAccess);
        if (folderId != null) moveToFolder(id, folderId);
        return id;
    }

    // ---- Session extraction from an HTTP request -----------------------

    /**
     * Extracts cookies and session-relevant headers from a raw Cookie header value
     * and the full headers list. Returns a pair (cookies, headers).
     */
    public static Map.Entry<Map<String, String>, Map<String, String>> extractSession(
            String cookieHeaderValue,
            List<burp.api.montoya.http.message.HttpHeader> allHeaders) {

        // --- Cookies ---
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeaderValue != null && !cookieHeaderValue.isBlank()) {
            for (String part : cookieHeaderValue.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    cookies.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        }

        // --- Session-relevant headers ---
        Set<String> SESSION_HEADERS = Set.of(
            "authorization", "x-auth-token", "x-session-token",
            "x-access-token", "x-api-key", "token"
        );
        Map<String, String> headers = new LinkedHashMap<>();
        for (var h : allHeaders) {
            if (SESSION_HEADERS.contains(h.name().toLowerCase())) {
                headers.put(h.name(), h.value());
            }
        }

        return Map.entry(cookies, headers);
    }

    // ---- Serialization -------------------------------------------------

    public static String serialize(Map<String, String> cookies, Map<String, String> headers) {
        JsonObject obj = new JsonObject();
        JsonObject c = new JsonObject();
        cookies.forEach(c::addProperty);
        JsonObject h = new JsonObject();
        headers.forEach(h::addProperty);
        obj.add("cookies", c);
        obj.add("headers", h);
        return GSON.toJson(obj);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> deserializeMap(JsonObject obj, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        if (obj == null || !obj.has(key)) return result;
        JsonElement el = obj.get(key);
        if (el.isJsonObject()) {
            for (var entry : el.getAsJsonObject().entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    // ---- Helpers -------------------------------------------------------

    private AccountRecord mapRow(ResultSet rs) throws SQLException {
        String authMaterial = rs.getString("auth_material");
        JsonObject json = authMaterial != null
            ? GSON.fromJson(authMaterial, JsonObject.class)
            : new JsonObject();
        Map<String, String> cookies = deserializeMap(json, "cookies");
        Map<String, String> headers = deserializeMap(json, "headers");

        long crid = rs.getLong("canary_request_id");
        Long canaryId = rs.wasNull() ? null : crid;
        long fid = rs.getLong("folder_id");
        Long folderId = rs.wasNull() ? null : fid;

        return new AccountRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("role_desc"),
            cookies,
            headers,
            rs.getString("expected_access"),
            canaryId,
            folderId,
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}
