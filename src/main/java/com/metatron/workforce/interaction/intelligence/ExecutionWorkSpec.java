package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Frontier-semantic work decomposition for an execution request.
 * This is a plan/proposal only: capability names are not authority, assignment or execution evidence.
 */
public record ExecutionWorkSpec(
        String stepId,
        String objective,
        String target,
        String requiredCapability,
        List<String> dependsOn,
        Consequence consequence) {

    public enum Consequence { READ_ONLY, MUTATING }

    public ExecutionWorkSpec {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(dependsOn, "dependsOn");
        Objects.requireNonNull(consequence, "consequence");
        if (stepId.isBlank()) throw new IllegalArgumentException("stepId must not be blank");
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (requiredCapability.isBlank()) throw new IllegalArgumentException("requiredCapability must not be blank");
        dependsOn = List.copyOf(dependsOn);
    }
}
