package io.droptracker.ui;

import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Design tokens for the DropTracker side panel, mapped from the droptracker.io web
 * design system (dark parchment surfaces, gold accents) to Swing colors.
 *
 * Surfaces get lighter as they nest: SURFACE_0 is the page background, SURFACE_1 is a
 * card, SURFACE_2 is nested/hover content inside a card, SURFACE_3 is borders/accents.
 */
public final class DropTrackerTheme {

    /* Surfaces */
    public static final Color SURFACE_0 = new Color(0x15110c);
    public static final Color SURFACE_1 = new Color(0x211a12);
    public static final Color SURFACE_2 = new Color(0x2c2318);
    public static final Color SURFACE_3 = new Color(0x3a2f20);

    /* Text */
    public static final Color TEXT = new Color(0xefe6d2);
    public static final Color TEXT_MUTED = new Color(0xd8c9a3);

    /* Accents */
    public static final Color GOLD = new Color(0xffb83f);
    public static final Color GOLD_BRIGHT = new Color(0xffd966);
    public static final Color GREEN = new Color(0x6fbf73);
    public static final Color RED = new Color(0xe05c4d);
    public static final Color EMBER = new Color(0xff8c42);

    /* Subtle borders */
    public static final Color BRONZE = new Color(0x7a5a32);
    public static final Color STONE = new Color(0x5a5a52);

    private DropTrackerTheme() {
    }

    /** Standard matte card border: 1px SURFACE_3 line with 8px inner padding. */
    public static Border cardBorder() {
        return cardBorder(8, 8, 8, 8);
    }

    /** Matte card border with custom inner padding. */
    public static Border cardBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SURFACE_3, 1),
            new EmptyBorder(top, left, bottom, right));
    }

    /** Accented card border (bronze), for panels that should stand out. */
    public static Border accentCardBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BRONZE, 1),
            new EmptyBorder(top, left, bottom, right));
    }

    /** Creates a SURFACE_1 card panel with the standard matte border. */
    public static JPanel card(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(SURFACE_1);
        panel.setBorder(cardBorder());
        return panel;
    }

    /** Applies the standard themed button look (SURFACE_2 body, gold-bright hover). */
    public static void styleButton(JButton button) {
        button.setFocusPainted(false);
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(SURFACE_2);
        button.setForeground(TEXT);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SURFACE_3, 1),
            new EmptyBorder(4, 8, 4, 8)));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(SURFACE_3);
                    button.setForeground(GOLD_BRIGHT);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(SURFACE_2);
                button.setForeground(TEXT);
            }
        });
    }

    /** Small round status dot ("●") in the given color. */
    public static JLabel statusDot(Color color) {
        JLabel dot = new JLabel("●");
        dot.setFont(FontManager.getRunescapeSmallFont());
        dot.setForeground(color);
        return dot;
    }

    /**
     * Compact status "chip": small bordered label used for submission states.
     * The chip text is rendered in the accent color on a SURFACE_2 body.
     */
    public static JLabel chip(String text, Color accent) {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(accent);
        label.setOpaque(true);
        label.setBackground(SURFACE_2);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SURFACE_3, 1),
            new EmptyBorder(1, 4, 1, 4)));
        return label;
    }
}
