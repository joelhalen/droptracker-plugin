package io.droptracker.models.api;

import java.util.Collections;
import java.util.List;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

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
 * <p>Every accessor degrades to a safe empty/default value, so a missing,
 * truncated or older manifest can only ever cost us a feature, never break
 * startup.
 */
@Data
public class Manifest {

	/** Content hash of the served sections; changes whenever any payload does. */
	@SerializedName("version")
	private String version;

	@SerializedName("combat_achievement_varps")
	private List<Integer> combatAchievementVarps;

	@SerializedName("combat_achievement_tasks")
	private List<CombatAchievementTask> combatAchievementTasks;

	@SerializedName("quest_ids")
	private List<Integer> questIds;

	@SerializedName("sync")
	private SyncSettings sync;

	public List<Integer> getCombatAchievementVarps() {
		return combatAchievementVarps == null ? Collections.emptyList() : combatAchievementVarps;
	}

	/**
	 * Per-task varbits, so completion can be reported per boss rather than as a
	 * single total. Empty means the plugin reports only the raw varps.
	 */
	public List<CombatAchievementTask> getCombatAchievementTasks() {
		return combatAchievementTasks == null ? Collections.emptyList() : combatAchievementTasks;
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

	/** One combat achievement task: which varbit holds it, and whose it is. */
	@Data
	public static class CombatAchievementTask {
		@SerializedName("varbit")
		private Integer varbit;

		@SerializedName("boss")
		private String boss;
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
}
