package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionUtilTest {

    @Test
    public void equalVersions() {
        assertEquals(0, VersionUtil.compare("5.4.0", "5.4.0"));
        assertEquals(0, VersionUtil.compare("5.4", "5.4.0"));
        assertEquals(0, VersionUtil.compare("v5.4.0", "5.4.0"));
    }

    @Test
    public void ordering() {
        assertTrue(VersionUtil.compare("5.4.0", "5.5.0") < 0);
        assertTrue(VersionUtil.compare("5.10.0", "5.9.9") > 0);
        assertTrue(VersionUtil.compare("4.9.9", "5.0.0") < 0);
        assertTrue(VersionUtil.compare("5.4.1", "5.4.0") > 0);
    }

    @Test
    public void isOlderThan() {
        assertTrue(VersionUtil.isOlderThan("5.4.0", "5.5.0"));
        assertFalse(VersionUtil.isOlderThan("5.5.0", "5.5.0"));
        assertFalse(VersionUtil.isOlderThan("5.6.0", "5.5.0"));
    }

    @Test
    public void malformedInputsAreSafe() {
        assertEquals(0, VersionUtil.compare(null, "0"));
        assertEquals(0, VersionUtil.compare("", "0"));
        assertTrue(VersionUtil.compare("abc", "1.0") < 0);
        assertEquals(0, VersionUtil.compare("5.x.0", "5.0.0"));
    }
}
