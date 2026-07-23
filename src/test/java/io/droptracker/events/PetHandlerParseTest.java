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

    // Regression: skilling pets have no boss source and are absent from PET_TO_SOURCE,
    // but must still be recognised via the Pet enum, else their name is dropped.
    @Test
    public void isPetNameRecognisesSkillingPets() {
        for (String skillingPet : new String[]{
                "Beaver", "Heron", "Rock golem", "Rocky", "Giant squirrel",
                "Tangleroot", "Rift guardian", "Baby chinchompa"}) {
            assertTrue(skillingPet + " should be recognised", PetHandler.isPetName(skillingPet));
        }
    }

    @Test
    public void isPetNameRecognisesBossPetsAndRejectsNonPets() {
        // Newer boss pets live only in PET_TO_SOURCE, not the Pet enum.
        assertTrue(PetHandler.isPetName("Yami"));
        assertTrue(PetHandler.isPetName("Dom"));
        assertTrue(PetHandler.isPetName("Baby mole"));
        assertFalse(PetHandler.isPetName("Dragon warhammer"));
    }
}
