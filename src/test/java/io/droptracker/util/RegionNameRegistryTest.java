package io.droptracker.util;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class RegionNameRegistryTest {

    // Tests are not part of the shipped plugin jar, so constructing a plain
    // Gson here does not trip the plugin-hub instantiation check.
    private static final RegionNameRegistry REGISTRY = new RegionNameRegistry(new Gson());

    /**
     * region_names.json was extracted from the 397 region-carrying constants of
     * RuneLite's DiscordGameEventType, covering 974 distinct region ids. If the
     * resource is regenerated against a newer RuneLite these counts move, and
     * that should be a deliberate change rather than a silent one.
     */
    @Test
    public void registryCoversExpectedRegionCount() {
        assertEquals(974, REGISTRY.size());
    }

    @Test
    public void namesRaidRegions() {
        // Every region of a multi-region raid resolves to the same area.
        assertEquals("Theatre of Blood", REGISTRY.nameOf(12867));
        assertEquals("Theatre of Blood", REGISTRY.nameOf(13379));
        assertEquals("Chambers of Xeric", REGISTRY.nameOf(12889));
        assertEquals("Tombs of Amascut", REGISTRY.nameOf(14160));
    }

    @Test
    public void namesBossRegions() {
        assertEquals("Vorkath", REGISTRY.nameOf(9023));
        assertEquals("Cerberus", REGISTRY.nameOf(4883));
        assertEquals("Cerberus", REGISTRY.nameOf(5395));
    }

    @Test
    public void namesTheRegionTheEmbedEditorAdvertises() {
        // The custom-embed UI documents {location} with this exact sample.
        assertEquals("Catacombs of Kourend", REGISTRY.nameOf(6557));
    }

    @Test
    public void reportsAreaType() {
        assertEquals("RAIDS", REGISTRY.typeOf(12867));
        assertEquals("BOSSES", REGISTRY.typeOf(9023));
        assertEquals("DUNGEONS", REGISTRY.typeOf(6557));
        assertEquals("CITIES", REGISTRY.typeOf(12850));
        assertEquals("MINIGAMES", REGISTRY.typeOf(9043));
        assertEquals("REGIONS", REGISTRY.typeOf(12598));
    }

    @Test
    public void lookupExposesNameAndType() {
        RegionNameRegistry.Area area = REGISTRY.lookup(12598);
        assertNotNull(area);
        assertEquals("Grand Exchange", area.getName());
        assertEquals("REGIONS", area.getType());
    }

    @Test
    public void unmappedRegionResolvesToNull() {
        // Callers fall back to reporting the bare region id, so null is the
        // contract for "no name" rather than an empty string.
        assertNull(REGISTRY.lookup(0));
        assertNull(REGISTRY.nameOf(0));
        assertNull(REGISTRY.typeOf(0));
        assertNull(REGISTRY.nameOf(-1));
    }
}
