package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;

/**
 * Small vector emblems for the kinds of news that carry no item (a lead
 * change, a bingo line, the event starting), so their icon slot never sits
 * empty. Drawn with plain shapes: the RuneScape fonts have no symbol glyphs,
 * and nothing here needs an image resource.
 */
final class PopupGlyphs {
    private PopupGlyphs() {
    }

    /** Paints the kind's emblem centred in a {@code size} square at (x, y), with a drop shadow. */
    static void draw(Graphics2D g, EventPopupCard.Kind kind, int x, int y, int size, Color color) {
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke stroke = g.getStroke();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(1, 1);
        shape(g, kind, x, y, size, Color.BLACK);
        g.translate(-1, -1);
        shape(g, kind, x, y, size, color);
        g.setStroke(stroke);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            aa != null ? aa : RenderingHints.VALUE_ANTIALIAS_DEFAULT);
    }

    private static void shape(Graphics2D g, EventPopupCard.Kind kind, int x, int y, int size, Color c) {
        // Work in a padded square so every emblem has the same optical weight.
        int pad = Math.max(1, size / 8);
        int s = size - pad * 2;
        int ox = x + pad;
        int oy = y + pad;
        float line = Math.max(1.5f, s / 9f);
        g.setColor(c);
        g.setStroke(new BasicStroke(line, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case COMPLETE: {
                int[] xs = {ox + s / 8, ox + s * 3 / 8, ox + s * 7 / 8};
                int[] ys = {oy + s / 2, oy + s * 6 / 8, oy + s / 5};
                g.setStroke(new BasicStroke(line * 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawPolyline(xs, ys, 3);
                break;
            }
            case TILE:
                grid(g, ox, oy, s, c, new boolean[]{false, false, false, false, true, false, false, false, false});
                break;
            case LINE:
                grid(g, ox, oy, s, c, new boolean[]{false, false, false, true, true, true, false, false, false});
                break;
            case BLACKOUT:
                grid(g, ox, oy, s, c, new boolean[]{true, true, true, true, true, true, true, true, true});
                break;
            case PROGRESS: {
                int bar = Math.max(2, s / 5);
                int gap = (s - bar * 3) / 2;
                for (int i = 0; i < 3; i++) {
                    int h = s * (i + 1) / 3;
                    g.fillRect(ox + i * (bar + gap), oy + s - h, bar, h);
                }
                break;
            }
            case LEAD: {
                // Crown: three points over a band.
                Polygon crown = new Polygon();
                crown.addPoint(ox, oy + s * 3 / 4);
                crown.addPoint(ox, oy + s / 4);
                crown.addPoint(ox + s / 4, oy + s / 2);
                crown.addPoint(ox + s / 2, oy + s / 8);
                crown.addPoint(ox + s * 3 / 4, oy + s / 2);
                crown.addPoint(ox + s, oy + s / 4);
                crown.addPoint(ox + s, oy + s * 3 / 4);
                g.fillPolygon(crown);
                g.fillRect(ox, oy + s * 13 / 16, s + 1, Math.max(2, s / 8));
                break;
            }
            case STARTED:
            case ENDED: {
                // Flag on a pole; the finish flag is chequered.
                int pole = Math.max(2, s / 8);
                g.fillRect(ox + s / 8, oy, pole, s);
                int fx = ox + s / 8 + pole;
                int fw = s * 3 / 4;
                int fh = s / 2;
                if (kind == EventPopupCard.Kind.STARTED) {
                    Polygon flag = new Polygon();
                    flag.addPoint(fx, oy);
                    flag.addPoint(fx + fw, oy + fh / 2);
                    flag.addPoint(fx, oy + fh);
                    g.fillPolygon(flag);
                } else {
                    int cell = Math.max(2, fw / 4);
                    for (int row = 0; row * cell < fh; row++) {
                        for (int col = 0; col * cell < fw; col++) {
                            if ((row + col) % 2 == 0) {
                                g.fillRect(fx + col * cell, oy + row * cell, cell, cell);
                            }
                        }
                    }
                    g.drawRect(fx, oy, fw, fh);
                }
                break;
            }
            case BOARD:
            case ROLL: {
                g.drawRoundRect(ox, oy, s, s, s / 4, s / 4);
                int pip = Math.max(2, s / 6);
                int[][] spots = kind == EventPopupCard.Kind.ROLL
                    ? new int[][]{{1, 1}, {3, 1}, {2, 2}, {1, 3}, {3, 3}}
                    : new int[][]{{1, 1}, {2, 2}, {3, 3}};
                for (int[] spot : spots) {
                    g.fillOval(ox + s * spot[0] / 4 - pip / 2, oy + s * spot[1] / 4 - pip / 2, pip, pip);
                }
                break;
            }
            case DIGEST:
            default: {
                // A short list: three lines, the first one a heading.
                g.drawLine(ox + s / 8, oy + s / 4, ox + s * 7 / 8, oy + s / 4);
                g.drawLine(ox + s / 8, oy + s / 2, ox + s * 3 / 4, oy + s / 2);
                g.drawLine(ox + s / 8, oy + s * 3 / 4, ox + s * 5 / 8, oy + s * 3 / 4);
                break;
            }
        }
    }

    /** A 3x3 bingo board with the given cells filled. */
    private static void grid(Graphics2D g, int x, int y, int s, Color c, boolean[] filled) {
        int cell = s / 3;
        int inset = Math.max(1, cell / 6);
        for (int i = 0; i < 9; i++) {
            int cx = x + (i % 3) * cell + inset;
            int cy = y + (i / 3) * cell + inset;
            int w = cell - inset * 2;
            if (filled[i]) {
                g.fillRect(cx, cy, w, w);
            } else {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
                g.fillRect(cx, cy, w, w);
                g.setColor(c);
            }
        }
    }
}
