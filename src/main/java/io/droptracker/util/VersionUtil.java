package io.droptracker.util;

/**
 * Minimal dotted-version comparison for plugin update checks.
 */
public final class VersionUtil {

    private VersionUtil() {
    }

    /**
     * Compares two dotted version strings numerically (e.g. "5.4.0" vs "5.10.2").
     * Missing segments are treated as zero; non-numeric segments compare as zero.
     *
     * @return negative if a &lt; b, 0 if equal, positive if a &gt; b
     */
    public static int compare(String a, String b) {
        String[] as = sanitize(a).split("\\.");
        String[] bs = sanitize(b).split("\\.");
        int length = Math.max(as.length, bs.length);
        for (int i = 0; i < length; i++) {
            int ai = segment(as, i);
            int bi = segment(bs, i);
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    public static boolean isOlderThan(String current, String other) {
        return compare(current, other) < 0;
    }

    private static String sanitize(String version) {
        if (version == null) {
            return "0";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? "0" : trimmed;
    }

    private static int segment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
