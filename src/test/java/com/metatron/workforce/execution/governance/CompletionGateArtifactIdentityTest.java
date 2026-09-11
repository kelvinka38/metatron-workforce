package com.metatron.workforce.execution.governance;

import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionGateArtifactIdentityTest {
    private static final Instant NOW = Instant.parse("2026-09-11T06:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SHA = "1111111111111111111111111111111111111111";

    @Test
    void partialReleaseIdentityStillFailsClosed() {
        Fixture f = new Fixture();
        GovernancePlanService.BoundPlan bound = f.bound();
        CompletionCandidate partial = candidate(bound, SHA, "", "", "", "");

        CompletionDecision decision = f.completion.decide(partial);

        assertEquals(CompletionDecision.Verdict.DENY, decision.verdict());
        assertTrue(decision.codes().contains("EXACT_ARTIFACT_IDENTITY_MISMATCH"));
    }

    @Test
    void completeMatchingReleaseIdentityPasses() {
        Fixture f = new Fixture();
        GovernancePlanService.BoundPlan bound = f.bound();
        CompletionCandidate complete = candidate(bound, SHA, SHA, SHA, SHA, SHA);

        CompletionDecision decision = f.completion.decide(complete);

        assertEquals(CompletionDecision.Verdict.ALLOW, decision.verdict());
    }

    private static CompletionCandidate candidate(GovernancePlanService.BoundPlan bound,
                                                  String source, String tested, String approved,
                                                  String deployed, String observed) {
        return new CompletionCandidate(
                "objective-artifact", "step-1", bound.plan().planId(), bound.plan().version(),
                bound.snapshot().snapshotId(), List.of("test-report:pass"), List.of("step-1"), List.of(),
                Map.of("tests pass", "test-report:pass"),
                Map.of("test-report", "test-report:pass"), true,
                source, tested, approved, deployed, observed, NOW);
    }

    private static final class Fixture {
        final GovernanceStateStore store = new InMemoryGovernanceStateStore();
        final AuthorityFreshnessValidator freshness = new AuthorityFreshnessValidator(store);
        final ConstraintEvaluator constraints = new ConstraintEvaluator();
        final SotDiscoveryService discovery = new SotDiscoveryService(AuthorityManifestCatalog.classpath(), store, CLOCK);
        final DerivationValidator derivation = new DerivationValidator(store, CLOCK);
        final GovernancePlanService plans = new GovernancePlanService(discovery, derivation, store, CLOCK);
        final ExecutionAttemptService attempts = new ExecutionAttemptService();
        final CompletionGate completion = new CompletionGate(store, freshness, constraints, CLOCK);

        GovernancePlanService.BoundPlan bound() {
            ExecutionWorkSpec work = new ExecutionWorkSpec(
                    "step-1", "complete governed release", "kelvinka38/metatron-workforce",
                    "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                    List.of("tests pass"), List.of("test-report"));
            return plans.bindAuthorizedWork("objective-artifact", "founder", work,
                    "founder", "approval:objective-artifact", Map.of());
        }
    }
}
