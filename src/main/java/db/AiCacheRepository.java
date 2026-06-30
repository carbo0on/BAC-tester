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
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /**
     * Coarse grouping lookup: returns the folder previously chosen for any
     * endpoint sharing this {@code featureKey} (host + base path), most recent
     * first. This lets a new endpoint join an existing feature folder with ZERO
     * API calls — e.g. /api/users/123 reuses the folder already chosen for
     * /api/users/list. The returned entry's name/description belong to the prior
     * endpoint, so callers should only reuse its folder, not its name.
     */
    public Optional<CacheEntry> lookupByFeatureKey(String featureKey) throws SQLException {
        if (featureKey == null || featureKey.isBlank()) return Optional.empty();
        synchronized (db) {
            String sql = "SELECT signature, folder_id, folder_path, name, description "
                + "FROM ai_endpoint_cache WHERE feature_key = ? AND folder_id IS NOT NULL "
                + "ORDER BY created_at DESC LIMIT 1";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, featureKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    private static CacheEntry map(ResultSet rs) throws SQLException {
        long fid = rs.getLong("folder_id");
        Long folderId = rs.wasNull() ? null : fid;
        return new CacheEntry(
            rs.getString("signature"),
            folderId,
            rs.getString("folder_path"),
            rs.getString("name"),
            rs.getString("description"));
    }

    public void put(String signature, String featureKey, Long folderId, String folderPath,
                    String name, String description) throws SQLException {
        if (signature == null) return;
        synchronized (db) {
            String sql = "INSERT OR REPLACE INTO ai_endpoint_cache "
                + "(signature, feature_key, folder_id, folder_path, name, description, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, signature);
                ps.setString(2, featureKey);
                if (folderId != null) ps.setLong(3, folderId); else ps.setNull(3, Types.INTEGER);
                ps.setString(4, folderPath);
                ps.setString(5, name);
                ps.setString(6, description);
                ps.setLong(7, Instant.now().getEpochSecond());
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
