package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.testing.GovernanceTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-17): the real, actually-dispatched capability path --
 * AutonomousManagementRunner.executeNode()'s SafetyGoverned-wrapped composition calling
 * GovernedAutonomousExecutionCapability.execute() as one atomic delegate call -- created a real durable
 * Assignment (a real capacity reservation attributed to WORKER-GENERAL-ENGINEERING) but only linked it to
 * the ManagementObjective (management.addAssignmentReference(...)) AFTER that whole call returned
 * successfully. When execution then failed and both real Assignments were CANCELLED (a real,
 * correctly-attributed execution failure, not a staffing failure), ManagementObjective.assignmentRefs()
 * stayed empty the entire time execution was in flight, so WorkCardRenderer/WorkObservabilityController
 * incorrectly reported "STAFFING UNASSIGNED" and "Performer UNASSIGNED" even though a real Assignment
 * existed.
 *
 * (The sibling prepareAssignment()/executeAssigned() consumer path used by
 * AutonomousManagementRunner.executeNode() when an assignment consumer is configured is fixed the same
 * way for its own contract, but is not what these tests exercise: it is not the composition production's
 * SafetyGovernedAutonomousExecutionCapability-wrapped runner actually dispatches through.)
 *
 * This proves: (1) the real Assignment is referenced on the Management Objective, and the monitor
 * already resolves the real performer from durable Assignment attribution, BEFORE the capability call
 * that will decide success/failure even returns; (2) once execution fails, that historical Assignment
 * reference is preserved rather than erased, the monitor still never claims UNASSIGNED, and the
 * capacity reservation is still correctly released.
 */
class AssignmentLifecycleObservabilityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private static final String WORKER_ID = GeneralWorkspaceAutonomousCapability.WORKER_ID;
    private static final String CAPABILITY = GeneralWorkspaceAutonomousCapability.CAPABILITY;

    @Test
    void realAssignmentIsReferencedAndPerformerResolvesTruthfullyBeforeCapabilitySuccessIsKnown() {
        WorkforceCoreService core = stagedCore();
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicBoolean assertedInsideExecution = new AtomicBoolean(false);

        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE; }
            @Override public String authorizationReference() { return GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE; }
            @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                String objectiveId = request.objectiveId();
                assertFalse(management.get(objectiveId).assignmentRefs().isEmpty(),
                        "the real Assignment must already be referenced before this capability call returns");
                WorkforceCoreService.Assignment assignment = core.allAssignments().stream()
                        .filter(a -> objectiveId.equals(a.objectiveRef())).findFirst().orElseThrow();
                assertEquals(WORKER_ID, assignment.workerId());
                assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, assignment.status());

                String card = new WorkCardRenderer(management, core).render(objectiveId);
                assertFalse(card.contains("UNASSIGNED"),
                        "monitor must not say UNASSIGNED while a real Assignment already exists");
                assertTrue(card.contains(WORKER_ID),
                        "monitor must resolve the real performer from durable Assignment attribution");
                assertedInsideExecution.set(true);

                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-general-engineering", List.of("evidence:fake-general-engineering-work"), "PASS");
            }
        };
        GovernedAutonomousExecutionCapability governed = governedCapability(delegate, core, management);

        try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, request, available) -> request.executionWorkPlan(),
                List.of(governed), new AutonomyCoordinationService(), CLOCK,
                "runner-assignment-lifecycle-success", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
            HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                    management, List.of(governed), runner, "worker-head", CLOCK);

            var receipt = ingress.submit(
                    "human-primary", "metatron", "case-assignment-lifecycle-success",
                    "conversation-assignment-lifecycle-success", "message-assignment-lifecycle-success",
                    "workplace", request());
            runner.runOnce();

            String objectiveId = receipt.objectiveId();
            assertTrue(assertedInsideExecution.get(), "capability execution must have actually run");
            assertEquals(1, core.allAssignments().size());
            assertEquals(WORKER_ID, core.allAssignments().getFirst().workerId());
            assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().getFirst().status());
            assertTrue(management.get(objectiveId).assignmentRefs().contains(
                    core.allAssignments().getFirst().assignmentId()),
                    "the Management Objective must reference the real completed Assignment");

            // The Objective's own terminal state depends on the separate MUTATING-graph Observation/SoT
            // completion gate (AutonomyCoordinationService.requireGovernedCompletion), which is out of
            // scope for this test (no ObservationClosureService is wired here). What matters for
            // assignment/observability truthfulness -- proven above and below -- already holds regardless
            // of that unrelated gate: the real completed Assignment is durably referenced and the monitor
            // never claims UNASSIGNED.
            String finalCard = new WorkCardRenderer(management, core).render(objectiveId);
            assertFalse(finalCard.contains("UNASSIGNED"));
            assertTrue(finalCard.contains(WORKER_ID));
        }
    }

    @Test
    void failedExecutionPreservesHistoricalAssignmentAttributionAndStillReleasesCapacity() {
        WorkforceCoreService core = stagedCore();
        ManagementAutonomyService management = new ManagementAutonomyService();

        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE; }
            @Override public String authorizationReference() { return GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE; }
            @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(false, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-general-engineering", List.of("evidence:fake-general-engineering-failure"),
                        "workspace materialization failed with a genuine 404");
            }
        };
        GovernedAutonomousExecutionCapability governed = governedCapability(delegate, core, management);

        try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, request, available) -> request.executionWorkPlan(),
                List.of(governed), new AutonomyCoordinationService(), CLOCK,
                "runner-assignment-lifecycle-failure", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
            HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                    management, List.of(governed), runner, "worker-head", CLOCK);

            var receipt = ingress.submit(
                    "human-primary", "metatron", "case-assignment-lifecycle-failure",
                    "conversation-assignment-lifecycle-failure", "message-assignment-lifecycle-failure",
                    "workplace", request());
            runner.runOnce();

            String objectiveId = receipt.objectiveId();
            // This ordinary (non-safety, non-authorization) MUTATING execution failure is bounded-
            // locally-retried in place (same step, same graph version) up to MAX_MUTATING_DISPATCH_ATTEMPTS
            // (3) times within this single runOnce() call before escalating, so it genuinely attempts the
            // work three times -- each attempt creates its own real Assignment, none of which are ever
            // silently dropped or rewritten away.
            assertEquals(3, core.allAssignments().size(),
                    "each bounded local retry attempt must create its own real, historically-preserved Assignment");
            for (WorkforceCoreService.Assignment assignment : core.allAssignments()) {
                assertEquals(WORKER_ID, assignment.workerId());
                assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED, assignment.status(),
                        "every attempt's execution failure must cancel its Assignment truthfully, not silently vanish it");
                assertTrue(management.get(objectiveId).assignmentRefs().contains(assignment.assignmentId()),
                        "the Management Objective must keep referencing every real historical Assignment after failure");
            }
            assertFalse(management.get(objectiveId).status() == ManagementObjective.Status.COMPLETED);
            assertEquals(ManagementObjective.Status.ESCALATED, management.get(objectiveId).status(),
                    "an ordinary MUTATING execution failure that exhausts bounded local retry must escalate, "
                            + "never vanish or silently complete");

            String card = new WorkCardRenderer(management, core).render(objectiveId);
            assertFalse(card.contains("UNASSIGNED"),
                    "monitor must never rewrite history to UNASSIGNED merely because execution later failed");
            assertTrue(card.contains(WORKER_ID));

            assertTrue(core.allCapacityReservations().stream()
                            .filter(r -> WORKER_ID.equals(r.workerId()))
                            .allMatch(r -> r.status() == WorkforceCoreService.ReservationStatus.RELEASED),
                    "capacity must still be correctly released after the Assignment is cancelled");
        }
    }

    /**
     * Wires GovernedAutonomousExecutionCapability the way production's LiveManagementConfiguration does
     * for MUTATING General Workspace work (real governance binding, durable execution attempts, no
     * WorkerActor runtime needed since executeOnWorkerActor() falls back to the direct delegate call when
     * RuntimeCapacityCoordinator has no actors), with the same onAssignmentCreated() listener production
     * wires to keep the Management Objective's Assignment reference truthful.
     */
    private static GovernedAutonomousExecutionCapability governedCapability(
            AutonomousExecutionCapability delegate, WorkforceCoreService core, ManagementAutonomyService management) {
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, harness.admission, CLOCK, null, new ExecutionAttemptService(),
                new RuntimeCapacityCoordinator(new RuntimeRegistry()), harness.plans, harness.attemptBindings, harness.gate);
        governed.onAssignmentCreated((objectiveId, assignmentId) -> management.addAssignmentReference(
                objectiveId, management.get(objectiveId).ownerWorkerId(), assignmentId, CLOCK.instant()));
        return governed;
    }

    private static WorkforceCoreService stagedCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:general-engineering-worker",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-general-cognitive-engineering:v1");
        core.admitWorker(WORKER_ID, "participant:general-engineering-worker");
        core.participate("participation:general-engineering-worker:metatron",
                WORKER_ID, "organization:metatron",
                "position:general-engineering-executor", "role:general-code-and-runtime-worker");
        core.attestCapability(WORKER_ID, CAPABILITY, 1.0, "evidence:general-engineering-capability-acceptance:v1");
        core.setAvailability(WORKER_ID, true, 2.0);
        return core;
    }

    private static NormalizedRequest request() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Take ownership of one governed Objective: build and deliver a complete runnable web "
                        + "application called Metatron Workforce Control Center. Assign the implementation "
                        + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                        + "tests, runtime verification, Git evidence, and terminal completion.",
                "repository:kelvinka38/metatron-workforce-control-center",
                CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("canonical Worker WORKER-GENERAL-ENGINEERING performs the requested workspace execution",
                        "the work is executed under a real Workforce Assignment attributed to WORKER-GENERAL-ENGINEERING"),
                List.of("general-workspace-execution durable work product/evidence",
                        "worker-assignment evidence attributed to WORKER-GENERAL-ENGINEERING",
                        "workspace-source:fresh-new-application"));
        return new NormalizedRequest(
                "Build Metatron Workforce Control Center", WORKER_ID,
                List.of("execute autonomously under governed Workforce Assignment attribution"),
                IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product",
                List.of(),
                List.of("do not claim completion before independent Observation"),
                "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
