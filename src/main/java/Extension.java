import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import db.DatabaseManager;
import ui.MainTab;

/**
 * BAC Time-Machine — Burp Suite Extension entry point.
 *
 * Initialization order:
 *   1. Resolve / persist the SQLite database path via Burp preferences.
 *   2. Initialize DatabaseManager (creates schema if first run).
 *   3. Register extension unloading handler to close the DB connection cleanly.
 *   4. Build and register the main suite tab.
 */
public class Extension implements BurpExtension {

    private DatabaseManager dbManager;

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

        // --- 2. Initialize SQLite (runs on Burp's init thread, one-time only) ----
        try {
            dbManager = new DatabaseManager(dbPath, logging);
            dbManager.initialize();
            logging.logToOutput("[BAC] Database initialised at: " + dbPath);
        } catch (Exception e) {
            logging.logToError("[BAC] Failed to initialise database: " + e.getMessage());
            return; // abort — extension cannot function without the DB
        }

        // --- 3. Clean up when the extension is unloaded -------------------------
        api.extension().registerUnloadingHandler(() -> {
            dbManager.close();
            logging.logToOutput("[BAC] Extension unloaded.");
        });

        // --- 4. Register main suite tab -----------------------------------------
        // Montoya 2026.4 takes (String caption, Component) — no SuiteTab interface
        MainTab mainTab = new MainTab(api, dbManager);
        api.userInterface().registerSuiteTab(mainTab.caption(), mainTab.uiComponent());

        logging.logToOutput("[BAC] BAC Time-Machine loaded successfully.");
    }
}
