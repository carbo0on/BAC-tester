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

    public record FolderRecord(long id, String name, Long parentId, int sortOrder, String color) {}

    public long createFolder(String name, Long parentId) throws SQLException {
        synchronized (db) {
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
    }

    public List<FolderRecord> getAllFolders() throws SQLException {
        synchronized (db) {
            String sql = "SELECT id, name, parent_id, sort_order, color FROM folders ORDER BY sort_order, name";
            List<FolderRecord> result = new ArrayList<>();
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    long pid = rs.getLong("parent_id");
                    result.add(new FolderRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.wasNull() ? null : pid,
                        rs.getInt("sort_order"),
                        rs.getString("color")
                    ));
                }
            }
            return result;
        }
    }

    public void renameFolder(long id, String newName) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE folders SET name = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, newName);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public void updateParent(long id, Long newParentId, int sortOrder) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE folders SET parent_id = ?, sort_order = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                if (newParentId != null) ps.setLong(1, newParentId); else ps.setNull(1, Types.INTEGER);
                ps.setInt(2, sortOrder);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    /** Set (or clear with null) a folder's color tag, e.g. "RED"/"GREEN"/"BLUE". */
    public void setColor(long id, String color) throws SQLException {
        synchronized (db) {
            String sql = "UPDATE folders SET color = ? WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, color);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public void deleteFolder(long id) throws SQLException {
        synchronized (db) {
            String sql = "DELETE FROM folders WHERE id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    public int countInFolder(long folderId) throws SQLException {
        synchronized (db) {
            String sql = "SELECT COUNT(*) FROM test_cases WHERE folder_id = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setLong(1, folderId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    public int countInbox() throws SQLException {
        synchronized (db) {
            String sql = "SELECT COUNT(*) FROM test_cases WHERE folder_id IS NULL";
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
