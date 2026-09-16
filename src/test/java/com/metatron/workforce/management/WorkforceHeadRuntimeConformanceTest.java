package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
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
import com.metatron.workforce.observation.ObservationVerifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-conformance acceptance for Point 3: Head owns the Objective machinery end-to-end. */
class WorkforceHeadRuntimeConformanceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T11:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CAPABILITY = "test.head.execute";
    private static final String WORKER = "worker:point3-head-executor";

    @Test
    void headFormsWorkerAssignsExecutesVerifiesAndReportsFromEmptyWorkforce() {
        WorkforceCoreService core = new WorkforceCoreService();
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AutonomySafetyService safety = safety();
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety, new InMemoryAutonomySchedulingStateStore(), 4);

        HeadTestCapability effect = new HeadTestCapability();
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new HeadTestStaffingPolicy()));
        AutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                effect, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, staffing);

        ObservationVerifier verifier = new ObservationVerifier() {
            @Override public boolean supports(com.metatron.workforce.observation.ObservationRequirement requirement) {
                return true;
            }

            @Override
            public Optional<ObservationReport> observe(
                    com.metatron.workforce.observation.ObservationRequirement requirement,
                    List<String> executionEvidenceReferences,
                    Instant at) {
                var completed = core.allAssignments().stream()
                        .filter(a -> a.workerId().equals(WORKER))
                        .filter(a -> a.status() == WorkforceCoreService.AssignmentStatus.COMPLETED)
                        .findFirst();
                if (completed.isEmpty()) return Optional.empty();
                return Optional.of(new ObservationReport(
                        "report:point3:" + requirement.criterionId(),
                        requirement.requirementId(),
                        requirement.objectiveId(),
                        requirement.target(),
                        "assigned worker completed the governed execution",
                        "independent-core-assignment-state",
                        at,
                        at,
                        List.of("observation:assignment=" + completed.orElseThrow().assignmentId()),
                        1.0,
                        ObservationReport.Quality.HIGH,
                        "",
                        ObservationReport.CriterionResult.PASS));
            }
        };
        ObservationClosureService observation = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of(verifier));

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(governed), coordination, observation, safety, CLOCK)
                .configureScheduling(scheduler);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(governed), runner, "worker-head", CLOCK);

        var receipt = ingress.submit(
                "human-primary", "org-metatron", "case-point3", "conversation-point3",
                "message-point3", "chat", validRequest());
        assertEquals("ACCEPTED", receipt.objectiveStatus());
        assertTrue(core.allWorkers().isEmpty(), "Human ingress must not pre-create workers");

        runner.runOnce();

        ManagementObjective objective = management.get(receipt.objectiveId());
        assertEquals(ManagementObjective.Status.COMPLETED, objective.status());
        assertEquals(1, effect.effects.get());
        assertEquals(1, core.allParticipants().size());
        assertEquals(1, core.allWorkers().size());
        assertEquals(WORKER, core.allWorkers().getFirst().workerId());
        assertEquals(1, core.allParticipations().size());
        assertTrue(core.capabilities(WORKER).stream().anyMatch(c -> c.capabilityRef().equals(CAPABILITY)));
        assertTrue(core.allAssignments().stream().anyMatch(a ->
                a.workerId().equals(WORKER) && a.status() == WorkforceCoreService.AssignmentStatus.COMPLETED));
        assertFalse(objective.assignmentRefs().isEmpty());

        assertEquals(ObservationClosureService.Verdict.PASSED, observation.verdict(receipt.objectiveId()));
        assertTrue(scheduler.decisionsForObjective(receipt.objectiveId()).stream()
                .anyMatch(d -> d.staffingBootstrapStepIds().contains("step-point3")));
        assertTrue(management.findAutonomousWork(receipt.objectiveId()).orElseThrow().evidenceReferences().stream()
                .anyMatch(e -> e.equals("staffing:policy=" + CAPABILITY)));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.PLAN_RECORDED));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.WORK_STEP_COMPLETED));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.COMPLETED));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.REPORT_READY));
        assertTrue(management.outbox().stream().anyMatch(message ->
                message.messageType().equals("ObjectiveOutcomeReportReady")
                        && message.payload().contains("status=COMPLETED")));
    }

    @Test
    void invalidCapabilityPlanBlocksWithTruthfulRegistryFailure() {
        WorkforceCoreService core = new WorkforceCoreService();
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AutonomySafetyService safety = safety();
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety, new InMemoryAutonomySchedulingStateStore(), 4);
        AtomicInteger plannerCalls = new AtomicInteger();

        HeadTestCapability effect = new HeadTestCapability();
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new HeadTestStaffingPolicy()));
        AutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                effect, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, staffing);

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> plannerCalls.incrementAndGet() == 1
                        ? List.of(step("step-invalid", "tool.that.does.not.exist"))
                        : List.of(step("step-replanned", CAPABILITY)),
                List.of(governed), coordination, CLOCK,
                "runner-point3", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)
                .configureScheduling(scheduler);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(governed), runner, "worker-head", CLOCK);

        var receipt = ingress.submit(
                "human-primary", "org-metatron", "case-point3-replan", "conversation-point3-replan",
                "message-point3-replan", "chat", validRequest());

        runner.runOnce();
        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertTrue(management.history(receipt.objectiveId()).stream().anyMatch(e ->
                e.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED
                        && e.detail().contains("missing_capability_id=tool.that.does.not.exist")
                        && e.detail().contains("registry_result=NOT_REGISTERED")
                        && e.detail().contains("planner_contract_defect=true")));
        assertFalse(management.history(receipt.objectiveId()).stream().anyMatch(e ->
                e.type() == ManagementAutonomyService.ManagementEvent.Type.STAFFING_NEED_DETECTED));

        runner.runOnce();

        assertEquals(1, plannerCalls.get());
        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertEquals(0, effect.effects.get());
        assertFalse(management.history(receipt.objectiveId()).stream().anyMatch(e ->
                e.detail().contains("staffing-required:tool.that.does.not.exist")));
    }

    private static AutonomySafetyService safety() {
        return new AutonomySafetyService(
                new InMemoryAutonomySafetyStateStore(), CLOCK, 100.0, 32,
                Duration.ofHours(1), AutonomySafetyState.RiskLevel.HIGH);
    }

    private static NormalizedRequest validRequest() {
        return new NormalizedRequest(
                "Complete the Point 3 Head objective", "runtime conformance",
                List.of("no human machinery sequencing"), IntelligenceDepth.ANALYZE,
                "verified outcome", List.of(), List.of("do not bypass governance"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step("step-point3", CAPABILITY)), false, null, LlmProvider.OPENAI, "");
    }

    private static ExecutionWorkSpec step(String stepId, String capability) {
        return new ExecutionWorkSpec(
                stepId, "Execute the Head-owned work", "runtime-conformance-target", capability, List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("the assigned worker completed the governed execution"),
                List.of("independent assignment completion evidence"));
    }

    private static final class HeadTestCapability implements AutonomousExecutionCapability {
        private final AtomicInteger effects = new AtomicInteger();

        @Override public String capabilityRef() { return CAPABILITY; }
        @Override public String authorityReference() { return "authority:point3"; }
        @Override public String authorizationReference() { return "authorization:point3"; }
        @Override public boolean supportsWorker(String workerId) { return WORKER.equals(workerId); }

        @Override
        public CapabilityResult execute(CapabilityRequest request) {
            if (!request.allocated()) throw new AssertionError("effect reached without Worker/Assignment allocation");
            effects.incrementAndGet();
            return new CapabilityResult(
                    true, request.allocatedWorkerId(), request.assignmentReference(),
                    "work:point3", List.of("execution:point3:completed"), "PASS");
        }
    }

    private static final class HeadTestStaffingPolicy implements AutonomousStaffingPolicy {
        @Override public String capabilityRef() { return CAPABILITY; }

        @Override
        public FormationSpec formationSpec() {
            return new FormationSpec(
                    true,
                    "participant:point3-head-executor",
                    WorkforceCoreService.ParticipantType.AI,
                    "provenance:point3-runtime-conformance",
                    WORKER,
                    "org-metatron",
                    "participation:point3-head-executor",
                    "position:point3-executor",
                    "role:point3-executor",
                    1.0,
                    "evidence:point3-capability",
                    "qualification:point3-executor",
                    "evidence:point3-qualification",
                    "authority:point3",
                    1.0,
                    "runtime-profile:point3",
                    "cost-limit:point3",
                    "lifecycle:point3");
        }
    }
}
