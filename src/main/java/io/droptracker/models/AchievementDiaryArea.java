package io.droptracker.models;

import net.runelite.api.Client;

/**
 * The twelve achievement diary areas and how to read a player's progress.
 *
 * <p>Adapted from RuneProfile (BSD 2-Clause,
 * github.com/ReinhardtR/runeprofile-plugin), which in turn reads the counts the
 * way the game itself does.
 *
 * <p>There is no client API for diary progress, so this calls the game's own
 * {@code [proc,diary_completion_info]} script (2200) and reads the results off
 * the int stack. Doing it this way means we get whatever the game considers
 * complete, including task counts that change with a game update, rather than
 * maintaining a per-task varbit table that would silently rot.
 *
 * @see <a href="https://github.com/RuneStar/cs2-scripts/blob/master/scripts/%5Bproc%2Cdiary_completion_info%5D.cs2">the script</a>
 */
public enum AchievementDiaryArea {
	KARAMJA(0),
	ARDOUGNE(1),
	FALADOR(2),
	FREMENNIK(3),
	KANDARIN(4),
	DESERT(5),
	LUMBRIDGE(6),
	MORYTANIA(7),
	VARROCK(8),
	WILDERNESS(9),
	WESTERN_PROVINCES(10),
	KOUREND(11);

	private static final int DIARY_COMPLETION_INFO_SCRIPT = 2200;

	/** The script returns a triple per tier; the completed count is the first. */
	private static final int VALUES_PER_TIER = 3;
	private static final int TIER_COUNT = 4;

	private final int id;

	AchievementDiaryArea(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	/**
	 * Completed task counts for this area, indexed by tier
	 * (0 easy, 1 medium, 2 hard, 3 elite).
	 *
	 * <p>Must be called on the client thread. Returns an all-zero array if the
	 * script returns less than expected, which happens when it is called before
	 * the player is fully logged in — a zero is indistinguishable from real
	 * "nothing completed", so callers should only sync when logged in.
	 */
	public int[] getTiersCompletedCount(Client client) {
		int[] completed = new int[TIER_COUNT];

		client.runScript(DIARY_COMPLETION_INFO_SCRIPT, id);
		int[] stack = client.getIntStack();
		if (stack == null || stack.length < TIER_COUNT * VALUES_PER_TIER) {
			return completed;
		}

		for (int tier = 0; tier < TIER_COUNT; tier++) {
			completed[tier] = stack[tier * VALUES_PER_TIER];
		}
		return completed;
	}
}
