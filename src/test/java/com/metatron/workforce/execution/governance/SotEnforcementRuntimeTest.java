package com.metatron.workforce.execution.governance;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SotEnforcementRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-09-10T06:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void mutatingActionRequiresExactPermitAndStaleAuthorityBlocksNextEffect() {
        Fixture f = new Fixture();
        ExecutionWorkSpec work = work("step-1", "patch governed repository");
        GovernancePlanService.BoundPlan bound = f.plans.bindAuthorizedWork(
                "objective-1", "founder", work, "founder", "approval:objective-1", Map.of());
        ExecutionAttempt attempt = f.attempts.begin(
                "dispatch-1", "objective-1", "step-1", "worker-1", "assignment-1", "auth-1",
                "runtime-1", 1, Duration.ofMinutes(1), NOW);
        f.attemptBindings.bind(attempt, bound);

        ExecutionIntent intent = new ExecutionIntent(
                "objective-1", attempt.attemptId(), attempt.fencingToken(), "worker-1", "assignment-1", "auth-1",
                bound.plan().planId(), bound.plan().version(), "step-1", "workspace.file.patch",
                ActionFabric.Consequence.MUTATING, bound.plan().targetScope(), bound.snapshot().snapshotId(),
                bound.derivation().receiptId(), Map.of("path", "README.md"), NOW);
        ExecutionPermit permit = f.gate.authorize(intent);
        assertNotNull(permit);

        AtomicInteger calls = new AtomicInteger();
        ActionFabric fabric = new ActionFabric(List.of(action(calls)), f.gate);
        ActionFabric.ActionRequest request = new ActionFabric.ActionRequest(
                "workspace.file.patch", "worker-1", "assignment-1", "auth-1", "objective-1", "step-1",
                "idempotency-1", true, Map.of("path", "README.md"));
        assertThrows(GovernanceDeniedException.class, () -> fabric.execute(request));
        assertEquals(0, calls.get());
        assertTrue(fabric.execute(request, permit).success());
        assertEquals(1, calls.get());

        f.store.setCurrentAuthorityDigest(work.target(), "changed-authority-digest");
        GovernanceDeniedException stale = assertThrows(GovernanceDeniedException.class, () -> f.gate.authorize(intent));
        assertEquals("AUTHORITY_STALE", stale.code());
        assertEquals(1, calls.get());
    }

    @Test
    void approvedWorkCannotBeSilentlyReplaced() {
        Fixture f = new Fixture();
        f.plans.bindAuthorizedWork("objective-2", "founder", work("step-1", "implement approved A"),
                "founder", "approval:objective-2", Map.of());
        GovernanceDeniedException drift = assertThrows(GovernanceDeniedException.class, () ->
                f.plans.bindAuthorizedWork("objective-2", "founder", work("step-1", "silently replace with B"),
                        "founder", "approval:objective-2", Map.of()));
        assertEquals("PLAN_DEVIATION", drift.code());
    }

    @Test
    void unknownMutatingTargetStillFailsClosed() {
        Fixture f = new Fixture();
        ExecutionWorkSpec unknown = new ExecutionWorkSpec(
                "step-unknown", "attempt mutation outside discovered authority", "unknown-owner/unknown-repository",
                "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("test-report"));

        GovernanceDeniedException denied = assertThrows(GovernanceDeniedException.class, () ->
                f.plans.bindAuthorizedWork("objective-unknown", "founder", unknown,
                        "founder", "approval:objective-unknown", Map.of()));

        assertEquals("AUTHORITY_UNRESOLVED", denied.code());
    }

    @Test
    void completionIsInstitutionalDecisionNotWorkerClaim() {
        Fixture f = new Fixture();
        ExecutionWorkSpec work = work("step-1", "complete governed repair");
        GovernancePlanService.BoundPlan bound = f.plans.bindAuthorizedWork(
                "objective-3", "founder", work, "founder", "approval:objective-3", Map.of());
        CompletionGate completion = new CompletionGate(f.store, f.freshness, f.constraints, CLOCK);

        CompletionCandidate missingEvidence = new CompletionCandidate(
                "objective-3", "step-1", bound.plan().planId(), bound.plan().version(), bound.snapshot().snapshotId(),
                List.of("worker-says-complete"), List.of("step-1"), List.of(), Map.of(), Map.of(), true,
                "", "", "", "", "", NOW);
        CompletionDecision denied = completion.decide(missingEvidence);
        assertEquals(CompletionDecision.Verdict.DENY, denied.verdict());
        assertTrue(denied.codes().contains("ACCEPTANCE_INCOMPLETE"));
        assertTrue(denied.codes().contains("EVIDENCE_INSUFFICIENT"));

        CompletionCandidate proven = new CompletionCandidate(
                "objective-3", "step-1", bound.plan().planId(), bound.plan().version(), bound.snapshot().snapshotId(),
                List.of("test-report:pass"), List.of("step-1"), List.of(),
                Map.of("tests pass", "test-report:pass"), Map.of("test-report", "test-report:pass"), true,
                "", "", "", "", "", NOW);
        assertEquals(CompletionDecision.Verdict.ALLOW, completion.decide(proven).verdict());
    }

    private static ExecutionWorkSpec work(String step, String objective) {
        return new ExecutionWorkSpec(step, objective, "kelvinka38/metatron-workforce",
                "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("test-report"));
    }

    private static ActionFabric.Action action(AtomicInteger calls) {
        return new ActionFabric.Action() {
            @Override public String actionRef() { return "workspace.file.patch"; }
            @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.MUTATING; }
            @Override public Set<String> allowedWorkers() { return Set.of("worker-1"); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of("auth-1"); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                calls.incrementAndGet();
                return ActionFabric.ActionObservation.success(actionRef(), "patched", Map.of(), List.of("patch-evidence"));
            }
        };
    }

    private static final class Fixture {
        final GovernanceStateStore store = new InMemoryGovernanceStateStore();
        final AuthorityFreshnessValidator freshness = new AuthorityFreshnessValidator(store);
        final ConstraintEvaluator constraints = new ConstraintEvaluator();
        final PlanConformanceValidator planConformance = new PlanConformanceValidator();
        final SotDiscoveryService discovery = new SotDiscoveryService(AuthorityManifestCatalog.classpath(), store, CLOCK);
        final DerivationValidator derivation = new DerivationValidator(store, CLOCK);
        final GovernancePlanService plans = new GovernancePlanService(discovery, derivation, store, CLOCK);
        final ExecutionAttemptService attempts = new ExecutionAttemptService();
        final GovernanceAttemptBindingService attemptBindings = new GovernanceAttemptBindingService(store, CLOCK);
        final ExecutionGate gate = new ExecutionGate(store, attempts, freshness, planConformance, constraints, CLOCK);
    }
}
