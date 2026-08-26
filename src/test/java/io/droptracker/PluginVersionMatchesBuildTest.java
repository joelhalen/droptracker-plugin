package io.droptracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@code DropTrackerPlugin.pluginVersion} to {@code build.gradle}'s version.
 * <p>
 * The version lives in two places that nothing keeps in sync: gradle's is what
 * the Plugin Hub builds and publishes, the field's is what the client reports
 * as {@code p_v} and compares against the server's advertised latest version.
 * Bump one without the other and the update nag misfires for everybody — a
 * client on the newest build reports the old number, so the server keeps
 * telling it to fetch a release it is already running, every single login.
 */
public class PluginVersionMatchesBuildTest {

    private static final Pattern GRADLE_VERSION =
        Pattern.compile("^version\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    @Test
    public void fieldMatchesGradle() throws IOException {
        Path gradle = findBuildGradle();
        String text = new String(Files.readAllBytes(gradle), StandardCharsets.UTF_8);
        Matcher m = GRADLE_VERSION.matcher(text);
        assertTrue("no top-level `version = '...'` in " + gradle, m.find());

        assertEquals(
            "build.gradle and DropTrackerPlugin.pluginVersion disagree — bump both",
            m.group(1),
            new DropTrackerPlugin().pluginVersion);
    }

    /** Tests may run from the project dir or a parent; walk up until it turns up. */
    private static Path findBuildGradle() {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("build.gradle");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("build.gradle not found from " + Paths.get("").toAbsolutePath());
    }
}
