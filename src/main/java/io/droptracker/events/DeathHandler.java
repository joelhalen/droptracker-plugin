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
import io.droptracker.service.RecentCombatTracker;
import io.droptracker.util.DeathRegions;
import io.droptracker.util.DeathValuation;
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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NPCManager;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.VisibleForTesting;

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
    private ItemManager itemManager;

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

    /**
     * The engagements of the last few ticks, in both directions.
     *
     * <p>The live scan below can only ever find an actor that is still targeting
     * us on the death tick, and on a great many deaths nothing is: the game drops
     * an NPC's target as soon as that target dies, instanced bosses despawn with
     * the instance, and in a group the boss has already moved on to whoever is
     * still alive. This is the memory that lets those deaths still be attributed.
     */
    @VisibleForTesting
    final RecentCombatTracker recentCombat = new RecentCombatTracker();

    @Override
    public boolean isEnabled() {
        return config.deathEmbeds();
    }

    public void onInteractingChanged(InteractingChanged event) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        Actor source = event.getSource();
        Actor target = event.getTarget();
        int tick = client.getTickCount();

        if (source == localPlayer) {
            // Outbound: what we are attacking.
            if (target != null) {
                if (target.getCombatLevel() > 0) {
                    lastTarget = new WeakReference<>(target);
                }
                recentCombat.recordAttacking(target, tick);
            } else {
                recentCombat.endAttacking(null, tick);
            }
            return;
        }

        // Inbound: what is attacking us. This half of the signal used to be
        // thrown away, even though it is the one that actually answers "who
        // killed me" — the outbound target only says who we were fighting.
        if (target == localPlayer) {
            recentCombat.recordAttackedBy(source, tick);
        } else if (source != null) {
            // It turned away from us (or onto someone else). Remembering *when*
            // it disengaged is what makes a boss that dropped its target on the
            // death tick rank ahead of every older candidate.
            recentCombat.endAttackedBy(source, tick);
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
            if (self) {
                // Respawning starts a new fight; nothing before the death can
                // explain the next one.
                recentCombat.clear();
            } else {
                recentCombat.forget(event.getActor());
            }
        }
    }

    private void submitDeath(Player localPlayer) {
        WorldPoint location = currentLocation(localPlayer);
        Killer killer = identifyKiller(localPlayer);
        boolean pk = killer != null && killer.isPlayer();
        boolean npc = killer != null && killer.isNpc();

        // Classified before the fields are built because the valuation below
        // needs it: a safe death loses nothing regardless of what is carried.
        // An unknown location is treated as dangerous — the server filters on
        // this, and guessing "safe" would silently swallow the notification.
        boolean safeDeath = location != null && DeathRegions.isSafe(client, location.getRegionID());

        String playerName = getPlayerName();
        if (playerName == null) {
            log.debug("Skipping death submission: no resolvable player name");
            return;
        }

        // Read while the containers still hold what the player died with — they
        // are emptied within a tick or two of here, and this is still the death
        // tick. After the name check so a submission we are about to drop does
        // not price a full inventory on the client thread first.
        DeathValuation.Valuation valuation = DeathValuation.value(client, itemManager, safeDeath);
        CustomWebhookBody webhook = createWebhookBody(playerName + " has died!");
        CustomWebhookBody.Embed embed = createEmbed(playerName + " has died!", "death");

        // LinkedHashMap: field order is the order they appear in the embed, and
        // addFields turns a null or blank value into the literal "N/A", so a
        // value we do not have must be left out rather than added empty.
        Map<String, Object> fieldData = new LinkedHashMap<>();

        String killerName = killer != null
                ? NpcUtilities.canonicalizeSpecialSource(killer.getName())
                : null;
        if (killerName != null) {
            fieldData.put("source", killerName);
        }
        fieldData.put("killer_type", pk ? KILLER_PLAYER : npc ? KILLER_NPC : KILLER_UNKNOWN);
        fieldData.put("is_pvp", pk);

        if (killer != null) {
            if (npc && killer.getNpcId() != null) {
                fieldData.put("killer_npc_id", killer.getNpcId());
            }
            if (killer.getCombatLevel() > 0) {
                fieldData.put("killer_combat_level", killer.getCombatLevel());
            }
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
        }

        // Sent unconditionally, not inside the location block: the server's
        // safe-death filter defaults to muting safe deaths, so a death that
        // could not be located must still say which side of that line it fell
        // on rather than arriving with the field missing.
        fieldData.put("is_safe_death", safeDeath);

        if (valuation != null) {
            // What the death actually cost — the value a group filters on.
            fieldData.put("value_lost", valuation.getLostValue());
            fieldData.put("value_kept", valuation.getKeptValue());
            fieldData.put("items_lost", valuation.getLostItemCount());
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
        return actor != null ? RecentCombatTracker.cleanName(actor.getName()) : null;
    }

    /**
     * @return who most likely killed us, or null when the death cannot be
     *         attributed at all (poison, falling damage, an attacker we never
     *         saw engage us).
     */
    @Nullable
    @VisibleForTesting
    Killer identifyKiller(Player localPlayer) {
        boolean pvpEnabled = isPvpEnabled();

        Actor live = findLiveKiller(localPlayer, pvpEnabled);
        if (live != null) {
            return Killer.of(live);
        }

        // Nothing is targeting us any more. Fall back to who was, moments ago:
        // by the time ActorDeath fires the interaction is usually already gone,
        // which is exactly how a death ends up with no source on it at all.
        return recentCombat.mostLikelyKiller(client.getTickCount(), pvpEnabled)
                .map(Killer::of)
                .orElse(null);
    }

    /** The Dink-style scan: an actor that is still targeting us right now. */
    @Nullable
    private Actor findLiveKiller(Player localPlayer, boolean pvpEnabled) {
        Predicate<Actor> interactingWithUs = actor -> isInteractingWith(localPlayer, actor);

        // Fast path: whatever we were last engaged with, if it is still a
        // plausible killer.
        Actor recentTarget = lastTarget.get();
        if (isLikelyKiller(localPlayer, recentTarget, pvpEnabled)) {
            return recentTarget;
        }

        WorldView worldView = worldView(localPlayer);
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

    /**
     * The world view to search for candidates.
     *
     * <p>The local player's own view rather than the top level one: content that
     * runs in a nested world view keeps its NPCs there, and scanning the top
     * level would find none of them.
     */
    @Nullable
    private WorldView worldView(@Nullable Player localPlayer) {
        WorldView own = localPlayer != null ? localPlayer.getWorldView() : null;
        return own != null ? own : client.getTopLevelWorldView();
    }

    private boolean isPvpEnabled() {
        if (isPvpSafeZone()) {
            // Bank/lobby areas of PvP worlds and Deadman — nobody can be
            // attacked here, so no bystander should be blamed.
            return false;
        }
        return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) > 0
                || client.getWorldType().contains(WorldType.PVP)
                || client.getWorldType().contains(WorldType.DEADMAN);
    }

    private boolean isPvpSafeZone() {
        Widget widget = client.getWidget(InterfaceID.PvpIcons.SAFEZONE);
        return widget != null && !widget.isHidden();
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
                                        .thenComparing(c -> npcManager != null ? npcManager.getHealth(c.getId()) : null,
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

    /**
     * Who killed us, as a snapshot rather than a live scene reference.
     *
     * <p>The fallback path names actors that have already despawned by the time
     * the submission is built, so the attribution cannot be an {@link Actor}.
     */
    @VisibleForTesting
    static final class Killer {

        private final String name;
        private final Integer npcId;
        private final int combatLevel;
        private final boolean player;
        private final boolean npc;

        private Killer(@Nullable String name, @Nullable Integer npcId, int combatLevel,
                boolean player, boolean npc) {
            this.name = name;
            this.npcId = npcId;
            this.combatLevel = combatLevel;
            this.player = player;
            this.npc = npc;
        }

        static Killer of(Actor actor) {
            if (actor instanceof NPC) {
                NPC killerNpc = (NPC) actor;
                String name = actorName(killerNpc);
                if (name == null) {
                    // Some instanced NPCs report no name until their composition
                    // is transformed; without this the embed would carry an NPC
                    // id and no source at all.
                    NPCComposition composition = killerNpc.getTransformedComposition();
                    name = composition != null ? RecentCombatTracker.cleanName(composition.getName()) : null;
                }
                return new Killer(name, killerNpc.getId(), killerNpc.getCombatLevel(), false, true);
            }
            return new Killer(actorName(actor), null, actor.getCombatLevel(), actor instanceof Player, false);
        }

        static Killer of(RecentCombatTracker.Engagement engagement) {
            return new Killer(engagement.getName(), engagement.getNpcId(), engagement.getCombatLevel(),
                    engagement.isPlayer(), engagement.isNpc());
        }

        @Nullable
        String getName() {
            return name;
        }

        @Nullable
        Integer getNpcId() {
            return npcId;
        }

        int getCombatLevel() {
            return combatLevel;
        }

        boolean isPlayer() {
            return player;
        }

        boolean isNpc() {
            return npc;
        }
    }
}
