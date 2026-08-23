package io.droptracker.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * A complete picture of an account's current state, as opposed to the event
 * submissions the rest of the plugin sends.
 *
 * <p>The distinction matters: a drop or a combat achievement submission says
 * "this just happened", which can never describe what a player already had when
 * they installed the plugin. A snapshot says "this is everything, right now", so
 * the server can render a collection log, show combat achievement progress, and
 * rank players — none of which the event stream can answer.
 *
 * <p>Snapshots are idempotent by construction: sending the same one twice must
 * leave the server in the same state, which is what makes retrying safe.
 *
 * <p>Collection log items are <b>additive only</b>. The map holds what this
 * client has observed, which after a partial read is a subset of what the player
 * owns — so the server upserts and never deletes. Authoritative totals travel
 * separately in {@link #clogSlots} / {@link #clogSlotsTotal}, which the game
 * itself reports and which are correct even when {@link #items} is incomplete.
 */
@Data
public class StateSnapshot {

	/** Identity, resolved server-side exactly as submissions are. */
	@SerializedName("player_name")
	private String playerName;

	@SerializedName("acc_hash")
	private String accountHash;

	/** Which manifest this client read its varp/quest ids from. */
	@SerializedName("manifest_version")
	private String manifestVersion;

	/** What triggered this sync — "login", "interval", "clog", "manual". */
	@SerializedName("source")
	private String source;

	/** Plugin version, matching the {@code p_v} field on submissions. */
	@SerializedName("p_v")
	private String pluginVersion;

	/** Raw IRONMAN varbit; sent undecoded so a new account type needs no release. */
	@SerializedName("account_type")
	private Integer accountType;

	@SerializedName("combat_level")
	private Integer combatLevel;

	/** Skill name -> total experience. */
	@SerializedName("skills")
	private Map<String, Integer> skills = new HashMap<>();

	/** Quest id -> 0 not started, 1 in progress, 2 finished. */
	@SerializedName("quests")
	private Map<Integer, Integer> quests = new HashMap<>();

	/** Varp id -> raw 32-bit value holding combat achievement completion bits. */
	@SerializedName("ca_varps")
	private Map<Integer, Integer> combatAchievementVarps = new HashMap<>();

	/** One entry per diary area and tier. */
	@SerializedName("diary_tiers")
	private List<DiaryTier> diaryTiers = new ArrayList<>();

	/** Collection log item id -> quantity. Additive; see the class note. */
	@SerializedName("items")
	private Map<Integer, Integer> items = new HashMap<>();

	/**
	 * Filled slots as the game reports them. Authoritative even when
	 * {@link #items} is partial, so a profile can show "412/1584" honestly
	 * before a full scrape has ever run.
	 */
	@SerializedName("clog_slots")
	private Integer clogSlots;

	@SerializedName("clog_slots_total")
	private Integer clogSlotsTotal;

	/**
	 * True only when {@link #items} came from a full collection log read.
	 * The server uses this to decide whether the item map may be trusted as a
	 * complete set — never to delete rows, only to know whether "not present"
	 * means "not obtained" or merely "not yet seen".
	 */
	@SerializedName("clog_complete")
	private boolean clogComplete;

	@Data
	public static class DiaryTier {
		@SerializedName("area_id")
		private final int areaId;

		/** 0 easy, 1 medium, 2 hard, 3 elite. */
		@SerializedName("tier")
		private final int tier;

		@SerializedName("completed")
		private final int completedCount;
	}
}
