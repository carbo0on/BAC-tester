package db;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** CRUD for the folders table (folder tree used by Library tab). */
public class FolderRepository {

    private final DatabaseManager db;

    public FolderRepository(DatabaseManager db) {
        this.db = db;
    }

    public record FolderRecord(long id, String name, Long parentId, int sortOrder) {}

    public synchronized long createFolder(String name, Long parentId) throws SQLException {
        String sql = "INSERT INTO folders (name, parent_id, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            if (parentId != null) ps.setLong(2, parentId); else ps.setNull(2, Types.INTEGER);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized List<FolderRecord> getAllFolders() throws SQLException {
        String sql = "SELECT id, name, parent_id, sort_order FROM folders ORDER BY sort_order, name";
        List<FolderRecord> result = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                long pid = rs.getLong("parent_id");
                result.add(new FolderRecord(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.wasNull() ? null : pid,
                    rs.getInt("sort_order")
                ));
            }
        }
        return result;
    }

    public synchronized void renameFolder(long id, String newName) throws SQLException {
        String sql = "UPDATE folders SET name = ? WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteFolder(long id) throws SQLException {
        String sql = "DELETE FROM folders WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized int countInFolder(long folderId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM test_cases WHERE folder_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized int countInbox() throws SQLException {
        String sql = "SELECT COUNT(*) FROM test_cases WHERE folder_id IS NULL";
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
