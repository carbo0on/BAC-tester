package ui;

import burp.api.montoya.MontoyaApi;
import capture.CaptureService;
import db.AccountRepository;
import db.DatabaseManager;
import db.FolderRepository;
import db.RunRepository;
import db.TestCaseRepository;
import engine.RunEngine;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * Root "BAC Time-Machine" Burp suite tab.
 * Hosts 5 sub-tabs: Library | Accounts | Test Run | Compare | Settings.
 */
public class MainTab {

    private final MontoyaApi api;
    private final JTabbedPane tabbedPane;
    private final LibraryTab libraryTab;
    private final AccountsTab accountsTab;
    private final TestRunTab testRunTab;
    private final CompareTab compareTab;

    // Sub-tab indices
    private static final int TAB_LIBRARY  = 0;
    private static final int TAB_ACCOUNTS = 1;
    private static final int TAB_TESTRUN  = 2;
    private static final int TAB_COMPARE  = 3;

    public MainTab(MontoyaApi api, DatabaseManager db,
                   CaptureService captureService, AccountRepository accountRepo) {
        this.api = api;

        FolderRepository   folderRepo = new FolderRepository(db);
        TestCaseRepository tcRepo     = new TestCaseRepository(db);
        RunRepository      runRepo    = new RunRepository(db);
        RunEngine          runEngine  = new RunEngine(api, db);

        libraryTab  = new LibraryTab(api, folderRepo, tcRepo, captureService, db);
        accountsTab = new AccountsTab(api, accountRepo, tcRepo);
        testRunTab  = new TestRunTab(api, runEngine, accountRepo, tcRepo, folderRepo, db);
        compareTab  = new CompareTab(api, db, tcRepo, runRepo, accountRepo);
        ExportImportManager exportImport = new ExportImportManager(api, db);
        SettingsTab settingsTab = new SettingsTab(api, db, exportImport);
        // When settings are saved (or an import finishes), refresh the Library
        // so coloring mode / new test cases show immediately.
        settingsTab.setOnSaved(() -> libraryTab.refresh());

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Library",  libraryTab);
        tabbedPane.addTab("Accounts", accountsTab);
        tabbedPane.addTab("Test Run", testRunTab);
        tabbedPane.addTab("Compare",  compareTab);
        tabbedPane.addTab("Settings", settingsTab);
        // Crisp vector icons instead of emoji (consistent across platforms/themes).
        tabbedPane.setIconAt(TAB_LIBRARY,  BacIcons.folder(new Color(0x5F, 0x96, 0xE0)));
        tabbedPane.setIconAt(TAB_ACCOUNTS, BacIcons.account());
        tabbedPane.setIconAt(TAB_TESTRUN,  BacIcons.dot(new Color(0x20, 0x90, 0x20)));
        tabbedPane.setIconAt(TAB_COMPARE,  BacIcons.dot(new Color(0x5F, 0x96, 0xE0)));
        tabbedPane.setIconAt(4,            BacIcons.dot(new Color(0x88, 0x88, 0x88)));

        // Wire Library → Compare (after tabbedPane is ready)
        libraryTab.setAccountRepository(accountRepo);
        libraryTab.setOnAddToWorkingSet(ids -> {
            compareTab.addToWorkingSet(ids);
            tabbedPane.setSelectedIndex(TAB_COMPARE);
        });
        libraryTab.setOnOpenInCompare(id -> {
            compareTab.openTestCase(id);
            tabbedPane.setSelectedIndex(TAB_COMPARE);
        });
        libraryTab.setOnRunSelected((accountId, tcIds) -> {
            testRunTab.startRunDirectly(accountId, tcIds);
            tabbedPane.setSelectedIndex(TAB_TESTRUN);
        });
        // A-vs-B pair: replay the requests as each account, and pre-load them into
        // the Compare working set so the responses can be diffed directly (#9).
        libraryTab.setOnRunPair((accountIds, tcIds) -> {
            testRunTab.startAccountsDirectly(accountIds, tcIds);
            compareTab.addToWorkingSet(tcIds);
            tabbedPane.setSelectedIndex(TAB_TESTRUN);
        });

        // Wire TestRun → Compare
        testRunTab.setOnOpenInCompare(id -> {
            compareTab.openTestCase(id);
            tabbedPane.setSelectedIndex(TAB_COMPARE);
        });

        // Refresh TestRunTab scope when the Library saves new test cases
        captureService.addOnSaveListener(() -> {
            testRunTab.refreshScope();
            testRunTab.refreshAccounts();
        });

        // Refresh TestRunTab accounts when switching to it
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == TAB_TESTRUN) {
                testRunTab.refreshAccounts();
                testRunTab.refreshScope();
            }
        });

        api.userInterface().applyThemeToComponent(tabbedPane);
    }

    public String caption()        { return "BAC Time-Machine"; }
    public Component uiComponent() { return tabbedPane; }

    /** Pre-fill the Accounts editor from a captured session and switch to the Accounts tab. */
    public void importAccountFromSession(Map<String, String> cookies,
                                         Map<String, String> headers,
                                         String suggestedName) {
        accountsTab.prefillFromSession(cookies, headers, suggestedName);
        tabbedPane.setSelectedIndex(TAB_ACCOUNTS);
    }

}
