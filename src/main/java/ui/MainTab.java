package ui;

import burp.api.montoya.MontoyaApi;
import capture.CaptureService;
import db.DatabaseManager;
import db.FolderRepository;
import db.TestCaseRepository;

import javax.swing.*;
import java.awt.*;

/**
 * Root "BAC Time-Machine" Burp suite tab.
 * Hosts sub-tabs: Library | Accounts | Test Run | Compare | Settings.
 * Phases 3-7 will replace the placeholder panels for each sub-tab.
 */
public class MainTab {

    private final MontoyaApi api;
    private final JTabbedPane tabbedPane;
    private final LibraryTab libraryTab;

    public MainTab(MontoyaApi api, DatabaseManager db, CaptureService captureService) {
        this.api = api;

        FolderRepository folderRepo = new FolderRepository(db);
        TestCaseRepository tcRepo   = new TestCaseRepository(db);

        libraryTab = new LibraryTab(api, folderRepo, tcRepo, captureService);

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Library",    libraryTab);
        tabbedPane.addTab("Accounts",   placeholder("Accounts — Phase 3"));
        tabbedPane.addTab("Test Run",   placeholder("Test Run — Phase 4"));
        tabbedPane.addTab("Compare",    placeholder("Compare — Phase 5"));
        tabbedPane.addTab("Settings",   placeholder("Settings — Phase 7"));

        api.userInterface().applyThemeToComponent(tabbedPane);
    }

    public String caption()       { return "BAC Time-Machine"; }
    public Component uiComponent() { return tabbedPane; }

    /** Refresh the Library tab — called after external saves. */
    public void refreshLibrary() { libraryTab.refresh(); }

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
