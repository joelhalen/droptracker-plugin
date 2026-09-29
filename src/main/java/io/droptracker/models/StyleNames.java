package io.droptracker.models;

import javax.annotation.Nullable;
import java.util.Locale;

/** Loose name matching for the {@code ::dtpopup} style arguments. */
final class StyleNames {
    private StyleNames() {
    }

    @Nullable
    static <E extends Enum<E>> E parse(E[] values, @Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = normalize(raw);
        if (wanted.isEmpty()) {
            return null;
        }
        try {
            int index = Integer.parseInt(wanted) - 1;
            return index >= 0 && index < values.length ? values[index] : null;
        } catch (NumberFormatException ignored) {
            // not a number: match by name
        }
        for (E value : values) {
            if (normalize(value.name()).equals(wanted) || normalize(value.toString()).equals(wanted)) {
                return value;
            }
        }
        return null;
    }

    /** Lowercase, letters and digits only: "Showcase (stone)" == "showcase_stone". */
    private static String normalize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
