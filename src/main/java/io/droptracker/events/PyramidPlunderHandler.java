package io.droptracker.events;

import lombok.AllArgsConstructor;
import java.util.Collections;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemStack;

/**
 * Tracks Pharaoh's sceptres found in Pyramid Plunder. The sceptre comes from
 * a golden chest or sarcophagus and never fires a RuneLite loot event
 * (runelite/runelite#11670), so the game message is the only signal:
 *
 *   "You have found a Pharaoh's sceptre!"                         (inventory)
 *   "You have found a Pharaoh's sceptre! It fell on the floor."   (full inventory)
 *
 * The floor variant is confirmed from an in-game screenshot (Dink #741); the
 * inventory variant is the same line without the suffix. Both are red
 * GAMEMESSAGEs, which players cannot send, so no region check is needed (the
 * player is ejected from the pyramid as the message arrives anyway).
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
@AllArgsConstructor(onConstructor_ = @Inject)
public class PyramidPlunderHandler extends BaseEventHandler {

    public static final String SOURCE_NAME = "Pyramid Plunder";

    private static final Pattern SCEPTRE_PATTERN = Pattern.compile(
        "^You have found (?:a|the) Pharaoh['’]s sceptre!(?: It fell on the floor\\.?)?$",
        Pattern.CASE_INSENSITIVE
    );

    private final DropHandler dropHandler;

    @Override
    public boolean isEnabled() {
        return config.trackActivities();
    }

    public void onGameMessage(String message) {
        if (!isEnabled() || !plugin.isTracking || message == null) {
            return;
        }
        if (!isSceptreMessage(message)) {
            return;
        }
        dropHandler.onActivityLoot(SOURCE_NAME,
            Collections.singletonList(new ItemStack(ItemID.PHARAOHS_SCEPTRE, 1)));
    }

    @VisibleForTesting
    static boolean isSceptreMessage(String message) {
        return SCEPTRE_PATTERN.matcher(message.trim()).matches();
    }
}
