package ui;

import db.FolderRepository.FolderRecord;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Modal dialog for saving a test case with full metadata.
 * Opened from "Send to BAC (save as test case)" context menu item.
 */
public class SaveDialog extends JDialog {

    private boolean confirmed = false;

    private final JTextField nameField;
    private final JComboBox<FolderItem> folderCombo;
    private final JComboBox<String> accessCombo;
    private final JTextArea notesArea;

    public SaveDialog(Frame owner, List<FolderRecord> folders, String defaultName) {
        super(owner, "Save as Test Case", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        nameField  = new JTextField(defaultName, 36);
        folderCombo = new JComboBox<>(buildFolderItems(folders).toArray(new FolderItem[0]));
        accessCombo = new JComboBox<>(new String[]{"UNKNOWN", "ALLOWED", "DENIED"});
        notesArea  = new JTextArea(3, 36);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        buildLayout();
        pack();
        setMinimumSize(getSize());
        setResizable(true);
        setLocationRelativeTo(owner);
    }

    private void buildLayout() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(5, 0, 5, 10);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0; fc.insets = new Insets(5, 0, 5, 0);

        int row = 0;
        lc.gridy = row; form.add(new JLabel("Name:"), lc);
        fc.gridy = row; form.add(nameField, fc);
        row++;

        lc.gridy = row; form.add(new JLabel("Folder:"), lc);
        fc.gridy = row; form.add(folderCombo, fc);
        row++;

        lc.gridy = row; form.add(new JLabel("Expected access:"), lc);
        fc.gridy = row; form.add(accessCombo, fc);
        row++;

        lc.gridy = row; lc.insets = new Insets(8, 0, 5, 10);
        form.add(new JLabel("Notes:"), lc);
        fc.gridy = row; fc.weighty = 1.0; fc.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(notesArea), fc);

        JButton okBtn     = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        okBtn.setPreferredSize(new Dimension(90, okBtn.getPreferredSize().height));
        cancelBtn.setPreferredSize(new Dimension(90, cancelBtn.getPreferredSize().height));
        okBtn.addActionListener(e -> { confirmed = true; dispose(); });
        cancelBtn.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(okBtn);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnRow.add(cancelBtn);
        btnRow.add(okBtn);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(btnRow, BorderLayout.SOUTH);
    }

    // ---- result accessors ----------------------------------------------

    public boolean isConfirmed()        { return confirmed; }
    public String  getSelectedName()    { return nameField.getText().trim(); }
    public String  getNotes()           { return notesArea.getText().trim(); }
    public String  getExpectedAccess()  { return (String) accessCombo.getSelectedItem(); }

    /** null → Inbox */
    public Long getSelectedFolderId() {
        FolderItem item = (FolderItem) folderCombo.getSelectedItem();
        return item != null ? item.id() : null;
    }

    // ---- helpers -------------------------------------------------------

    private static List<FolderItem> buildFolderItems(List<FolderRecord> all) {
        List<FolderItem> items = new ArrayList<>();
        items.add(new FolderItem(null, "Inbox"));
        appendChildren(null, all, items, 0);
        return items;
    }

    private static void appendChildren(Long parentId, List<FolderRecord> all,
                                       List<FolderItem> out, int depth) {
        for (FolderRecord fr : all) {
            if (Objects.equals(fr.parentId(), parentId)) {
                out.add(new FolderItem(fr.id(), "  ".repeat(depth) + fr.name()));
                appendChildren(fr.id(), all, out, depth + 1);
            }
        }
    }

    private record FolderItem(Long id, String label) {
        @Override public String toString() { return label; }
    }
}
