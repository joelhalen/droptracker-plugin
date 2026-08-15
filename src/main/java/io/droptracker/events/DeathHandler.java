package io.droptracker.events;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.DeathRegions;
import io.droptracker.util.NpcUtilities;
import io.droptracker.util.RegionNameRegistry;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ParamID;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.NPCManager;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

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

    private static final String ATTACK_OPTION = "Attack";

    private static final String KILLER_NPC = "npc";
    private static final String KILLER_PLAYER = "player";
    private static final String KILLER_UNKNOWN = "unknown";

    @Inject
    private NPCManager npcManager;

    @Inject
    private RegionNameRegistry regionNames;

    private long lastDeathAtMs = 0;

    /**
     * The last actor the local player interacted with.
     *
     * Reading {@code localPlayer.getInteracting()} on the death tick is not
     * enough on its own: the interaction is frequently already cleared by then,
     * and it answers the wrong question anyway (who we were attacking, not who
     * was attacking us). Tracking the target as it changes gives a candidate
     * that survives the death tick.
     *
     * Weakly held so a despawning actor can still be collected; the referent
     * may be null.
     */
    private WeakReference<Actor> lastTarget = new WeakReference<>(null);

    @Override
    public boolean isEnabled() {
        return config.deathEmbeds();
    }

    public void onInteractingChanged(InteractingChanged event) {
        if (event.getSource() == client.getLocalPlayer()
                && event.getTarget() != null
                && event.getTarget().getCombatLevel() > 0) {
            lastTarget = new WeakReference<>(event.getTarget());
        }
    }

    public void onActorDeath(ActorDeath event) {
        Player localPlayer = client.getLocalPlayer();
        boolean self = localPlayer != null && event.getActor() == localPlayer;

        try {
            if (!self || !isEnabled() || !plugin.isTracking) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastDeathAtMs < DUPLICATE_WINDOW_MS) {
                return;
            }
            lastDeathAtMs = now;

            submitDeath(localPlayer);
        } finally {
            // Clear the tracked target once it is dead — ours or theirs — so a
            // later death cannot be attributed to a corpse.
            if (self || event.getActor() == lastTarget.get()) {
                lastTarget = new WeakReference<>(null);
            }
        }
    }

    private void submitDeath(Player localPlayer) {
        WorldPoint location = currentLocation(localPlayer);
        Actor killer = identifyKiller(localPlayer);
        boolean pk = killer instanceof Player;
        boolean npc = killer instanceof NPC;

        String playerName = getPlayerName();
        CustomWebhookBody webhook = createWebhookBody(playerName + " has died!");
        CustomWebhookBody.Embed embed = createEmbed(playerName + " has died!", "death");

        // LinkedHashMap: field order is the order they appear in the embed, and
        // addFields turns a null or blank value into the literal "N/A", so a
        // value we do not have must be left out rather than added empty.
        Map<String, Object> fieldData = new LinkedHashMap<>();

        String killerName = NpcUtilities.canonicalizeSpecialSource(actorName(killer));
        if (killerName != null) {
            fieldData.put("source", killerName);
        }
        fieldData.put("killer_type", pk ? KILLER_PLAYER : npc ? KILLER_NPC : KILLER_UNKNOWN);
        fieldData.put("is_pvp", pk);

        if (npc) {
            NPC killerNpc = (NPC) killer;
            fieldData.put("killer_npc_id", killerNpc.getId());
            if (killerNpc.getCombatLevel() > 0) {
                fieldData.put("killer_combat_level", killerNpc.getCombatLevel());
            }
        } else if (pk && killer.getCombatLevel() > 0) {
            fieldData.put("killer_combat_level", killer.getCombatLevel());
        }

        if (location != null) {
            int regionId = location.getRegionID();
            fieldData.put("region_id", regionId);

            RegionNameRegistry.Area area = regionNames.lookup(regionId);
            if (area != null) {
                fieldData.put("region_name", area.getName());
                if (area.getType() != null) {
                    fieldData.put("region_type", area.getType());
                }
                // "location" is what the notification embed renders and what
                // the custom-embed {location} token has always been documented
                // to be ("Catacombs of Kourend"). Leaving it out when the
                // region is unnamed lets the server fall back to showing the
                // bare region id rather than printing coordinates at people.
                fieldData.put("location", area.getName());
            }

            // The raw point still travels, just no longer as "location".
            fieldData.put("coordinates", location.getX() + "," + location.getY() + "," + location.getPlane());
            fieldData.put("plane", location.getPlane());
            fieldData.put("instanced", isInstance(localPlayer));
            fieldData.put("is_safe_death", DeathRegions.isSafe(client, regionId));
        }

        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);

        sendData(webhook, SubmissionType.DEATH);
    }

    /**
     * The local player's position, corrected for instances.
     *
     * Inside an instance {@code getWorldLocation()} returns a point in template
     * space, whose region id belongs to whichever chunk the instance was built
     * from — so every raid, Gauntlet, Inferno and Nightmare death used to
     * report a region that had nothing to do with where it happened.
     */
    @Nullable
    private WorldPoint currentLocation(Player localPlayer) {
        if (localPlayer == null) {
            return null;
        }
        WorldView worldView = localPlayer.getWorldView();
        if (worldView != null && worldView.isInstance()) {
            return WorldPoint.fromLocalInstance(client, localPlayer.getLocalLocation(), worldView.getPlane());
        }
        return localPlayer.getWorldLocation();
    }

    private boolean isInstance(Player localPlayer) {
        WorldView worldView = localPlayer != null ? localPlayer.getWorldView() : null;
        return worldView != null && worldView.isInstance();
    }

    @Nullable
    private static String actorName(@Nullable Actor actor) {
        if (actor == null) {
            return null;
        }
        String name = actor.getName();
        return name != null && !name.trim().isEmpty() ? name : null;
    }

    /**
     * @return the actor that most likely killed us, or null when the death
     *         cannot be attributed (poison, falling damage, an attacker that
     *         already despawned).
     */
    @Nullable
    private Actor identifyKiller(Player localPlayer) {
        boolean pvpEnabled = isPvpEnabled();
        Predicate<Actor> interactingWithUs = actor -> isInteractingWith(localPlayer, actor);

        // Fast path: whatever we were last engaged with, if it is still a
        // plausible killer.
        Actor recentTarget = lastTarget.get();
        if (isLikelyKiller(localPlayer, recentTarget, pvpEnabled)) {
            return recentTarget;
        }

        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return null;
        }

        // A player can only have killed us where PvP is possible. Prefer one
        // who is not a friend/clanmate, since those are usually bystanders.
        if (pvpEnabled) {
            Optional<? extends Player> pker = worldView.players().stream()
                    .filter(interactingWithUs)
                    .min(pkComparator(localPlayer));
            if (pker.isPresent()) {
                return pker.get();
            }
        }

        // Otherwise the best-ranked NPC currently attacking us.
        return worldView.npcs().stream()
                .filter(interactingWithUs)
                .filter(npc -> isValidKiller(npc.getTransformedComposition()))
                .min(npcComparator(localPlayer))
                .orElse(null);
    }

    private boolean isPvpEnabled() {
        return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) > 0
                || client.getWorldType().contains(WorldType.PVP)
                || client.getWorldType().contains(WorldType.DEADMAN);
    }

    /** Whether the actor is alive and targeting the local player. */
    private static boolean isInteractingWith(Player localPlayer, @Nullable Actor actor) {
        return actor != null
                && actor != localPlayer
                && !actor.isDead()
                && actor.getInteracting() == localPlayer;
    }

    /** Whether an NPC is a plausible killer rather than scenery or a pet. */
    private static boolean isValidKiller(@Nullable NPCComposition composition) {
        return composition != null
                && composition.isInteractible()
                && !composition.isFollower()
                && composition.getCombatLevel() > 0;
    }

    private static boolean isLikelyKiller(Player localPlayer, @Nullable Actor actor, boolean pvpEnabled) {
        if (!isInteractingWith(localPlayer, actor)) {
            return false;
        }

        if (actor instanceof Player) {
            Player other = (Player) actor;
            return pvpEnabled && !other.isClanMember() && !other.isFriend() && !other.isFriendsChatMember();
        }

        if (actor instanceof NPC) {
            NPCComposition composition = ((NPC) actor).getTransformedComposition();
            return isValidKiller(composition) && ArrayUtils.contains(composition.getActions(), ATTACK_OPTION);
        }

        return false;
    }

    /**
     * Orders candidate NPCs by how likely each is to be the killer, best first
     * (so {@code Stream#min} picks the winner).
     */
    private Comparator<NPC> npcComparator(Player localPlayer) {
        return Comparator
                .comparing(
                        NPC::getTransformedComposition,
                        Comparator.nullsFirst(
                                Comparator
                                        // named in the hitpoints UI — i.e. something the game
                                        // itself treats as a notable opponent
                                        .comparing((NPCComposition c) -> c.getStringValue(ParamID.NPC_HP_NAME),
                                                Comparator.comparing(StringUtils::isNotEmpty))
                                        .thenComparing(c -> ArrayUtils.contains(c.getActions(), ATTACK_OPTION))
                                        .thenComparingInt(NPCComposition::getCombatLevel)
                                        .thenComparingInt(NPCComposition::getSize)
                                        .thenComparing(NPCComposition::isMinimapVisible)
                                        .thenComparing(c -> npcManager.getHealth(c.getId()),
                                                Comparator.nullsFirst(Comparator.naturalOrder()))))
                .thenComparingInt(npc -> -distanceTo(localPlayer, npc))
                .reversed();
    }

    /**
     * Orders candidate players by how likely each is to be the killer, best
     * first. Friends and clanmates rank last; a similar combat level and
     * proximity rank higher.
     */
    private static Comparator<Player> pkComparator(Player localPlayer) {
        return Comparator
                .comparing(Player::isClanMember)
                .thenComparing(Player::isFriend)
                .thenComparing(Player::isFriendsChatMember)
                .thenComparingInt(p -> Math.abs(localPlayer.getCombatLevel() - p.getCombatLevel()))
                .thenComparingInt(p -> -p.getCombatLevel())
                .thenComparing(p -> p.getOverheadIcon() == null)
                .thenComparing(p -> p.getTeam() == localPlayer.getTeam())
                .thenComparingInt(p -> distanceTo(localPlayer, p));
    }

    private static int distanceTo(Player localPlayer, Actor other) {
        if (localPlayer.getLocalLocation() == null || other.getLocalLocation() == null) {
            return Integer.MAX_VALUE;
        }
        return localPlayer.getLocalLocation().distanceTo(other.getLocalLocation());
    }
}
