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
        long createdAt,
        long updatedAt
    ) {
        /** Display label for dropdowns. */
        public String label() {
            String role = roleDesc != null && !roleDesc.isBlank() ? " (" + roleDesc + ")" : "";
            return name + role;
        }
    }

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
            String sql = "DELETE FROM accounts WHERE id=?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    // ---- Read ----------------------------------------------------------

    public List<AccountRecord> getAll() throws SQLException {
        synchronized (db) {
            String sql = """
                SELECT id, name, role_desc, auth_material, expected_access,
                       canary_request_id, created_at, updated_at
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
                       canary_request_id, created_at, updated_at
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

        return new AccountRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("role_desc"),
            cookies,
            headers,
            rs.getString("expected_access"),
            canaryId,
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}
