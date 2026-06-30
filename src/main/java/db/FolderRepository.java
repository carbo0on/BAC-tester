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

    /**
     * Resolves a "/"-separated folder path (e.g. "Authentication/Login") to a
     * leaf folder id, creating any missing folders along the way. Empty or null
     * paths return {@code null} (Inbox). Used by the AI auto-organizer so it can
     * place a request into a nested function folder in one call.
     */
    public Long findOrCreatePath(String path) throws SQLException {
        if (path == null) return null;
        List<String> segments = new ArrayList<>();
        for (String seg : path.split("/")) {
            String s = seg.trim();
            if (!s.isEmpty()) segments.add(s);
        }
        if (segments.isEmpty()) return null;

        synchronized (db) {
            List<FolderRecord> all = getAllFolders();
            Long parentId = null;
            for (String name : segments) {
                Long match = null;
                for (FolderRecord fr : all) {
                    if (Objects.equals(fr.parentId(), parentId) && name.equalsIgnoreCase(fr.name())) {
                        match = fr.id();
                        break;
                    }
                }
                if (match == null) {
                    long created = createFolder(name, parentId);
                    // Keep the in-memory snapshot consistent for the next segment.
                    all.add(new FolderRecord(created, name, parentId, 0, null));
                    match = created;
                }
                parentId = match;
            }
            return parentId;
        }
    }

    /**
     * Like {@link #findOrCreatePath(String)} but matches each segment against
     * existing sibling folders <em>canonically</em> (case-, space- and
     * plural-insensitive) before creating a new one. This stops the AI organizer
     * from spawning near-duplicate folders like "Users" / "User" / "user
     * management" that fragment the tree. New folders keep the supplied casing.
     */
    public Long findOrCreatePathCanonical(String path) throws SQLException {
        if (path == null) return null;
        List<String> segments = new ArrayList<>();
        for (String seg : path.split("/")) {
            String s = seg.trim();
            if (!s.isEmpty()) segments.add(s);
        }
        if (segments.isEmpty()) return null;

        synchronized (db) {
            List<FolderRecord> all = getAllFolders();
            Long parentId = null;
            for (String name : segments) {
                String norm = canonical(name);
                Long match = null;
                for (FolderRecord fr : all) {
                    if (Objects.equals(fr.parentId(), parentId) && canonical(fr.name()).equals(norm)) {
                        match = fr.id();
                        break;
                    }
                }
                if (match == null) {
                    long created = createFolder(name, parentId);
                    all.add(new FolderRecord(created, name, parentId, 0, null));
                    match = created;
                }
                parentId = match;
            }
            return parentId;
        }
    }

    /** Normalized folder-name key: lowercase, alphanumerics only, naive singular. */
    public static String canonical(String s) {
        if (s == null) return "";
        String x = s.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (x.length() > 3 && x.endsWith("s") && !x.endsWith("ss")) x = x.substring(0, x.length() - 1);
        return x;
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

    /**
     * Deletes a folder <em>and all its sub-folders</em>, moving every test case
     * found anywhere in that subtree back to the Inbox (folder_id = NULL).
     *
     * <p>The plain {@link #deleteFolder(long)} fails with a foreign-key error when
     * the folder still has children (the AI organizer routinely nests folders),
     * which is why deleting such folders appeared "impossible". This walks the
     * whole subtree and removes it bottom-up inside one transaction so the
     * parent_id self-reference is never left dangling. Returns the ids that were
     * deleted (subtree root first) so callers can update their selection state.
     */
    public List<Long> deleteFolderCascade(long rootId) throws SQLException {
        synchronized (db) {
            // 1. Collect the subtree (root + all descendants) breadth-first.
            List<FolderRecord> all = getAllFolders();
            Map<Long, List<Long>> childrenOf = new HashMap<>();
            for (FolderRecord f : all) {
                if (f.parentId() != null)
                    childrenOf.computeIfAbsent(f.parentId(), k -> new ArrayList<>()).add(f.id());
            }
            List<Long> ordered = new ArrayList<>();   // shallow → deep
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(rootId);
            while (!queue.isEmpty()) {
                Long cur = queue.poll();
                ordered.add(cur);
                List<Long> kids = childrenOf.get(cur);
                if (kids != null) queue.addAll(kids);
            }

            Connection conn = db.getConnection();
            boolean prev = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                // 2. Move every test case in the subtree to the Inbox.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE test_cases SET folder_id = NULL WHERE folder_id = ?")) {
                    for (Long fid : ordered) { ps.setLong(1, fid); ps.addBatch(); }
                    ps.executeBatch();
                }
                // 3. Delete deepest-first so no row's parent is removed before it.
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM folders WHERE id = ?")) {
                    for (int i = ordered.size() - 1; i >= 0; i--) {
                        ps.setLong(1, ordered.get(i)); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                // 4. Drop any AI cache rows that pointed at the removed folders so
                //    future captures get re-classified instead of resurrecting them.
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM ai_endpoint_cache WHERE folder_id = ?")) {
                    for (Long fid : ordered) { ps.setLong(1, fid); ps.addBatch(); }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
            return ordered;
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
