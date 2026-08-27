package io.droptracker.models.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire-format tests for {@code GET /manifest}, pinning the plugin's parsing of
 * the server contract against bodies shaped like the ones production serves.
 *
 * <p>The case that matters most is {@link #survivesASectionChangingShape()}.
 * The server changed {@code combat_achievement_tasks} from an array to an
 * object; parsing the document in one shot meant Gson abandoned it at that
 * field, so clients silently lost the varp list, the quest ids and the sync
 * kill switch as well, and the only trace was a single debug line reading
 * "Expected BEGIN_ARRAY but was BEGIN_OBJECT".
 */
public class ManifestParseTest {

    private final Gson gson = new Gson();

    /** Keys are served sorted, so the sections a client cares about come last. */
    private static final String LIVE_SHAPE =
        "{\"collection_log\":[{\"name\":\"Bosses\",\"pages\":[{\"name\":\"Abyssal Sire\","
            + "\"items\":[13262,13265,13277]}]}],"
            + "\"combat_achievement_tasks\":{\"varps\":[3116,3117],\"tasks\":["
            + "{\"index\":0,\"varp\":3116,\"bit\":0,\"tier\":\"Easy\",\"tier_id\":1,"
            + "\"name\":\"Noxious Foe\",\"description\":\"Kill an Aberrant Spectre.\","
            + "\"type\":\"Kill Count\",\"monster\":\"Aberrant Spectre\"}]},"
            + "\"combat_achievement_varps\":[3116,3117,3387,5673],"
            + "\"quest_ids\":[3,11],"
            + "\"sync\":{\"enabled\":true,\"interval_minutes\":90,\"rapid_seconds\":5},"
            + "\"version\":\"71c8790895b1\"}";

    @Test
    public void survivesASectionChangingShape() {
        Manifest manifest = Manifest.fromJson(gson, LIVE_SHAPE);

        assertNotNull(manifest);
        assertEquals("71c8790895b1", manifest.getVersion());
        // The three things a client actually reads, all of which used to be
        // collateral damage from the unmodelled section sitting before them.
        assertEquals(4, manifest.getCombatAchievementVarps().size());
        assertEquals(Integer.valueOf(5673), manifest.getCombatAchievementVarps().get(3));
        assertEquals(2, manifest.getQuestIds().size());
        assertEquals(90, manifest.getSync().getIntervalMinutes());
        assertEquals(5, manifest.getSync().getRapidSeconds());
        assertTrue(manifest.getSync().isEnabled());
    }

    @Test
    public void anUnreadableSectionCostsOnlyItself() {
        Manifest manifest = Manifest.fromJson(gson,
            "{\"combat_achievement_varps\":[3116],\"quest_ids\":{\"oops\":1},"
                + "\"sync\":{\"enabled\":false}}");

        assertNotNull(manifest);
        assertEquals(1, manifest.getCombatAchievementVarps().size());
        assertTrue(manifest.getQuestIds().isEmpty());
        // The kill switch still reaches us, which is the whole point: it is the
        // only lever that stops a misbehaving client without a Hub release.
        assertTrue(!manifest.getSync().isEnabled());
    }

    @Test
    public void missingSectionsFallBackToDefaults() {
        Manifest manifest = Manifest.fromJson(gson, "{\"version\":\"abc\"}");

        assertNotNull(manifest);
        assertTrue(manifest.getCombatAchievementVarps().isEmpty());
        assertTrue(manifest.getQuestIds().isEmpty());
        assertTrue(manifest.getSync().isEnabled());
        assertEquals(60, manifest.getSync().getIntervalMinutes());
        assertEquals(3, manifest.getSync().getRapidSeconds());
    }

    @Test
    public void readsTheTeamIndicatorKillSwitch() {
        Manifest manifest = Manifest.fromJson(gson,
            "{\"team_indicators\":{\"enabled\":false,\"max_roster_age_minutes\":15}}");

        assertNotNull(manifest);
        assertTrue(!manifest.getTeamIndicators().isEnabled());
        assertEquals(15, manifest.getTeamIndicators().getMaxRosterAgeMinutes());
    }

    @Test
    public void teamIndicatorsDefaultToOnWhenAbsentOrUnreadable() {
        // Same posture as sync's switch: it exists to turn the feature off
        // deliberately, never to fail closed on a manifest we could not read.
        assertTrue(Manifest.fromJson(gson, "{\"version\":\"abc\"}")
            .getTeamIndicators().isEnabled());
        assertTrue(Manifest.fromJson(gson, "{\"team_indicators\":[1,2]}")
            .getTeamIndicators().isEnabled());
        assertEquals(60, Manifest.fromJson(gson, "{\"team_indicators\":{}}")
            .getTeamIndicators().getMaxRosterAgeMinutes());
    }

    @Test
    public void nonsenseSyncValuesFallBackRatherThanDisablingSync() {
        Manifest manifest = Manifest.fromJson(gson,
            "{\"sync\":{\"interval_minutes\":0,\"rapid_seconds\":-1}}");

        assertNotNull(manifest);
        assertEquals(60, manifest.getSync().getIntervalMinutes());
        assertEquals(3, manifest.getSync().getRapidSeconds());
        assertTrue(manifest.getSync().isEnabled());
    }

    /**
     * The premise {@code fromJson} exists for: Gson's reflective adapter
     * abandons the entire document at the first field whose type does not
     * match, rather than skipping that field. If this ever stops being true,
     * the section-by-section loop can go with it.
     */
    @Test(expected = JsonSyntaxException.class)
    public void oneShotParsingAbandonsTheWholeDocument() {
        gson.fromJson("{\"combat_achievement_varps\":[3116],\"quest_ids\":{\"oops\":1}}",
            Manifest.class);
    }

    /**
     * A document that is not an object yields null rather than throwing:
     * callers treat null as "no manifest" and fall back, and an unchecked
     * ClassCastException here would sail past DropTrackerApi's catch.
     */
    @Test
    public void nonObjectDocumentsAreNull() {
        assertNull(Manifest.fromJson(gson, "null"));
        assertNull(Manifest.fromJson(gson, "[]"));
        assertNull(Manifest.fromJson(gson, "\"nope\""));
    }
}
