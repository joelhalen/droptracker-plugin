package io.droptracker.service;

import io.droptracker.service.StateSyncScheduler.Reaction;
import net.runelite.api.GameState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Which game state changes are logins, and which are just loading screens. */
public class StateSyncSchedulerTest {

    @Test
    public void arrivingFromTheLoginScreenIsALogin() {
        assertEquals(Reaction.LOGIN, StateSyncScheduler.react(GameState.LOGGING_IN, GameState.LOGGED_IN));
        assertEquals(Reaction.LOGIN, StateSyncScheduler.react(GameState.LOGIN_SCREEN, GameState.LOGGED_IN));
        assertEquals(Reaction.LOGIN, StateSyncScheduler.react(null, GameState.LOGGED_IN));
    }

    @Test
    public void aLoadingScreenIsNotALogin() {
        // LOGGED_IN -> LOADING -> LOGGED_IN: a teleport, a new region, a house.
        assertEquals(Reaction.IGNORE, StateSyncScheduler.react(GameState.LOGGED_IN, GameState.LOADING));
        assertEquals(Reaction.IGNORE, StateSyncScheduler.react(GameState.LOGGED_IN, GameState.LOGGED_IN));
    }

    @Test
    public void comingBackFromAHopOrADroppedConnectionResumesTheTimer() {
        assertEquals(Reaction.RESUME, StateSyncScheduler.react(GameState.HOPPING, GameState.LOGGED_IN));
        assertEquals(Reaction.RESUME, StateSyncScheduler.react(GameState.CONNECTION_LOST, GameState.LOGGED_IN));
    }

    @Test
    public void leavingTheGamePausesTheTimer() {
        assertEquals(Reaction.PAUSE, StateSyncScheduler.react(GameState.LOGGED_IN, GameState.LOGIN_SCREEN));
        assertEquals(Reaction.PAUSE, StateSyncScheduler.react(GameState.LOGGED_IN, GameState.HOPPING));
        assertEquals(Reaction.PAUSE, StateSyncScheduler.react(GameState.LOGGED_IN, GameState.CONNECTION_LOST));
    }
}
