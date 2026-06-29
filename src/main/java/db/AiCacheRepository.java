package db;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Lookup table for the AI auto-organizer. Keyed by a normalized endpoint
 * signature (method + host + templated path), it remembers the folder, name and
 * description previously chosen for that endpoint so subsequent similar requests
 * are grouped instantly without another API call. This is what keeps AI usage
 * low: each distinct endpoint costs at most one classification.
 */
public class AiCacheRepository {

    private final DatabaseManager db;

    public AiCacheRepository(DatabaseManager db) {
        this.db = db;
    }

    public record CacheEntry(String signature, Long folderId, String folderPath,
                             String name, String description) {}

    public Optional<CacheEntry> lookup(String signature) throws SQLException {
        if (signature == null) return Optional.empty();
        synchronized (db) {
            String sql = "SELECT signature, folder_id, folder_path, name, description "
                + "FROM ai_endpoint_cache WHERE signature = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, signature);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    long fid = rs.getLong("folder_id");
                    Long folderId = rs.wasNull() ? null : fid;
                    return Optional.of(new CacheEntry(
                        rs.getString("signature"),
                        folderId,
                        rs.getString("folder_path"),
                        rs.getString("name"),
                        rs.getString("description")));
                }
            }
        }
    }

    public void put(String signature, Long folderId, String folderPath,
                    String name, String description) throws SQLException {
        if (signature == null) return;
        synchronized (db) {
            String sql = "INSERT OR REPLACE INTO ai_endpoint_cache "
                + "(signature, folder_id, folder_path, name, description, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, signature);
                if (folderId != null) ps.setLong(2, folderId); else ps.setNull(2, Types.INTEGER);
                ps.setString(3, folderPath);
                ps.setString(4, name);
                ps.setString(5, description);
                ps.setLong(6, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }
        }
    }

    /** Clears the whole cache (e.g. when the user wants AI to re-classify everything). */
    public void clear() throws SQLException {
        synchronized (db) {
            try (Statement st = db.getConnection().createStatement()) {
                st.executeUpdate("DELETE FROM ai_endpoint_cache");
            }
        }
    }
}
