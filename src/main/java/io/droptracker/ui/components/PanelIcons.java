package io.droptracker.ui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

import javax.swing.Icon;

/**
 * Small symbols painted with Graphics2D instead of drawn as text.
 * <p>
 * These used to be Unicode characters. RuneLite's bundled fonts stop at U+2122,
 * so they only ever rendered by falling back to a system font — a chain that
 * does not cover them on macOS, where they came out as missing-glyph boxes.
 * Painting the shapes removes the dependency on font coverage entirely.
 * <p>
 * Symbols that Latin-1 already covers (notably {@code ×}) are left as text;
 * only shapes with no in-font equivalent live here.
 */
public final class PanelIcons {

    private PanelIcons() {
    }

    /** Tick mark, for completed / obtained state. */
    public static Icon check(Color color, int size) {
        return new ShapeIcon(size, color, (g, s, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(Math.max(1.6f, s * 0.16f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float p = new Path2D.Float();
            p.moveTo(s * 0.18f, s * 0.54f);
            p.lineTo(s * 0.42f, s * 0.78f);
            p.lineTo(s * 0.84f, s * 0.22f);
            g.draw(p);
        });
    }

    /** Five-pointed star; {@code filled} distinguishes pinned from unpinned. */
    public static Icon star(Color color, int size, boolean filled) {
        return new ShapeIcon(size, color, (g, s, c) -> {
            g.setColor(c);
            Path2D.Float p = starPath(s);
            if (filled) {
                g.fill(p);
            } else {
                g.setStroke(new BasicStroke(Math.max(1f, s * 0.1f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(p);
            }
        });
    }

    /** Circular arrow, for refresh / retry actions. */
    public static Icon refresh(Color color, int size) {
        return new ShapeIcon(size, color, (g, s, c) -> {
            g.setColor(c);
            float inset = s * 0.18f;
            float d = s - inset * 2f;
            g.setStroke(new BasicStroke(Math.max(1.4f, s * 0.13f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Open arc, with the gap where the arrowhead sits.
            g.drawArc(Math.round(inset), Math.round(inset),
                Math.round(d), Math.round(d), 55, 285);
            // Arrowhead at the open end (top-right of the arc).
            float cx = s / 2f;
            float cy = s / 2f;
            float r = d / 2f;
            double a = Math.toRadians(55);
            float hx = cx + (float) (Math.cos(a) * r);
            float hy = cy - (float) (Math.sin(a) * r);
            float h = s * 0.24f;
            Path2D.Float head = new Path2D.Float();
            head.moveTo(hx + h * 0.5f, hy - h * 0.1f);
            head.lineTo(hx - h * 0.5f, hy - h * 0.35f);
            head.lineTo(hx + h * 0.05f, hy + h * 0.6f);
            head.closePath();
            g.fill(head);
        });
    }

    /** Filled circle, for status dots. */
    public static Icon dot(Color color, int size) {
        return new ShapeIcon(size, color, (g, s, c) -> {
            g.setColor(c);
            float inset = s * 0.2f;
            g.fill(new Ellipse2D.Float(inset, inset, s - inset * 2f, s - inset * 2f));
        });
    }

    /**
     * Paints a tick directly onto an existing surface, for callers already
     * compositing an image rather than showing an {@link Icon}.
     */
    public static void paintCheck(Graphics2D g, int x, int y, int size, Color color) {
        Graphics2D g2 = (Graphics2D) g.create(x, y, size, size);
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(Math.max(1.6f, size * 0.16f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float p = new Path2D.Float();
            p.moveTo(size * 0.18f, size * 0.54f);
            p.lineTo(size * 0.42f, size * 0.78f);
            p.lineTo(size * 0.84f, size * 0.22f);
            g2.draw(p);
        } finally {
            g2.dispose();
        }
    }

    private static Path2D.Float starPath(float s) {
        Path2D.Float p = new Path2D.Float();
        float cx = s / 2f;
        float cy = s / 2f + s * 0.03f;
        float outer = s * 0.46f;
        float inner = outer * 0.42f;
        for (int i = 0; i < 10; i++) {
            double angle = Math.toRadians(-90 + i * 36);
            float r = (i % 2 == 0) ? outer : inner;
            float px = cx + (float) (Math.cos(angle) * r);
            float py = cy + (float) (Math.sin(angle) * r);
            if (i == 0) {
                p.moveTo(px, py);
            } else {
                p.lineTo(px, py);
            }
        }
        p.closePath();
        return p;
    }

    @FunctionalInterface
    private interface ShapePainter {
        void paint(Graphics2D g, float size, Color color);
    }

    /** Square icon that antialiases and hands a clean surface to its painter. */
    private static final class ShapeIcon implements Icon {
        private final int size;
        private final Color color;
        private final ShapePainter painter;

        private ShapeIcon(int size, Color color, ShapePainter painter) {
            this.size = size;
            this.color = color;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create(x, y, size, size);
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
                painter.paint(g2, size, color);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
