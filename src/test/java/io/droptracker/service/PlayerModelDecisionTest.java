package io.droptracker.service;

import io.droptracker.models.api.ModelStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Whether an outfit has to be exported and sent, given what the server says it
 * already holds.
 *
 * <p>Why this exists: the uploader used to remember one outfit, so a player
 * alternating between two re-sent one of them on every switch. The server
 * measured four in five uploads (of ~264k a day, 2026-09-14) as a model it
 * already had — each one a mesh export on the client thread and tens of
 * kilobytes off the player's connection, discarded on arrival. Asking first is
 * only worth it if the answers are read correctly, which is what this pins.
 */
public class PlayerModelDecisionTest {

    private static final long NOW = 1_000_000L;

    private static ModelStatus status(boolean accepted, boolean hasModel, boolean hasPet) {
        return new ModelStatus(accepted, hasModel, hasPet);
    }

    @Test
    public void anOutfitTheServerHoldsNeedsNothingSent() {
        assertEquals(PlayerModelService.Action.SETTLED,
                PlayerModelService.decide(status(true, true, false), false, NOW, 0));
    }

    @Test
    public void anOutfitTheServerLacksIsUploaded() {
        assertEquals(PlayerModelService.Action.UPLOAD,
                PlayerModelService.decide(status(true, false, false), false, NOW, 0));
    }

    @Test
    public void noAnswerFallsBackToSending() {
        // An older or unreachable server must leave the client behaving the way
        // it did before the check endpoint existed: send, and let the server
        // decide. Silence must never be read as "they have it".
        assertEquals(PlayerModelService.Action.UPLOAD,
                PlayerModelService.decide(null, false, NOW, 0));
    }

    @Test
    public void aPetOutIsSentWhenTheStoredOutfitHasNone() {
        // The fingerprint covers the character, not the follower, so an outfit
        // stored before the pet was out is held without one.
        assertEquals(PlayerModelService.Action.UPLOAD,
                PlayerModelService.decide(status(true, true, false), true, NOW, 0));
    }

    @Test
    public void aPetAlreadyStoredAlongsideTheOutfitNeedsNothing() {
        assertEquals(PlayerModelService.Action.SETTLED,
                PlayerModelService.decide(status(true, true, true), true, NOW, 0));
    }

    @Test
    public void anAccountTheServerDoesNotKnowIsNotWorthSendingTo() {
        // Identity comes from submissions; a model from an unknown account is
        // refused, so sending one is pure waste on both sides.
        assertEquals(PlayerModelService.Action.UNKNOWN_ACCOUNT,
                PlayerModelService.decide(status(false, false, false), false, NOW, 0));
    }

    @Test
    public void theUploadFloorHoldsAMissBack() {
        // Cycling through more outfits than the server keeps misses every time;
        // without a floor that is one export and upload per idle moment.
        assertEquals(PlayerModelService.Action.WAIT,
                PlayerModelService.decide(status(true, false, false), false, NOW, NOW + 1));
    }

    @Test
    public void theFloorNeverHoldsBackAnOutfitTheServerAlreadyHas() {
        // Being inside the window must not stop us recording what is worn —
        // that answer costs nothing and is how the profile follows a switch.
        assertEquals(PlayerModelService.Action.SETTLED,
                PlayerModelService.decide(status(true, true, false), false, NOW, NOW + 60_000));
    }

    @Test
    public void theFloorIsOpenOnceItsWindowHasPassed() {
        assertEquals(PlayerModelService.Action.UPLOAD,
                PlayerModelService.decide(status(true, false, false), false, NOW, NOW));
    }
}
