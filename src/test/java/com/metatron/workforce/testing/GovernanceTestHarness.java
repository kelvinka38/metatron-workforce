package com.metatron.workforce.testing;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.governance.AuthorityFreshnessValidator;
import com.metatron.workforce.execution.governance.AuthorityManifestCatalog;
import com.metatron.workforce.execution.governance.ConstraintEvaluator;
import com.metatron.workforce.execution.governance.DerivationValidator;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.execution.governance.ExecutionIntent;
import com.metatron.workforce.execution.governance.ExecutionPermit;
import com.metatron.workforce.execution.governance.GovernanceAdmissionValidator;
import com.metatron.workforce.execution.governance.GovernanceAttemptBindingService;
import com.metatron.workforce.execution.governance.GovernanceExecutionContext;
import com.metatron.workforce.execution.governance.GovernancePlanService;
import com.metatron.workforce.execution.governance.GovernanceStateStore;
import com.metatron.workforce.execution.governance.InMemoryGovernanceStateStore;
import com.metatron.workforce.execution.governance.PlanConformanceValidator;
import com.metatron.workforce.execution.governance.SotDiscoveryService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Shared test harness that exercises the real fail-closed SoT/plan/attempt/permit path. */
public final class GovernanceTestHarness {
    public record BoundMutation(
            GovernancePlanService.BoundPlan boundPlan,
            ExecutionAttempt attempt,
            GovernanceExecutionContext context) {}

    public final GovernanceStateStore store;
    public final AuthorityFreshnessValidator freshness;
    public final ConstraintEvaluator constraints;
    public final PlanConformanceValidator planConformance;
    public final SotDiscoveryService discovery;
    public final DerivationValidator derivation;
    public final GovernancePlanService plans;
    public final ExecutionAttemptService attempts;
    public final GovernanceAttemptBindingService attemptBindings;
    public final ExecutionGate gate;
    public final ExecutionAdmissionService admission;
    private final Clock clock;

    public GovernanceTestHarness(Clock clock) {
        this.clock = clock;
        this.store = new InMemoryGovernanceStateStore();
        this.freshness = new AuthorityFreshnessValidator(store);
        this.constraints = new ConstraintEvaluator();
        this.planConformance = new PlanConformanceValidator();
        this.discovery = new SotDiscoveryService(AuthorityManifestCatalog.classpath(), store, clock);
        this.derivation = new DerivationValidator(store, clock);
        this.plans = new GovernancePlanService(discovery, derivation, store, clock);
        this.attempts = new ExecutionAttemptService();
        this.attemptBindings = new GovernanceAttemptBindingService(store, clock);
        this.gate = new ExecutionGate(store, attempts, freshness, planConformance, constraints, clock);
        this.admission = new ExecutionAdmissionService(new GovernanceAdmissionValidator(store, freshness, planConformance));
    }

    public BoundMutation bind(String objectiveId, String actorId, String workerId,
                              String assignmentRef, String authorizationRef, String runtimeId,
                              ExecutionWorkSpec work) {
        GovernancePlanService.BoundPlan bound = plans.bindAuthorizedWork(
                objectiveId, actorId, work, actorId, "test-approval:" + objectiveId, Map.of());
        ExecutionAttempt attempt = attempts.begin(
                "test-dispatch:" + objectiveId + ":" + work.stepId(), objectiveId, work.stepId(), workerId,
                assignmentRef, authorizationRef, runtimeId, 1, Duration.ofMinutes(5), clock.instant());
        attemptBindings.bind(attempt, bound);
        GovernanceExecutionContext context = new GovernanceExecutionContext(
                attempt.attemptId(), attempt.fencingToken(), bound.plan().planId(), bound.plan().version(),
                bound.snapshot().snapshotId(), bound.derivation().receiptId(), bound.plan().targetScope());
        return new BoundMutation(bound, attempt, context);
    }

    public ExecutionPermit permit(BoundMutation mutation, String objectiveId, String workerId,
                                  String assignmentRef, String authorizationRef, String stepId,
                                  String actionRef, Map<String, String> inputs) {
        return gate.authorize(new ExecutionIntent(
                objectiveId, mutation.attempt().attemptId(), mutation.attempt().fencingToken(), workerId,
                assignmentRef, authorizationRef, mutation.boundPlan().plan().planId(),
                mutation.boundPlan().plan().version(), stepId, actionRef, ActionFabric.Consequence.MUTATING,
                mutation.context().targetScope(), mutation.boundPlan().snapshot().snapshotId(),
                mutation.boundPlan().derivation().receiptId(), inputs, clock.instant()));
    }
}
