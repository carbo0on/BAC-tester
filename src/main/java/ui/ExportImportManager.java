package ui;

import burp.api.montoya.MontoyaApi;
import com.google.gson.*;
import db.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Handles export of the full library to a .bac.json bundle and
 * multi-file import with folder-conflict resolution and deduplication.
 */
public class ExportImportManager {

    private static final int BUNDLE_VERSION = 1;

    private final MontoyaApi api;
    private final DatabaseManager db;
    private final FolderRepository folderRepo;
    private final TestCaseRepository tcRepo;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-export-import");
        t.setDaemon(true);
        return t;
    });

    public ExportImportManager(MontoyaApi api, DatabaseManager db) {
        this.api = api;
        this.db = db;
        this.folderRepo = new FolderRepository(db);
        this.tcRepo = new TestCaseRepository(db);
    }

    // ---- Export ----------------------------------------------------------

    public void showExportDialog(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Library");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("BAC Bundle (*.bac.json)", "json"));
        chooser.setSelectedFile(new File("bac-library-" + LocalDate.now() + ".bac.json"));

        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File outFile = chooser.getSelectedFile();
        // Normalise extension to .bac.json
        String fname = outFile.getName();
        if (!fname.toLowerCase().endsWith(".json")) {
            outFile = new File(outFile.getParentFile(), fname + ".bac.json");
        }
        final File target = outFile;

        if (target.exists()) {
            int ow = JOptionPane.showConfirmDialog(parent,
                "File already exists. Overwrite?\n" + target.getName(),
                "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
            if (ow != JOptionPane.YES_OPTION) return;
        }

        executor.submit(() -> {
            try {
                JsonObject bundle = buildBundle();
                Files.writeString(target.toPath(), gson.toJson(bundle));
                int tcCount = bundle.getAsJsonArray("testCases").size();
                int fCount  = bundle.getAsJsonArray("folders").size();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(parent,
                        "Exported " + tcCount + " test case(s) and " + fCount + " folder(s) to:\n"
                            + target.getAbsolutePath(),
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e) {
                api.logging().logToError("[BAC] Export failed: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(parent, "Export failed: " + e.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private JsonObject buildBundle() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("version", BUNDLE_VERSION);
        root.addProperty("exportedAt", Instant.now().toString());

        // Folders
        JsonArray foldersArr = new JsonArray();
        for (var f : folderRepo.getAllFolders()) {
            JsonObject fo = new JsonObject();
            fo.addProperty("id", f.id());
            fo.addProperty("name", f.name());
            fo.add("parentId", f.parentId() != null ? new JsonPrimitive(f.parentId()) : JsonNull.INSTANCE);
            fo.addProperty("sortOrder", f.sortOrder());
            foldersArr.add(fo);
        }
        root.add("folders", foldersArr);

        // Test cases + all versioned baselines
        JsonArray tcArr = new JsonArray();
        for (var tc : tcRepo.getAll()) {
            JsonObject tco = new JsonObject();
            tco.addProperty("id", tc.id());
            tco.add("name", tc.name() != null ? new JsonPrimitive(tc.name()) : JsonNull.INSTANCE);
            tco.addProperty("method", tc.method());
            tco.addProperty("url", tc.url());
            tco.addProperty("host", tc.host());
            tco.addProperty("port", tc.port());
            tco.addProperty("isHttps", tc.isHttps());
            tco.addProperty("isStateChanging", tc.isStateChanging());
            tco.add("folderId", tc.folderId() != null ? new JsonPrimitive(tc.folderId()) : JsonNull.INSTANCE);
            tco.add("notes", tc.notes() != null ? new JsonPrimitive(tc.notes()) : JsonNull.INSTANCE);
            tco.add("colorTag", tc.colorTag() != null ? new JsonPrimitive(tc.colorTag()) : JsonNull.INSTANCE);

            byte[] reqRaw = tcRepo.getRequestRaw(tc.id());
            tco.addProperty("requestRaw", reqRaw != null ? Base64.getEncoder().encodeToString(reqRaw) : "");

            Optional<Long> primaryId = tcRepo.getPrimaryBaselineId(tc.id());
            JsonArray blArr = new JsonArray();
            for (var bl : tcRepo.getBaselines(tc.id())) {
                JsonObject blo = new JsonObject();
                blo.add("label", bl.label() != null ? new JsonPrimitive(bl.label()) : JsonNull.INSTANCE);
                blo.add("status", bl.status() != null ? new JsonPrimitive(bl.status()) : JsonNull.INSTANCE);
                blo.add("length", bl.length() != null ? new JsonPrimitive(bl.length()) : JsonNull.INSTANCE);
                blo.addProperty("capturedAt", bl.capturedAt());
                blo.addProperty("isPrimary", primaryId.isPresent() && primaryId.get() == bl.id());
                blo.addProperty("responseRaw",
                    bl.responseRaw() != null ? Base64.getEncoder().encodeToString(bl.responseRaw()) : "");
                blArr.add(blo);
            }
            tco.add("baselines", blArr);
            tcArr.add(tco);
        }
        root.add("testCases", tcArr);
        return root;
    }

    // ---- Import ----------------------------------------------------------

    public void showImportDialog(Component parent, Consumer<Integer> onDone) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Library — select one or more .bac.json files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("BAC Bundle (*.bac.json, *.json)", "json"));

        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;

        String[] strategies = {"Merge into existing folders", "Isolate under Imported/<filename>/"};
        int choice = JOptionPane.showOptionDialog(parent,
            "How should folders be handled?", "Import — Folder Strategy",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, strategies, strategies[0]);
        if (choice < 0) return; // user cancelled
        boolean isolate = choice == 1;

        boolean dedup = !"false".equalsIgnoreCase(readSetting("dedup_on_import", "true"));

        executor.submit(() -> {
            int total = 0, skipped = 0;
            StringBuilder errors = new StringBuilder();
            for (File f : files) {
                try {
                    int[] res = importFile(f, isolate, dedup);
                    total += res[0];
                    skipped += res[1];
                } catch (Exception e) {
                    api.logging().logToError("[BAC] Import error for " + f.getName() + ": " + e.getMessage());
                    errors.append("\n• ").append(f.getName()).append(": ").append(e.getMessage());
                }
            }
            final int imported = total;
            final int dups = skipped;
            final String errMsg = errors.toString();
            SwingUtilities.invokeLater(() -> {
                if (errMsg.isEmpty()) {
                    JOptionPane.showMessageDialog(parent,
                        "Imported " + imported + " test case(s), skipped " + dups + " duplicate(s).",
                        "Import Complete", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(parent,
                        "Imported " + imported + " test case(s), skipped " + dups + " duplicate(s)."
                            + "\n\nSome files had errors:" + errMsg,
                        "Import Finished (with warnings)", JOptionPane.WARNING_MESSAGE);
                }
                if (onDone != null) onDone.accept(imported);
            });
        });
    }

    /** @return [importedCount, skippedDuplicateCount] */
    private int[] importFile(File file, boolean isolate, boolean dedup) throws Exception {
        String json = Files.readString(file.toPath());
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) throw new IllegalArgumentException("Not a valid JSON bundle");

        JsonArray foldersArr = root.has("folders") ? root.getAsJsonArray("folders") : new JsonArray();
        JsonArray tcArr      = root.has("testCases") ? root.getAsJsonArray("testCases") : new JsonArray();

        // Create isolation root folder if requested
        Long isolationRoot = null;
        if (isolate) {
            String stem = file.getName().replaceAll("\\.bac\\.json$|\\.json$", "");
            isolationRoot = folderRepo.createFolder("Imported/" + stem, null);
        }

        // Import folders, building old-ID → new-ID map
        Map<Long, Long> folderIdMap = new HashMap<>();
        List<JsonObject> folderList = new ArrayList<>();
        for (var e : foldersArr) folderList.add(e.getAsJsonObject());
        importFolders(folderList, folderIdMap, isolationRoot, isolate);

        // Pre-load existing dedup keys once (host|method|url|reqHash) for performance
        Set<String> existing = dedup ? loadDedupKeys() : Collections.emptySet();

        int count = 0, skipped = 0;
        for (var e : tcArr) {
            try {
                int r = importTestCase(e.getAsJsonObject(), folderIdMap, isolationRoot, dedup, existing);
                if (r == 1) count++; else if (r == 0) skipped++;
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Skip TC during import: " + ex.getMessage());
            }
        }
        return new int[]{count, skipped};
    }

    /**
     * Import folders with old→new ID remapping.
     * In merge mode, an existing folder with the same (name, parent) is reused
     * instead of creating a duplicate. In isolate mode everything is recreated
     * under the isolation root.
     */
    private void importFolders(List<JsonObject> list, Map<Long, Long> idMap,
                               Long isolationRoot, boolean isolate) throws Exception {
        // Index of existing folders by parentId + " " + name (merge mode only)
        Map<String, Long> existingByKey = new HashMap<>();
        if (!isolate) {
            for (var f : folderRepo.getAllFolders()) {
                existingByKey.put(folderKey(f.parentId(), f.name()), f.id());
            }
        }

        Set<Long> done = new HashSet<>();
        // Iterate until all folders whose parents are resolvable have been created
        for (int pass = 0; pass <= list.size() && done.size() < list.size(); pass++) {
            for (var fo : list) {
                long oldId = fo.get("id").getAsLong();
                if (done.contains(oldId)) continue;
                Long oldParent = fo.get("parentId").isJsonNull() ? null : fo.get("parentId").getAsLong();
                Long newParent;
                if (oldParent == null) {
                    newParent = isolationRoot;
                } else if (idMap.containsKey(oldParent)) {
                    newParent = idMap.get(oldParent);
                } else {
                    continue; // parent not yet processed
                }

                String name = fo.get("name").getAsString();
                Long newId;
                String key = folderKey(newParent, name);
                if (!isolate && existingByKey.containsKey(key)) {
                    newId = existingByKey.get(key); // merge: reuse existing
                } else {
                    newId = folderRepo.createFolder(name, newParent);
                    existingByKey.put(key, newId);
                }
                idMap.put(oldId, newId);
                done.add(oldId);
            }
        }
    }

    private static String folderKey(Long parentId, String name) {
        return (parentId == null ? "root" : parentId) + " " + (name == null ? "" : name);
    }

    /** @return 1 = imported, 0 = skipped duplicate, -1 = error */
    private int importTestCase(JsonObject tco, Map<Long, Long> folderIdMap,
                               Long isolationRoot, boolean dedup, Set<String> existing) throws Exception {
        String method = tco.get("method").getAsString();
        String url    = tco.get("url").getAsString();
        String host   = tco.get("host").getAsString();
        int    port   = tco.get("port").getAsInt();
        boolean https = tco.get("isHttps").getAsBoolean();
        String name   = str(tco, "name");
        String notes  = str(tco, "notes");
        String color  = str(tco, "colorTag");

        byte[] reqRaw = decodeB64(tco.has("requestRaw") ? tco.get("requestRaw").getAsString() : "");
        String reqHash = TestCaseRepository.sha256(reqRaw);

        // Deduplication check (against pre-loaded set, includes items added this run)
        if (dedup) {
            String key = dedupKey(host, method, url, reqHash);
            if (existing.contains(key)) {
                api.logging().logToOutput("[BAC] Import: duplicate skipped — " + method + " " + url);
                return 0;
            }
            existing.add(key);
        }

        // Resolve folder
        Long oldFolderId = tco.has("folderId") && !tco.get("folderId").isJsonNull()
            ? tco.get("folderId").getAsLong() : null;
        Long newFolderId = oldFolderId == null
            ? isolationRoot
            : folderIdMap.getOrDefault(oldFolderId, isolationRoot);

        // Find primary baseline (marked isPrimary, or last in list)
        JsonArray blArr = tco.has("baselines") ? tco.getAsJsonArray("baselines") : new JsonArray();
        JsonObject primaryBl = null;
        for (var e : blArr) {
            JsonObject bl = e.getAsJsonObject();
            if (bl.has("isPrimary") && bl.get("isPrimary").getAsBoolean()) { primaryBl = bl; break; }
        }
        if (primaryBl == null && blArr.size() > 0)
            primaryBl = blArr.get(blArr.size() - 1).getAsJsonObject();

        byte[] primaryResp = primaryBl != null ? decodeB64(str(primaryBl, "responseRaw")) : new byte[0];
        int primaryStatus  = primaryBl != null && has(primaryBl, "status") ? primaryBl.get("status").getAsInt() : 0;
        int primaryLength  = primaryBl != null && has(primaryBl, "length") ? primaryBl.get("length").getAsInt() : 0;

        long newTcId = tcRepo.save(new TestCaseRepository.SaveRequest(
            name, notes, newFolderId, null,
            method, url, host, port, https,
            reqRaw, primaryResp, primaryStatus, primaryLength
        ));

        if (color != null) tcRepo.setColorTag(newTcId, color);

        // Import remaining baselines (old ones NOT deleted — versioning invariant)
        for (var e : blArr) {
            JsonObject bl = e.getAsJsonObject();
            if (bl == primaryBl) continue; // already saved as the initial baseline
            String blLabel = str(bl, "label");
            byte[] resp = decodeB64(str(bl, "responseRaw"));
            int st  = has(bl, "status") ? bl.get("status").getAsInt() : 0;
            int len = has(bl, "length") ? bl.get("length").getAsInt() : resp.length;
            tcRepo.addBaseline(newTcId, null, blLabel != null ? blLabel : "imported", st, len, resp);
        }
        return 1;
    }

    private Set<String> loadDedupKeys() throws Exception {
        Set<String> keys = new HashSet<>();
        for (var tc : tcRepo.getAll()) {
            byte[] raw = tcRepo.getRequestRaw(tc.id());
            String hash = raw != null ? TestCaseRepository.sha256(raw) : "";
            keys.add(dedupKey(tc.host(), tc.method(), tc.url(), hash));
        }
        return keys;
    }

    private static String dedupKey(String host, String method, String url, String reqHash) {
        return host + "|" + method + "|" + url + "|" + reqHash;
    }

    private String readSetting(String key, String def) {
        try {
            String v = db.getSetting(key);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static byte[] decodeB64(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        try { return Base64.getDecoder().decode(s); } catch (Exception e) { return new byte[0]; }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static boolean has(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }
}
