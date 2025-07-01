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

@Slf4j
@Singleton
public class ExperienceHandler extends BaseEventHandler {
    public static final int LEVEL_FOR_MAX_XP = Experience.MAX_VIRT_LEVEL + 1; // 127
    static final @VisibleForTesting int INIT_GAME_TICKS = 16; // ~10s
    private static final Set<WorldType> SPECIAL_WORLDS = EnumSet.of(WorldType.PVP_ARENA, WorldType.QUEST_SPEEDRUNNING, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD, WorldType.DEADMAN, WorldType.SEASONAL);
    private static final int SKILL_COUNT = Skill.values().length;
    private static final String COMBAT_NAME = "Combat";
    private static final Set<String> COMBAT_COMPONENTS;
    
    // Configuration constants
    private static final int XP_INTERVAL_MILLIONS = 10; // Track every 10M XP milestone
    private static final int LEVEL_MIN_VALUE = 1; // Minimum level to track
    private static final int LEVEL_INTERVAL = 1; // Track every level
    private static final boolean TRACK_VIRTUAL_LEVELS = true; // Track levels above 99
    private static final boolean TRACK_COMBAT_LEVEL = true; // Track combat level increases
    private static final boolean TRACK_ALL_XP_CHANGES = true; // Track all XP changes
    private static final int XP_UPDATE_INTERVAL_TICKS = 10; // Send XP updates every 10 ticks (6 seconds)
    
    private final BlockingQueue<String> levelledSkills = new ArrayBlockingQueue<>(SKILL_COUNT + 1);
    private final Set<Skill> xpReached = EnumSet.noneOf(Skill.class);
    private final Map<String, Integer> currentLevels = new HashMap<>();
    private final Map<Skill, Integer> currentXp = new EnumMap<>(Skill.class);
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class); // Track previous XP for changes
    private final Set<Skill> xpChanged = EnumSet.noneOf(Skill.class); // Track which skills had XP changes
    
    private int ticksWaited = 0;
    private int initTicks = 0;
    private int xpUpdateTicks = 0;
    private Set<WorldType> specialWorldType = null;
    private Skill lastSkillTrained = null;
    private long lastXpGainTime = 0;

    @Inject
    private ClientThread clientThread;

    @Override
    public void process(Object... args) {
        /* Unused */
    }
    
    @Override
    public boolean isEnabled() {
        return config.trackExperience();
    }

    private void initLevels() {
        for (Skill skill : Skill.values()) {
            int xp = client.getSkillExperience(skill);
            int level = client.getRealSkillLevel(skill); // O(1)
            if (level >= MAX_REAL_LEVEL) {
                level = getLevel(xp);
            }
            currentLevels.put(skill.getName(), level);
            currentXp.put(skill, xp);
            previousXp.put(skill, xp); // Initialize previous XP tracking
        }
        currentLevels.put(COMBAT_NAME, calculateCombatLevel());
        this.initTicks = 0;
        this.specialWorldType = getSpecialWorldTypes();
        log.debug("Initialized current skill levels: {}", currentLevels);
    }

    public void reset() {
        levelledSkills.clear();
        clientThread.invoke(() -> {
            this.initTicks = 0;
            this.ticksWaited = 0;
            this.xpUpdateTicks = 0;
            xpReached.clear();
            xpChanged.clear();
            currentXp.clear();
            previousXp.clear();
            currentLevels.clear();
            this.specialWorldType = null;
            this.lastSkillTrained = null;
            this.lastXpGainTime = 0;
        });
    }

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
        
        // Handle regular XP updates
        if (TRACK_ALL_XP_CHANGES && !xpChanged.isEmpty()) {
            if (++this.xpUpdateTicks >= XP_UPDATE_INTERVAL_TICKS) {
                this.xpUpdateTicks = 0;
                if (isEnabled()) {
                    sendXpUpdate();
                } else {
                    xpChanged.clear();
                }
            }
        }
    }

    public void onStatChanged(StatChanged statChange) {
        this.handleStatChange(statChange.getSkill(), statChange.getLevel(), statChange.getXp());
    }

    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
            this.reset();
        } else if (gameStateChanged.getGameState() == GameState.LOGGED_IN && !getSpecialWorldTypes().equals(this.specialWorldType)) {
            // world switched where player may have different level profiles; re-initialize
            this.reset();
        }
    }

    private void handleStatChange(Skill skill, int level, int xp) {
        if (xp <= 0 || level <= 1 || !isEnabled()) return;

        Integer previousXp = currentXp.put(skill, xp);
        if (previousXp == null) {
            return;
        }

        // Track any XP change
        if (TRACK_ALL_XP_CHANGES && xp != previousXp) {
            xpChanged.add(skill);
            lastSkillTrained = skill;
            lastXpGainTime = System.currentTimeMillis();
            this.xpUpdateTicks = 0; // Reset counter to delay sending
            
            // Update previous XP for tracking
            this.previousXp.put(skill, previousXp);
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

            // allow more accumulation of level ups into single notification
            this.ticksWaited = 0;
        }
    }

    private void attemptNotify() {
        notifyLevels();
        notifyXp();
    }

    private void sendXpUpdate() {
        if (xpChanged.isEmpty()) return;
        
        // Create webhook body for XP update
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " gained experience");
        CustomWebhookBody.Embed embed = createEmbed("Experience Update", "xp_update");
        
        // Calculate total XP gained
        Map<String, Object> fieldData = new HashMap<>();
        List<String> skillsUpdated = new ArrayList<>();
        int totalXpGained = 0;
        
        for (Skill skill : xpChanged) {
            int currentSkillXp = currentXp.get(skill);
            int previousSkillXp = previousXp.get(skill);
            int xpGained = currentSkillXp - previousSkillXp;
            
            if (xpGained > 0) {
                skillsUpdated.add(skill.getName());
                totalXpGained += xpGained;
                fieldData.put(skill.getName().toLowerCase() + "_xp_gained", xpGained);
                fieldData.put(skill.getName().toLowerCase() + "_xp_total", currentSkillXp);
                fieldData.put(skill.getName().toLowerCase() + "_level", currentLevels.get(skill.getName()));
            }
            
            // Update previous XP to current
            previousXp.put(skill, currentSkillXp);
        }
        
        // Add general fields
        fieldData.put("skills_trained", String.join(", ", skillsUpdated));
        fieldData.put("total_xp_gained", totalXpGained);
        fieldData.put("last_skill_trained", lastSkillTrained != null ? lastSkillTrained.getName() : "Unknown");
        fieldData.put("total_level", client.getTotalLevel());
        fieldData.put("total_xp", client.getOverallExperience());
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Clear the changed skills set
        xpChanged.clear();
        
        // Send the data
        sendData(webhook, SubmissionType.EXPERIENCE);
    }

    private void notifyXp() {
        final int n = xpReached.size();
        if (n == 0) return;

        int interval = XP_INTERVAL_MILLIONS * 1_000_000;
        Map<String, Integer> current = new HashMap<>(32);
        currentXp.forEach((k, v) -> current.put(k.getName(), v));
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
        xpReached.clear();

        String totalXp = QuantityFormatter.formatNumber(client.getOverallExperience());
        
        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " reached an XP milestone!");
        CustomWebhookBody.Embed embed = createEmbed("XP Milestone Reached", "xp_milestone");
        
        // Add fields
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("skills", skillMessage.toString());
        fieldData.put("total_level", client.getTotalLevel());
        fieldData.put("total_xp", totalXp);
        fieldData.put("interval", interval);
        
        // Add current XP for each skill that hit a milestone
        for (String skillName : milestones) {
            fieldData.put(skillName.toLowerCase() + "_xp", current.get(skillName));
        }
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Send the data
        sendData(webhook, SubmissionType.EXPERIENCE_MILESTONE);
    }

    private void notifyLevels() {

        /* For level ups specifically  */
        int n = levelledSkills.size();
        if (n == 0) return;

        // Prepare level state
        int totalLevel = client.getTotalLevel();
        List<String> levelled = new ArrayList<>(n);
        int count = levelledSkills.drainTo(levelled);
        if (count == 0) return;

        Map<String, Integer> lSkills = new HashMap<>(count);
        Map<String, Integer> currentLevels = new HashMap<>(this.currentLevels);

        // Build skill message
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
        }

        // Check for combat level increase
        Boolean combatLevelUp = lSkills.remove(COMBAT_NAME) != null;
        Integer combatLevel = currentLevels.get(COMBAT_NAME);
        if (combatLevel == null) {
            combatLevel = calculateCombatLevel();
        }

        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " leveled up!");
        CustomWebhookBody.Embed embed = createEmbed("Level Up!", "level");
        
        // Add fields
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("skills", skillMessage.toString());
        fieldData.put("total_level", totalLevel);
        fieldData.put("total_xp", QuantityFormatter.formatNumber(client.getOverallExperience()));
        if (combatLevelUp != null && combatLevelUp) {
            fieldData.put("combat_level", combatLevel);
        }
        
        // Add individual skill levels
        for (Map.Entry<String, Integer> entry : lSkills.entrySet()) {
            fieldData.put(entry.getKey().toLowerCase() + "_level", entry.getValue());
        }
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Send the data
        sendData(webhook, SubmissionType.LEVEL_UP);
    }

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

    private int getRealLevel(Skill skill) {
        Integer cachedLevel = currentLevels.get(skill.getName());
        return cachedLevel != null
            ? Math.min(cachedLevel, MAX_REAL_LEVEL)
            : client.getRealSkillLevel(skill);
    }

    private int getLevel(int xp) {
        // treat 200M XP as level 127
        if (xp >= Experience.MAX_SKILL_XP)
            return LEVEL_FOR_MAX_XP;

        // log(n) operation to support virtual levels
        return Experience.getLevelForXp(xp);
    }

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
