package com.metatron.workforce.phase9;

import java.time.Instant;
import java.util.Objects;

public record BoundaryProvenance(
        String source,
        String evidenceReference,
        Instant recordedAt) {

    public BoundaryProvenance {
        Objects.requireNonNull(source);
        Objects.requireNonNull(evidenceReference);
        Objects.requireNonNull(recordedAt);
        if (source.isBlank() || evidenceReference.isBlank()) {
            throw new IllegalArgumentException("provenance fields must not be blank");
        }
    }
}
