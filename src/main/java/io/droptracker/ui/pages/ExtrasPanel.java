package io.droptracker.ui.pages;

import java.awt.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DeathMessageApi;
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

    public JPanel create() {
        JPanel page = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        page.setBorder(new EmptyBorder(8, 0, 8, 0));
        page.add(SettingsForm.left(DropTrackerTheme.label("Extras", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD)));
        page.add(DropTrackerTheme.gap(4));
        page.add(SettingsForm.left(SettingsForm.wrapped("Set these up once. They're saved to your account on the DropTracker, not in your "
            + "plugin settings, so they follow you to every clan.", DropTrackerTheme.TEXT_MUTED)));
        page.add(DropTrackerTheme.gap(10));
        page.add(card("Death message", "What your clans' Discord says when you die. Add a few and one is "
            + "picked at random.", "Edit death message", () -> DeathMessageDialog.open(client, config, deathMessageApi)));
        return page;
    }

    private JComponent card(String title, String text, String button, Runnable action) {
        JPanel card = io.droptracker.ui.components.PanelElements.heightCappedPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(8, 10, 10, 10));
        card.add(SettingsForm.left(DropTrackerTheme.label(title, FontManager.getRunescapeFont(), DropTrackerTheme.TEXT)));
        card.add(SettingsForm.left(SettingsForm.wrapped(text, DropTrackerTheme.TEXT_MUTED)));
        card.add(DropTrackerTheme.gap(8));
        JButton open = DropTrackerTheme.button(button);
        open.addActionListener(e -> {
            String name = config.lastAccountName();
            if (name == null || name.isEmpty()) {
                JOptionPane.showMessageDialog(card, "Log in to the game first, so the DropTracker knows which "
                    + "account this is for.", title, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            action.run();
        });
        open.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        card.add(SettingsForm.left(open));
        return SettingsForm.left(card);
    }

}
