package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/**
 * Deterministic gate used after context/information acquisition and before scarce frontier cognition.
 */
public final class CognitionNeedGate {
    public Decision evaluate(Input input) {
        Objects.requireNonNull(input, "input");
        return evaluateRuntime(new RuntimeInput(
                input.deterministicResultSufficient(),
                input.groundedAnswerSufficient(),
                input.validReusableArtifactAvailable(),
                input.blockedByMissingAuthorityOrRequiredEvidence(),
                input.request().materiallyAmbiguous()));
    }

    /** Runtime-neutral form used by the shared Intelligence Fabric after semantic normalization. */
    public Decision evaluateRuntime(RuntimeInput input) {
        Objects.requireNonNull(input, "input");
        if (input.blockedByMissingAuthorityOrRequiredEvidence()) {
            return new Decision(Disposition.BLOCKED, "required_authority_or_evidence_unavailable");
        }
        if (input.validReusableArtifactAvailable()) {
            return new Decision(Disposition.NOT_REQUIRED, "reusable_cognitive_artifact_available");
        }
        if (input.deterministicResultSufficient()) {
            return new Decision(Disposition.NOT_REQUIRED, "deterministic_result_sufficient");
        }
        if (input.groundedAnswerSufficient()) {
            return new Decision(Disposition.NOT_REQUIRED, "grounded_evidence_answer_sufficient");
        }
        if (input.materiallyAmbiguous()) {
            return new Decision(Disposition.BLOCKED, "human_semantic_choice_required");
        }
        return new Decision(Disposition.REQUIRED, "frontier_cognition_required");
    }

    public enum Disposition {
        NOT_REQUIRED,
        REQUIRED,
        BLOCKED
    }

    public record Input(
            NormalizedRequest request,
            boolean deterministicResultSufficient,
            boolean groundedAnswerSufficient,
            boolean validReusableArtifactAvailable,
            boolean blockedByMissingAuthorityOrRequiredEvidence) {
        public Input {
            Objects.requireNonNull(request, "request");
        }
    }

    public record RuntimeInput(
            boolean deterministicResultSufficient,
            boolean groundedAnswerSufficient,
            boolean validReusableArtifactAvailable,
            boolean blockedByMissingAuthorityOrRequiredEvidence,
            boolean materiallyAmbiguous) {}

    public record Decision(Disposition disposition, String reason) {
        public Decision {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }

        public boolean cognitionRequired() {
            return disposition == Disposition.REQUIRED;
        }
    }
}
