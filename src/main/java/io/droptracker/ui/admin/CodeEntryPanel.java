package io.droptracker.ui.admin;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import javax.swing.text.*;

import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;

/**
 * Three boxes of three digits for a {@code 123-456-789} verification code.
 * Typing moves to the next box, backspace in an empty box moves back, and a
 * pasted code in any spelling fills all three.
 */
class CodeEntryPanel extends JPanel {

    private final List<JTextField> boxes = new ArrayList<>();
    private final Runnable onChange;

    CodeEntryPanel(Runnable onSubmit, Runnable onChange) {
        super(new FlowLayout(FlowLayout.CENTER, 6, 0));
        this.onChange = onChange;
        setOpaque(false);
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                add(DropTrackerTheme.label("-", FontManager.getRunescapeBoldFont(), DropTrackerTheme.TEXT_MUTED));
            }
            JTextField box = new JTextField(3);
            box.setFont(FontManager.getRunescapeBoldFont().deriveFont(20f));
            box.setHorizontalAlignment(JTextField.CENTER);
            box.setBackground(DropTrackerTheme.SURFACE_2);
            box.setForeground(DropTrackerTheme.GOLD_BRIGHT);
            box.setCaretColor(DropTrackerTheme.GOLD_BRIGHT);
            box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DropTrackerTheme.BRONZE, 1), new EmptyBorder(6, 8, 6, 8)));
            ((AbstractDocument) box.getDocument()).setDocumentFilter(new DigitsFilter(i));
            int index = i;
            box.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    changed(index);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    changed(index);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            box.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && box.getText().isEmpty() && index > 0) {
                        boxes.get(index - 1).requestFocusInWindow();
                    } else if (e.getKeyCode() == KeyEvent.VK_ENTER && code() != null) {
                        onSubmit.run();
                    }
                }
            });
            boxes.add(box);
            add(box);
        }
    }

    /** The code as {@code 123-456-789}, or null until all nine digits are in. */
    @Nullable
    String code() {
        StringBuilder sb = new StringBuilder();
        for (JTextField box : boxes) {
            if (box.getText().length() != 3) {
                return null;
            }
            sb.append(sb.length() > 0 ? "-" : "").append(box.getText());
        }
        return sb.toString();
    }

    void clear() {
        for (JTextField box : boxes) {
            box.setText("");
        }
        focusFirst();
    }

    void focusFirst() {
        boxes.get(0).requestFocusInWindow();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (JTextField box : boxes) {
            box.setEnabled(enabled);
        }
    }

    /** Fills the boxes from {@code start} with up to nine digits. */
    void fill(String digits, int start) {
        int box = start;
        for (int i = 0; i < digits.length() && box < boxes.size(); i += 3, box++) {
            boxes.get(box).setText(digits.substring(i, Math.min(i + 3, digits.length())));
        }
    }

    private void changed(int index) {
        if (boxes.size() == 3 && boxes.get(index).getText().length() == 3 && index < 2) {
            SwingUtilities.invokeLater(() -> boxes.get(index + 1).requestFocusInWindow());
        }
        onChange.run();
    }

    /** Digits only, three per box; a longer paste spills into the boxes after. */
    private final class DigitsFilter extends DocumentFilter {
        private final int index;

        DigitsFilter(int index) {
            this.index = index;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
            throws BadLocationException {
            replace(fb, offset, 0, text, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
            throws BadLocationException {
            String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
            if (digits.length() > 3) {
                // A pasted code: spread it over this box and the ones after.
                String all = digits.substring(0, Math.min(digits.length(), 9 - index * 3));
                SwingUtilities.invokeLater(() -> fill(all, index));
                return;
            }
            int room = 3 - (fb.getDocument().getLength() - length);
            super.replace(fb, offset, length, digits.substring(0, Math.min(digits.length(), Math.max(room, 0))), attrs);
        }
    }
}
