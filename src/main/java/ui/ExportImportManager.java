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
    private final FolderRepository folderRepo;
    private final TestCaseRepository tcRepo;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-export-import");
        t.setDaemon(true);
        return t;
    });

    public ExportImportManager(MontoyaApi api, DatabaseManager db) {
        this.api = api;
        this.folderRepo = new FolderRepository(db);
        this.tcRepo = new TestCaseRepository(db);
    }

    // ---- Export ----------------------------------------------------------

    public void showExportDialog(Component parent) {
        boolean includeAuth = JOptionPane.showConfirmDialog(parent,
            "<html>Include account auth material (cookies / tokens) in export?<br><br>" +
            "<b>WARNING:</b> Auth material contains live session credentials.<br>" +
            "Only include when necessary and keep the file secure.</html>",
            "Export Auth Material?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Library");
        chooser.setFileFilter(new FileNameExtensionFilter("BAC Bundle (*.bac.json)", "bac.json", "json"));
        chooser.setSelectedFile(new File("bac-library-" + LocalDate.now() + ".bac.json"));

        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File outFile = chooser.getSelectedFile();
        if (!outFile.getName().contains(".bac.json") && !outFile.getName().endsWith(".json")) {
            outFile = new File(outFile.getParentFile(), outFile.getName() + ".bac.json");
        }
        final File target = outFile;

        executor.submit(() -> {
            try {
                JsonObject bundle = buildBundle(includeAuth);
                Files.writeString(target.toPath(), gson.toJson(bundle));
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(parent, "Exported to:\n" + target.getAbsolutePath(),
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e) {
                api.logging().logToError("[BAC] Export failed: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(parent, "Export failed: " + e.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private JsonObject buildBundle(boolean includeAuth) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("version", BUNDLE_VERSION);
        root.addProperty("exportedAt", Instant.now().toString());
        root.addProperty("includesAuthMaterial", includeAuth);

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
        chooser.setFileFilter(new FileNameExtensionFilter("BAC Bundle (*.bac.json, *.json)", "bac.json", "json"));

        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;

        String[] strategies = {"Merge into existing folders", "Isolate under Imported/<filename>/"};
        int choice = JOptionPane.showOptionDialog(parent,
            "How should folder conflicts be handled?", "Import — Folder Strategy",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, strategies, strategies[0]);
        boolean isolate = choice == 1;

        executor.submit(() -> {
            int total = 0;
            for (File f : files) {
                try {
                    total += importFile(f, isolate);
                } catch (Exception e) {
                    api.logging().logToError("[BAC] Import error for " + f.getName() + ": " + e.getMessage());
                }
            }
            final int imported = total;
            SwingUtilities.invokeLater(() -> { if (onDone != null) onDone.accept(imported); });
        });
    }

    private int importFile(File file, boolean isolate) throws Exception {
        String json = Files.readString(file.toPath());
        JsonObject root = gson.fromJson(json, JsonObject.class);

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
        importFolders(folderList, folderIdMap, isolationRoot);

        // Import test cases
        int count = 0;
        for (var e : tcArr) {
            try {
                if (importTestCase(e.getAsJsonObject(), folderIdMap, isolationRoot)) count++;
            } catch (Exception ex) {
                api.logging().logToError("[BAC] Skip TC: " + ex.getMessage());
            }
        }
        return count;
    }

    private void importFolders(List<JsonObject> list, Map<Long, Long> idMap, Long isolationRoot) throws Exception {
        Set<Long> done = new HashSet<>();
        for (int pass = 0; pass < list.size() + 1 && done.size() < list.size(); pass++) {
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
                long newId = folderRepo.createFolder(fo.get("name").getAsString(), newParent);
                idMap.put(oldId, newId);
                done.add(oldId);
            }
        }
    }

    private boolean importTestCase(JsonObject tco, Map<Long, Long> folderIdMap, Long isolationRoot) throws Exception {
        String method = tco.get("method").getAsString();
        String url    = tco.get("url").getAsString();
        String host   = tco.get("host").getAsString();
        int    port   = tco.get("port").getAsInt();
        boolean https = tco.get("isHttps").getAsBoolean();
        String name   = tco.has("name") && !tco.get("name").isJsonNull() ? tco.get("name").getAsString() : null;

        byte[] reqRaw = decodeB64(tco.has("requestRaw") ? tco.get("requestRaw").getAsString() : "");

        // Deduplication check: same host + method + url + request-hash
        if (isDuplicate(host, method, url, TestCaseRepository.sha256(reqRaw))) {
            api.logging().logToOutput("[BAC] Import: duplicate skipped — " + method + " " + url);
            return false;
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
        int primaryStatus  = primaryBl != null && has(primaryBl, "status")  ? primaryBl.get("status").getAsInt()  : 0;
        int primaryLength  = primaryBl != null && has(primaryBl, "length")  ? primaryBl.get("length").getAsInt()  : 0;

        long newTcId = tcRepo.save(new TestCaseRepository.SaveRequest(
            name, null, newFolderId, null,
            method, url, host, port, https,
            reqRaw, primaryResp, primaryStatus, primaryLength
        ));

        // Import remaining baselines (old ones NOT deleted — versioning invariant)
        for (var e : blArr) {
            JsonObject bl = e.getAsJsonObject();
            boolean isPrimary = bl.has("isPrimary") && bl.get("isPrimary").getAsBoolean();
            if (isPrimary && bl == primaryBl) continue; // already saved as initial baseline
            String blLabel = str(bl, "label");
            byte[] resp = decodeB64(str(bl, "responseRaw"));
            int st  = has(bl, "status") ? bl.get("status").getAsInt()  : 0;
            int len = has(bl, "length") ? bl.get("length").getAsInt() : resp.length;
            tcRepo.addBaseline(newTcId, null, blLabel != null ? blLabel : "imported", st, len, resp);
        }
        return true;
    }

    private boolean isDuplicate(String host, String method, String url, String reqHash) {
        try {
            for (var tc : tcRepo.getAll()) {
                if (tc.host().equals(host) && tc.method().equals(method) && tc.url().equals(url)) {
                    byte[] raw = tcRepo.getRequestRaw(tc.id());
                    if (raw != null && TestCaseRepository.sha256(raw).equals(reqHash)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
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
