package com.metatron.workforce.phase10;

import java.time.Instant;
import java.util.Objects;

public record VerticalSliceRequest(
        String requestId,
        String humanId,
        String headOfWorkforceId,
        String instruction,
        Instant requestedAt) {
    public VerticalSliceRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(humanId);
        Objects.requireNonNull(headOfWorkforceId);
        Objects.requireNonNull(instruction);
        Objects.requireNonNull(requestedAt);
    }
}
