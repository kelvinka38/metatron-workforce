package com.metatron.workforce.phase9;

import java.util.Objects;

public record BoundaryDecision(
        BoundaryStatus status,
        String authorityReference,
        String reason,
        BoundaryProvenance provenance) {

    public BoundaryDecision {
        Objects.requireNonNull(status);
        Objects.requireNonNull(authorityReference);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(provenance);
        if (authorityReference.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("decision fields must not be blank");
        }
    }
}
