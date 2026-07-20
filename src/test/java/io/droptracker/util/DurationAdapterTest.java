package io.droptracker.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DurationAdapterTest {

    // Tests are not shipped in the plugin jar, so a plain Gson here does not
    // trip the plugin-hub "new Gson()" instantiation check.
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Duration.class, new DurationAdapter())
            .create();

    @Test
    public void roundTripsIso8601() {
        Duration original = Duration.ofMinutes(90);
        String json = gson.toJson(original);
        assertEquals("\"PT1H30M\"", json);
        assertEquals(original, gson.fromJson(json, Duration.class));
    }

    @Test
    public void roundTripsSubMinuteDurations() {
        Duration original = Duration.ofMillis(83_400);
        assertEquals(original, gson.fromJson(gson.toJson(original), Duration.class));
    }

    @Test
    public void malformedStringDeserializesToNull() {
        assertNull(gson.fromJson("\"not-a-duration\"", Duration.class));
    }

    @Test
    public void formatDurationBucketsByLargestUnit() {
        assertEquals("just now", DurationAdapter.formatDuration(Duration.ofSeconds(10)));
        assertEquals("30 min ago", DurationAdapter.formatDuration(Duration.ofMinutes(30)));
        assertEquals("5 hr ago", DurationAdapter.formatDuration(Duration.ofHours(5)));
        assertEquals("2 day ago", DurationAdapter.formatDuration(Duration.ofDays(2)));
    }

    @Test
    public void formatDurationRoundsDownToBucketBoundary() {
        // 59s still reads as "just now"; the 60th second rolls to minutes.
        assertEquals("just now", DurationAdapter.formatDuration(Duration.ofSeconds(59)));
        assertEquals("1 min ago", DurationAdapter.formatDuration(Duration.ofSeconds(60)));
    }
}
