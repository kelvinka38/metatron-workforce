package com.metatron.workforce.phase9;

import java.util.Objects;

public record BoundaryResult(
        String requestId,
        String contractId,
        BoundaryStatus status,
        Object output,
        String authorityReference,
        BoundaryProvenance provenance) {

    public BoundaryResult {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(contractId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(authorityReference);
        Objects.requireNonNull(provenance);
        if (requestId.isBlank() || contractId.isBlank() || authorityReference.isBlank()) {
            throw new IllegalArgumentException("boundary result identity fields must not be blank");
        }
    }

    public boolean succeeded() {
        return status == BoundaryStatus.SUCCESS;
    }

    public boolean failed() {
        return status == BoundaryStatus.FAILURE;
    }
}
