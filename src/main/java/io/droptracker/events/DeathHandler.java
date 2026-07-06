package io.droptracker.events;

import java.util.HashMap;
import java.util.Map;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;

/**
 * Tracks local player deaths and submits them to the DropTracker.
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
public class DeathHandler extends BaseEventHandler {

    /** Suppress duplicate submissions if the client fires multiple death events. */
    private static final long DUPLICATE_WINDOW_MS = 5_000;

    private long lastDeathAtMs = 0;

    @Override
    public boolean isEnabled() {
        return config.deathEmbeds();
    }

    public void onActorDeath(ActorDeath event) {
        if (!isEnabled() || !plugin.isTracking) {
            return;
        }
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || event.getActor() != localPlayer) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastDeathAtMs < DUPLICATE_WINDOW_MS) {
            return;
        }
        lastDeathAtMs = now;

        String killerName = determineKiller(localPlayer);
        String killerType = determineKillerType(localPlayer);
        WorldPoint location = localPlayer.getWorldLocation();

        String playerName = getPlayerName();
        CustomWebhookBody webhook = createWebhookBody(playerName + " has died!");
        CustomWebhookBody.Embed embed = createEmbed(playerName + " has died!", "death");

        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("source", killerName);
        fieldData.put("killer_type", killerType);
        if (location != null) {
            fieldData.put("region_id", location.getRegionID());
            fieldData.put("location", location.getX() + "," + location.getY() + "," + location.getPlane());
        }
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);

        sendData(webhook, SubmissionType.DEATH);
    }

    private String determineKiller(Player localPlayer) {
        Actor interacting = localPlayer.getInteracting();
        if (interacting == null) {
            return "Unknown";
        }
        String name = interacting.getName();
        return (name != null && !name.trim().isEmpty()) ? name : "Unknown";
    }

    private String determineKillerType(Player localPlayer) {
        Actor interacting = localPlayer.getInteracting();
        if (interacting instanceof NPC) {
            return "npc";
        }
        if (interacting instanceof Player) {
            return "player";
        }
        return "unknown";
    }
}
