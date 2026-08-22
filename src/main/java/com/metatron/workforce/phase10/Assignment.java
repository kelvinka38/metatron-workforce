package com.metatron.workforce.phase10;

import java.time.Instant;
import java.util.Objects;

public record Assignment(
        String assignmentId,
        String fromWorkerId,
        String toWorkerId,
        String responsibility,
        Instant assignedAt,
        String provenance) {
    public Assignment {
        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(fromWorkerId);
        Objects.requireNonNull(toWorkerId);
        Objects.requireNonNull(responsibility);
        Objects.requireNonNull(assignedAt);
        Objects.requireNonNull(provenance);
        if (responsibility.isBlank() || provenance.isBlank()) {
            throw new IllegalArgumentException("assignment responsibility and provenance are mandatory");
        }
    }
}
