package io.droptracker.ui.pages;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DeathMessageApi;
import io.droptracker.service.PlayerModelService;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.admin.SettingsForm;
import io.droptracker.ui.components.DeathMessageDialog;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Things a player sets up once on the DropTracker that aren't plugin settings:
 * they live with the account on the server, so they follow it to every clan.
 * One card each.
 */
@RequiredArgsConstructor
public class ExtrasPanel {
    private final DropTrackerConfig config;
    private final Client client;
    private final DeathMessageApi deathMessageApi;
    private final PlayerModelService playerModelService;

    public JPanel create() {
        JPanel page = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        page.setBorder(new EmptyBorder(8, 0, 8, 0));
        page.add(SettingsForm.left(DropTrackerTheme.label("Extras", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD)));
        page.add(DropTrackerTheme.gap(4));
        page.add(wrapped("Set these up once. They're saved to your account on the DropTracker, not in your "
            + "plugin settings, so they follow you to every clan.", 60));
        page.add(DropTrackerTheme.gap(10));
        page.add(card("Your messages", "What your clans' Discord says when you die, log a collection log slot, "
            + "get a drop or set a personal best. Add a few and one is picked at random.", "Edit messages",
            b -> DeathMessageDialog.open(client, config, deathMessageApi)));
        page.add(DropTrackerTheme.gap(8));
        page.add(card("Your character", "The model shown on your droptracker.io profile. Stand still with your "
            + "favourite gear on, then send it. Your pet comes along too.", "Send my model", this::sendModel));
        page.add(DropTrackerTheme.gap(8));
        page.add(card("Your privacy", "Whether you're pinged when your drops are posted, and whether you show "
            + "on droptracker.io and in the DropTracker Discord. Your clans still see you.", "Privacy settings",
            this::privacy));
        // Spare height collects below the cards instead of inside them.
        page.add(Box.createVerticalGlue());
        return page;
    }

    /** The website's privacy switches (users table): key, label. */
    private static final String[][] PRIVACY = {
        {"hidden", "Hide me on droptracker.io and in the DropTracker Discord"},
        {"global_ping", "Ping me in the DropTracker Discord"},
        {"group_ping", "Ping me in my clans' Discords"},
        {"never_ping", "Never ping me (beats both above)"},
    };

    /** Loads the switches, shows them, saves what changed. */
    private void privacy(JButton button) {
        String name = config.lastAccountName();
        long hash = client.getAccountHash();
        button.setEnabled(false);
        deathMessageApi.privacy(name, hash, null, (settings, error) -> {
            button.setEnabled(true);
            if (settings == null) {
                JOptionPane.showMessageDialog(button, error, "Your privacy", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
            List<JCheckBox> boxes = new ArrayList<>();
            for (String[] option : PRIVACY) {
                JCheckBox box = new JCheckBox(option[1], Boolean.TRUE.equals(settings.get(option[0])));
                boxes.add(box);
                form.add(box);
            }
            if (JOptionPane.showConfirmDialog(button, form, "Your privacy", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            Map<String, Boolean> changes = new HashMap<>();
            for (int i = 0; i < PRIVACY.length; i++) {
                if (boxes.get(i).isSelected() != Boolean.TRUE.equals(settings.get(PRIVACY[i][0]))) {
                    changes.put(PRIVACY[i][0], boxes.get(i).isSelected());
                }
            }
            if (changes.isEmpty()) {
                return;
            }
            deathMessageApi.privacy(name, hash, changes, (saved, saveError) -> JOptionPane.showMessageDialog(button,
                saved != null ? "Saved. It can take a minute to show on the site." : saveError, "Your privacy",
                JOptionPane.INFORMATION_MESSAGE));
        });
    }

    /** Sends the character as it looks now; the button narrates, then resets. */
    private void sendModel(JButton button) {
        String idle = button.getText();
        button.setEnabled(false);
        button.setText("Sending model...");
        playerModelService.sendCurrentModel((ok, message) -> {
            // Already on the EDT.
            button.setText(ok ? "Model sent!" : "Failed to send");
            button.setToolTipText(message);
            Timer restore = new Timer(4000, ev -> {
                button.setText(idle);
                button.setEnabled(true);
            });
            restore.setRepeats(false);
            restore.start();
        });
    }

    private JComponent card(String title, String text, String button, Consumer<JButton> action) {
        JPanel card = io.droptracker.ui.components.PanelElements.heightCappedPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(8, 10, 10, 10));
        card.add(SettingsForm.left(DropTrackerTheme.label(title, FontManager.getRunescapeFont(), DropTrackerTheme.TEXT)));
        card.add(wrapped(text, 80));
        card.add(DropTrackerTheme.gap(8));
        JButton open = DropTrackerTheme.button(button);
        open.addActionListener(e -> {
            String name = config.lastAccountName();
            if (name == null || name.isEmpty()) {
                JOptionPane.showMessageDialog(card, "Log in to the game first, so the DropTracker knows which "
                    + "account this is for.", title, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            action.accept(open);
        });
        open.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        card.add(SettingsForm.left(open));
        return SettingsForm.left(card);
    }

    /**
     * Muted text wrapped to a fixed width, {@code inset} px narrower than the
     * panel. Not a JTextArea: before the panel has a width, a wrapping text area
     * reports a tall preferred size and BoxLayout lets it soak up the card's
     * spare height, which pushed each button to the bottom of its card.
     */
    private static JComponent wrapped(String text, int inset) {
        return SettingsForm.left(DropTrackerTheme.label("<html><div style='width:" + (PluginPanel.PANEL_WIDTH - inset)
            + "px'>" + text + "</div></html>", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED));
    }

}
