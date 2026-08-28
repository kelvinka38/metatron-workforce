package com.metatron.workforce.execution;

import java.util.List;
import java.util.Objects;

public record Assignment(
        String assignmentId,
        String workerId,
        List<GuidanceRequirement> requiredGuidance
) {
    public Assignment {
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(workerId, "workerId");
        requiredGuidance = requiredGuidance == null ? List.of() : List.copyOf(requiredGuidance);
    }

    /** Backward-compatible assignment with no guidance prerequisite. */
    public Assignment(String assignmentId, String workerId) {
        this(assignmentId, workerId, List.of());
    }
}
