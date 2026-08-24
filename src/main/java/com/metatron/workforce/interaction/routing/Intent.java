package com.metatron.workforce.interaction.routing;

import java.util.Objects;

public record Intent(String action, String target, String rawText) {
    public Intent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(rawText, "rawText");
    }
}
