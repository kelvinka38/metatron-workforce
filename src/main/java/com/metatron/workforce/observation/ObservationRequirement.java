package com.metatron.workforce.observation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Workforce request to the authoritative Observation boundary; not an Observation itself. */
public record ObservationRequirement(
        String requirementId,
        String objectiveId,
        String stepId,
        String criterionId,
        String target,
        String criterion,
        List<String> evidenceRequirements,
        Instant createdAt) {
    public ObservationRequirement {
        require(requirementId, "requirementId");
        require(objectiveId, "objectiveId");
        require(stepId, "stepId");
        require(criterionId, "criterionId");
        target = target == null ? "" : target.trim();
        require(criterion, "criterion");
        evidenceRequirements = List.copyOf(Objects.requireNonNull(evidenceRequirements, "evidenceRequirements"));
        if (evidenceRequirements.isEmpty()) throw new IllegalArgumentException("evidenceRequirements must not be empty");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
