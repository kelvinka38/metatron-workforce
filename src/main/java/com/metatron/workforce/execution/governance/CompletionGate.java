package com.metatron.workforce.execution.governance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Final authority for governed completion; Worker/Brain COMPLETE remains only a candidate. */
public final class CompletionGate {
    private final GovernanceStateStore store;
    private final AuthorityFreshnessValidator freshness;
    private final ConstraintEvaluator constraints;
    private final Clock clock;

    public CompletionGate(GovernanceStateStore store, AuthorityFreshnessValidator freshness,
                          ConstraintEvaluator constraints, Clock clock) {
        this.store = Objects.requireNonNull(store); this.freshness = Objects.requireNonNull(freshness);
        this.constraints = Objects.requireNonNull(constraints); this.clock = Objects.requireNonNull(clock);
    }

    public CompletionDecision decide(CompletionCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Instant now = clock.instant();
        List<String> codes = new ArrayList<>();
        List<String> evidence = new ArrayList<>(candidate.evidenceReferences());
        ExecutionPlanBinding plan = store.planBinding(candidate.planId(), candidate.planVersion()).orElse(null);
        AuthoritySnapshot snapshot = store.snapshot(candidate.authoritySnapshotId()).orElse(null);

        if (plan == null) codes.add("PLAN_NOT_APPROVED");
        if (snapshot == null) codes.add("SOT_DISCOVERY_REQUIRED");
        String authorityDigest = snapshot == null ? "unresolved" : snapshot.digest();

        if (plan != null && snapshot != null) {
            try {
                freshness.requireCurrent(snapshot);
                if (plan.status() != ExecutionPlanBinding.Status.APPROVED) codes.add("PLAN_NOT_APPROVED");
                if (!plan.objectiveId().equals(candidate.objectiveId())) codes.add("PLAN_BINDING_MISMATCH");
                if (!plan.authoritySnapshotId().equals(snapshot.snapshotId()) || !plan.authorityDigest().equals(snapshot.digest())) {
                    codes.add("PLAN_BINDING_MISMATCH");
                }
                boolean active = candidate.stepId().isBlank()
                        ? plan.steps().keySet().stream().allMatch(step -> store.approvedPlanForStep(candidate.objectiveId(), step)
                                .filter(p -> p.planId().equals(plan.planId()) && p.version() == plan.version()
                                        && p.planDigest().equals(plan.planDigest())).isPresent())
                        : store.approvedPlanForStep(candidate.objectiveId(), candidate.stepId())
                                .filter(p -> p.planId().equals(plan.planId()) && p.version() == plan.version()
                                        && p.planDigest().equals(plan.planDigest())).isPresent();
                if (!active) codes.add("PLAN_NOT_APPROVED");

                Set<String> completed = new LinkedHashSet<>(candidate.completedStepIds());
                if (!completed.containsAll(plan.steps().keySet())) codes.add("MANDATORY_STEPS_INCOMPLETE");
                if (!candidate.unresolvedRequiredFailures().isEmpty()) codes.add("UNRESOLVED_REQUIRED_FAILURES");
                if (!requirementsSatisfied(plan.acceptanceCriteria(), candidate.acceptanceSatisfaction())) codes.add("ACCEPTANCE_INCOMPLETE");
                if (!requirementsSatisfied(plan.evidenceRequirements(), candidate.evidenceSatisfaction())) codes.add("EVIDENCE_INSUFFICIENT");
                if (!plan.evidenceRequirements().isEmpty() && evidence.isEmpty()) codes.add("EVIDENCE_INSUFFICIENT");
                if (!candidate.observationPassed()) codes.add("OBSERVATION_REQUIRED");
                if (candidate.carriesArtifactIdentity() && !exactArtifactIdentity(candidate)) codes.add("EXACT_ARTIFACT_IDENTITY_MISMATCH");

                ConstraintBundle bundle = store.constraintBundle(plan.constraintBundleId()).orElse(null);
                if (bundle == null) codes.add("DERIVATION_UNVERIFIED");
                else {
                    try {
                        constraints.requirePass(bundle, new ConstraintEvaluator.Context(
                                true, plan.status() == ExecutionPlanBinding.Status.APPROVED,
                                true, true, true, true, !plan.evidenceRequirements().isEmpty(), plan.reviewEvidence()));
                    } catch (GovernanceDeniedException denied) {
                        codes.add(denied.code());
                    }
                }
            } catch (GovernanceDeniedException denied) {
                codes.add(denied.code());
            }
        }

        List<String> uniqueCodes = codes.stream().distinct().toList();
        CompletionDecision.Verdict verdict = uniqueCodes.isEmpty()
                ? CompletionDecision.Verdict.ALLOW : CompletionDecision.Verdict.DENY;
        String material = candidate.objectiveId() + "|" + candidate.planId() + "|" + candidate.planVersion()
                + "|" + authorityDigest + "|" + verdict + "|" + String.join(",", uniqueCodes) + "|" + now.toEpochMilli();
        evidence.add("completion-gate:" + verdict);
        uniqueCodes.forEach(code -> evidence.add("completion-denial:" + code));
        CompletionDecision decision = new CompletionDecision(
                "completion:" + GovernanceDigests.sha256(material), verdict, candidate.objectiveId(), candidate.stepId(),
                candidate.planId(), candidate.planVersion(), authorityDigest, uniqueCodes, evidence, now);
        store.saveCompletionDecision(decision);
        return decision;
    }

    private static boolean requirementsSatisfied(List<String> required, java.util.Map<String, String> satisfaction) {
        if (required == null || required.isEmpty()) return true;
        return required.stream().allMatch(requirement -> {
            String evidence = satisfaction.get(requirement);
            return evidence != null && !evidence.isBlank();
        });
    }

    private static boolean exactArtifactIdentity(CompletionCandidate candidate) {
        List<String> values = List.of(candidate.sourceSha(), candidate.testedSha(), candidate.approvedSha(),
                candidate.deployedSha(), candidate.observedSha());
        if (values.stream().anyMatch(value -> !value.matches("[0-9a-fA-F]{40}"))) return false;
        String expected = values.getFirst().toLowerCase();
        return values.stream().allMatch(value -> value.equalsIgnoreCase(expected));
    }
}
