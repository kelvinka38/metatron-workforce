package com.metatron.workforce.execution.governance;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Final fail-closed authorization boundary before a mutating Action enters tool code.
 * It performs only local/store checks on the hot path; no GitHub or frontier-model call occurs here.
 */
public final class ExecutionGate {
    private static final Duration DEFAULT_PERMIT_TTL = Duration.ofSeconds(30);

    private final GovernanceStateStore store;
    private final ExecutionAttemptService attempts;
    private final AuthorityFreshnessValidator freshness;
    private final PlanConformanceValidator planConformance;
    private final ConstraintEvaluator constraints;
    private final Clock clock;
    private final Duration permitTtl;

    public ExecutionGate(GovernanceStateStore store, ExecutionAttemptService attempts,
                         AuthorityFreshnessValidator freshness, PlanConformanceValidator planConformance,
                         ConstraintEvaluator constraints, Clock clock) {
        this(store, attempts, freshness, planConformance, constraints, clock, DEFAULT_PERMIT_TTL);
    }

    public ExecutionGate(GovernanceStateStore store, ExecutionAttemptService attempts,
                         AuthorityFreshnessValidator freshness, PlanConformanceValidator planConformance,
                         ConstraintEvaluator constraints, Clock clock, Duration permitTtl) {
        this.store = Objects.requireNonNull(store); this.attempts = Objects.requireNonNull(attempts);
        this.freshness = Objects.requireNonNull(freshness); this.planConformance = Objects.requireNonNull(planConformance);
        this.constraints = Objects.requireNonNull(constraints); this.clock = Objects.requireNonNull(clock);
        this.permitTtl = Objects.requireNonNull(permitTtl);
        if (permitTtl.isZero() || permitTtl.isNegative()) throw new IllegalArgumentException("permitTtl must be positive");
    }

    public ExecutionPermit authorize(ExecutionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        Instant now = clock.instant();
        try {
            ExecutionAttempt attempt = requireCurrentAttempt(intent, now);
            ExecutionAttemptGovernanceBinding attemptBinding = store.attemptBinding(intent.attemptId())
                    .orElseThrow(() -> new GovernanceDeniedException("SOT_DISCOVERY_REQUIRED", "execution attempt has no governance binding"));
            requireAttemptBinding(intent, attemptBinding);

            ExecutionPlanBinding plan = store.planBinding(intent.planId(), intent.planVersion())
                    .orElseThrow(() -> new GovernanceDeniedException("PLAN_NOT_APPROVED", "plan not found"));
            AuthoritySnapshot snapshot = store.snapshot(intent.authoritySnapshotId())
                    .orElseThrow(() -> new GovernanceDeniedException("SOT_DISCOVERY_REQUIRED", "authority snapshot not found"));
            DerivationReceipt receipt = store.derivationReceipt(intent.derivationReceiptId())
                    .orElseThrow(() -> new GovernanceDeniedException("DERIVATION_UNVERIFIED", "derivation receipt not found"));
            ConstraintBundle bundle = store.constraintBundle(receipt.constraintBundleId())
                    .orElseThrow(() -> new GovernanceDeniedException("DERIVATION_UNVERIFIED", "constraint bundle not found"));

            freshness.requireCurrent(snapshot);
            boolean activePlan = store.approvedPlanForStep(intent.objectiveId(), intent.stepId())
                    .filter(active -> active.planId().equals(plan.planId()) && active.version() == plan.version()
                            && active.planDigest().equals(plan.planDigest())).isPresent();
            if (!activePlan) throw new GovernanceDeniedException("PLAN_NOT_APPROVED", "plan is not active for step");
            boolean bindingMatch = attemptBinding.planId().equals(plan.planId())
                    && attemptBinding.planVersion() == plan.version()
                    && attemptBinding.planDigest().equals(plan.planDigest())
                    && attemptBinding.authoritySnapshotId().equals(snapshot.snapshotId())
                    && attemptBinding.authorityDigest().equals(snapshot.digest())
                    && attemptBinding.derivationReceiptId().equals(receipt.receiptId());
            if (!bindingMatch) throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "attempt/plan/authority mismatch");
            if (!receipt.executableCandidate() || !receipt.authorityDigest().equals(snapshot.digest())) {
                throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", "receipt no longer executable");
            }

            boolean actionAllowed = planConformance.actionAllowed(plan, intent.stepId(), intent.actionRef());
            boolean scopeAllowed = planConformance.scopeAllowed(plan, intent.stepId(), intent.targetScope());
            boolean assignmentMatch = attempt.workerId().equals(intent.workerId())
                    && attempt.assignmentRef().equals(intent.assignmentRef())
                    && attempt.authorizationRef().equals(intent.authorizationRef());
            constraints.requirePass(bundle, new ConstraintEvaluator.Context(
                    true,
                    plan.status() == ExecutionPlanBinding.Status.APPROVED,
                    assignmentMatch,
                    bindingMatch,
                    actionAllowed,
                    scopeAllowed,
                    !plan.evidenceRequirements().isEmpty(),
                    plan.reviewEvidence()));

            if (!actionAllowed) throw new GovernanceDeniedException("ACTION_OUTSIDE_PLAN", intent.actionRef());
            if (!scopeAllowed) throw new GovernanceDeniedException("ACTION_OUTSIDE_SCOPE", intent.targetScope());
            if (!assignmentMatch) throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "attempt actor attribution mismatch");

            String material = intent.objectiveId() + "|" + intent.attemptId() + "|" + intent.fencingToken()
                    + "|" + intent.workerId() + "|" + intent.assignmentRef() + "|" + intent.authorizationRef()
                    + "|" + plan.planId() + "|" + plan.version() + "|" + plan.planDigest()
                    + "|" + intent.stepId() + "|" + intent.actionRef() + "|" + snapshot.digest()
                    + "|" + intent.targetScope() + "|" + now.toEpochMilli();
            return new ExecutionPermit("permit:" + GovernanceDigests.sha256(material), intent.objectiveId(),
                    intent.attemptId(), intent.fencingToken(), intent.workerId(), intent.assignmentRef(),
                    intent.authorizationRef(), plan.planId(), plan.version(), plan.planDigest(), intent.stepId(),
                    intent.actionRef(), snapshot.digest(), intent.targetScope(), now, now.plus(permitTtl));
        } catch (GovernanceDeniedException denied) {
            persistDenial(intent, denied, now);
            throw denied;
        }
    }

    /** Gate owns the time source for permit validation; callers must not introduce a second clock. */
    public void requirePermitMatches(ExecutionPermit permit, String objectiveId, String workerId,
                                     String assignmentRef, String authorizationRef, String stepId,
                                     String actionRef) {
        requirePermitMatches(permit, objectiveId, workerId, assignmentRef, authorizationRef, stepId,
                actionRef, clock.instant());
    }

    public void requirePermitMatches(ExecutionPermit permit, String objectiveId, String workerId,
                                     String assignmentRef, String authorizationRef, String stepId,
                                     String actionRef, Instant at) {
        if (permit == null) throw new GovernanceDeniedException("EXECUTION_PERMIT_REQUIRED", actionRef);
        if (!permit.validAt(at)) throw new GovernanceDeniedException("EXECUTION_PERMIT_EXPIRED", permit.permitId());
        boolean match = permit.objectiveId().equals(objectiveId) && permit.workerId().equals(workerId)
                && permit.assignmentRef().equals(assignmentRef) && permit.authorizationRef().equals(authorizationRef)
                && permit.stepId().equals(stepId) && permit.actionRef().equals(actionRef);
        if (!match) throw new GovernanceDeniedException("EXECUTION_PERMIT_MISMATCH", permit.permitId());
        ExecutionAttemptGovernanceBinding binding = store.attemptBinding(permit.attemptId())
                .orElseThrow(() -> new GovernanceDeniedException("EXECUTION_PERMIT_MISMATCH", "attempt binding missing"));
        if (binding.fencingToken() != permit.fencingToken() || !binding.planDigest().equals(permit.planDigest())
                || !binding.authorityDigest().equals(permit.authorityDigest())) {
            throw new GovernanceDeniedException("EXECUTION_PERMIT_MISMATCH", "permit no longer matches attempt binding");
        }
    }

    private ExecutionAttempt requireCurrentAttempt(ExecutionIntent intent, Instant now) {
        ExecutionAttempt attempt = attempts.find(intent.attemptId())
                .orElseThrow(() -> new GovernanceDeniedException("EXECUTION_ATTEMPT_REQUIRED", intent.attemptId()));
        if (attempt.fencingToken() != intent.fencingToken()) throw new GovernanceDeniedException("EXECUTION_ATTEMPT_FENCED", "token mismatch");
        if (attempt.terminal()) throw new GovernanceDeniedException("EXECUTION_ATTEMPT_FENCED", "attempt terminal:" + attempt.status());
        if (!attempt.leaseExpiresAt().isAfter(now)) throw new GovernanceDeniedException("EXECUTION_ATTEMPT_FENCED", "lease expired");
        long currentToken = attempts.all().stream()
                .filter(other -> other.objectiveId().equals(attempt.objectiveId()) && other.stepId().equals(attempt.stepId()))
                .mapToLong(ExecutionAttempt::fencingToken).max().orElse(intent.fencingToken());
        if (currentToken != intent.fencingToken()) throw new GovernanceDeniedException("EXECUTION_ATTEMPT_FENCED", "stale fencing token");
        if (!attempt.objectiveId().equals(intent.objectiveId()) || !attempt.stepId().equals(intent.stepId())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "attempt objective/step mismatch");
        }
        return attempt;
    }

    private static void requireAttemptBinding(ExecutionIntent intent, ExecutionAttemptGovernanceBinding binding) {
        if (binding.fencingToken() != intent.fencingToken() || !binding.objectiveId().equals(intent.objectiveId())
                || !binding.stepId().equals(intent.stepId()) || !binding.planId().equals(intent.planId())
                || binding.planVersion() != intent.planVersion() || !binding.authoritySnapshotId().equals(intent.authoritySnapshotId())
                || !binding.derivationReceiptId().equals(intent.derivationReceiptId())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "execution intent differs from attempt binding");
        }
    }

    private void persistDenial(ExecutionIntent intent, GovernanceDeniedException denied, Instant at) {
        String id = "denial:" + GovernanceDigests.sha256(intent.objectiveId() + "|" + intent.stepId() + "|"
                + intent.actionRef() + "|" + denied.code() + "|" + at.toEpochMilli());
        store.saveDenial(new GovernanceDenial(id, denied.code(), intent.objectiveId(), intent.stepId(),
                intent.actionRef(), denied.getMessage(), List.of(
                        "governance-denial:" + denied.code(),
                        "attempt:" + intent.attemptId(),
                        "plan:" + intent.planId() + "@" + intent.planVersion(),
                        "authority-snapshot:" + intent.authoritySnapshotId()), at));
    }
}
