package io.droptracker.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.droptracker.ui.components.PanelElements;
import java.awt.Font;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.Test;

/**
 * Text from the server must never be rendered as HTML by Swing, whose
 * renderer fetches {@code <img src>} over the network.
 */
public class HtmlSafetyTest {

    private static final String HOSTILE = "<html><img src='http://example.invalid/x.png'>";
    private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 12);

    @Test
    public void plainLabelsShowMarkupLiterally() {
        JLabel label = DropTrackerTheme.label(HOSTILE, FONT, DropTrackerTheme.TEXT);
        assertNull(label.getClientProperty(BasicHTML.propertyKey));
        assertEquals(HOSTILE, label.getText());
        // Still literal when the text arrives later.
        label.setText(HOSTILE + " ");
        assertNull(label.getClientProperty(BasicHTML.propertyKey));
    }

    @Test
    public void buttonsAndChipsShowMarkupLiterally() {
        JButton button = DropTrackerTheme.button(HOSTILE);
        assertNull(button.getClientProperty(BasicHTML.propertyKey));
        assertNull(DropTrackerTheme.chip(HOSTILE, DropTrackerTheme.GOLD).getClientProperty(BasicHTML.propertyKey));
    }

    @Test
    public void richLabelsStillRenderOurOwnMarkup() {
        JLabel label = DropTrackerTheme.richLabel("<html><b>ours</b></html>", FONT, DropTrackerTheme.TEXT);
        assertNotNull(label.getClientProperty(BasicHTML.propertyKey));
    }

    @Test
    public void escapingLeavesNoTags() {
        String escaped = PanelElements.escapeHtml(HOSTILE + " & \"co\"");
        assertFalse(escaped.contains("<"));
        assertFalse(escaped.contains(">"));
        assertTrue(escaped.contains("&lt;img"));
        assertTrue(escaped.contains("&amp;"));
        assertEquals("", PanelElements.escapeHtml(null));
        // Outside Latin-1 stays as typed: Swing prints newer named entities literally.
        assertEquals("a … b", PanelElements.escapeHtml("a … b"));
    }

    @Test
    public void tooltipsEscapeTheirText() {
        assertNull(PanelElements.tip(null));
        assertNull(PanelElements.tip("  "));
        String tip = PanelElements.tip(HOSTILE);
        assertTrue(tip.startsWith("<html>&lt;html&gt;&lt;img"));
        assertFalse(tip.substring(6, tip.length() - 7).contains("<"));
    }
}
