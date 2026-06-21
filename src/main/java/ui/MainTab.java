package ui;

import burp.api.montoya.MontoyaApi;
import capture.CaptureService;
import db.AccountRepository;
import db.DatabaseManager;
import db.FolderRepository;
import db.TestCaseRepository;

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

    // Sub-tab indices
    private static final int TAB_LIBRARY  = 0;
    private static final int TAB_ACCOUNTS = 1;

    public MainTab(MontoyaApi api, DatabaseManager db,
                   CaptureService captureService, AccountRepository accountRepo) {
        this.api = api;

        FolderRepository  folderRepo = new FolderRepository(db);
        TestCaseRepository tcRepo    = new TestCaseRepository(db);

        libraryTab  = new LibraryTab(api, folderRepo, tcRepo, captureService);
        accountsTab = new AccountsTab(api, accountRepo, tcRepo);

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Library",  libraryTab);
        tabbedPane.addTab("Accounts", accountsTab);
        tabbedPane.addTab("Test Run", placeholder("Test Run — Phase 4"));
        tabbedPane.addTab("Compare",  placeholder("Compare — Phase 5"));
        tabbedPane.addTab("Settings", placeholder("Settings — Phase 7"));

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

    private JPanel placeholder(String text) {
        JPanel p = new JPanel(new GridBagLayout());
        JLabel l = new JLabel(text + " (coming soon)");
        l.setFont(l.getFont().deriveFont(Font.ITALIC, 13f));
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        p.add(l);
        api.userInterface().applyThemeToComponent(p);
        return p;
    }
}
