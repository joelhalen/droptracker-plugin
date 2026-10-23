package io.droptracker.events;

import org.junit.Test;

import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the pet chat-message patterns exposed via {@code @VisibleForTesting}.
 * PET_REGEX gates local pet detection; CLAN_REGEX pulls the pet name and
 * milestone out of the clan broadcast.
 */
public class PetHandlerParseTest {

    @Test
    public void petRegexMatchesFunnyFeelingVariants() {
        assertTrue(PetHandler.PET_REGEX.matcher("You have a funny feeling like you're being followed.").matches());
        assertTrue(PetHandler.PET_REGEX.matcher("You have a funny feeling like you would have been followed...").matches());
        assertTrue(PetHandler.PET_REGEX.matcher("You feel something weird sneaking into your backpack.").matches());
    }

    @Test
    public void petRegexRejectsUnrelatedMessages() {
        assertFalse(PetHandler.PET_REGEX.matcher("You have completed a Treasure Trail.").matches());
        assertFalse(PetHandler.PET_REGEX.matcher("You feel a funny sensation.").matches());
    }

    @Test
    public void clanRegexExtractsUserPetAndMilestoneForFollow() {
        Matcher m = PetHandler.CLAN_REGEX.matcher(
                "Koeppy has a funny feeling like he would have been followed: Herbi at 500 kills.");
        assertTrue(m.find());
        assertEquals("Koeppy", m.group("user"));
        assertEquals("Herbi", m.group("pet"));
        assertEquals("500 kills.", m.group("milestone"));
    }

    @Test
    public void clanRegexExtractsBackpackVariant() {
        Matcher m = PetHandler.CLAN_REGEX.matcher(
                "Big Man feels something weird sneaking into his backpack: Baby mole at 200 kills.");
        assertTrue(m.find());
        assertEquals("Big Man", m.group("user"));
        assertEquals("Baby mole", m.group("pet"));
        assertEquals("200 kills.", m.group("milestone"));
    }

    // Pet names come from the server's manifest ("pets": name -> kill-count boss).
    // Skilling pets have no boss and must still be recognised; chat spells
    // names in whatever case the game uses.
    @Test
    public void petKeyFindsManifestPetsIgnoringCase() {
        java.util.Map<String, String> pets = new java.util.HashMap<>();
        pets.put("Baby mole", "Giant Mole");
        pets.put("Heron", null);
        pets.put("Lil' zik", "Theatre of Blood");
        assertEquals("Baby mole", PetHandler.petKey(pets, "baby Mole"));
        assertEquals("Heron", PetHandler.petKey(pets, "Heron"));
        assertEquals("Lil' zik", PetHandler.petKey(pets, "Lil' Zik"));
        assertEquals(null, PetHandler.petKey(pets, "Dragon warhammer"));
        assertEquals(null, PetHandler.petKey(java.util.Collections.emptyMap(), "Baby mole"));
    }
}
