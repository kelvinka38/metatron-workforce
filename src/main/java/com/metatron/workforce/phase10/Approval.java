package com.metatron.workforce.phase10;

import java.time.Instant;
import java.util.Objects;

public record Approval(
        String approvalId,
        Status status,
        String authorityReference,
        Instant decidedAt,
        String provenance) {
    public enum Status { APPROVED, REJECTED }

    public Approval {
        Objects.requireNonNull(approvalId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(authorityReference);
        Objects.requireNonNull(decidedAt);
        Objects.requireNonNull(provenance);
        if (authorityReference.isBlank() || provenance.isBlank()) {
            throw new IllegalArgumentException("authority and provenance are mandatory");
        }
    }
}
