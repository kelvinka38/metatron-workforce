package com.metatron.workforce.interaction.evidence;

import java.time.Instant;
import java.util.Objects;

public record Evidence(String source, String reference, String content, Instant observedAt) {
    public Evidence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
