package io.droptracker.events;

import lombok.AccessLevel;
import lombok.Setter;
import net.runelite.api.gameval.VarbitID;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.droptracker.service.KCService;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles pet drop notifications.
 *
 * <p><b>Detection state machine:</b></p>
 * <ol>
 *   <li>A "funny feeling" game message primes the handler: {@link #petName} is set to the
 *       sentinel value {@link #PRIMED_NAME} and the {@code duplicate} / {@code backpack} flags
 *       are recorded.</li>
 *   <li>The following game messages (same or subsequent ticks) populate {@link #petName}:
 *     <ul>
 *       <li>{@code "Untradeable drop: <pet>"} — from untradeable loot notifications setting</li>
 *       <li>{@code "New item added to your collection log: <pet>"} — if clog notifications enabled</li>
 *     </ul>
 *   </li>
 *   <li>An optional clan-chat message ({@link #CLAN_REGEX}) populates {@link #milestone} with
 *       the KC at which the pet was received.</li>
 *   <li>After up to {@link #MAX_TICKS_WAIT} ticks, {@link #handleNotify()} fires with whatever
 *       data has accumulated.</li>
 * </ol>
 *
 * <p><b>Duplicate detection:</b> The "would have been" phrase in the funny-feeling message
 * indicates the player already owns the pet. Additionally, if collection-log notifications are
 * enabled, the <em>absence</em> of a clog notification for the pet implies prior ownership.</p>
 *
 * <p><b>Kill count:</b> Pet names are mapped to their source NPC via {@link #PET_TO_SOURCE}
 * and the KC is looked up via {@link KCService}.</p>
 *
 * <p>Enabled/disabled via {@link io.droptracker.DropTrackerConfig#petEmbeds()}.</p>
 */
@Slf4j
@Singleton
public class PetHandler extends BaseEventHandler {

    /** Warning shown when the untradeable loot notification setting is disabled. */
    public static final String UNTRADEABLE_WARNING = "Pet Notifier cannot reliably identify pet names unless you enable the game setting: Untradeable loot notifications";

    /**
     * Matches the primary pet-drop game message, e.g.:
     * {@code "You have a funny feeling like you're being followed."}
     * or {@code "You feel something weird sneaking into your backpack."}
     */
    @VisibleForTesting
    static final Pattern PET_REGEX = Pattern.compile("You (?:have a funny feeling like you|feel something weird sneaking).*");

    /**
     * Matches the clan-chat announcement for a pet drop, e.g.:
     * {@code "PlayerName has a funny feeling like they're being followed: Pet name at 500 kills."}
     * Named groups: {@code user}, {@code pet}, {@code milestone}.
     */
    @VisibleForTesting
    static final Pattern CLAN_REGEX = Pattern.compile("\\b(?<user>[\\w\\s]+) (?:has a funny feeling like .+ followed|feels something weird sneaking into .+ backpack): (?<pet>.+) at (?<milestone>.+)");

    /** Matches an untradeable drop notification to extract the pet name. */
    private static final Pattern UNTRADEABLE_REGEX = Pattern.compile("Untradeable drop: (.+)");

    /** Matches a collection-log notification to extract the pet name. */
    private static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (.+)");

    /**
     * Maximum ticks to wait for the clan-chat milestone message before firing the notification
     * with only the pet name (no milestone information).
     */
    @VisibleForTesting
    static final int MAX_TICKS_WAIT = 5;

    /**
     * Ticks elapsed since the handler was primed ({@link #petName} set) but the clan-chat
     * milestone has not yet arrived.
     */
    private final AtomicInteger ticksWaited = new AtomicInteger();

    @Inject
    private ItemIDSearch itemSearcher;

    @Inject
    private KCService kcService;

    @Setter(AccessLevel.PRIVATE)
    private volatile String petName = null;

    private volatile String milestone = null;

    /** True when the pet is a duplicate (player already owns it). */
    private volatile boolean duplicate = false;

    /** True when the "backpack" variant of the funny-feeling message was received. */
    private volatile boolean backpack = false;

    /** True when a collection-log notification was observed for this pet drop. */
    private volatile boolean collection = false;

    /**
     * Sentinel value assigned to {@link #petName} immediately after the "funny feeling" message
     * is received, indicating the handler is primed and waiting for the pet name message.
     */
    private static final String PRIMED_NAME = "";

    @Override
    public boolean isEnabled() {
        return config.petEmbeds();
    }

    /**
     * Processes a game message for pet detection. Two phases:
     * <ol>
     *   <li>If not primed ({@link #petName} is null), checks for the funny-feeling trigger
     *       and sets the primed state.</li>
     *   <li>If primed, checks for untradeable-drop or collection-log messages that contain
     *       the actual pet name, and records it.</li>
     * </ol>
     *
     * @param chatMessage the sanitized game chat message text
     */
    public void onGameMessage(String chatMessage) {
        if (!isEnabled()) return;

        if (petName == null) {
            if (PET_REGEX.matcher(chatMessage).matches()) {
                // Prime the handler; pet name will arrive in a following message
                this.petName = PRIMED_NAME;
                this.duplicate = chatMessage.contains("would have been");
                this.backpack = chatMessage.contains(" backpack");
            }
        } else if (PRIMED_NAME.equals(petName) || !collection) {
            parseItemFromGameMessage(chatMessage)
                    .filter(item -> item.itemName.startsWith("Pet ") || isPetName(item.itemName))
                    .ifPresent(parseResult -> {
                        this.petName = parseResult.itemName;
                        if (parseResult.collectionLog) {
                            this.collection = true;
                        }
                    });
        }
    }

    /**
     * Processes a clan-chat notification that may contain the milestone kill count for this pet.
     * Only processes messages matching the local player's name to avoid acting on clan members' pets.
     *
     * @param message the clan-chat message text
     */
    public void onClanChatNotification(String message) {
        if (petName == null) {
            // Not primed — this clan message cannot be about our pet
            return;
        }

        Matcher matcher = CLAN_REGEX.matcher(message);
        if (matcher.find()) {
            String user = matcher.group("user").trim();
            if (user.equals(getPlayerName())) {
                this.petName = matcher.group("pet");
                this.milestone = StringUtils.removeEnd(matcher.group("milestone"), ".");
            }
        }
    }

    /**
     * Per-tick update. Fires the notification either when a milestone has been received or
     * when {@link #MAX_TICKS_WAIT} ticks have elapsed without one.
     */
    public void onTick() {
        if (petName == null) return;

        if (milestone != null || ticksWaited.incrementAndGet() > MAX_TICKS_WAIT) {
            // Fire with whatever data we have; milestone may still be null
            if (isEnabled()) {
                this.handleNotify();
            }
            this.reset();
        }
    }

    /** Resets all per-drop state after a notification has been sent or the wait times out. */
    public void reset() {
        this.petName = null;
        this.milestone = null;
        this.duplicate = false;
        this.backpack = false;
        this.collection = false;
        this.ticksWaited.set(0);
    }

    /**
     * Builds and sends the pet drop webhook embed using the accumulated per-drop state.
     *
     * <p>Ownership history is determined as follows:
     * <ul>
     *   <li>{@code duplicate} flag set → definitely previously owned</li>
     *   <li>Collection-log notification enabled → absence of clog notification implies prior ownership</li>
     *   <li>Otherwise → ownership status unknown ({@code null})</li>
     * </ul>
     * </p>
     */
    private void handleNotify() {
        Boolean previouslyOwned;
        if (duplicate) {
            previouslyOwned = true;
        } else if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) % 2 == 1) {
            // When clog chat notifications are enabled, the absence of a clog notification
            // for the pet means the player already has it in the collection log
            previouslyOwned = !collection;
        } else {
            previouslyOwned = null;
        }

        String gameMessage;
        if (backpack) {
            gameMessage = "feels something weird sneaking into their backpack";
        } else if (previouslyOwned != null && previouslyOwned) {
            gameMessage = "has a funny feeling like they would have been followed...";
        } else {
            gameMessage = "has a funny feeling like they're being followed";
        }

        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " received a pet!");
        CustomWebhookBody.Embed embed = createEmbed("Pet Drop!", "pet");
        
        // Add fields
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("pet_name", petName != null && !petName.isEmpty() ? ucFirst(petName) : "Unknown Pet");
        fieldData.put("game_message", gameMessage);
        fieldData.put("duplicate", duplicate);
        
        if (milestone != null) {
            fieldData.put("milestone", milestone);
        }
        
        if (previouslyOwned != null) {
            fieldData.put("previously_owned", previouslyOwned);
        }
        
        // Try to get KC information for the pet source
        String pet = petName != null ? ucFirst(petName) : null;
        if (pet != null && PET_TO_SOURCE.containsKey(pet)) {
            String source = PET_TO_SOURCE.get(pet);
            Integer kc = kcService.getKillCount(LootRecordType.UNKNOWN, source);
            if (kc != null && kc > 0) {
                fieldData.put("source", source);
                fieldData.put("killcount", kc);
            }
        }
        
        // Add timestamp
        fieldData.put("timestamp", System.currentTimeMillis() / 1000);
        
        addFields(embed, fieldData);
        
        // Try to set pet image
        if (pet != null && !pet.isEmpty()) {
            Integer itemId = itemSearcher.findItemId(pet);
            if (itemId != null) {
                embed.setImage(plugin.itemImageUrl(itemId));
            }
        }
        
        webhook.getEmbeds().add(embed);
        
        // Send the data
        sendData(webhook, SubmissionType.PET); 
    }

    private static Optional<ParseResult> parseItemFromGameMessage(String message) {
        Matcher untradeableMatcher = UNTRADEABLE_REGEX.matcher(message);
        if (untradeableMatcher.find()) {
            return Optional.of(new ParseResult(untradeableMatcher.group(1), false));
        }

        Matcher collectionMatcher = COLLECTION_LOG_REGEX.matcher(message);
        if (collectionMatcher.find()) {
            return Optional.of(new ParseResult(collectionMatcher.group(1), true));
        }

        return Optional.empty();
    }

    private static class ParseResult {
        final String itemName;
        final boolean collectionLog;

        ParseResult(String itemName, boolean collectionLog) {
            this.itemName = itemName;
            this.collectionLog = collectionLog;
        }
    }

    /**
     * Converts text into "upper case first" form, as is used by OSRS for item names.
     *
     * @param text the string to be transformed
     * @return the text with only the first character capitalized
     */
    private static String ucFirst(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.length() < 2) return text.toUpperCase();
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
    }

    private static boolean isPetName(String itemName) {
        return PET_TO_SOURCE.containsKey(ucFirst(itemName));
    }

    /**
     * Maps pet names (title-cased as they appear in game) to their source NPC/activity names.
     * Used to look up the player's kill count at the time of the pet drop for inclusion in the
     * webhook embed. Only covers pets with a known, single kill-count source.
     */
    private static final Map<String, String> PET_TO_SOURCE = Map.ofEntries(
        Map.entry("Abyssal orphan", "Abyssal Sire"),
        Map.entry("Baby mole", "Giant Mole"),
        Map.entry("Baron", "Duke Sucellus"),
        Map.entry("Butch", "Vardorvis"),
        Map.entry("Callisto cub", "Callisto"),
        Map.entry("Chompy chick", "Chompy bird"),
        Map.entry("Hellpuppy", "Cerberus"),
        Map.entry("Herbi", "Herbiboar"),
        Map.entry("Huberte", "The Hueycoatl"),
        Map.entry("Ikkle hydra", "Alchemical Hydra"),
        Map.entry("Jal-nib-rek", "TzKal-Zuk"),
        Map.entry("Kalphite princess", "Kalphite Queen"),
        Map.entry("Lil' creator", "Spoils of war"),
        Map.entry("Lil' zik", "Theatre of Blood"),
        Map.entry("Lil'viathan", "The Leviathan"),
        Map.entry("Little nightmare", "Nightmare"),
        Map.entry("Moxi", "Amoxliatl"),
        Map.entry("Muphin", "Phantom Muspah"),
        Map.entry("Nexling", "Nex"),
        Map.entry("Nid", "Araxxor"),
        Map.entry("Noon", "Grotesque Guardians"),
        Map.entry("Pet chaos elemental", "Chaos Elemental"),
        Map.entry("Pet dagannoth prime", "Dagannoth Prime"),
        Map.entry("Pet dagannoth rex", "Dagannoth Rex"),
        Map.entry("Pet dagannoth supreme", "Dagannoth Supreme"),
        Map.entry("Pet dark core", "Corporeal Beast"),
        Map.entry("Pet general graardor", "General Graardor"),
        Map.entry("Pet k'ril tsutsaroth", "K'ril Tsutsaroth"),
        Map.entry("Pet kraken", "Kraken"),
        Map.entry("Pet smoke devil", "Thermonuclear smoke devil"),
        Map.entry("Pet snakeling", "Zulrah"),
        Map.entry("Pet zilyana", "Commander Zilyana"),
        Map.entry("Phoenix", "Wintertodt"),
        Map.entry("Prince black dragon", "King Black Dragon"),
        Map.entry("Scorpia's offspring", "Scorpia"),
        Map.entry("Scurry", "Scurrius"),
        Map.entry("Skotos", "Skotizo"),
        Map.entry("Smolcano", "Zalcano"),
        Map.entry("Sraracha", "Sarachnis"),
        Map.entry("Tiny tempor", "Tempoross"),
        Map.entry("Tzrek-jad", "TzTok-Jad"),
        Map.entry("Venenatis spiderling", "Venenatis"),
        Map.entry("Vet'ion jr.", "Vet'ion"),
        Map.entry("Vorki", "Vorkath"),
        Map.entry("Wisp", "The Whisperer"),
        Map.entry("Yami", "Yama"),
        Map.entry("Youngllef", "Gauntlet"),
        Map.entry("Dom", "Doom of Mokhaitl")
    );
}