package io.droptracker.models.api;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Server-controlled reference data, fetched from {@code GET /manifest} once per
 * session.
 *
 * <p>This exists so that "which game values do we read?" is a server decision
 * rather than a compile-time constant. The combat achievement varps are the
 * motivating case: they are <em>not</em> a contiguous range (3116-3128, then
 * 3387, 3718, 3773, 3774, 4204, 4496, 4721, 5673), because Jagex appends a new
 * varp at an arbitrary id whenever the previous one runs out of bits. Hardcoding
 * them means every batch of new combat achievements silently stops being
 * tracked until a Plugin Hub release ships — with no error to notice.
 *
 * <p><b>Fields must not be {@code final} primitives.</b> A {@code final}
 * primitive with a constant initializer is a compile-time constant (JLS 4.12.4),
 * so the compiler inlines the initializer at every read site and the value Gson
 * writes into the field is never observed. The bug looks like "the server is
 * sending the wrong data".
 *
 * <p><b>Parse through {@link #fromJson(Gson, String)}, never
 * {@code gson.fromJson(json, Manifest.class)}.</b> Gson's reflective adapter
 * abandons the whole document on the first field whose type does not match, so
 * one section changing shape server-side costs us every other section too. That
 * is not hypothetical: when {@code combat_achievement_tasks} went from an array
 * to an object, clients lost the varp list, the quest ids and the sync kill
 * switch along with it, and the only symptom was one debug line. Sections are
 * therefore deserialized one at a time, and a section we cannot read is dropped
 * on its own.
 *
 * <p>The served payload carries sections this class does not model, which is
 * deliberate and not an oversight to correct:
 * <ul>
 *   <li>{@code combat_achievement_tasks} — the task registry (name, tier,
 *       monster, and the varp/bit recording completion). The server decodes the
 *       raw varps we send against it at read time, so a registry correction
 *       applies retroactively to everything already stored; reading it here
 *       would duplicate that decode against staler data. It also carries no
 *       varbit ids, so there is nothing for a client to read directly.</li>
 *   <li>{@code collection_log} — the tab/page structure, used by the site to
 *       lay out a profile. The plugin reports items, not layout.</li>
 * </ul>
 *
 * <p>Every accessor degrades to a safe empty/default value, so a missing,
 * truncated or older manifest can only ever cost us a feature, never break
 * startup.
 */
@Data
@Slf4j
public class Manifest {

	private static final Type INT_LIST = new TypeToken<List<Integer>>() {}.getType();

	/** Content hash of the served sections; changes whenever any payload does. */
	@SerializedName("version")
	private String version;

	@SerializedName("combat_achievement_varps")
	private List<Integer> combatAchievementVarps;

	@SerializedName("quest_ids")
	private List<Integer> questIds;

	@SerializedName("sync")
	private SyncSettings sync;

	@SerializedName("team_indicators")
	private TeamIndicatorSettings teamIndicators;

	/**
	 * Parses a manifest document section by section.
	 *
	 * <p>A section that will not deserialize is dropped and logged; the rest of
	 * the manifest survives. A document that is not a JSON object at all yields
	 * null, because at that point there are no sections to salvage.
	 *
	 * <p>Deliberately reads into {@link JsonElement} and tests the shape rather
	 * than asking Gson for a {@code JsonObject}: the latter parses whatever is
	 * there and then <em>casts</em>, so an array-shaped document raises
	 * ClassCastException — unchecked, and past the caller's catch.
	 *
	 * @return the manifest, or null if the document is not a JSON object
	 * @throws com.google.gson.JsonSyntaxException if {@code json} is not valid JSON
	 */
	public static Manifest fromJson(Gson gson, String json) {
		JsonElement parsed = gson.fromJson(json, JsonElement.class);
		if (parsed == null || !parsed.isJsonObject()) {
			return null;
		}
		JsonObject root = parsed.getAsJsonObject();
		Manifest manifest = new Manifest();
		manifest.version = section(gson, root, "version", String.class);
		manifest.combatAchievementVarps = section(gson, root, "combat_achievement_varps", INT_LIST);
		manifest.questIds = section(gson, root, "quest_ids", INT_LIST);
		manifest.sync = section(gson, root, "sync", SyncSettings.class);
		manifest.teamIndicators = section(gson, root, "team_indicators", TeamIndicatorSettings.class);
		return manifest;
	}

	/** One section, or null if it is absent or not the shape we expect. */
	private static <T> T section(Gson gson, JsonObject root, String name, Type type) {
		JsonElement element = root.get(name);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		try {
			return gson.fromJson(element, type);
		} catch (JsonParseException e) {
			// Warn rather than swallow: a section whose shape changed is a
			// broken contract to go and fix, not a routine condition, and this
			// is the only place it is observable from.
			log.warn("Ignoring manifest section '{}': {}", name, e.toString());
			return null;
		}
	}

	public List<Integer> getCombatAchievementVarps() {
		return combatAchievementVarps == null ? Collections.emptyList() : combatAchievementVarps;
	}

	/**
	 * Quest ids to poll. Empty means "fall back to RuneLite's Quest enum", which
	 * is correct but lags new quest releases.
	 */
	public List<Integer> getQuestIds() {
		return questIds == null ? Collections.emptyList() : questIds;
	}

	public SyncSettings getSync() {
		return sync == null ? SyncSettings.defaults() : sync;
	}

	@Data
	public static class SyncSettings {
		@SerializedName("enabled")
		private Boolean enabled;

		@SerializedName("interval_minutes")
		private Integer intervalMinutes;

		@SerializedName("rapid_seconds")
		private Integer rapidSeconds;

		static SyncSettings defaults() {
			SyncSettings settings = new SyncSettings();
			settings.enabled = Boolean.TRUE;
			settings.intervalMinutes = 60;
			settings.rapidSeconds = 3;
			return settings;
		}

		/**
		 * Server-side kill switch for state sync. Defaults to enabled so a
		 * manifest we could not read never silently disables the feature — the
		 * switch is for turning sync <em>off</em> deliberately.
		 */
		public boolean isEnabled() {
			return enabled == null || enabled;
		}

		public int getIntervalMinutes() {
			return intervalMinutes == null || intervalMinutes <= 0 ? 60 : intervalMinutes;
		}

		public int getRapidSeconds() {
			return rapidSeconds == null || rapidSeconds < 0 ? 3 : rapidSeconds;
		}
	}

	public TeamIndicatorSettings getTeamIndicators() {
		return teamIndicators == null ? TeamIndicatorSettings.defaults() : teamIndicators;
	}

	/** Server-side controls for the clan-chat event team badges (web103a). */
	@Data
	public static class TeamIndicatorSettings {
		@SerializedName("enabled")
		private Boolean enabled;

		@SerializedName("max_roster_age_minutes")
		private Integer maxRosterAgeMinutes;

		static TeamIndicatorSettings defaults() {
			TeamIndicatorSettings settings = new TeamIndicatorSettings();
			settings.enabled = Boolean.TRUE;
			settings.maxRosterAgeMinutes = 60;
			return settings;
		}

		/**
		 * Kill switch. Defaults to enabled for the same reason sync's does: a
		 * manifest we could not read must not silently disable a feature the
		 * user turned on. This exists to stop a misbehaving decoration for
		 * every client in minutes, without a Plugin Hub round-trip.
		 */
		public boolean isEnabled() {
			return enabled == null || enabled;
		}

		/**
		 * Backstop refetch interval for a client that somehow missed a
		 * roster_version bump. The version gate is the real mechanism.
		 */
		public int getMaxRosterAgeMinutes() {
			return maxRosterAgeMinutes == null || maxRosterAgeMinutes <= 0
				? 60 : maxRosterAgeMinutes;
		}
	}
}
