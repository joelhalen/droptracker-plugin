package io.droptracker.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.VisibleForTesting;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.StructComposition;
import net.runelite.client.game.ItemManager;

/**
 * Which item ids the collection log actually has slots for, read from the cache.
 *
 * <p>Needed because a collection log <em>unlock</em> reaches us as a chat
 * message carrying only the item's name, and a name does not identify an item.
 * {@link io.droptracker.util.ItemIDSearch} answers with the earliest id sharing
 * that name, which for a duplicated name is the wrong one: the collection log's
 * Coal bag is 25627, the item cache says 764. Recording the wrong id leaves the
 * slot looking unfilled on the player's profile, and puts an id that is not a
 * slot into the account's item map.
 *
 * <p>The cache states the answer outright. Walking it costs one pass over five
 * structs and their page enums:
 *
 * <pre>
 *   enum 2102         -> the five tab structs
 *   tab struct  p683  -> enum of page structs
 *   page struct p690  -> enum of item ids
 *   enum 3721         -> id replacements
 * </pre>
 *
 * <p><b>Enum 3721 is not optional.</b> A dozen slots are stored against one id
 * and drawn as another — the Coal bag's page entry is 12019, the log draws
 * 25627 — and the game remaps through 3721 before drawing, so the replaced id
 * is what the collection log reports and what the server stores.
 *
 * <p>The walk comes from WikiSync (github.com/weirdgloop/WikiSync), Copyright
 * (c) 2021, andmcadams, BSD 2-Clause License (see LICENSE), which reads the same
 * enums for the same reason.
 */
@Slf4j
@Singleton
public class CollectionLogSlots {

	/** Enum holding the collection log's top level tab structs. */
	private static final int TAB_ENUM = 2102;
	/** Tab struct param: the enum of page structs under that tab. */
	private static final int PARAM_TAB_PAGES = 683;
	/** Page struct param: the enum of item ids on that page. */
	private static final int PARAM_PAGE_ITEMS = 690;
	/** Enum mapping a page's stored item id onto the id the log draws. */
	private static final int REPLACEMENT_ENUM = 3721;

	private final Client client;
	private final ItemManager itemManager;

	/** Every slot id, or empty until the first successful read. */
	private Set<Integer> slotIds = new HashSet<>();

	/**
	 * Slot name -> id, built only when a lookup by name is actually needed.
	 *
	 * <p>Names that belong to more than one slot map to null: every Graceful
	 * piece and all the decorative armour exist several times over, and a chat
	 * message naming one cannot say which was unlocked.
	 */
	private Map<String, Integer> slotByName = null;

	@Inject
	public CollectionLogSlots(Client client, ItemManager itemManager) {
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Drops the cached read, so a new session re-reads the cache. */
	public void reset() {
		slotIds = new HashSet<>();
		slotByName = null;
	}

	/**
	 * The id the collection log records for a newly unlocked item.
	 *
	 * <p>Must be called on the client thread. Returns {@code candidate} when it
	 * is already a slot, the right slot when the candidate is a same-named
	 * impostor, and null when neither is true — an unknown name, or one that
	 * several slots share. Null means "do not record this": the next full read
	 * of the log reports the slot properly, so guessing here would only write
	 * something wrong in the meantime.
	 */
	public Integer resolve(Integer candidate, String itemName) {
		if (!load()) {
			// Without the cache there is nothing to check against; the caller's
			// candidate is as good as it gets.
			return candidate;
		}
		return resolve(candidate, itemName, slotIds, byName());
	}

	/**
	 * The rule itself, separated from the cache read so it can be tested.
	 *
	 * <p>{@code slotByName} maps a lowercased slot name onto its id, with names
	 * shared by several slots mapped to null — see {@link #indexByName}.
	 */
	@VisibleForTesting
	static Integer resolve(Integer candidate, String itemName,
	                       Set<Integer> slotIds, Map<String, Integer> slotByName) {
		if (candidate != null && slotIds.contains(candidate)) {
			return candidate;
		}
		if (itemName == null || itemName.trim().isEmpty()) {
			return null;
		}
		return slotByName.get(itemName.trim().toLowerCase());
	}

	/**
	 * Lowercased slot name -> its id, with ambiguous names mapped to null.
	 *
	 * <p>Null rather than absent so the distinction survives: a name several
	 * slots share is one we must refuse to answer, not one we have never seen.
	 * Every Graceful piece and all the decorative armour land here.
	 */
	@VisibleForTesting
	static Map<String, Integer> indexByName(Map<Integer, String> namesById) {
		Map<String, Integer> byName = new HashMap<>();
		for (Map.Entry<Integer, String> entry : namesById.entrySet()) {
			String name = entry.getValue();
			if (name == null || name.isEmpty() || "null".equals(name)) {
				continue;
			}
			String key = name.toLowerCase();
			byName.put(key, byName.containsKey(key) ? null : entry.getKey());
		}
		return byName;
	}

	/** True once the slot ids have been read. Must be on the client thread. */
	public boolean load() {
		if (!slotIds.isEmpty()) {
			return true;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			// The cache is only reliably readable once the client has loaded.
			return false;
		}

		try {
			Map<Integer, Integer> replacements = new HashMap<>();
			EnumComposition replacementEnum = client.getEnum(REPLACEMENT_ENUM);
			if (replacementEnum != null) {
				int[] keys = replacementEnum.getKeys();
				int[] values = replacementEnum.getIntVals();
				for (int i = 0; i < keys.length && i < values.length; i++) {
					replacements.put(keys[i], values[i]);
				}
			}

			Set<Integer> found = new HashSet<>();
			for (int tabStructId : client.getEnum(TAB_ENUM).getIntVals()) {
				StructComposition tabStruct = client.getStructComposition(tabStructId);
				for (int pageStructId : client.getEnum(tabStruct.getIntValue(PARAM_TAB_PAGES)).getIntVals()) {
					StructComposition pageStruct = client.getStructComposition(pageStructId);
					for (int itemId : client.getEnum(pageStruct.getIntValue(PARAM_PAGE_ITEMS)).getIntVals()) {
						found.add(replacements.getOrDefault(itemId, itemId));
					}
				}
			}

			if (found.isEmpty()) {
				log.debug("Collection log structure read produced no slots");
				return false;
			}
			slotIds = found;
			log.debug("Read {} collection log slots from the cache", found.size());
			return true;
		} catch (Exception e) {
			// A cache the client has not loaded, or an enum that moved in a game
			// update. Neither is worth failing an unlock over.
			log.debug("Could not read the collection log structure: {}", e.toString());
			return false;
		}
	}

	/** Lazily names every slot; only an unrecognised id ever needs this. */
	private Map<String, Integer> byName() {
		if (slotByName != null) {
			return slotByName;
		}
		Map<Integer, String> namesById = new HashMap<>();
		for (int itemId : slotIds) {
			try {
				ItemComposition composition = itemManager.getItemComposition(itemId);
				namesById.put(itemId, composition == null ? null : composition.getName());
			} catch (Exception e) {
				log.debug("Could not name collection log slot {}: {}", itemId, e.toString());
			}
		}
		slotByName = indexByName(namesById);
		return slotByName;
	}
}
