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
    private final Map<String, Integer> previousLevels = new HashMap<>(); // Track previous levels for level increase calculation
    
    private int ticksWaited = 0;
    private int initTicks = 0;
    private int xpUpdateTicks = 0;
    private Set<WorldType> specialWorldType = null;
    private Skill lastSkillTrained = null;
    private long lastXpGainTime = 0;

    @Inject
    private ClientThread clientThread;
    
    @Inject 
    private static Gson gson;

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
            previousLevels.clear();
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
     * Creates a simplified field data map for experience submissions.
     * Only includes essential data without verbose null fields.
     * 
     * @param submissionType the type of submission (xp_update, level_up, xp_milestone)
     * @param skillsTrainedList list of skill names that were trained
     * @param skillsLeveledList list of skill names that leveled up
     * @param experienceData map containing experience data for relevant skills
     * @return a map containing only the essential fields
     */
    private Map<String, Object> createStandardizedFieldData(String submissionType, 
                                                           List<String> skillsTrainedList, 
                                                           List<String> skillsLeveledList,
                                                           Map<String, Object> experienceData) {
        Map<String, Object> fieldData = new HashMap<>();
        
        // General player stats
        fieldData.put("total_level", client.getTotalLevel());
        fieldData.put("total_xp", client.getOverallExperience());
        fieldData.put("combat_level", currentLevels.get(COMBAT_NAME));
        fieldData.put("last_skill_trained", lastSkillTrained != null ? lastSkillTrained.getName() : "Unknown");
        
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

    private void sendXpUpdate() {
        if (xpChanged.isEmpty()) return;
        
        // Calculate total XP gained and build skills message
        List<String> skillsUpdated = new ArrayList<>();
        int totalXpGained = 0;
        
        for (Skill skill : xpChanged) {
            int currentSkillXp = currentXp.get(skill);
            int previousSkillXp = previousXp.get(skill);
            int xpGained = currentSkillXp - previousSkillXp;
            
            if (xpGained > 0) {
                skillsUpdated.add(skill.getName());
                totalXpGained += xpGained;
            }
            
            // Update previous XP to current
            previousXp.put(skill, currentSkillXp);
        }
        
        // Create experience data for relevant skills
        Map<String, Object> experienceData = new HashMap<>();
        experienceData.put("total_xp_gained", totalXpGained);
        
        for (Skill skill : xpChanged) {
            int currentSkillXp = currentXp.get(skill);
            int previousSkillXp = previousXp.get(skill);
            int xpGained = currentSkillXp - previousSkillXp;
            
            if (xpGained > 0) {
                String skillName = skill.getName().toLowerCase();
                Map<String, Object> skillData = new HashMap<>();
                skillData.put("xp_gained", xpGained);
                skillData.put("xp_total", currentSkillXp);
                experienceData.put(skillName, skillData);
            }
        }
        
        // Create standardized field data
        List<String> skillsLeveled = new ArrayList<>(); // No level ups for XP updates
        Map<String, Object> fieldData = createStandardizedFieldData("xp_update", skillsUpdated, skillsLeveled, experienceData);
        
        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " gained experience");
        CustomWebhookBody.Embed embed = createEmbed("Experience Update", "xp_update");
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Clear the changed skills set
        xpChanged.clear();
        
        // Send the data
        sendData(webhook, SubmissionType.EXPERIENCE);
        return;
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
        Map<String, Object> fieldData = createStandardizedFieldData("xp_milestone", milestones, skillsLeveled, experienceData);
        
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
        
        // Create standardized field data
        Map<String, Object> fieldData = createStandardizedFieldData("level_up", skillsTrainedList, skillsLeveledList, experienceData);
        
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
