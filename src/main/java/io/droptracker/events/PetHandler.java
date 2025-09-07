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

@Slf4j
@Singleton
public class PetHandler extends BaseEventHandler {

    public static final String UNTRADEABLE_WARNING = "Pet Notifier cannot reliably identify pet names unless you enable the game setting: Untradeable loot notifications";

    @VisibleForTesting
    static final Pattern PET_REGEX = Pattern.compile("You (?:have a funny feeling like you|feel something weird sneaking).*");

    @VisibleForTesting
    static final Pattern CLAN_REGEX = Pattern.compile("\\b(?<user>[\\w\\s]+) (?:has a funny feeling like .+ followed|feels something weird sneaking into .+ backpack): (?<pet>.+) at (?<milestone>.+)");

    private static final Pattern UNTRADEABLE_REGEX = Pattern.compile("Untradeable drop: (.+)");
    private static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (.+)");
    
    /**
     * The maximum number ticks to wait for milestone to be populated,
     * before firing notification with only the petName.
     */
    @VisibleForTesting
    static final int MAX_TICKS_WAIT = 5;

    /**
     * Tracks the number of ticks that occur where milestone is not populated
     * while petName is populated.
     */
    private final AtomicInteger ticksWaited = new AtomicInteger();

    @Inject
    private ItemIDSearch itemSearcher;

    @Inject
    private KCService kcService;

    @Setter(AccessLevel.PRIVATE)
    private volatile String petName = null;

    private volatile String milestone = null;

    private volatile boolean duplicate = false;

    private volatile boolean backpack = false;

    private volatile boolean collection = false;

    private static final String PRIMED_NAME = "";

    @Override
    public boolean isEnabled() {
        return true; // Always track pets
    }

    public void onGameMessage(String chatMessage) {
        if (!isEnabled()) return;

        if (petName == null) {
            if (PET_REGEX.matcher(chatMessage).matches()) {
                // Prime the notifier to trigger next tick
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

    public void onClanChatNotification(String message) {
        if (petName == null) {
            // We have not received the normal message about a pet drop, so this clan message cannot be relevant to us
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

    public void onTick() {
        if (petName == null) return;

        if (milestone != null || ticksWaited.incrementAndGet() > MAX_TICKS_WAIT) {
            // ensure notifier was not disabled during wait ticks
            if (isEnabled()) {
                this.handleNotify();
            }
            this.reset();
        }
    }

    public void reset() {
        this.petName = null;
        this.milestone = null;
        this.duplicate = false;
        this.backpack = false;
        this.collection = false;
        this.ticksWaited.set(0);
    }

    private void handleNotify() {
        Boolean previouslyOwned;
        if (duplicate) {
            previouslyOwned = true;
        } else if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) % 2 == 1) {
            // when collection log chat notification is enabled, presence or absence of notification indicates ownership history
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

    // Simplified pet to source mapping - just for basic KC tracking
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