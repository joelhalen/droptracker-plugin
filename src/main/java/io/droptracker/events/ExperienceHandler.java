package io.droptracker.events;

import com.google.common.collect.ImmutableSet;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.QuantityFormatter;
import org.jetbrains.annotations.VisibleForTesting;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import com.google.gson.Gson;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static net.runelite.api.Experience.MAX_REAL_LEVEL;

/**
 * Handles experience gain and level-up notifications.
 *
 * <p><b>Initialization delay:</b> Stat packets arrive over several game ticks after login.
 * The handler waits {@link #INIT_GAME_TICKS} (~10 s) before recording the baseline skill
 * levels to avoid treating existing XP as newly gained. If a {@link StatChanged} event
 * arrives before initialization completes, a forced re-initialization is scheduled.</p>
 *
 * <p><b>Level-up accumulation:</b> Multiple skills may level up within the same tick (e.g.
 * combat stats). Level-up names are queued in {@link #levelledSkills} and flushed after
 * a 2-tick settling window so a single webhook embed covers all simultaneous level-ups.</p>
 *
 * <p><b>XP milestones:</b> After reaching 99 in a skill, each {@link #XP_INTERVAL_MILLIONS}
 * million XP boundary triggers an XP-milestone notification (default: every 1 M XP).
 * Milestones are also batched over the same 2-tick window.</p>
 *
 * <p><b>Virtual levels:</b> Levels above 99 (virtual levels up to 126, plus the sentinel
 * {@link #LEVEL_FOR_MAX_XP} = 127 for 200 M XP) are tracked when
 * {@link #TRACK_VIRTUAL_LEVELS} is {@code true}.</p>
 *
 * <p><b>World-switch reset:</b> When the player hops to a world with a different
 * {@link WorldType} set (e.g. Seasonal → Standard), stored skill levels are cleared and
 * re-initialized to prevent spurious level-up notifications.</p>
 *
 * <p>Enabled/disabled via {@link io.droptracker.DropTrackerConfig#trackExperience()}.</p>
 */
@Slf4j
@Singleton
public class ExperienceHandler extends BaseEventHandler {

    /**
     * Sentinel virtual level (127) used to represent a skill at the maximum XP of 200 M.
     * {@link net.runelite.api.Experience#MAX_VIRT_LEVEL} is 126 (level reached just below 200 M);
     * adding 1 gives a distinct value used only for the 200 M cap display.
     */
    public static final int LEVEL_FOR_MAX_XP = Experience.MAX_VIRT_LEVEL + 1; // 127

    /**
     * Number of game ticks to wait after login before capturing the baseline skill levels.
     * Approximately 10 seconds (16 × ~600 ms). Prevents the initial stat-packet delivery
     * from being mis-classified as XP gains.
     */
    static final @VisibleForTesting int INIT_GAME_TICKS = 16; // ~10s

    /**
     * World types that represent non-standard play environments (PvP Arena, Seasonal, etc.).
     * Used to detect world-type changes that necessitate a level baseline re-initialization.
     */
    private static final Set<WorldType> SPECIAL_WORLDS = EnumSet.of(WorldType.PVP_ARENA, WorldType.QUEST_SPEEDRUNNING, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD, WorldType.DEADMAN, WorldType.SEASONAL);

    /** Total number of OSRS skills; used as the queue capacity for {@link #levelledSkills}. */
    private static final int SKILL_COUNT = Skill.values().length;

    /** Display name used as the map key for the player's overall combat level. */
    private static final String COMBAT_NAME = "Combat";

    /** Skills that contribute to the combat level formula. Populated in the static initializer. */
    private static final Set<String> COMBAT_COMPONENTS;

    // -------------------------------------------------------------------------
    // Tracking configuration constants
    // These are currently hard-coded; future work could expose them as config items.
    // -------------------------------------------------------------------------

    /** Fire an XP-milestone notification for every N million XP gained past 99 (default: 1 M). */
    private static final int XP_INTERVAL_MILLIONS = 1;

    /** Minimum skill level required before a level-up notification is sent. */
    private static final int LEVEL_MIN_VALUE = 1;

    /** Send a notification every Nth level (1 = every level, 5 = multiples of 5, etc.). */
    private static final int LEVEL_INTERVAL = 1;

    /** When {@code true}, virtual levels above 99 (up to 126/127) trigger notifications. */
    private static final boolean TRACK_VIRTUAL_LEVELS = true;

    /** When {@code true}, overall combat level increases also trigger a notification. */
    private static final boolean TRACK_COMBAT_LEVEL = true;

    // -------------------------------------------------------------------------
    // Mutable per-session state
    // -------------------------------------------------------------------------

    /**
     * Queue of skill names (including {@link #COMBAT_NAME}) that leveled up this tick window.
     * Drained in {@link #notifyLevels()} after the 2-tick settling period.
     */
    private final BlockingQueue<String> levelledSkills = new ArrayBlockingQueue<>(SKILL_COUNT + 1);

    /** Skills for which an XP milestone was crossed this tick window. */
    private final Set<Skill> xpReached = EnumSet.noneOf(Skill.class);

    /** Most recent known level for each skill name (including {@link #COMBAT_NAME}). */
    private final Map<String, Integer> currentLevels = new HashMap<>();

    /** Most recent known XP total for each skill. */
    private final Map<Skill, Integer> currentXp = new EnumMap<>(Skill.class);

    /**
     * Level each skill was at immediately before the current level-up event.
     * Stored so the embed can report the number of levels gained in one session.
     */
    private final Map<String, Integer> previousLevels = new HashMap<>();

    /** Ticks elapsed since a level-up or XP milestone was first queued; resets on new events. */
    private int ticksWaited = 0;

    /** Ticks counted during the post-login initialization delay (counts up to {@link #INIT_GAME_TICKS}). */
    private int initTicks = 0;

    /**
     * The subset of {@link #SPECIAL_WORLDS} that were active when levels were last initialized.
     * {@code null} until first initialization; compared on world-hop to detect environment changes.
     */
    private Set<WorldType> specialWorldType = null;

    @Inject
    private ClientThread clientThread;
    
    @Inject 
    private static Gson gson;

    
    @Override
    public boolean isEnabled() {
        return config.trackExperience();
    }

    /**
     * Captures the current skill levels and XP totals from the game client as the baseline.
     * Called once the {@link #INIT_GAME_TICKS} delay has elapsed to ensure the client has
     * received the full stat packet for the logged-in character.
     * Also records the current world type set so world-hops can be detected.
     */
    private void initLevels() {
        for (Skill skill : Skill.values()) {
            int xp = client.getSkillExperience(skill);
            int level = client.getRealSkillLevel(skill); // O(1)
            if (level >= MAX_REAL_LEVEL) {
                level = getLevel(xp);
            }
            currentLevels.put(skill.getName(), level);
            currentXp.put(skill, xp);
        }
        currentLevels.put(COMBAT_NAME, calculateCombatLevel());
        this.initTicks = 0;
        this.specialWorldType = getSpecialWorldTypes();
        log.debug("Initialized current skill levels: {}", currentLevels);
    }

    /**
     * Clears all accumulated level/XP state and resets the initialization counter.
     * Called on logout ({@link GameState#LOGIN_SCREEN}) and on world-hops that change
     * the active {@link WorldType} set to prevent cross-session level confusion.
     */
    public void reset() {
        levelledSkills.clear();
        clientThread.invoke(() -> {
            this.initTicks = 0;
            this.ticksWaited = 0;
            xpReached.clear();
            currentXp.clear();
            currentLevels.clear();
            previousLevels.clear();
            this.specialWorldType = null;
        });
    }

    /**
     * Per-tick update driving the two-phase initialization and notification flush.
     *
     * <ol>
     *   <li>If {@link #initTicks} has exceeded {@link #INIT_GAME_TICKS}, call
     *       {@link #initLevels()} to capture the baseline skill state.</li>
     *   <li>If the baseline isn't ready yet, increment {@link #initTicks} and wait.</li>
     *   <li>Once initialized, if queued level-ups or XP milestones exist, increment
     *       {@link #ticksWaited}. After 2 ticks fire {@link #attemptNotify()} to send
     *       a single batched embed covering all simultaneous skill changes.</li>
     * </ol>
     */
    public void onTick() {
        if (this.initTicks > INIT_GAME_TICKS) {
            initLevels();
            return;
        }

        if (currentLevels.size() < SKILL_COUNT) {
            this.initTicks++;
            return;
        }

        // Handle level ups and XP milestones
        if (!levelledSkills.isEmpty() || !xpReached.isEmpty()) {
            // We wait a couple extra ticks so we can ensure that we process all the levels of the previous tick
            if (++this.ticksWaited > 2) {
                this.ticksWaited = 0;
                // ensure notifier was not disabled during ticks waited
                if (isEnabled()) {
                    attemptNotify();
                } else {
                    levelledSkills.clear();
                    xpReached.clear();
                }
            }
        }
    }

    /**
     * Delegates a skill XP change event to {@link #handleStatChange}.
     *
     * @param statChange the RuneLite stat-changed event
     */
    public void onStatChanged(StatChanged statChange) {
        this.handleStatChange(statChange.getSkill(), statChange.getLevel(), statChange.getXp());
    }

    /**
     * Reacts to game state transitions.
     * <ul>
     *   <li>{@link GameState#LOGIN_SCREEN} – player logged out; reset all accumulated state.</li>
     *   <li>{@link GameState#LOGGED_IN} with a different world-type set – player hopped worlds;
     *       reset to re-initialize with the new character profile.</li>
     * </ul>
     *
     * @param gameStateChanged the RuneLite game-state-changed event
     */
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
            this.reset();
        } else if (gameStateChanged.getGameState() == GameState.LOGGED_IN && !getSpecialWorldTypes().equals(this.specialWorldType)) {
            // world switched where player may have different level profiles; re-initialize
            this.reset();
        }
    }

    /**
     * Core stat-change processing. Compares the incoming skill XP and level against the
     * stored baseline and queues level-up / XP-milestone notifications as appropriate.
     *
     * <p>Edge cases handled:
     * <ul>
     *   <li>First stat event before baseline — forces re-initialization via {@link #INIT_GAME_TICKS}.</li>
     *   <li>Level regression (de-leveling on seasonal worlds, etc.) — resets all state.</li>
     *   <li>XP milestone only triggers for skills at or above {@link Experience#MAX_REAL_LEVEL}.</li>
     *   <li>Combat level is recalculated from the 7 combat components after any component change.</li>
     * </ul>
     * </p>
     *
     * @param skill the skill whose XP changed
     * @param level the new real skill level (as reported by the client)
     * @param xp    the new XP total for the skill
     */
    private void handleStatChange(Skill skill, int level, int xp) {
        if (xp <= 0 || level <= 1 || !isEnabled()) return;

        Integer previousXp = currentXp.put(skill, xp);
        if (previousXp == null) {
            return;
        }

        String skillName = skill.getName();
        int virtualLevel = level < MAX_REAL_LEVEL ? level : getLevel(xp); // avoid log(n) query when not needed
        Integer previousLevel = currentLevels.put(skillName, virtualLevel);

        if (previousLevel == null) {
            this.initTicks = INIT_GAME_TICKS; // force init on next tick
            return;
        }

        if (virtualLevel < previousLevel || xp < previousXp) {
            // base skill level should never regress; reset notifier state
            reset();
            return;
        }

        // Check normal skill level up
        checkLevelUp(true, skillName, previousLevel, virtualLevel);

        // Check if xp milestone reached
        int xpInterval = XP_INTERVAL_MILLIONS * 1_000_000;
        if (xpInterval > 0 && level >= MAX_REAL_LEVEL && xp > previousXp) {
            int remainder = xp % xpInterval;
            if (remainder == 0 || xp - remainder > previousXp || xp >= Experience.MAX_SKILL_XP) {
                log.debug("Observed XP milestone for {} to {}", skill, xp);
                xpReached.add(skill);
                this.ticksWaited = 0;
            }
        }

        // Skip combat level checking if no level up has occurred
        if (virtualLevel <= previousLevel) {
            // only return if we don't need to initialize combat level for the first time
            if (currentLevels.containsKey(COMBAT_NAME))
                return;
        }

        // Check for combat level increase
        if (COMBAT_COMPONENTS.contains(skillName) && currentLevels.size() >= SKILL_COUNT) {
            int combatLevel = calculateCombatLevel();
            Integer previousCombatLevel = currentLevels.put(COMBAT_NAME, combatLevel);
            checkLevelUp(TRACK_COMBAT_LEVEL, COMBAT_NAME, previousCombatLevel, combatLevel);
        }
    }

    /**
     * Determines whether a skill level change constitutes a notification-worthy level-up and,
     * if so, adds the skill name to {@link #levelledSkills}.
     *
     * @param configEnabled {@code true} if the relevant config toggle is on (e.g. combat tracking)
     * @param skill         display name of the skill (or {@link #COMBAT_NAME})
     * @param previousLevel the level before this change, or {@code null} if unknown
     * @param currentLevel  the level after this change
     */
    private void checkLevelUp(boolean configEnabled, String skill, Integer previousLevel, int currentLevel) {
        if (previousLevel == null || currentLevel <= previousLevel) {
            log.trace("Ignoring non-level-up for {}: {}", skill, currentLevel);
            return;
        }

        if (!configEnabled) {
            log.trace("Ignoring level up of {} to {} due to disabled config setting", skill, currentLevel);
            return;
        }

        if (!checkLevelInterval(previousLevel, currentLevel, COMBAT_NAME.equals(skill))) {
            log.trace("Ignoring level up of {} from {} to {} that does not align with config interval", skill, previousLevel, currentLevel);
            return;
        }

        if (levelledSkills.offer(skill)) {
            log.debug("Observed level up for {} to {}", skill, currentLevel);
            
            // Track previous level for level increase calculation
            previousLevels.put(skill, previousLevel);

            // allow more accumulation of level ups into single notification
            this.ticksWaited = 0;
        }
    }

    private void attemptNotify() {
        notifyLevels();
        notifyXp();
    }

    /**
     * Creates a simplified field data map for level-up submissions.
     * 
     * @param skillsTrainedList list of skill names that were trained
     * @param skillsLeveledList list of skill names that leveled up
     * @param experienceData map containing experience data for relevant skills
     * @return a map containing only the essential fields
     */
    private Map<String, Object> createLevelUpFieldData(List<String> skillsTrainedList, 
                                                      List<String> skillsLeveledList,
                                                      Map<String, Object> experienceData) {
        Map<String, Object> fieldData = new HashMap<>();
        
        // General player stats
        fieldData.put("total_level", client.getTotalLevel());
        fieldData.put("total_xp", client.getOverallExperience());
        fieldData.put("combat_level", currentLevels.get(COMBAT_NAME));
        
        // Skills data - flatten to simple fields
        fieldData.put("skills_trained", String.join(",", skillsTrainedList));
        fieldData.put("skills_leveled", String.join(",", skillsLeveledList));
        
        // Experience data - flatten to individual fields
        for (Map.Entry<String, Object> entry : experienceData.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> skillData = (Map<String, Object>) value;
                for (Map.Entry<String, Object> skillEntry : skillData.entrySet()) {
                    fieldData.put(key + "_" + skillEntry.getKey(), skillEntry.getValue());
                }
            } else {
                fieldData.put(key, value);
            }
        }
        
        return fieldData;
    }

    private void notifyXp() {
        final int n = xpReached.size();
        if (n == 0) return;

        int interval = XP_INTERVAL_MILLIONS * 1_000_000;
        List<String> milestones = new ArrayList<>(n);
        
        StringBuilder skillMessage = new StringBuilder();
        boolean first = true;
        
        for (Skill skill : xpReached) {
            int xp = currentXp.getOrDefault(skill, 0);
            xp -= xp % interval;
            milestones.add(skill.getName());
            
            if (!first) {
                skillMessage.append(", ");
            }
            first = false;
            
            skillMessage.append(skill.getName())
                       .append(" to ")
                       .append(QuantityFormatter.formatNumber(xp))
                       .append(" XP");
        }
        
        // Create experience data for milestone skills
        Map<String, Object> experienceData = new HashMap<>();
        experienceData.put("xp_milestone_interval", interval);
        
        for (Skill skill : xpReached) {
            int xp = currentXp.getOrDefault(skill, 0);
            xp -= xp % interval; // Get the milestone XP amount
            String skillName = skill.getName().toLowerCase();
            Map<String, Object> skillData = new HashMap<>();
            skillData.put("xp_milestone", xp);
            skillData.put("xp_total", currentXp.get(skill));
            experienceData.put(skillName, skillData);
        }
        
        // Create standardized field data
        List<String> skillsLeveled = new ArrayList<>(); // No level ups for XP milestones
        Map<String, Object> fieldData = createLevelUpFieldData(milestones, skillsLeveled, experienceData);
        
        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " reached an XP milestone!");
        CustomWebhookBody.Embed embed = createEmbed("XP Milestone Reached", "xp_milestone");
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Clear the reached skills set
        xpReached.clear();
        
        // Send the data
        sendData(webhook, SubmissionType.EXPERIENCE_MILESTONE);
    }

    private void notifyLevels() {
        /* For level ups specifically  */
        int n = levelledSkills.size();
        if (n == 0) return;

        // Prepare level state
        List<String> levelled = new ArrayList<>(n);
        int count = levelledSkills.drainTo(levelled);
        if (count == 0) return;

        Map<String, Integer> lSkills = new HashMap<>(count);
        Map<String, Integer> currentLevels = new HashMap<>(this.currentLevels);

        // Build skill message and collect skills involved
        Set<Skill> skillsInvolved = EnumSet.noneOf(Skill.class);
        StringBuilder skillMessage = new StringBuilder();
        
        for (int index = 0; index < count; index++) {
            String skill = levelled.get(index);
            if (index > 0) {
                if (count > 2) {
                    skillMessage.append(",");
                }
                skillMessage.append(" ");
                if (index + 1 == count) {
                    skillMessage.append("and ");
                }
            }
            Integer level = currentLevels.get(skill);
            skillMessage.append(skill)
                        .append(" to ")
                        .append(level < LEVEL_FOR_MAX_XP ? level : "Max XP (200M)");
            lSkills.put(skill, level);
            
            // Add skill to involved skills if it's not combat
            if (!COMBAT_NAME.equals(skill)) {
                for (Skill s : Skill.values()) {
                    if (s.getName().equals(skill)) {
                        skillsInvolved.add(s);
                        break;
                    }
                }
            }
        }

        // Check for combat level increase
        Boolean combatLevelUp = lSkills.remove(COMBAT_NAME) != null;
        Integer combatLevel = currentLevels.get(COMBAT_NAME);
        if (combatLevel == null) {
            combatLevel = calculateCombatLevel();
        }

        // Create skills leveled list and experience data
        List<String> skillsLeveledList = new ArrayList<>();
        List<String> skillsTrainedList = new ArrayList<>();
        Map<String, Object> experienceData = new HashMap<>();
        
        for (String skill : levelled) {
            if (!COMBAT_NAME.equals(skill)) {
                skillsTrainedList.add(skill);
                skillsLeveledList.add(skill);
                
                Integer previousLevel = previousLevels.get(skill);
                Integer currentLevel = currentLevels.get(skill);
                if (previousLevel != null && currentLevel != null) {
                    String skillName = skill.toLowerCase();
                    Map<String, Object> skillData = new HashMap<>();
                    skillData.put("level_gained", currentLevel - previousLevel);
                    skillData.put("new_level", currentLevel);
                    
                    // Add XP data if available
                    for (Skill s : Skill.values()) {
                        if (s.getName().equals(skill)) {
                            Integer skillXp = currentXp.get(s);
                            if (skillXp != null) {
                                skillData.put("xp_total", skillXp);
                            }
                            break;
                        }
                    }
                    
                    experienceData.put(skillName, skillData);
                }
            }
        }
        
        // Handle combat level separately
        if (combatLevelUp != null && combatLevelUp) {
            skillsLeveledList.add("Combat");
            Map<String, Object> combatData = new HashMap<>();
            combatData.put("level_gained", 1); // Assume +1 for combat level increases
            combatData.put("new_level", combatLevel);
            experienceData.put("combat", combatData);
        }
        
        // Create level-up field data
        Map<String, Object> fieldData = createLevelUpFieldData(skillsTrainedList, skillsLeveledList, experienceData);
        
        // Update combat level if it leveled up
        if (combatLevelUp != null && combatLevelUp) {
            fieldData.put("combat_level", combatLevel);
        }
        
        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " leveled up!");
        CustomWebhookBody.Embed embed = createEmbed("Level Up!", "level_up");
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Send the data
        sendData(webhook, SubmissionType.LEVEL_UP);
    }

    /**
     * Returns {@code true} if the new {@code level} meets the configured notification criteria.
     *
     * <p>A level qualifies when:
     * <ul>
     *   <li>It is at least {@link #LEVEL_MIN_VALUE}.</li>
     *   <li>Virtual levels are tracked (or it is a real level, i.e. ≤ 99).</li>
     *   <li>It falls on a {@link #LEVEL_INTERVAL} boundary — or any level in the range
     *       {@code (previous, current]} crosses such a boundary (handles skipped levels).</li>
     * </ul>
     * </p>
     *
     * @param previous         level before the change
     * @param level            level after the change
     * @param skipVirtualCheck {@code true} for combat level (which has no virtual range)
     * @return {@code true} if a notification should be sent for this level change
     */
    private boolean checkLevelInterval(int previous, int level, boolean skipVirtualCheck) {
        if (level < LEVEL_MIN_VALUE)
            return false;

        if (!skipVirtualCheck && level > MAX_REAL_LEVEL && !TRACK_VIRTUAL_LEVELS)
            return false;

        int interval = LEVEL_INTERVAL;
        if (interval <= 1 || level == MAX_REAL_LEVEL || level == LEVEL_FOR_MAX_XP)
            return true;

        // Check levels in (previous, current] for divisibility by interval
        // Allows for firing notification if jumping over a level that would've notified
        int remainder = level % interval;
        return remainder == 0 || (level - remainder) > previous;
    }

    /**
     * Computes the player's current combat level from the seven contributing skills,
     * preferring cached values in {@link #currentLevels} (capped at 99 for the formula).
     *
     * @return the calculated overall combat level
     */
    private int calculateCombatLevel() {
        return Experience.getCombatLevel(
            getRealLevel(Skill.ATTACK),
            getRealLevel(Skill.STRENGTH),
            getRealLevel(Skill.DEFENCE),
            getRealLevel(Skill.HITPOINTS),
            getRealLevel(Skill.MAGIC),
            getRealLevel(Skill.RANGED),
            getRealLevel(Skill.PRAYER)
        );
    }

    /**
     * Returns the real (non-virtual, capped at 99) level for a skill, using the cached
     * value where available to avoid unnecessary client calls.
     *
     * @param skill the skill to query
     * @return the real level (1–99)
     */
    private int getRealLevel(Skill skill) {
        Integer cachedLevel = currentLevels.get(skill.getName());
        return cachedLevel != null
            ? Math.min(cachedLevel, MAX_REAL_LEVEL)
            : client.getRealSkillLevel(skill);
    }

    /**
     * Converts an XP total to its corresponding level, including virtual levels above 99.
     * Returns the sentinel {@link #LEVEL_FOR_MAX_XP} (127) for the 200 M XP cap.
     *
     * <p>Note: {@link Experience#getLevelForXp(int)} is an O(log n) binary search;
     * prefer {@link net.runelite.api.Client#getRealSkillLevel(Skill)} for real levels
     * when a virtual result is not needed.</p>
     *
     * @param xp raw XP total
     * @return virtual level (1–{@link #LEVEL_FOR_MAX_XP})
     */
    private int getLevel(int xp) {
        // treat 200M XP as level 127
        if (xp >= Experience.MAX_SKILL_XP)
            return LEVEL_FOR_MAX_XP;

        // log(n) operation to support virtual levels
        return Experience.getLevelForXp(xp);
    }

    /**
     * Returns the intersection of the client's current world types with {@link #SPECIAL_WORLDS}.
     * Used to detect environment changes on world-hop that require re-initialization.
     *
     * @return a mutable set of currently active special world types (may be empty)
     */
    private Set<WorldType> getSpecialWorldTypes() {
        var world = client.getWorldType().clone();
        world.retainAll(SPECIAL_WORLDS); // O(1)
        return world;
    }

    static {
        COMBAT_COMPONENTS = ImmutableSet.of(
            Skill.ATTACK.getName(),
            Skill.STRENGTH.getName(),
            Skill.DEFENCE.getName(),
            Skill.HITPOINTS.getName(),
            Skill.MAGIC.getName(),
            Skill.RANGED.getName(),
            Skill.PRAYER.getName()
        );
    }
}
