import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.hotkey.HotKeyContext;
import capture.CaptureService;
import db.DatabaseManager;
import db.FolderRepository;
import db.TestCaseRepository;
import ui.MainTab;
import ui.SaveDialog;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * BAC Time-Machine — Burp Suite Extension entry point.
 *
 * Initialization order:
 *   1. Resolve / persist the SQLite database path via Burp preferences.
 *   2. Initialize DatabaseManager (creates schema if first run).
 *   3. Register extension unloading handler to close the DB cleanly.
 *   4. Build CaptureService and suite tab.
 *   5. Register context menu items provider.
 *   6. Register Ctrl+Alt+A hotkey for quick-save.
 */
public class Extension implements BurpExtension {

    private DatabaseManager dbManager;
    private MainTab mainTab;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("BAC Time-Machine");
        Logging logging = api.logging();

        // --- 1. Resolve database path -------------------------------------------
        String dbPath = api.persistence().preferences().getString("bac_db_path");
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = System.getProperty("user.home") + "/.bac-timemachine/store.db";
            api.persistence().preferences().setString("bac_db_path", dbPath);
        }

        // --- 2. Initialize SQLite -----------------------------------------------
        try {
            dbManager = new DatabaseManager(dbPath, logging);
            dbManager.initialize();
            logging.logToOutput("[BAC] Database initialised at: " + dbPath);
        } catch (Exception e) {
            logging.logToError("[BAC] Failed to initialise database: " + e.getMessage());
            return;
        }

        // --- 3. Unloading handler -----------------------------------------------
        api.extension().registerUnloadingHandler(() -> {
            dbManager.close();
            logging.logToOutput("[BAC] Extension unloaded.");
        });

        // --- 4. Build capture service + suite tab --------------------------------
        TestCaseRepository tcRepo = new TestCaseRepository(dbManager);
        CaptureService captureService = new CaptureService(api, tcRepo);

        mainTab = new MainTab(api, dbManager, captureService);
        api.userInterface().registerSuiteTab(mainTab.caption(), mainTab.uiComponent());

        // --- 5. Context menu -----------------------------------------------------
        api.userInterface().registerContextMenuItemsProvider(new BacContextMenuProvider(
            api, captureService, new FolderRepository(dbManager)));

        // --- 6. Hotkey: Ctrl+Alt+A = quick-save to Inbox -------------------------
        api.userInterface().registerHotKeyHandler(
            HotKeyContext.HTTP_MESSAGE_EDITOR,
            HotKey.hotKey("BAC: Quick-save request", "Ctrl+Alt+A"),
            event -> event.messageEditorRequestResponse().ifPresent(editor -> {
                HttpRequestResponse rr = editor.requestResponse();
                captureService.quickSaveToInbox(rr);
                api.logging().logToOutput("[BAC] Hotkey quick-save triggered.");
            })
        );

        logging.logToOutput("[BAC] BAC Time-Machine loaded. Hotkey: Ctrl+Alt+A");
    }

    // ---- Context menu provider ------------------------------------------

    private static class BacContextMenuProvider implements ContextMenuItemsProvider {

        private final MontoyaApi api;
        private final CaptureService captureService;
        private final FolderRepository folderRepo;

        BacContextMenuProvider(MontoyaApi api, CaptureService cs, FolderRepository fr) {
            this.api = api;
            this.captureService = cs;
            this.folderRepo = fr;
        }

        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            List<HttpRequestResponse> targets = resolveTargets(event);
            if (targets.isEmpty()) return List.of();

            List<Component> items = new ArrayList<>();

            // --- Quick-save all selected to Inbox ---
            JMenuItem quickSave = new JMenuItem("Quick-save to Inbox");
            quickSave.addActionListener(e -> {
                for (HttpRequestResponse rr : targets) captureService.quickSaveToInbox(rr);
            });
            items.add(quickSave);

            // --- Save with dialog (single selection only) ---
            if (targets.size() == 1) {
                HttpRequestResponse rr = targets.get(0);
                JMenuItem saveWithDialog = new JMenuItem("Send to BAC (save as test case)…");
                saveWithDialog.addActionListener(e -> openSaveDialog(rr, event));
                items.add(saveWithDialog);
            }

            // --- Create/Update account from this session (Phase 3) ---
            JMenuItem createAccount = new JMenuItem("Create/Update account from this request's session");
            createAccount.setEnabled(false); // Phase 3
            items.add(createAccount);

            items.add(new JSeparator());
            JMenuItem header = new JMenuItem("BAC Time-Machine");
            header.setEnabled(false);
            items.add(0, header);
            items.add(1, new JSeparator());

            return items;
        }

        private List<HttpRequestResponse> resolveTargets(ContextMenuEvent event) {
            // Prefer message editor (single focused request)
            var editor = event.messageEditorRequestResponse();
            if (editor.isPresent()) {
                HttpRequestResponse rr = editor.get().requestResponse();
                if (rr.hasResponse()) return List.of(rr);
            }
            // Fall back to selected table rows
            return event.selectedRequestResponses().stream()
                .filter(HttpRequestResponse::hasResponse)
                .toList();
        }

        private void openSaveDialog(HttpRequestResponse rr, ContextMenuEvent event) {
            SwingUtilities.invokeLater(() -> {
                try {
                    var folders = folderRepo.getAllFolders();
                    String defaultName = rr.request().method() + " "
                        + extractLastSegment(rr.request().url());

                    Frame parent = api.userInterface().swingUtils().suiteFrame();
                    SaveDialog dlg = new SaveDialog(parent, folders, defaultName);
                    api.userInterface().applyThemeToComponent(dlg.getContentPane());
                    dlg.setVisible(true);

                    if (dlg.isConfirmed()) {
                        captureService.saveWithMetadata(rr,
                            dlg.getSelectedName(),
                            dlg.getSelectedFolderId(),
                            null, // owner account: Phase 3
                            dlg.getNotes());
                    }
                } catch (Exception e) {
                    api.logging().logToError("[BAC] Save dialog error: " + e.getMessage());
                }
            });
        }

        private static String extractLastSegment(String url) {
            if (url == null) return "/";
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) return path.substring(slash + 1);
            return path;
        }
    }
}
