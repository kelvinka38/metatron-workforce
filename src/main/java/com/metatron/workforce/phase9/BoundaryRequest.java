package com.metatron.workforce.phase9;

import java.util.Objects;

public record BoundaryRequest(
        String requestId,
        String contractId,
        String actorId,
        String authorityReference,
        Object input,
        BoundaryProvenance provenance) {

    public BoundaryRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(contractId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(authorityReference);
        Objects.requireNonNull(input);
        Objects.requireNonNull(provenance);
        if (requestId.isBlank() || contractId.isBlank() || actorId.isBlank()
                || authorityReference.isBlank()) {
            throw new IllegalArgumentException("boundary request identity fields must not be blank");
        }
    }
}
