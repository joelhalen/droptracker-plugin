package io.droptracker.events;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemStack;

/**
 * Tracks deep sea trawling (Sailing) catches. Trawled fish never fire a
 * RuneLite loot event — they land in the ship's shared trawling net and are
 * only later collected into the inventory or cargo hold (where they turn into
 * fish crates, losing item identity). We therefore count the per-catch game
 * message ("You catch 2 raw haddock!"), which also attributes each fish to
 * the player who caught it rather than whoever empties the net.
 *
 * Ordinary fishing produces "You catch a ..." messages too, so only species
 * from the static trawling map are ever submitted — never a generic
 * name-to-id lookup. Crewmate catches ("<name> catches ..."), "Trawler's
 * trust" bonus lines and net-empty/collection messages are ignored. Prior
 * art for the message formats: github.com/CarelessEsper/deepSeaTrawling.
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
public class TrawlingHandler extends BaseEventHandler {

    public static final String SOURCE_NAME = "Deep Sea Trawling";

    /* "You catch a raw halibut!" / "You catch 2 raw giant krill!" */
    private static final Pattern CATCH_PATTERN = Pattern.compile(
        "^You catch (?<qty>an?|\\d+) (?<fish>.+?)[!.]?$"
    );

    /* Trawling-only species, keyed by lowercase item name without the "raw "
     * prefix. Acts as both the id map and the allowlist that keeps ordinary
     * fishing catches out. Trophy fish are included in case their
     * announcement arrives as a catch-style message. */
    private static final Map<String, Integer> FISH_IDS;
    static {
        Map<String, Integer> ids = new HashMap<>();
        ids.put("giant krill", ItemID.RAW_GIANT_KRILL);
        ids.put("haddock", ItemID.RAW_HADDOCK);
        ids.put("yellowfin", ItemID.RAW_YELLOWFIN);
        ids.put("halibut", ItemID.RAW_HALIBUT);
        ids.put("bluefin", ItemID.RAW_BLUEFIN);
        ids.put("marlin", ItemID.RAW_MARLIN);
        ids.put("giant blue krill", ItemID.POH_TROPHYDROP_GIANT_KRILL);
        ids.put("golden haddock", ItemID.POH_TROPHYDROP_HADDOCK);
        ids.put("orangefin", ItemID.POH_TROPHYDROP_YELLOWFIN);
        ids.put("huge halibut", ItemID.POH_TROPHYDROP_HALIBUT);
        ids.put("purplefin", ItemID.POH_TROPHYDROP_BLUEFIN);
        ids.put("swift marlin", ItemID.POH_TROPHYDROP_MARLIN);
        FISH_IDS = Collections.unmodifiableMap(ids);
    }

    private final DropHandler dropHandler;

    @Inject
    public TrawlingHandler(DropHandler dropHandler) {
        this.dropHandler = dropHandler;
    }

    @Override
    public boolean isEnabled() {
        return config.trackTrawling();
    }

    public void onGameMessage(String message) {
        if (!isEnabled() || !plugin.isTracking || message == null) {
            return;
        }

        ParsedCatch parsed = parseCatch(message);
        if (parsed == null) {
            return;
        }

        Integer itemId = resolveFishId(parsed.getFishName());
        if (itemId == null) {
            /* Ordinary fishing catch, or a species we don't know yet */
            return;
        }

        dropHandler.onActivityLoot(SOURCE_NAME,
            Collections.singletonList(new ItemStack(itemId, parsed.getQuantity())));
    }

    /** Parses a "You catch ..." message; null for everything else. */
    @VisibleForTesting
    static ParsedCatch parseCatch(String message) {
        /* The "Trawler's trust" bonus line duplicates fish that already had
         * their own catch message, so it must never be counted. */
        if (message.contains("Trawler's trust")) {
            return null;
        }
        Matcher matcher = CATCH_PATTERN.matcher(message);
        if (!matcher.matches()) {
            return null;
        }
        String qtyToken = matcher.group("qty");
        int quantity = qtyToken.startsWith("a") ? 1 : Integer.parseInt(qtyToken);
        if (quantity <= 0) {
            return null;
        }
        return new ParsedCatch(matcher.group("fish").trim(), quantity);
    }

    /** Maps a message fish name to a trawling item id, or null when unknown. */
    @VisibleForTesting
    static Integer resolveFishId(String fishName) {
        String name = fishName.toLowerCase(Locale.ROOT).trim();
        if (name.startsWith("raw ")) {
            name = name.substring(4);
        }
        Integer id = FISH_IDS.get(name);
        if (id == null && name.endsWith("s")) {
            /* Multi-catch messages may pluralize ("2 raw haddocks") */
            id = FISH_IDS.get(name.substring(0, name.length() - 1));
        }
        return id;
    }

    @Value
    @VisibleForTesting
    static class ParsedCatch {
        String fishName;
        int quantity;
    }
}
