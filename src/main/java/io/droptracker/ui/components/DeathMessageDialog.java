package io.droptracker.ui.components;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.annotation.Nullable;
import javax.swing.JDialog;
import javax.swing.JFrame;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DeathMessageApi;
import net.runelite.api.Client;

/**
 * The window around {@link DeathMessageEditor}, opened from the Home tab.
 * One at a time: a second click brings the open one forward.
 */
public final class DeathMessageDialog {

    @Nullable
    private static JDialog openDialog;

    private DeathMessageDialog() {
    }

    /** Opens the editor (or brings the open one forward). Call on the Swing thread. */
    public static void open(Client client, DropTrackerConfig config, DeathMessageApi api) {
        if (openDialog != null && openDialog.isDisplayable()) {
            openDialog.toFront();
            return;
        }
        JFrame parent = PanelElements.getParentFrame(client);
        JDialog dialog = new JDialog(parent, "DropTracker - Your death message", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        DeathMessageEditor editor = new DeathMessageEditor(
            api, client::getAccountHash, config::lastAccountName, dialog::dispose, dialog::pack);
        dialog.setContentPane(editor);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                editor.markClosed();
                if (openDialog == dialog) {
                    openDialog = null;
                }
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        openDialog = dialog;
        dialog.setVisible(true);
        editor.load();
    }
}
