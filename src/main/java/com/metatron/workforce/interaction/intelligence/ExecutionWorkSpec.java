package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Frontier-semantic work decomposition for an execution request.
 * This is a plan/proposal only: capability names are not authority, assignment, execution evidence or Observation.
 *
 * <p>Infrastructure authentication/provisioning is deliberately excluded from Work. Repository credentials are
 * owned by the institutional Repository Control Plane / Execution substrate; a planner or Worker may consume
 * governed repository actions but may never manufacture an interactive GitHub connect/login/auth/token step.</p>
 */
public record ExecutionWorkSpec(
        String stepId,
        String objective,
        String target,
        String requiredCapability,
        List<String> dependsOn,
        Consequence consequence,
        List<String> acceptanceCriteria,
        List<String> evidenceRequirements,
        com.metatron.workforce.core.CompletionPolicy completionPolicy) {

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
        completionPolicy = completionPolicy == null ? com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED : completionPolicy;
        if (stepId.isBlank()) throw new IllegalArgumentException("stepId must not be blank");
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (requiredCapability.isBlank()) throw new IllegalArgumentException("requiredCapability must not be blank");
        rejectRepositoryCredentialWorkStep(objective, target, requiredCapability);
    }

    /**
     * Backward-compatible shape for existing persisted plans and bounded callers that don't yet
     * declare a completion policy. Defaults to CompletionPolicy.EXECUTION_REQUIRED -- today's behavior,
     * unchanged, for every existing caller of this constructor. A caller that knows the Objective's
     * requested completion semantics (PR-only deliverable, production deployment required) should use
     * the canonical constructor above and pass an explicit CompletionPolicy instead.
     */
    public ExecutionWorkSpec(String stepId, String objective, String target, String requiredCapability,
                             List<String> dependsOn, Consequence consequence, List<String> acceptanceCriteria,
                             List<String> evidenceRequirements) {
        this(stepId, objective, target, requiredCapability, dependsOn, consequence, acceptanceCriteria,
                evidenceRequirements, com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
    }

    /** Backward-compatible shape for existing persisted plans and bounded callers. */
    public ExecutionWorkSpec(String stepId, String objective, String target, String requiredCapability,
                             List<String> dependsOn, Consequence consequence) {
        this(stepId, objective, target, requiredCapability, dependsOn, consequence, List.of(), List.of(),
                com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
    }

    public boolean verifiable() {
        return !acceptanceCriteria.isEmpty() && !evidenceRequirements.isEmpty();
    }

    /**
     * Repository authentication is infrastructure state, never executable Work. This guard sits at the Work
     * contract boundary so provider prompts, channels and future planners cannot reintroduce a connect stage.
     */
    static void rejectRepositoryCredentialWorkStep(String objective, String target, String requiredCapability) {
        String semantic = (clean(objective) + " " + clean(target) + " " + clean(requiredCapability))
                .toLowerCase(Locale.ROOT);
        boolean repositoryIdentity = semantic.contains("github") || semantic.contains("repository");
        boolean credentialProvisioning = semantic.contains("connect github")
                || semantic.contains("github connect")
                || semantic.contains("github login")
                || semantic.contains("login github")
                || semantic.contains("github oauth")
                || semantic.contains("oauth github")
                || semantic.contains("github token")
                || semantic.contains("github credential")
                || semantic.contains("repository credential")
                || semantic.contains("repository login")
                || semantic.contains("repository auth")
                || semantic.contains("authenticate github")
                || semantic.contains("authorize github")
                || semantic.contains("setup github auth")
                || semantic.contains("set up github auth");
        if (repositoryIdentity && credentialProvisioning) {
            throw new IllegalArgumentException(
                    "repository-control-plane-dependency-cannot-be-work-step: credential/connect/login/auth belongs to Execution infrastructure");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }
}
