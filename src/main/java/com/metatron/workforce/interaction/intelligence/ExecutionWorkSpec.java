package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Frontier-semantic work decomposition for an execution request.
 * This is a plan/proposal only: capability names are not authority, assignment, execution evidence or Observation.
 */
public record ExecutionWorkSpec(
        String stepId,
        String objective,
        String target,
        String requiredCapability,
        List<String> dependsOn,
        Consequence consequence,
        List<String> acceptanceCriteria,
        List<String> evidenceRequirements) {

    public enum Consequence { READ_ONLY, MUTATING }

    public ExecutionWorkSpec {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(objective, "objective");
        target = target == null ? "" : target;
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        Objects.requireNonNull(consequence, "consequence");
        acceptanceCriteria = normalize(acceptanceCriteria);
        evidenceRequirements = normalize(evidenceRequirements);
        if (stepId.isBlank()) throw new IllegalArgumentException("stepId must not be blank");
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (requiredCapability.isBlank()) throw new IllegalArgumentException("requiredCapability must not be blank");
    }

    /** Backward-compatible shape for existing persisted plans and bounded callers. */
    public ExecutionWorkSpec(String stepId, String objective, String target, String requiredCapability,
                             List<String> dependsOn, Consequence consequence) {
        this(stepId, objective, target, requiredCapability, dependsOn, consequence, List.of(), List.of());
    }

    public boolean verifiable() {
        return !acceptanceCriteria.isEmpty() && !evidenceRequirements.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }
}
