package io.droptracker.ui.admin;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

import io.droptracker.ui.DropTrackerTheme;

/** A painted on/off switch: clearer than a checkbox for a settings row. */
class ToggleSwitch extends JComponent {

    private static final int W = 34;
    private static final int H = 18;

    private boolean on;
    private final Runnable onChange;

    ToggleSwitch(boolean on, Runnable onChange) {
        this.on = on;
        this.onChange = onChange;
        setPreferredSize(new Dimension(W, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    toggle();
                }
            }
        });
    }

    boolean isOn() {
        return on;
    }

    void setOn(boolean on) {
        this.on = on;
        repaint();
    }

    private void toggle() {
        if (isEnabled()) {
            setOn(!on);
            onChange.run();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int y = (getHeight() - H) / 2;
        Color track = on ? DropTrackerTheme.GOLD : DropTrackerTheme.SURFACE_3;
        g2.setColor(isEnabled() ? track : track.darker());
        g2.fillRoundRect(0, y, W, H, H, H);
        g2.setColor(on ? DropTrackerTheme.SURFACE_0 : DropTrackerTheme.TEXT_MUTED);
        g2.fillOval(on ? W - H + 3 : 3, y + 3, H - 6, H - 6);
        if (isFocusOwner()) {
            g2.setColor(DropTrackerTheme.GOLD_BRIGHT);
            g2.drawRoundRect(0, y, W - 1, H - 1, H, H);
        }
        g2.dispose();
    }
}
