package io.droptracker.ui.components;

import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Shared loading / error / empty state views used across all side panel pages,
 * so every page presents the same visual language for these states.
 */
public final class StateViews {

    private StateViews() {
    }

    /** Subtle centered "Loading…" label. */
    public static JPanel loading(String text) {
        return centeredLabelPanel(text != null ? text : "Loading…", DropTrackerTheme.TEXT_MUTED, 20);
    }

    /** Short muted hint for empty content. */
    public static JPanel empty(String hint) {
        return centeredLabelPanel(hint != null ? hint : "Nothing here yet.", DropTrackerTheme.TEXT_MUTED, 20);
    }

    /** Error message with a retry (or back) button. */
    public static JPanel error(String message, String buttonText, Runnable retryAction) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DropTrackerTheme.SURFACE_0);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(Box.createRigidArea(new Dimension(0, 20)));

        JLabel errorLabel = new JLabel("<html><div style='text-align:center;'>" + message + "</div></html>");
        errorLabel.setFont(FontManager.getRunescapeSmallFont());
        errorLabel.setForeground(DropTrackerTheme.RED);
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorLabel.setHorizontalAlignment(JLabel.CENTER);
        panel.add(errorLabel);

        if (retryAction != null) {
            panel.add(Box.createRigidArea(new Dimension(0, 12)));
            JButton retryButton = new JButton(buttonText != null ? buttonText : "Retry");
            DropTrackerTheme.styleButton(retryButton);
            retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            retryButton.addActionListener(e -> retryAction.run());
            panel.add(retryButton);
        }

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static JPanel centeredLabelPanel(String text, java.awt.Color color, int topGap) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DropTrackerTheme.SURFACE_0);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(Box.createRigidArea(new Dimension(0, topGap)));

        JLabel label = new JLabel("<html><div style='text-align:center;'>" + text + "</div></html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(JLabel.CENTER);
        panel.add(label);

        panel.add(Box.createVerticalGlue());
        return panel;
    }
}
