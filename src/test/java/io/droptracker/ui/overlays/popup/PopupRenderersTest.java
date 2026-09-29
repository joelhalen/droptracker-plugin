package io.droptracker.ui.overlays.popup;

import com.google.gson.Gson;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.EventNudgeStyle;
import io.droptracker.models.EventPopupCard;
import io.droptracker.models.EventPopupStyle;
import io.droptracker.models.api.EventNotification;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.util.ChatMessageUtil;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Paints every pop-up style for every kind of news, through every stage of
 * its animation, off-screen: nothing may throw, and every card must take up
 * real space. The cards come from the service's real renderer, so this also
 * pins that each notification type fills in its showcase card.
 *
 * <p>Set POPUP_GALLERY_DIR to also write the frames out as PNGs (and
 * POPUP_SPRITES_DIR to a folder of {@code <itemId>.png} icons to use real
 * item sprites) for eyeballing layouts without a game client.
 */
public class PopupRenderersTest {
    private static final long[] AGES = {0, 60, 140, 240, 360, 520, 800, 1600};

    private final Gson gson = new Gson();

    @Test
    public void everyKindRendersAStructuredCard() {
        Map<String, Toast> toasts = sampleToasts();
        assertEquals(10, toasts.size());
        for (Map.Entry<String, Toast> entry : toasts.entrySet()) {
            EventPopupCard card = entry.getValue().getCard();
            assertNotNull(entry.getKey() + " has no card", card);
            assertTrue(entry.getKey(), !card.getHeadline().isEmpty());
        }
        EventPopupCard tile = toasts.get("tile").getCard();
        assertEquals(EventPopupCard.Kind.TILE, tile.getKind());
        assertEquals("Bandos set", tile.getHeadline());
        assertEquals("2nd of 5", tile.getRightValue());
        EventPopupCard progress = toasts.get("progress").getCard();
        assertEquals(0.4f, progress.fraction(), 0.001f);
        assertEquals("2/5", progress.getRightValue());
    }

    @Test
    public void everyStylePaintsEveryKindThroughItsAnimation() throws IOException {
        PopupRenderers renderers = new PopupRenderers(PopupRenderersTest::sprite);
        Map<String, Toast> toasts = sampleToasts();
        String galleryDir = System.getenv("POPUP_GALLERY_DIR");

        for (EventPopupStyle style : EventPopupStyle.values()) {
            PopupRenderer renderer = renderers.popup(style);
            paintAll(renderer, "popup-" + style.key(), renderer.preferredWidth(), toasts, galleryDir);
        }
        for (EventNudgeStyle style : EventNudgeStyle.values()) {
            PopupRenderer renderer = renderers.nudge(style);
            paintAll(renderer, "hud-" + style.key(), 200, toasts, galleryDir);
        }
    }

    @Test
    public void styleNamesParseLoosely() {
        assertEquals(EventPopupStyle.SHOWCASE_STONE, EventPopupStyle.parse("Showcase (stone)"));
        assertEquals(EventPopupStyle.SHOWCASE_STONE, EventPopupStyle.parse("showcase_stone"));
        assertEquals(EventPopupStyle.RIBBON, EventPopupStyle.parse("4"));
        assertEquals(EventNudgeStyle.SHOWCASE_MINI, EventNudgeStyle.parse("mini showcase"));
        assertEquals(null, EventNudgeStyle.parse("9"));
        assertEquals(EventPopupStyle.CLASSIC, EventPopupStyle.RIBBON.next());
    }

    private void paintAll(PopupRenderer renderer, String name, int width, Map<String, Toast> toasts,
                          String galleryDir) throws IOException {
        // Settled frame of every kind, stacked, plus an animation strip of the tile.
        List<BufferedImage> settled = new ArrayList<>();
        for (Map.Entry<String, Toast> entry : toasts.entrySet()) {
            Toast toast = entry.getValue();
            BufferedImage last = null;
            for (long age : AGES) {
                last = paint(renderer, toast, width, toast.getCreatedAt() + age);
            }
            // Deep into the fade: still paints, just faint.
            paint(renderer, toast, width, toast.getCreatedAt() + toast.lifetimeMs() - 100);
            settled.add(last);
        }
        if (galleryDir == null) {
            return;
        }
        File dir = new File(galleryDir);
        dir.mkdirs();
        ImageIO.write(stack(settled, width), "png", new File(dir, name + ".png"));
        List<BufferedImage> strip = new ArrayList<>();
        Toast tile = toasts.get("tile");
        for (long age : new long[]{40, 120, 200, 300, 450, 700, 1200}) {
            strip.add(paint(renderer, tile, width, tile.getCreatedAt() + age));
        }
        ImageIO.write(row(strip), "png", new File(dir, name + "-anim.png"));
    }

    /** One card painted over a stand-in game backdrop. */
    private static BufferedImage paint(PopupRenderer renderer, Toast toast, int width, long now) {
        int canvasW = width + 20;
        int canvasH = 190;
        BufferedImage image = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x4c, 0x5a, 0x3a), 0, canvasH, new Color(0x2a, 0x30, 0x22)));
        g.fillRect(0, 0, canvasW, canvasH);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int height = renderer.draw(g, toast, 10, 10, width, now);
        g.dispose();
        long age = now - toast.getCreatedAt();
        assertTrue("height " + height + " at age " + age, height > 0 && height < canvasH - 10);
        return image;
    }

    private static BufferedImage stack(List<BufferedImage> images, int width) {
        int h = 0;
        for (BufferedImage image : images) {
            h += image.getHeight();
        }
        BufferedImage out = new BufferedImage(width + 20, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int y = 0;
        for (BufferedImage image : images) {
            g.drawImage(image, 0, y, null);
            y += image.getHeight();
        }
        g.dispose();
        return out;
    }

    private static BufferedImage row(List<BufferedImage> images) {
        int w = 0;
        for (BufferedImage image : images) {
            w += image.getWidth();
        }
        BufferedImage out = new BufferedImage(w, images.get(0).getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int x = 0;
        for (BufferedImage image : images) {
            g.drawImage(image, x, 0, null);
            x += image.getWidth();
        }
        g.dispose();
        return out;
    }

    /** A real sprite from POPUP_SPRITES_DIR when given, else a stand-in blob. */
    private static BufferedImage sprite(int itemId) {
        String dir = System.getenv("POPUP_SPRITES_DIR");
        if (dir != null) {
            try {
                BufferedImage image = ImageIO.read(new File(dir, itemId + ".png"));
                if (image != null) {
                    return image;
                }
            } catch (IOException ignored) {
                // fall through to the stand-in
            }
        }
        BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x9a, 0x7b, 0x4f));
        g.fillOval(6, 4, 24, 24);
        g.setColor(Color.BLACK);
        g.drawOval(6, 4, 24, 24);
        g.dispose();
        return image;
    }

    /* ---------------- samples, through the real renderer ---------------- */

    private Map<String, Toast> sampleToasts() {
        EventNotificationService service = new EventNotificationService(
            new DropTrackerConfig() {
                @Override
                public void setPinnedEventId(int eventId) {
                }

                @Override
                public void setLastVersionNotified(String versionNotified) {
                }

                @Override
                public void setLastAccountName(String accountName) {
                }

                @Override
                public void setCustomApiEndpoint(String customApiEndpoint) {
                }

                @Override
                public void setLastAccountHash(String accountHash) {
                }
            }, null, new ChatMessageUtil(), null, null, null, null);
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("tile", "event_completion|high|{\"task_id\":1,\"task_label\":\"Obtain Bandos tassets\","
            + "\"player_name\":\"Zezima\",\"received_item\":\"Bandos tassets\",\"icon_item_id\":11834,"
            + "\"points\":10,\"cell_idxs\":[6],\"cell_labels\":[\"Bandos set\"],"
            + "\"tiles_completed\":7,\"team_rank\":2,\"team_count\":5}");
        samples.put("complete", "event_completion|normal|{\"task_id\":2,\"task_label\":\"Obtain a Dragon warhammer\","
            + "\"player_name\":\"Zezima\",\"received_item\":\"Dragon warhammer\",\"icon_item_id\":13576,\"points\":5}");
        samples.put("progress", "event_task_progress|low|{\"task_id\":3,\"task_label\":\"Obtain 5 Abyssal whips\","
            + "\"player_name\":\"Lynx Titan\",\"received_item\":\"Abyssal whip\",\"icon_item_id\":4151,"
            + "\"progress\":2,\"target\":5}");
        samples.put("lead", "event_lead_change|high|{\"team_name\":\"Iron Eagles\",\"team_score\":1240}");
        samples.put("line", "event_line|high|{\"team_name\":\"Iron Eagles\",\"bonus_points\":25}");
        samples.put("blackout", "event_blackout|high|{\"team_name\":\"Iron Eagles\",\"bonus_points\":100}");
        samples.put("start", "event_started|high|{}");
        samples.put("end", "event_ended|high|{}");
        samples.put("board", "event_board_turn|normal|{\"player_name\":\"Zezima\",\"dice_str\":\"4 + 2\","
            + "\"tile_to\":18,\"next_task_label\":\"Obtain any Zenyte jewel\"}");
        samples.put("roll", "event_board_roll_prompt|high|{\"coins_awarded\":3}");

        long now = System.currentTimeMillis();
        Map<String, Toast> toasts = new LinkedHashMap<>();
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            String[] parts = sample.getValue().split("\\|", 3);
            EventNotification n = gson.fromJson("{\"id\":\"" + sample.getKey() + "\",\"type\":\"" + parts[0]
                + "\",\"ts\":1,\"priority\":\"" + parts[1] + "\","
                + "\"event\":{\"id\":7,\"name\":\"Autumn Bingo\"},\"data\":" + parts[2] + "}",
                EventNotification.class);
            service.getToasts().clear();
            // A tile folding two more updates exercises the "+N more" badges too.
            assertTrue(sample.getKey(), service.previewNotification(n, null, null,
                "tile".equals(sample.getKey()) ? 2 : 0));
            toasts.put(sample.getKey(), service.getToasts().getFirst().pinned(null, null, now));
        }
        return toasts;
    }
}
