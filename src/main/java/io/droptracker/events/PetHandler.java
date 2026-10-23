package io.droptracker.events;

import lombok.*;
import net.runelite.api.gameval.VarbitID;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

import io.droptracker.service.KCService;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.models.*;
import io.droptracker.models.api.Manifest;
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

    @Inject
    private io.droptracker.service.ManifestService manifestService;

    @Setter(AccessLevel.PRIVATE)
    private volatile String petName = null;

    private volatile String milestone = null;

    private volatile boolean duplicate = false;

    private volatile boolean backpack = false;

    private volatile boolean collection = false;

    /**
     * Stores a pet name detected from an untradeable-drop or collection-log message that
     * arrived *before* the "You have a funny feeling…" game message.  For first-time pet
     * drops the game sometimes sends the item-name notification ahead of the pet message,
     * so we cache it here and consume it when PET_REGEX eventually fires.
     */
    private volatile String pendingPetName = null;
    private volatile boolean pendingCollection = false;

    private static final String PRIMED_NAME = "";

    @Override
    public boolean isEnabled() {
        return true; // Always track pets
    }

    public void onGameMessage(String chatMessage) {
        if (!isEnabled()) return;

        if (petName == null) {
            // Pre-capture pet name from untradeable-drop or collection-log messages.
            // For first-time pet drops the game can send these item-name notifications
            // *before* "You have a funny feeling…", so we store any matching name here
            // and consume it when PET_REGEX fires below.
            parseItemFromGameMessage(chatMessage)
                    .filter(this::isPet)
                    .ifPresent(parseResult -> {
                        this.pendingPetName = parseResult.itemName;
                        this.pendingCollection = parseResult.collectionLog;
                    });

            if (PET_REGEX.matcher(chatMessage).matches()) {
                // If we already captured the name from a preceding message, use it
                // directly; otherwise prime the notifier to wait for the next message.
                if (pendingPetName != null) {
                    this.petName = pendingPetName;
                    this.collection = pendingCollection;
                } else {
                    this.petName = PRIMED_NAME;
                }
                this.duplicate = chatMessage.contains("would have been");
                this.backpack = chatMessage.contains(" backpack");
                this.pendingPetName = null;
                this.pendingCollection = false;
            }
        } else if (PRIMED_NAME.equals(petName) || !collection) {
            parseItemFromGameMessage(chatMessage)
                    .filter(this::isPet)
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
        this.pendingPetName = null;
        this.pendingCollection = false;
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

        String playerName = getPlayerName();
        if (playerName == null) {
            return;
        }

        CustomWebhookBody webhook = createWebhookBody(playerName + " received a pet!");
        CustomWebhookBody.Embed embed = createEmbed("Pet Drop!", "pet");
        
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
        
        // Try to get KC information for the pet source.
        // getKillCountWithStorage checks the in-memory cache AND falls back to data
        // stored by the RuneLite Chat Commands / Loot Tracker plugins, so it works
        // even on the very first kill of a session (when the cache is cold).
        String pet = petName != null ? petKey(pets(), petName) : null;
        String source = pet != null ? pets().get(pet) : null;
        if (source != null) {
            Integer kc = kcService.getKillCountWithStorage(LootRecordType.UNKNOWN, source);
            if (kc != null && kc > 0) {
                fieldData.put("source", source);
                fieldData.put("killcount", kc);
            }
        }
        
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

    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    private static class ParseResult {
        final String itemName;
        final boolean collectionLog;

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

    /** Pets by name, each with its kill-count boss (or null), from the server's manifest. */
    private Map<String, String> pets() {
        Manifest manifest = manifestService.getManifest();
        return manifest == null ? Collections.emptyMap() : manifest.getPets();
    }

    /** The manifest's spelling of a pet's name, matched ignoring case, or null. */
    @VisibleForTesting
    static String petKey(Map<String, String> pets, String itemName) {
        for (String name : pets.keySet()) {
            if (name.equalsIgnoreCase(itemName)) {
                return name;
            }
        }
        return null;
    }

    /** A pet's name in a chat line. Without a manifest, the collection log line is the pet's. */
    private boolean isPet(ParseResult item) {
        Map<String, String> pets = pets();
        return item.itemName.startsWith("Pet ") || petKey(pets, item.itemName) != null
            || (pets.isEmpty() && item.collectionLog);
    }
}