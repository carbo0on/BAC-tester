import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.hotkey.HotKeyHandler;
import capture.CaptureService;
import db.AccountRepository;
import db.DatabaseManager;
import db.FolderRepository;
import db.TestCaseRepository;
import ui.MainTab;
import ui.SaveDialog;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BAC Time-Machine — Burp Suite Extension entry point.
 *
 * Initialization order:
 *   1. Resolve / persist the SQLite database path.
 *   2. Initialize DatabaseManager (creates schema if first run).
 *   3. Register extension unloading handler.
 *   4. Build repositories, CaptureService, and suite tab.
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
        logging.logToOutput("[BAC] BAC Time-Machine — designed by Cataract.");

        // --- 1. Database path -------------------------------------------
        String dbPath = api.persistence().preferences().getString("bac_db_path");
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = System.getProperty("user.home") + "/.bac-timemachine/store.db";
            api.persistence().preferences().setString("bac_db_path", dbPath);
        }

        // --- 2. Initialize SQLite ----------------------------------------
        try {
            dbManager = new DatabaseManager(dbPath, logging);
            dbManager.initialize();
            logging.logToOutput("[BAC] Database initialised at: " + dbPath);
        } catch (Exception e) {
            logging.logToError("[BAC] Failed to initialise database: " + e.getMessage());
            return;
        }

        // --- 4. Repositories + tab ---------------------------------------
        AccountRepository  accountRepo = new AccountRepository(dbManager);
        TestCaseRepository tcRepo      = new TestCaseRepository(dbManager);
        CaptureService     capture     = new CaptureService(api, tcRepo);

        mainTab = new MainTab(api, dbManager, capture, accountRepo);
        api.userInterface().registerSuiteTab(mainTab.caption(), mainTab.uiComponent());

        // --- 3. Unloading handler ----------------------------------------
        // Registered after capture/mainTab exist so their workers can be stopped.
        api.extension().registerUnloadingHandler(() -> {
            capture.shutdown();
            if (mainTab != null) mainTab.shutdown();
            dbManager.close();
            logging.logToOutput("[BAC] Extension unloaded.");
        });

        // --- 5. Context menu ---------------------------------------------
        api.userInterface().registerContextMenuItemsProvider(
            new BacContextMenuProvider(api, capture, accountRepo,
                                       new FolderRepository(dbManager)));

        // --- 5b. Passive Live mode: route Proxy responses to the Live tab ---
        // Only Proxy traffic is forwarded (our own replayed requests are sent
        // via the Extensions tool, so they never re-enter and loop). The Live
        // tab decides whether testing is enabled and does the replay off-thread.
        api.http().registerHttpHandler(new HttpHandler() {
            @Override public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
                return RequestToBeSentAction.continueWith(req);
            }
            @Override public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived resp) {
                try {
                    if (resp.toolSource().isFromTool(ToolType.PROXY)) {
                        var req = resp.initiatingRequest();
                        if (req != null) {
                            boolean inScope = api.scope().isInScope(req.url());
                            mainTab.liveTab().onProxyResponse(req, resp, inScope);
                        }
                    }
                } catch (Exception ex) {
                    logging.logToError("[BAC] Live handler error: " + ex.getMessage());
                }
                return ResponseReceivedAction.continueWith(resp);
            }
        });

        // --- 6. Hotkey: quick-save to Inbox (configurable in Settings) ---
        // Default is Ctrl+Alt+B (matches the DB default and the Settings hint).
        // The combo is read from the settings table so users can rebind it;
        // changing it takes effect after the extension is reloaded.
        //
        // NOTE: previous versions registered the SAME combo in three separate
        // contexts at once, which Burp treats as a conflict and silently
        // disables — that is why the hotkey "didn't work". We now use the
        // context-less overload, which fires for ANY supported context with a
        // single registration.
        String hotkeyCombo = "Ctrl+Alt+B";
        try {
            String stored = dbManager.getSetting("hotkey_combo");
            if (stored != null && !stored.isBlank()) hotkeyCombo = stored.trim();
        } catch (Exception ignored) {}

        // Shared handler: works whether focus is in a message editor (Repeater /
        // Proxy intercept) or on a request table (Proxy history / Site map).
        final String hk = hotkeyCombo;
        HotKeyHandler quickSaveHandler = event -> {
            logging.logToOutput("[BAC] Quick-save hotkey fired (" + hk + ").");
            var editor = event.messageEditorRequestResponse();
            if (editor.isPresent() && editor.get().requestResponse() != null
                    && editor.get().requestResponse().request() != null) {
                capture.quickSaveToInbox(editor.get().requestResponse());
                return;
            }
            var selected = event.selectedRequestResponses();
            if (selected != null && !selected.isEmpty()) {
                selected.stream()
                    .filter(rr -> rr != null && rr.request() != null)
                    .forEach(capture::quickSaveToInbox);
                return;
            }
            logging.logToOutput("[BAC] Quick-save hotkey: no request in focus to save.");
        };

        // Single context-less registration → fires for any supported context
        // (Repeater, Proxy intercept, Proxy history, Site map) without conflicts.
        try {
            api.userInterface().registerHotKeyHandler(
                HotKey.hotKey("BAC: Quick-save request", hotkeyCombo), quickSaveHandler);
            logging.logToOutput("[BAC] BAC Time-Machine loaded. Quick-save hotkey: " + hotkeyCombo
                + " (focus an HTTP message editor or request table, then press it).");
        } catch (Exception e) {
            logging.logToError("[BAC] Hotkey \"" + hotkeyCombo + "\" did not register ("
                + e.getMessage() + "). Set a valid combo in BAC Settings and reload.");
        }
    }

    // ---- Context menu provider -----------------------------------------

    private class BacContextMenuProvider implements ContextMenuItemsProvider {

        private final MontoyaApi api;
        private final CaptureService capture;
        private final AccountRepository accountRepo;
        private final FolderRepository folderRepo;

        BacContextMenuProvider(MontoyaApi api, CaptureService cs,
                                AccountRepository ar, FolderRepository fr) {
            this.api = api;
            this.capture = cs;
            this.accountRepo = ar;
            this.folderRepo = fr;
        }

        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            List<HttpRequestResponse> targets = resolveTargets(event);
            if (targets.isEmpty()) return List.of();

            List<Component> items = new ArrayList<>();

            // Header (disabled label) + separator
            JMenuItem header = new JMenuItem("BAC Time-Machine");
            header.setEnabled(false);
            items.add(header);
            items.add(new JSeparator());

            // Quick-save (supports multi-select)
            JMenuItem quickSave = new JMenuItem("Quick-save to Inbox");
            quickSave.addActionListener(e ->
                targets.forEach(capture::quickSaveToInbox));
            items.add(quickSave);

            // Save with dialog (single only)
            if (targets.size() == 1) {
                HttpRequestResponse rr = targets.get(0);
                JMenuItem saveDialog = new JMenuItem("Send to BAC (save as test case)…");
                saveDialog.addActionListener(e -> openSaveDialog(rr));
                items.add(saveDialog);
            }

            items.add(new JSeparator());

            // Create/Update account from session
            if (targets.size() == 1) {
                HttpRequestResponse rr = targets.get(0);
                JMenuItem importSession = new JMenuItem("Create/Update account from this request's session");
                importSession.addActionListener(e -> importSessionFromRequest(rr));
                items.add(importSession);

                // IDOR enumeration on this request
                JMenuItem idor = new JMenuItem("Fuzz IDOR (enumerate identifier)…");
                idor.addActionListener(e -> openIdorFuzz(rr));
                items.add(idor);
            }

            return items;
        }

        private List<HttpRequestResponse> resolveTargets(ContextMenuEvent event) {
            // Works in Proxy, Repeater and elsewhere. A response is NOT required —
            // requests captured before they are sent get an empty baseline that can
            // be filled in later via "Re-baseline".
            var editor = event.messageEditorRequestResponse();
            if (editor.isPresent()) {
                HttpRequestResponse rr = editor.get().requestResponse();
                if (rr != null && rr.request() != null) return List.of(rr);
            }
            return event.selectedRequestResponses().stream()
                .filter(rr -> rr != null && rr.request() != null)
                .toList();
        }

        private void openSaveDialog(HttpRequestResponse rr) {
            SwingUtilities.invokeLater(() -> {
                try {
                    var folders = folderRepo.getAllFolders();
                    String defaultName = rr.request().method() + " "
                        + lastSegment(rr.request().url());
                    Frame parent = api.userInterface().swingUtils().suiteFrame();
                    SaveDialog dlg = new SaveDialog(parent, folders, defaultName);
                    api.userInterface().applyThemeToComponent(dlg.getContentPane());
                    dlg.setVisible(true);
                    if (dlg.isConfirmed()) {
                        capture.saveWithMetadata(rr,
                            dlg.getSelectedName(),
                            dlg.getSelectedFolderId(),
                            null, dlg.getNotes());
                    }
                } catch (Exception e) {
                    api.logging().logToError("[BAC] Save dialog error: " + e.getMessage());
                }
            });
        }

        private void openIdorFuzz(HttpRequestResponse rr) {
            SwingUtilities.invokeLater(() -> {
                try {
                    var req = rr.request();
                    byte[] requestRaw = req.toByteArray().getBytes();
                    var svc = req.httpService();
                    var service = burp.api.montoya.http.HttpService.httpService(
                        svc.host(), svc.port(), svc.secure());
                    var accounts = accountRepo.getAll();
                    Frame parent = api.userInterface().swingUtils().suiteFrame();
                    ui.IdorFuzzDialog dlg = new ui.IdorFuzzDialog(api, parent, requestRaw, service, accounts);
                    dlg.setVisible(true);
                } catch (Exception e) {
                    api.logging().logToError("[BAC] IDOR fuzz dialog error: " + e.getMessage());
                }
            });
        }

        private void importSessionFromRequest(HttpRequestResponse rr) {
            // Extract cookies + session headers from the request
            var req = rr.request();
            String cookieHeader = req.headerValue("Cookie");
            var extracted = AccountRepository.extractSession(cookieHeader, req.headers());
            Map<String, String> cookies = extracted.getKey();
            Map<String, String> headers = extracted.getValue();

            String suggestedName = "Account from " + req.httpService().host();

            // Pre-fill the Accounts tab and switch to it
            SwingUtilities.invokeLater(() ->
                mainTab.importAccountFromSession(cookies, headers, suggestedName));
        }

        private static String lastSegment(String url) {
            if (url == null) return "/";
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int slash = path.lastIndexOf('/');
            return slash >= 0 && slash < path.length() - 1
                ? path.substring(slash + 1) : path;
        }
    }
}
