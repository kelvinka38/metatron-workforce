package com.metatron.workforce.execution.governance;

import java.util.Map;
import java.util.Objects;

/** Deterministic constraint evaluator. Unknown MACHINE predicates and missing REVIEW evidence fail closed. */
public final class ConstraintEvaluator {
    public record Context(
            boolean authorityCurrent,
            boolean planApproved,
            boolean assignmentAuthorizationMatch,
            boolean planDigestMatch,
            boolean actionInPlan,
            boolean scopeInPlan,
            boolean requiredEvidenceDeclared,
            Map<String, String> reviewEvidence) {
        public Context {
            reviewEvidence = reviewEvidence == null ? Map.of() : Map.copyOf(reviewEvidence);
        }
    }

    public void requirePass(ConstraintBundle bundle, Context context) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(context, "context");
        for (ConstraintBinding binding : bundle.constraints()) {
            if (binding.kind() == ConstraintBinding.Kind.REVIEW) {
                String evidence = context.reviewEvidence().get(binding.constraintId());
                if (evidence == null || evidence.isBlank()) {
                    throw new GovernanceDeniedException("REVIEW_REQUIRED",
                            "missing review evidence for " + binding.constraintId());
                }
                continue;
            }
            boolean pass = switch (binding.predicateType()) {
                case "AUTHORITY_CURRENT" -> context.authorityCurrent();
                case "PLAN_APPROVED" -> context.planApproved();
                case "ASSIGNMENT_AUTHORIZATION_MATCH" -> context.assignmentAuthorizationMatch();
                case "PLAN_DIGEST_MATCH" -> context.planDigestMatch();
                case "ACTION_IN_PLAN" -> context.actionInPlan();
                case "SCOPE_IN_PLAN" -> context.scopeInPlan();
                case "REQUIRED_EVIDENCE_DECLARED" -> context.requiredEvidenceDeclared();
                default -> throw new GovernanceDeniedException("UNKNOWN_REQUIRED_CONSTRAINT",
                        binding.constraintId() + " uses unsupported predicate " + binding.predicateType());
            };
            if (!pass) {
                throw new GovernanceDeniedException("CONSTRAINT_VIOLATION", binding.constraintId());
            }
        }
    }
}
