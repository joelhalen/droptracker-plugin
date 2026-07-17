package io.droptracker.util;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Quantity formatting for event progress values: large targets like
 * 10,000,000 XP must never render as raw digit runs. Panel rows use comma
 * grouping ({@link #commas}); tight spots (HUD progress bar) use GP-style
 * abbreviations ({@link #abbrev}).
 */
public final class ValueFormat {

    private static final NumberFormat COMMAS = NumberFormat.getIntegerInstance(Locale.US);

    private ValueFormat() {
    }

    /** 10000000 → "10,000,000". */
    public static String commas(long value) {
        synchronized (COMMAS) {
            return COMMAS.format(value);
        }
    }

    /** 10000000 → "10M", 1250000 → "1.25M", 813636 → "813.6K", 500 → "500". */
    public static String abbrev(long value) {
        long abs = Math.abs(value);
        if (abs >= 1_000_000_000L) {
            return trim(value / 1_000_000_000.0, 2) + "B";
        }
        if (abs >= 1_000_000L) {
            return trim(value / 1_000_000.0, 2) + "M";
        }
        if (abs >= 10_000L) {
            return trim(value / 1_000.0, 1) + "K";
        }
        return commas(value);
    }

    /**
     * "have / need" for progress displays: abbreviated once values get long
     * enough that comma strings stop fitting, comma-grouped below that.
     */
    public static String progress(long have, long need) {
        if (need >= 100_000L || have >= 100_000L) {
            return abbrev(have) + " / " + abbrev(need);
        }
        return commas(have) + " / " + commas(need);
    }

    private static String trim(double value, int maxDecimals) {
        String out = String.format(Locale.US, "%." + maxDecimals + "f", value);
        while (out.contains(".") && (out.endsWith("0") || out.endsWith("."))) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
