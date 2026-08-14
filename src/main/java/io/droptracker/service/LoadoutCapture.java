package io.droptracker.service;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

/**
 * Reads what the player is wearing and carrying, for attaching to a personal
 * best.
 *
 * <p>"What did you bring?" is the first thing anyone asks about a good time, and
 * it is information the kill message alone cannot carry. This is item ids only —
 * no geometry, no model, no screenshot — so it costs a few hundred bytes and can
 * be rendered as the familiar inventory and equipment tabs.
 *
 * <p>Timing matters more than it looks. The capture has to happen while the
 * loadout still reflects the kill: a snapshot taken when the submission is
 * assembled can show a player who has already banked, eaten, died or teleported.
 * Callers therefore capture on the client thread as soon as the kill is
 * recognised, within a tick or two of the kill itself.
 *
 * <p>Encoded as a compact string rather than JSON because it travels in a
 * webhook embed field, and those are limited to 1024 characters — a full
 * 28-slot inventory plus worn equipment fits comfortably in this form and would
 * not as JSON.
 */
@Slf4j
@Singleton
public class LoadoutCapture {

	/** Slot-index/item-id/quantity triples, e.g. {@code "0-11802-1,3-995-1000"}. */
	private static final char ENTRY_SEPARATOR = ',';
	private static final char FIELD_SEPARATOR = '-';

	/**
	 * Hard ceiling on the encoded length. Embed field values are capped at 1024
	 * characters; a real loadout is far shorter, so hitting this means something
	 * unexpected and the value is dropped rather than truncated into nonsense.
	 */
	private static final int MAX_ENCODED_LENGTH = 1000;

	private final Client client;
	private final DropTrackerConfig config;

	@Inject
	public LoadoutCapture(Client client, DropTrackerConfig config) {
		this.client = client;
		this.config = config;
	}

	public boolean isEnabled() {
		return config.sendLoadoutWithPbs();
	}

	/**
	 * Worn equipment, or null when unavailable or disabled.
	 *
	 * <p>Must be called on the client thread.
	 */
	@Nullable
	public String captureEquipment() {
		return isEnabled() ? encode(client.getItemContainer(InventoryID.WORN)) : null;
	}

	/**
	 * Carried inventory, or null when unavailable or disabled.
	 *
	 * <p>Must be called on the client thread.
	 */
	@Nullable
	public String captureInventory() {
		return isEnabled() ? encode(client.getItemContainer(InventoryID.INV)) : null;
	}

	/**
	 * Encodes the occupied slots of a container.
	 *
	 * <p>Empty slots are omitted rather than written as zeros: the slot index is
	 * carried explicitly, so the layout survives without paying for the gaps.
	 * Returns null for an absent or empty container, which callers treat as "no
	 * loadout" — an empty string would be indistinguishable from a real capture
	 * of a player carrying nothing.
	 */
	@Nullable
	private String encode(@Nullable ItemContainer container) {
		if (container == null) {
			return null;
		}

		Item[] items = container.getItems();
		if (items == null || items.length == 0) {
			return null;
		}

		StringBuilder encoded = new StringBuilder();
		for (int slot = 0; slot < items.length; slot++) {
			Item item = items[slot];
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
				continue;
			}
			if (encoded.length() > 0) {
				encoded.append(ENTRY_SEPARATOR);
			}
			encoded.append(slot)
					.append(FIELD_SEPARATOR)
					.append(item.getId())
					.append(FIELD_SEPARATOR)
					.append(item.getQuantity());
		}

		if (encoded.length() == 0) {
			return null;
		}
		if (encoded.length() > MAX_ENCODED_LENGTH) {
			log.debug("Loadout encoding was {} chars; dropping rather than truncating",
					encoded.length());
			return null;
		}
		return encoded.toString();
	}
}
