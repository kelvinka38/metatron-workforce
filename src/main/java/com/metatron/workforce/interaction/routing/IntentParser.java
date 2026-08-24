package com.metatron.workforce.interaction.routing;

import java.util.Locale;
import java.util.Objects;

/** Deterministic first-pass parser; ambiguous requests remain unresolved rather than guessed. */
public final class IntentParser {
    public Intent parse(String text) {
        Objects.requireNonNull(text, "text");
        String raw = text.trim();
        if (raw.isEmpty()) return new Intent("unknown", "", raw);
        String lower = raw.toLowerCase(Locale.ROOT);
        String[] prefixes = {"audit ", "inspect ", "review ", "check ", "fix "};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String target = raw.substring(prefix.length()).trim();
                return new Intent(prefix.trim(), target, raw);
            }
        }
        return new Intent("unknown", raw, raw);
    }
}
