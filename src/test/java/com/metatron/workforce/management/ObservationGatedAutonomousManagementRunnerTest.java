package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.observation.InMemoryObservationStateStore;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationGatedAutonomousManagementRunnerTest {
    @Test
    void successfulExecutionWaitsForIndependentObservationWithoutRepeatingEffect() {
        Instant now = Instant.parse("2026-08-31T04:30:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        ObservationClosureService observation = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());
        AtomicInteger effects = new AtomicInteger();

        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return new CapabilityResult(true, "worker-auditor", "assignment-observation",
                        "work-observation", List.of("execution:evidence:success"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(capability), coordination, observation, clock,
                "runner-observation", Duration.ofMinutes(5), Duration.ofSeconds(5), 2);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-observation",
                "conversation-observation", "telegram:update:observation", "telegram", request());

        runner.runOnce();
        assertEquals(1, effects.get());
        assertEquals(ManagementObjective.Status.EXECUTING, management.get(receipt.objectiveId()).status());
        assertFalse(management.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveCompleted")));
        assertEquals(ObservationClosureService.Verdict.PENDING, observation.verdict(receipt.objectiveId()));
        assertEquals(1, observation.requirements(receipt.objectiveId()).size());

        runner.runOnce();
        assertEquals(1, effects.get(), "pending Observation must not replay the succeeded effect");
        assertEquals(ManagementObjective.Status.EXECUTING, management.get(receipt.objectiveId()).status());

        ObservationRequirement requirement = observation.requirements(receipt.objectiveId()).getFirst();
        observation.recordReport(new ObservationReport(
                "observation-report-1", requirement.requirementId(), receipt.objectiveId(), requirement.target(),
                "repository state independently confirms requested audit result",
                "independent-repository-read", now.plusSeconds(30), now.plusSeconds(30),
                List.of("observation:evidence:repository-state"), 0.99,
                ObservationReport.Quality.HIGH, "", ObservationReport.CriterionResult.PASS));

        runner.runOnce();

        assertEquals(1, effects.get());
        assertEquals(ManagementObjective.Status.COMPLETED, management.get(receipt.objectiveId()).status());
        assertTrue(management.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveCompleted")));
        assertEquals(ObservationClosureService.Verdict.PASSED, observation.verdict(receipt.objectiveId()));
    }

    @Test
    void failedObservationBlocksWithTheRealVerifierDetailNotJustTheGenericVerdict() {
        // Production incident (2026-09-22): an Objective reached 100% self-reported progress (all
        // planned steps succeeded) but independent Observation re-verification found a real failure --
        // ObservationVerifier implementations (e.g. GeneralWorkspaceObservationVerifier) already capture
        // rich diagnostic detail in ObservationReport.observedState() when they independently re-run a
        // build/test and it fails, but the durable BLOCKED reason only ever carried the generic
        // "observation-failed" string, discarding that detail -- the exact same class of gap fixed for
        // the CognitiveWorkerRuntime circuit breaker, recurring at a different governance checkpoint.
        Instant now = Instant.parse("2026-08-31T04:30:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        ObservationClosureService observation = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());

        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-auditor", "assignment-observation",
                        "work-observation", List.of("execution:evidence:success"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(capability), coordination, observation, clock,
                "runner-observation-fail", Duration.ofMinutes(5), Duration.ofSeconds(5), 2);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-observation-fail",
                "conversation-observation-fail", "telegram:update:observation-fail", "telegram", request());

        runner.runOnce();
        runner.runOnce();

        ObservationRequirement requirement = observation.requirements(receipt.objectiveId()).getFirst();
        String realFailureDetail = "Independent build verification failed: npm ERR! code E404 "
                + "npm ERR! 404 Not Found - GET https://registry.npmjs.org/@metatron%2fmissing-package";
        observation.recordReport(new ObservationReport(
                "observation-report-fail-1", requirement.requirementId(), receipt.objectiveId(), requirement.target(),
                realFailureDetail,
                "independent-sandbox-build-rerun", now.plusSeconds(30), now.plusSeconds(30),
                List.of("observation-sandbox-verification:exit=1"), 0.95,
                ObservationReport.Quality.HIGH, "", ObservationReport.CriterionResult.FAIL));

        runner.runOnce();

        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertEquals(ObservationClosureService.Verdict.FAILED, observation.verdict(receipt.objectiveId()));
        assertTrue(management.history(receipt.objectiveId()).stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED)
                        .anyMatch(event -> event.detail().contains("npm ERR! 404")),
                "the durable BLOCKED reason must contain the real independent-verification failure text, "
                        + "not just the generic \"observation-failed\" verdict name");
    }

    private static NormalizedRequest request() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce", "test.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository audit result is complete and evidence-backed"),
                List.of("independent repository state and cited audit evidence"));
        return new NormalizedRequest(
                "Audit repository", "metatron-workforce", List.of("read-only"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
