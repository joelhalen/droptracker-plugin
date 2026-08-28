package io.droptracker.service;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import org.apache.commons.lang3.ArrayUtils;

/**
 * A short rolling memory of who the local player was fighting.
 *
 * <p>Killer attribution that only looks at the death tick answers the question
 * "who is attacking me <em>right now</em>", and on the tick a player dies the
 * answer is very often "nobody": the game drops an NPC's target the moment that
 * target dies, bosses despawn with the instance, and in group content the NPC
 * has already switched to whoever is still standing. Every candidate is then
 * filtered away and the death is submitted with no source at all.
 *
 * <p>This tracker records each engagement as it is announced by
 * {@code InteractingChanged} — both directions, ours and theirs — as a
 * <em>snapshot</em> (name, NPC id, combat level) rather than a live reference,
 * so an actor that has since despawned or been garbage collected can still be
 * named. A death that no live candidate explains is then attributed to the most
 * plausible engagement from the last few ticks.
 *
 * <p>Not thread safe; every caller is on the client thread.
 */
public final class RecentCombatTracker {

    /** How stale a <em>finished</em> engagement may be and still be blamed (~7s). */
    public static final int RECENT_WINDOW_TICKS = 12;

    /**
     * How stale an engagement we never saw end may be and still be blamed (~30s).
     *
     * <p>An engagement stays "open" until the client tells us it ended, and that
     * message never arrives if the actor simply vanished (instance torn down,
     * region change, world hop). This ceiling is what stops such a leftover from
     * being blamed for an unrelated death minutes later.
     */
    public static final int OPEN_WINDOW_TICKS = 50;

    /** An engagement this fresh is treated as having been live at the death. */
    private static final int AT_DEATH_TICKS = 3;

    private static final int MAX_TRACKED = 24;

    private static final String ATTACK_OPTION = "Attack";

    /** Access-ordered so eviction drops the least recently touched engagement. */
    private final Map<String, Engagement> engagements = new LinkedHashMap<>(16, 0.75f, true);

    /** Records that {@code actor} started attacking (or otherwise targeting) us. */
    public void recordAttackedBy(@Nullable Actor actor, int tick) {
        Engagement engagement = upsert(actor, tick);
        if (engagement != null) {
            engagement.attackedUs = true;
            engagement.inboundOpen = true;
        }
    }

    /** Records that {@code actor} stopped targeting us. */
    public void endAttackedBy(@Nullable Actor actor, int tick) {
        Engagement engagement = actor != null ? engagements.get(key(actor)) : null;
        if (engagement != null && engagement.inboundOpen) {
            engagement.inboundOpen = false;
            engagement.lastTick = tick;
        }
    }

    /**
     * Records that the local player started interacting with {@code actor}.
     *
     * <p>Our own outbound target is implicitly exclusive, so any other open
     * outbound engagement is closed at the same tick.
     */
    public void recordAttacking(@Nullable Actor actor, int tick) {
        Engagement engagement = upsert(actor, tick);
        if (engagement != null) {
            engagement.weAttacked = true;
            engagement.outboundOpen = true;
        }
        endAttacking(actor, tick);
    }

    /**
     * Closes every open outbound engagement except {@code except} (pass null when
     * the local player stopped interacting with anything at all).
     */
    public void endAttacking(@Nullable Actor except, int tick) {
        String keep = except != null ? key(except) : null;
        for (Map.Entry<String, Engagement> entry : engagements.entrySet()) {
            if (entry.getKey().equals(keep)) {
                continue;
            }
            Engagement engagement = entry.getValue();
            if (engagement.outboundOpen) {
                engagement.outboundOpen = false;
                engagement.lastTick = tick;
            }
        }
    }

    /** Drops an actor from consideration — used when it dies. */
    public void forget(@Nullable Actor actor) {
        if (actor != null) {
            engagements.remove(key(actor));
        }
    }

    public void clear() {
        engagements.clear();
    }

    /**
     * @param tick       the current client tick
     * @param pvpEnabled whether another player could plausibly have killed us
     * @return the engagement most likely to explain a death on this tick
     */
    public Optional<Engagement> mostLikelyKiller(int tick, boolean pvpEnabled) {
        Engagement best = null;
        for (Engagement candidate : engagements.values()) {
            if (!eligible(candidate, tick, pvpEnabled)) {
                continue;
            }
            if (best == null || compare(candidate, best, tick) < 0) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Visible for tests: how many engagements are currently remembered. */
    public int size() {
        return engagements.size();
    }

    @Nullable
    private Engagement upsert(@Nullable Actor actor, int tick) {
        if (actor == null) {
            return null;
        }
        String key = key(actor);
        Engagement existing = engagements.get(key);
        if (existing != null) {
            existing.lastTick = tick;
            existing.actor = new WeakReference<>(actor);
            return existing;
        }

        Engagement created = snapshot(actor, tick);
        if (created == null) {
            return null;
        }
        engagements.put(key, created);
        prune(tick);
        return created;
    }

    private void prune(int tick) {
        Iterator<Engagement> values = engagements.values().iterator();
        while (values.hasNext()) {
            Engagement engagement = values.next();
            int age = tick - engagement.lastTick;
            if (age < 0 || age > OPEN_WINDOW_TICKS) {
                values.remove();
            }
        }
        // Access-ordered: the head is the least recently touched engagement.
        Iterator<String> keys = engagements.keySet().iterator();
        while (engagements.size() > MAX_TRACKED && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private static boolean eligible(Engagement engagement, int tick, boolean pvpEnabled) {
        if (engagement.player && (!pvpEnabled || engagement.friendly)) {
            // A friend or clanmate we traded with is not a killer, and outside
            // PvP no player is.
            return false;
        }
        Actor live = engagement.actor.get();
        if (live != null && live.isDead()) {
            // We killed it (or it died first); it cannot have killed us.
            return false;
        }
        int age = tick - engagement.lastTick;
        if (age < 0) {
            // Tick counter went backwards — a hop or relog; the record predates
            // this session's world and means nothing.
            return false;
        }
        return age <= (engagement.isOpen() ? OPEN_WINDOW_TICKS : RECENT_WINDOW_TICKS);
    }

    /**
     * Orders candidates best-first (negative means {@code a} is the better
     * explanation for the death).
     */
    private static int compare(Engagement a, Engagement b, int tick) {
        int result = Boolean.compare(b.wasLiveAt(tick), a.wasLiveAt(tick));
        if (result != 0) {
            return result;
        }
        // Something we were trading blows with beats something that only
        // attacked us, which beats something we merely clicked on.
        result = Integer.compare(b.directionRank(), a.directionRank());
        if (result != 0) {
            return result;
        }
        result = Boolean.compare(b.attackable, a.attackable);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(b.combatLevel, a.combatLevel);
        if (result != 0) {
            return result;
        }
        return Integer.compare(b.lastTick, a.lastTick);
    }

    /**
     * Keyed on scene index for NPCs and name for players rather than on the
     * {@link Actor} itself, so nothing here keeps a despawned actor alive.
     */
    private static String key(Actor actor) {
        if (actor instanceof NPC) {
            return "n" + ((NPC) actor).getIndex();
        }
        String name = cleanName(actor.getName());
        return "p" + (name != null ? name : String.valueOf(System.identityHashCode(actor)));
    }

    /**
     * @return a snapshot of the actor, or null when it could not plausibly kill
     *         anyone (a pet, a shopkeeper, an unnamed player)
     */
    @Nullable
    private static Engagement snapshot(Actor actor, int tick) {
        if (actor instanceof NPC) {
            NPC npc = (NPC) actor;
            NPCComposition composition = npc.getTransformedComposition();
            if (composition == null) {
                composition = npc.getComposition();
            }
            if (composition == null || composition.isFollower()) {
                return null;
            }
            boolean attackable = ArrayUtils.contains(composition.getActions(), ATTACK_OPTION);
            int combatLevel = composition.getCombatLevel();
            if (!attackable && combatLevel <= 0) {
                // Dialogue partners, pets and scenery face the player too.
                return null;
            }
            String name = cleanName(npc.getName());
            if (name == null) {
                name = cleanName(composition.getName());
            }
            // isInteractible() is deliberately not required here (it is in the
            // live scan): a few instanced attackers cannot be clicked, and this
            // snapshot is only ever consulted after the live scan came up empty.
            return new Engagement(actor, name, npc.getId(), combatLevel, false, attackable, false, tick);
        }

        if (actor instanceof Player) {
            Player player = (Player) actor;
            String name = cleanName(player.getName());
            if (name == null) {
                return null;
            }
            boolean friendly = player.isFriend() || player.isClanMember() || player.isFriendsChatMember();
            return new Engagement(actor, name, null, player.getCombatLevel(), true, true, friendly, tick);
        }

        return null;
    }

    /**
     * Actor names arrive with colour tags and non-breaking spaces; a name that is
     * nothing but markup is no name at all and must not become a blank source.
     */
    @Nullable
    public static String cleanName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace('\u00A0', ' ').replaceAll("<[^>]*>", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** An actor the local player was recently in combat with. */
    public static final class Engagement {

        private WeakReference<Actor> actor;
        private final String name;
        private final Integer npcId;
        private final int combatLevel;
        private final boolean player;
        private final boolean attackable;
        private final boolean friendly;

        private boolean attackedUs;
        private boolean weAttacked;
        private boolean inboundOpen;
        private boolean outboundOpen;
        private int lastTick;

        Engagement(Actor actor, @Nullable String name, @Nullable Integer npcId, int combatLevel,
                boolean player, boolean attackable, boolean friendly, int tick) {
            this.actor = new WeakReference<>(actor);
            this.name = name;
            this.npcId = npcId;
            this.combatLevel = combatLevel;
            this.player = player;
            this.attackable = attackable;
            this.friendly = friendly;
            this.lastTick = tick;
        }

        @Nullable
        public String getName() {
            return name;
        }

        @Nullable
        public Integer getNpcId() {
            return npcId;
        }

        public int getCombatLevel() {
            return combatLevel;
        }

        public boolean isPlayer() {
            return player;
        }

        public boolean isNpc() {
            return !player;
        }

        boolean isOpen() {
            return inboundOpen || outboundOpen;
        }

        /** Whether the engagement was, as far as we know, still running at {@code tick}. */
        boolean wasLiveAt(int tick) {
            return isOpen() || tick - lastTick <= AT_DEATH_TICKS;
        }

        int directionRank() {
            if (attackedUs && weAttacked) {
                return 2;
            }
            return attackedUs ? 1 : 0;
        }

        @Override
        public String toString() {
            return "Engagement(" + name + ", npcId=" + npcId + ", lvl=" + combatLevel
                    + ", player=" + player + ", rank=" + directionRank() + ", open=" + isOpen()
                    + ", lastTick=" + lastTick + ")";
        }
    }
}
